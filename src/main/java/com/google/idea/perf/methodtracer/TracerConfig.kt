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

package com.google.idea.perf.methodtracer

import com.google.idea.perf.agent.MethodListener
import com.google.idea.perf.util.ConcurrentAppendOnlyList
import com.intellij.util.PatternUtil
import org.objectweb.asm.Type
import java.lang.reflect.Method
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern
import kotlin.concurrent.withLock

// Things to improve:
// - Somehow gc or recycle Tracepoints that are no longer used.
// - Pretty-print method descriptors for better UX.

sealed class TracePattern {
    data class Exact(val method: Method): TracePattern()
    data class ByMethodName(val className: String, val methodName: String): TracePattern()
    data class ByMethodPattern(val className: String, val methodPattern: String): TracePattern()
    data class ByClassPattern(val classPattern: String): TracePattern()
}

/** Keeps track of which methods should be traced via bytecode instrumentation. */
object TracerConfig {
    private val tracepoints = ConcurrentAppendOnlyList<Tracepoint>()
    private val lock = ReentrantLock() // Protects the data structures below.
    private val classConfigs = mutableMapOf<String, ClassConfig>() // Keyed by 'JVM' class name.

    private class TracepointProperties(
        val flags: Int = TracepointFlags.TRACE_ALL,
        val parameters: Int = 0
    ) {
        companion object {
            val DEFAULT = TracepointProperties()
        }
    }

    /** Specifies which methods to instrument for a given class. */
    private class ClassConfig {
        /** Set of simple method names to instrumental and their associated properties. */
        val methodPatterns = LinkedHashMap<Pattern, TracepointProperties>()

        /** Map from method signature to method ID. */
        val methodIds = mutableMapOf<String, Int>()
    }

    fun trace(pattern: TracePattern, flags: Int, parameters: Collection<Int>) {
        when (pattern) {
            is TracePattern.Exact -> setTrace(pattern.method, flags, parameters)
            is TracePattern.ByMethodName -> setTrace(pattern.className, pattern.methodName, flags, parameters)
            is TracePattern.ByMethodPattern -> setTrace(pattern.className, pattern.methodPattern, flags, parameters)
            is TracePattern.ByClassPattern -> setTrace(pattern.classPattern, flags, parameters)
        }
    }

    fun untrace(pattern: TracePattern) {
        when (pattern) {
            is TracePattern.Exact -> setTrace(pattern.method, 0, emptyList())
            is TracePattern.ByMethodName -> setTrace(pattern.className, pattern.methodName, 0, emptyList())
            is TracePattern.ByMethodPattern -> setTrace(pattern.className, pattern.methodPattern, 0, emptyList())
            is TracePattern.ByClassPattern -> setTrace(pattern.classPattern, 0, emptyList())
        }
    }

    private fun setTrace(method: Method, flags: Int, parameters: Collection<Int>) {
        val classJvmName = method.declaringClass.name.replace('.', '/')
        var parameterBits = 0
        for (index in parameters) {
            parameterBits = parameterBits or (1 shl index)
        }

        val methodDesc = Type.getMethodDescriptor(method)
        val methodSignature = "${method.name}$methodDesc"

        lock.withLock {
            val methodId = if (flags and TracepointFlags.TRACE_ALL == 0) {
                getMethodId(classJvmName, method.name, methodDesc)
            }
            else {
                val classConfig = classConfigs.getOrPut(classJvmName) { ClassConfig() }
                classConfig.methodIds.getOrPut(methodSignature) {
                    val tracepoint = createTracepoint(
                        classJvmName, method.name, methodDesc, TracepointProperties.DEFAULT
                    )
                    tracepoints.append(tracepoint)
                }
            }

            if (methodId != null) {
                val tracepoint = getTracepoint(methodId)
                tracepoint.parameters.set(parameterBits)
                tracepoint.setFlags(flags)
            }
        }
    }

    private fun setTrace(
        className: String,
        methodPattern: String,
        flags: Int,
        parameters: Collection<Int>
    ) {
        val classJvmName = className.replace('.', '/')
        val methodRegex = Pattern.compile(PatternUtil.convertToRegex(methodPattern))
        var parameterBits = 0
        for (index in parameters) {
            parameterBits = parameterBits or (1 shl index)
        }

        lock.withLock {
            if (flags and TracepointFlags.TRACE_ALL == 0) {
                val classConfig = classConfigs[classJvmName] ?: return

                for ((signature, _) in classConfig.methodIds) {
                    val methodName = signature.substringBefore('(')

                    if (methodRegex.matcher(methodName).matches()) {
                        val methodDesc = signature.substring(methodPattern.length)
                        val methodId = getMethodId(classJvmName, methodPattern, methodDesc)
                        if (methodId != null) {
                            val tracepoint = getTracepoint(methodId)
                            tracepoint.unsetFlags(TracepointFlags.TRACE_ALL)
                            tracepoint.parameters.set(0)
                        }
                    }
                }
            }
            else {
                val classConfig = classConfigs.getOrPut(classJvmName) { ClassConfig() }
                classConfig.methodPatterns[methodRegex] = TracepointProperties(flags, parameterBits)

                // If the tracepoint already exists, set tracepoint properties.
                for ((signature, methodId) in classConfig.methodIds) {
                    val methodName = signature.substringBefore('(')

                    if (methodRegex.matcher(methodName).matches()) {
                        val tracepoint = getTracepoint(methodId)
                        tracepoint.setFlags(flags)
                        tracepoint.parameters.set(parameterBits)
                    }
                }
            }
        }
    }

    private fun setTrace(classPattern: String, flags: Int, parameters: Collection<Int>) {
    }

    /** Remove all tracing and return the affected class names. */
    fun untraceAll(): List<String> {
        lock.withLock {
            val classNames = classConfigs.keys.map { it.replace('/', '.') }
            classConfigs.clear()
            return classNames
        }
    }

    fun shouldInstrumentClass(classJvmName: String): Boolean {
        lock.withLock {
            val classConfig = classConfigs[classJvmName] ?: return false
            return classConfig.methodPatterns.isNotEmpty() || classConfig.methodIds.isNotEmpty()
        }
    }

    /**
     * Returns the method ID to be used for [MethodListener] events,
     * or null if the given method should not be instrumented.
     */
    fun getMethodId(classJvmName: String, methodName: String, methodDesc: String): Int? {
        lock.withLock {
            val classConfig = classConfigs[classJvmName] ?: return null
            val methodSignature = "$methodName$methodDesc"

            val existingId = classConfig.methodIds[methodSignature]
            if (existingId != null) {
                return existingId
            }

            val properties = classConfig.methodPatterns.entries.firstOrNull {
                it.key.matcher(methodName).matches()
            }?.value

            if (properties != null) {
                val tracepoint = createTracepoint(classJvmName, methodName, methodDesc, properties)
                val newId = tracepoints.append(tracepoint)
                classConfig.methodIds[methodSignature] = newId
                return newId
            }

            return null
        }
    }

    fun getTracepoint(methodId: Int): Tracepoint = tracepoints.get(methodId)

    private fun createTracepoint(
        classJvmName: String,
        methodName: String,
        methodDesc: String,
        properties: TracepointProperties
    ): Tracepoint {
        val classShortName = classJvmName.substringAfterLast('/')
        val className = classJvmName.replace('/', '.')
        return Tracepoint(
            displayName = "$classShortName.$methodName()",
            description = "$className#$methodName$methodDesc",
            flags = properties.flags,
            parameters = properties.parameters
        )
    }
}
