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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.util.Processor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassReader.SKIP_FRAMES
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
import org.objectweb.asm.ClassWriter.COMPUTE_MAXS
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.ASM8
import org.objectweb.asm.Type
import org.objectweb.asm.commons.AdviceAdapter
import org.objectweb.asm.commons.Method
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.jvm.javaMethod

private const val COMPOSITE_ELEMENT_CLASS = "com.intellij.psi.impl.source.tree.CompositeElement"
private const val STUB_INDEX_IMPL_CLASS = "com.intellij.psi.stubs.StubIndexImpl"
private val COMPOSITE_ELEMENT_JVM_CLASS = COMPOSITE_ELEMENT_CLASS.replace('.', '/')
private val STUB_INDEX_IMPL_JVM_CLASS = STUB_INDEX_IMPL_CLASS.replace('.', '/')
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
        val stubIndexImplClass = classes.firstOrNull { it.name == STUB_INDEX_IMPL_CLASS }
        if (stubIndexImplClass == null) {
            LOG.error("Failed to get $stubIndexImplClass class.")
        }

        VfsTracerTrampoline.installHook(VfsTracerHookImpl())

        transformer = TracerClassFileTransformer()
        instrumentation.addTransformer(transformer, true)
        instrumentation.retransformClasses(compositeElementClass)
        instrumentation.retransformClasses(stubIndexImplClass)
    }

    fun stopVfsTracing() {
        if (transformer != null) {
            val instrumentation = AgentLoader.instrumentation
            instrumentation?.removeTransformer(transformer)
        }
    }

    fun collectAndReset(): VirtualFileTree =
        VirtualFileTracerImpl.collectAndReset()
}

private object VirtualFileTracerImpl {
    var currentTree = MutableVirtualFileTree.createRoot()
    val lock = ReentrantLock()

    fun collectAndReset(): VirtualFileTree {
        lock.withLock {
            val tree = currentTree
            currentTree = MutableVirtualFileTree.createRoot()
            return tree
        }
    }

    fun incrementStats(fileName: String, stubIndexAccesses: Int = 0, psiWraps: Int = 0) {
        lock.withLock {
            currentTree.accumulate(fileName, stubIndexAccesses, psiWraps)
        }
    }
}

private class VfsTracerHookImpl: VfsTracerHook {
    override fun onPsiElementCreate(psiElement: Any?) {
        if (psiElement is PsiElement) {
            val fileName = getFileName(psiElement)
            if (fileName != null) {
                VirtualFileTracerImpl.incrementStats(fileName, psiWraps = 1)
            }
        }
    }

    override fun wrapStubIndexProcessor(processor: Any?): Any? {
        if (processor == null) {
            return null
        }

        @Suppress("UNCHECKED_CAST")
        processor as Processor<PsiElement>

        return Processor<PsiElement> {
            val fileName = getFileName(it)
            if (fileName != null) {
                VirtualFileTracerImpl.incrementStats(fileName, stubIndexAccesses = 1)
            }
            processor.process(it)
        }
    }

    private fun getFileName(psiElement: PsiElement?): String? {
        if (psiElement != null && psiElement.isValid) {
            val file = psiElement.containingFile
            val virtualFile = file.virtualFile
            return virtualFile?.canonicalPath
        }
        return null
    }
}

private class TracerClassFileTransformer: ClassFileTransformer {
    companion object {
        val HOOK_CLASS_JVM_NAME: String = Type.getInternalName(VfsTracerTrampoline::class.java)
        val ON_PSI_ELEMENT_CREATE: Method = Method.getMethod(VfsTracerTrampoline::onPsiElementCreate.javaMethod)
        val WRAP_STUB_INDEX_PROCESSOR: Method = Method.getMethod(VfsTracerTrampoline::wrapStubIndexProcessor.javaMethod)
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
            return when (className) {
                COMPOSITE_ELEMENT_JVM_CLASS -> tryTransformCompositeElement(classfileBuffer)
                STUB_INDEX_IMPL_JVM_CLASS -> tryTransformStubIndex(classfileBuffer)
                else -> null
            }
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
                            HOOK_CLASS_JVM_NAME,
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

    private fun tryTransformStubIndex(classBytes: ByteArray): ByteArray {
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
                if (name != "processElements" || descriptor != "(Lcom/intellij/psi/stubs/StubIndexKey;Ljava/lang/Object;Lcom/intellij/openapi/project/Project;Lcom/intellij/psi/search/GlobalSearchScope;Lcom/intellij/util/indexing/IdFilter;Ljava/lang/Class;Lcom/intellij/util/Processor;)Z") {
                    return super.visitMethod(access, name, descriptor, signature, exceptions)
                }

                val methodWriter = cv.visitMethod(access, name, descriptor, signature, exceptions)

                return object: AdviceAdapter(ASM_API, methodWriter, access, name, descriptor) {
                    override fun onMethodEnter() {
                        mv.visitVarInsn(Opcodes.ALOAD, 7)
                        mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            HOOK_CLASS_JVM_NAME,
                            WRAP_STUB_INDEX_PROCESSOR.name,
                            WRAP_STUB_INDEX_PROCESSOR.descriptor,
                            false
                        )
                        mv.visitVarInsn(Opcodes.ASTORE, 7)
                    }
                }
            }
        }

        reader.accept(classVisitor, SKIP_FRAMES)
        return writer.toByteArray()
    }
}
