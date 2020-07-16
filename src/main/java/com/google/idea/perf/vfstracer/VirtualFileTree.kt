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

import java.io.File
import java.util.*
import javax.swing.tree.TreePath

interface VirtualFileTree {
    val name: String
    val psiWraps: Int
    val reparseCount: Int
    val children: Map<String, VirtualFileTree>

    val isDirectory: Boolean get() = children.isNotEmpty()
    val isFile: Boolean get() = children.isEmpty()

    fun statEquals(other: VirtualFileTree): Boolean =
        psiWraps == other.psiWraps && reparseCount == other.reparseCount
}

class MutableVirtualFileTree(
    override val name: String
): VirtualFileTree {
    companion object {
        fun createRoot() = MutableVirtualFileTree("[root]")
    }

    override var psiWraps: Int = 0
    override var reparseCount: Int = 0
    override val children: MutableMap<String, MutableVirtualFileTree> = TreeMap()

    fun accumulate(tree: VirtualFileTree) {
        for ((childName, child) in tree.children) {
            val thisChild = children.getOrPut(childName) { MutableVirtualFileTree(childName) }
            thisChild.accumulate(child)
        }

        psiWraps += tree.psiWraps
        reparseCount += tree.reparseCount
    }

    fun accumulate(path: String, psiWraps: Int, reparseCount: Int) {
        val parts = getParts(path)
        var tree = this

        for (part in parts) {
            val child = tree.children.getOrPut(part) { MutableVirtualFileTree(part) }
            child.psiWraps += psiWraps
            child.reparseCount += reparseCount
            tree = child
        }
    }

    private fun getParts(path: String): List<String> {
        var file = File(path)
        val parts = mutableListOf<String>()
        do {
            val name = file.name
            parts.add(name)
            file = file.parentFile
        }
        while (file.parentFile != null)

        return parts.reversed()
    }

    override fun toString(): String = name
}

class VirtualFileTreePath(val parts: Array<MutableVirtualFileTree>) {
    fun toTreePath(): TreePath = TreePath(parts)
}

interface TreePatchEventListener {
    fun onTreeInsert(
        path: VirtualFileTreePath,
        parent: MutableVirtualFileTree,
        child: MutableVirtualFileTree
    )

    fun onTreeChange(
        path: VirtualFileTreePath,
        parent: MutableVirtualFileTree,
        child: MutableVirtualFileTree,
        newChild: VirtualFileTree
    )

    fun onTreeRemove(
        path: VirtualFileTreeDiff,
        parent: MutableVirtualFileTree,
        child: MutableVirtualFileTree
    )
}

class VirtualFileTreeDiff private constructor(
    private val underlyingTree: MutableVirtualFileTree,
    private val newTree: VirtualFileTree,
    private val insertedChildren: Map<String, VirtualFileTreeDiff>,
    private val changedChildren: Map<String, VirtualFileTreeDiff>
): VirtualFileTree {
    override val name get() = underlyingTree.name
    override val psiWraps: Int get() = underlyingTree.psiWraps
    override val reparseCount: Int get() = underlyingTree.reparseCount
    override val children: Map<String, VirtualFileTree> get() = underlyingTree.children

    fun patch(listener: TreePatchEventListener) {
        fun build(path: Stack<MutableVirtualFileTree>) = VirtualFileTreePath(path.toTypedArray())

        fun impl(
            pathBuilder: Stack<MutableVirtualFileTree>,
            treeDiff: VirtualFileTreeDiff
        ) {
            val underlyingTree = treeDiff.underlyingTree
            pathBuilder.push(underlyingTree)

            for ((childName, newChild) in treeDiff.insertedChildren) {
                val child = underlyingTree.children[childName]
                check(child == null)
                listener.onTreeInsert(
                    build(pathBuilder),
                    underlyingTree,
                    newChild.underlyingTree
                )
                impl(pathBuilder, newChild)
            }

            for ((childName, newChild) in treeDiff.changedChildren) {
                val child = underlyingTree.children[childName]
                check(child === newChild.underlyingTree)
                listener.onTreeChange(
                    build(pathBuilder),
                    underlyingTree,
                    newChild.underlyingTree,
                    newChild.newTree
                )
                impl(pathBuilder, newChild)
            }

            pathBuilder.pop()
        }

        val pathBuilder = Stack<MutableVirtualFileTree>()
        impl(pathBuilder, this)
    }

    companion object {
        fun create(
            oldTree: MutableVirtualFileTree?,
            newTree: VirtualFileTree
        ): VirtualFileTreeDiff {
            val insertedChildren = mutableMapOf<String, VirtualFileTreeDiff>()
            val changedChildren = mutableMapOf<String, VirtualFileTreeDiff>()

            if (oldTree != null) {
                for ((childName, newChild) in newTree.children) {
                    val oldChild = oldTree.children[childName]
                    val childDiff = create(oldChild, newChild)
                    if (oldChild == null) {
                        insertedChildren[childDiff.name] = childDiff
                    }
                    else if (!oldChild.statEquals(newChild)) {
                        changedChildren[childDiff.name] = childDiff
                    }
                }

                return VirtualFileTreeDiff(oldTree, newTree, insertedChildren, changedChildren)
            }
            else {
                for ((childName, child) in newTree.children) {
                    insertedChildren[childName] = create(null, child)
                }

                return VirtualFileTreeDiff(
                    MutableVirtualFileTree(newTree.name).apply {
                        psiWraps = newTree.psiWraps
                        reparseCount = newTree.reparseCount
                    },
                    newTree,
                    insertedChildren,
                    changedChildren
                )
            }
        }
    }
}
