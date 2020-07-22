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

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.fail

private fun assertStats(
    expectedStubIndexAccesses: Int,
    expectedPsiElementWraps: Int,
    tree: VirtualFileTree,
    vararg path: String
) {
    var targetNode: VirtualFileTree? = tree
    for (part in path) {
        if (targetNode != null) {
            targetNode = targetNode.children[part]
        }
        else {
            fail("Node ${path.joinToString("/")} does not exist.")
        }
    }
    assertEquals(expectedStubIndexAccesses, targetNode!!.stubIndexAccesses)
    assertEquals(expectedPsiElementWraps, targetNode.psiElementWraps)
}

class VirtualFileTreeTest {
    @Test
    fun testPathAccumulator() {
        val tree = MutableVirtualFileTree.createRoot()
        tree.accumulate("/com/example/Main.java", psiElementWraps = 1)
        tree.accumulate("/com/example/util/A.java", stubIndexAccesses = 2, psiElementWraps = 1)
        tree.accumulate("/com/example/util/B.java",  stubIndexAccesses = 2, psiElementWraps = 1)
        tree.accumulate("/java/lang/String.class", stubIndexAccesses = 100)
        tree.accumulate("/java/lang/System.class", stubIndexAccesses = 200)

        assertStats(304, 3, tree)
        assertStats(4, 3, tree, "com")
        assertStats(4, 3, tree, "com", "example")
        assertStats(0, 1, tree, "com", "example", "Main.java")
        assertStats(4, 2, tree, "com", "example", "util")
        assertStats(2, 1, tree, "com", "example", "util", "B.java")
        assertStats(2, 1, tree, "com", "example", "util", "B.java")
        assertStats(300, 0, tree, "java")
        assertStats(300, 0, tree, "java", "lang")
        assertStats(100, 0, tree, "java", "lang", "String.class")
        assertStats(200, 0, tree, "java", "lang", "System.class")
    }

    @Test
    fun testTreeAccumulator() {
        val accumulatedTree = MutableVirtualFileTree.createRoot()
        val emptyTree = MutableVirtualFileTree.createRoot()
        val tree1 = MutableVirtualFileTree.createRoot().apply {
            stubIndexAccesses = 100
            psiElementWraps = 1
            children["com"] = MutableVirtualFileTree("com").apply {
                stubIndexAccesses = 0
                psiElementWraps = 1
                children["example"] = MutableVirtualFileTree("example").apply {
                    stubIndexAccesses = 0
                    psiElementWraps = 1
                    children["Main.java"] = MutableVirtualFileTree("Main.java").apply {
                        stubIndexAccesses = 0
                        psiElementWraps = 1
                    }
                }
            }
            children["java"] = MutableVirtualFileTree("java").apply {
                stubIndexAccesses = 100
                psiElementWraps = 0
                children["lang"] = MutableVirtualFileTree("lang").apply {
                    stubIndexAccesses = 100
                    psiElementWraps = 0
                    children["String.class"] = MutableVirtualFileTree("String.class").apply {
                        stubIndexAccesses = 100
                        psiElementWraps = 0
                    }
                }
            }
        }
        val tree2 = MutableVirtualFileTree.createRoot().apply {
            stubIndexAccesses = 204
            psiElementWraps = 2
            children["com"] = MutableVirtualFileTree("com").apply {
                stubIndexAccesses = 4
                psiElementWraps = 2
                children["example"] = MutableVirtualFileTree("example").apply {
                    stubIndexAccesses = 4
                    psiElementWraps = 2
                    children["util"] = MutableVirtualFileTree("util").apply {
                        stubIndexAccesses = 4
                        psiElementWraps = 2
                        children["A.java"] = MutableVirtualFileTree("A.java").apply {
                            stubIndexAccesses = 2
                            psiElementWraps = 1
                        }
                        children["B.java"] = MutableVirtualFileTree("B.java").apply {
                            stubIndexAccesses = 2
                            psiElementWraps = 1
                        }
                    }
                }
            }
            children["java"] = MutableVirtualFileTree("java").apply {
                stubIndexAccesses = 200
                psiElementWraps = 0
                children["lang"] = MutableVirtualFileTree("lang").apply {
                    stubIndexAccesses = 200
                    psiElementWraps = 0
                    children["System.class"] = MutableVirtualFileTree("System.class").apply {
                        stubIndexAccesses = 200
                        psiElementWraps = 0
                    }
                }
            }
        }

        accumulatedTree.accumulate(emptyTree)
        assertStats(0, 0, accumulatedTree)

        accumulatedTree.accumulate(tree1)
        assertStats(100, 1, accumulatedTree)
        assertStats(0, 1, accumulatedTree, "com")
        assertStats(0, 1, accumulatedTree, "com", "example")
        assertStats(0, 1, accumulatedTree, "com", "example", "Main.java")
        assertStats(100, 0, accumulatedTree, "java")
        assertStats(100, 0, accumulatedTree, "java", "lang")
        assertStats(100, 0, accumulatedTree, "java", "lang", "String.class")

        accumulatedTree.accumulate(tree2)
        assertStats(304, 3, accumulatedTree)
        assertStats(4, 3, accumulatedTree, "com")
        assertStats(4, 3, accumulatedTree, "com", "example")
        assertStats(0, 1, accumulatedTree, "com", "example", "Main.java")
        assertStats(4, 2, accumulatedTree, "com", "example", "util")
        assertStats(2, 1, accumulatedTree, "com", "example", "util", "B.java")
        assertStats(2, 1, accumulatedTree, "com", "example", "util", "B.java")
        assertStats(300, 0, accumulatedTree, "java")
        assertStats(300, 0, accumulatedTree, "java", "lang")
        assertStats(100, 0, accumulatedTree, "java", "lang", "String.class")
        assertStats(200, 0, accumulatedTree, "java", "lang", "System.class")

        accumulatedTree.accumulate(emptyTree)
        assertStats(304, 3, accumulatedTree)
        assertStats(4, 3, accumulatedTree, "com")
        assertStats(4, 3, accumulatedTree, "com", "example")
        assertStats(0, 1, accumulatedTree, "com", "example", "Main.java")
        assertStats(4, 2, accumulatedTree, "com", "example", "util")
        assertStats(2, 1, accumulatedTree, "com", "example", "util", "B.java")
        assertStats(2, 1, accumulatedTree, "com", "example", "util", "B.java")
        assertStats(300, 0, accumulatedTree, "java")
        assertStats(300, 0, accumulatedTree, "java", "lang")
        assertStats(100, 0, accumulatedTree, "java", "lang", "String.class")
        assertStats(200, 0, accumulatedTree, "java", "lang", "System.class")
    }
}
