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

import com.google.idea.perf.TracerController
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager.getApplication
import com.intellij.openapi.rd.attachChild

class VfsTracerController(
    private val view: VfsTracerView,
    parentDisposable: Disposable
): TracerController("VFS Tracer", view) {
    private val accumulatedStats = MutableVirtualFileTree.createRoot()

    init {
        parentDisposable.attachChild(this)
    }

    override fun onControllerInitialize() {
    }

    override fun updateModel(): Boolean {
        return true
    }

    override fun updateUi() {
        val treeStats = VirtualFileTracer.collectAndReset()

        if (treeStats.children.isNotEmpty()) {
            accumulatedStats.accumulate(treeStats)
            val listStats = accumulatedStats.flattenedList()

            getApplication().invokeAndWait {
                view.listView.setStats(listStats)
                view.treeView.setStats(accumulatedStats)
            }
        }
    }

    override fun handleRawCommandFromEdt(text: String) {
        val command = text.trim()
        if (command == "start") {
            VirtualFileTracer.startVfsTracing()
        }
        else if (command == "stop") {
            VirtualFileTracer.stopVfsTracing()
        }
    }
}
