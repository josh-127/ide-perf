/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.idea.perf.vfstracer

import com.google.idea.perf.methodtracer.AgentLoader
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassReader.SKIP_FRAMES
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
import org.objectweb.asm.ClassWriter.COMPUTE_MAXS
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.ASM8
import org.objectweb.asm.Type
import org.objectweb.asm.commons.AdviceAdapter
import org.objectweb.asm.commons.Method
import java.io.File
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.jvm.javaMethod

@Deprecated("Use VirtualFileTree")
interface VirtualFileStats {
    val fileName: String
    val psiWraps: Int
    val reparseCount: Int
}

private class MutableVirtualFileStats(
    override val fileName: String
): VirtualFileStats {
    override var psiWraps: Int = 0
    override var reparseCount: Int = 0
}

private const val COMPOSITE_ELEMENT_CLASS = "com.intellij.psi.impl.source.tree.CompositeElement"
private val COMPOSITE_ELEMENT_JVM_CLASS = COMPOSITE_ELEMENT_CLASS.replace('.', '/')
private val LOG = Logger.getInstance(VirtualFileTracer::class.java)

object VirtualFileTracer {
    private var transformer: TracerClassFileTransformer? = null

    fun startVfsTracing() {
        val instrumentation = AgentLoader.instrumentation
        if (instrumentation == null) {
            LOG.error("Failed to get instrumentation instance.")
            return
        }

        val classes = instrumentation.allLoadedClasses
        val compositeElementClass = classes.firstOrNull { it.name == COMPOSITE_ELEMENT_CLASS }
        if (compositeElementClass == null) {
            LOG.error("Failed to get $compositeElementClass class.")
        }

        VfsTracerTrampoline.installHook(VfsTracerHookImpl())

        transformer = TracerClassFileTransformer()
        instrumentation.addTransformer(transformer, true)
        instrumentation.retransformClasses(compositeElementClass)
    }

    fun stopVfsTracing() {
        if (transformer != null) {
            val instrumentation = AgentLoader.instrumentation
            instrumentation?.removeTransformer(transformer)
        }
    }

    fun collectAndReset(): VirtualFileTree =
        VirtualFileTracerImpl.collectAndReset()

    val fileStats: Map<String, VirtualFileStats> get() =
        VirtualFileTracerImpl.getCurrentFileStats()
}

private object VirtualFileTracerImpl {
    var currentTree = MutableVirtualFileTree.createRoot()
    val fileStats: MutableMap<String, MutableVirtualFileStats> = mutableMapOf()
    val lock = ReentrantLock()

    fun collectAndReset(): VirtualFileTree {
        lock.withLock {
            val tree = currentTree
            currentTree = MutableVirtualFileTree.createRoot()
            return tree
        }
    }

    fun getCurrentFileStats(): Map<String, VirtualFileStats> {
        lock.withLock {
            return fileStats.toMap()
        }
    }

    fun incrementPsiWrap(fileName: String) {
        lock.withLock {
            currentTree.accumulate(fileName, 1, 0)

            val stats = fileStats.getOrPut(fileName) { MutableVirtualFileStats(fileName) }
            stats.psiWraps++
        }
    }
}

private class VfsTracerHookImpl: VfsTracerHook {
    override fun onPsiElementCreate(psiElement: Any?) {
        if (psiElement is PsiElement && psiElement.isValid) {
            val file = psiElement.containingFile
            val virtualFile = file.virtualFile
            val fileName = virtualFile?.canonicalPath
            if (fileName != null) {
                VirtualFileTracerImpl.incrementPsiWrap(fileName)
            }
        }
    }
}

private class TracerClassFileTransformer: ClassFileTransformer {
    companion object {
        val HOOK_CLASS_NAME: String = Type.getInternalName(VfsTracerTrampoline::class.java)
        val ON_PSI_ELEMENT_CREATE: Method = Method.getMethod(VfsTracerTrampoline::onPsiElementCreate.javaMethod)
        const val ASM_API = ASM8
    }

    override fun transform(
        loader: ClassLoader?,
        className: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray
    ): ByteArray? {
        try {
            if (className == COMPOSITE_ELEMENT_JVM_CLASS) {
                return tryTransformCompositeElement(classfileBuffer)
            }
            return null
        }
        catch (e: Throwable) {
            LOG.warn("Failed to instrument class $className", e)
            throw e
        }
    }

    private fun tryTransformCompositeElement(classBytes: ByteArray): ByteArray {
        val reader = ClassReader(classBytes)
        val writer = ClassWriter(reader, COMPUTE_MAXS or COMPUTE_FRAMES)

        val classVisitor = object: ClassVisitor(ASM_API, writer) {
            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor {
                if (name != "createPsiNoLock") {
                    return super.visitMethod(access, name, descriptor, signature, exceptions)
                }

                val methodWriter = cv.visitMethod(access, name, descriptor, signature, exceptions)

                return object: AdviceAdapter(ASM_API, methodWriter, access, name, descriptor) {
                    override fun onMethodExit(opcode: Int) {
                        mv.visitInsn(Opcodes.DUP)
                        mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            HOOK_CLASS_NAME,
                            ON_PSI_ELEMENT_CREATE.name,
                            ON_PSI_ELEMENT_CREATE.descriptor,
                            false
                        )
                    }
                }
            }
        }

        reader.accept(classVisitor, SKIP_FRAMES)
        return writer.toByteArray()
    }
}
