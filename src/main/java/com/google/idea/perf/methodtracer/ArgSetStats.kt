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

class ArgSetStats(
    val args: String?,
    var callCount: Long = 0L,
    var wallTime: Long = 0L,
    var maxWallTime: Long = 0L
)

class ArgStatMap(
    val tracepoints: Map<Tracepoint, List<ArgSetStats>>
) {
    companion object {
        fun fromCallTree(root: CallTree): ArgStatMap {
            val allStats = mutableMapOf<Tracepoint, MutableMap<String?, ArgSetStats>>()
            val ancestors = mutableSetOf<TracepointInstance>()

            fun dfs(node: CallTree) {
                val nonRecursive = node.tracepointInstance !in ancestors

                val arguments = node.tracepointInstance.arguments
                val statsForTracepoint = allStats.getOrPut(node.tracepointInstance.tracepoint) { mutableMapOf() }
                val stats = statsForTracepoint.getOrPut(arguments) { ArgSetStats(arguments) }

                stats.callCount += node.callCount
                if (nonRecursive) {
                    stats.wallTime += node.wallTime
                    stats.maxWallTime = maxOf(stats.maxWallTime, node.maxWallTime)
                    ancestors.add(node.tracepointInstance)
                }

                for (child in node.children.values) {
                    dfs(child)
                }

                if (nonRecursive) {
                    ancestors.remove(node.tracepointInstance)
                }
            }

            dfs(root)
            assert(ancestors.isEmpty())

            return ArgStatMap(allStats.mapValues { it.value.values.toList() })
        }
    }
}
