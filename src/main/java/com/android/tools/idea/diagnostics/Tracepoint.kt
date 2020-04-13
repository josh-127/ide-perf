package com.android.tools.idea.diagnostics

/** Represents a method (usually) for which we are gathering call counts and timing information. */
class Tracepoint(private val name: String) {
    override fun toString(): String = name

    companion object {
        /** A special tracepoint representing the root of a call tree. */
        val ROOT = Tracepoint("[root]")
    }
}