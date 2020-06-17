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

import org.junit.Test
import kotlin.test.assertEquals

private fun assertCommand(expected: TracerCommand, actual: String) {
    assertEquals(expected, parseTracerCommand(actual))
}

class TracerCommandTest {
    @Test
    fun testCommandParser() {
        assertCommand(TracerCommand.Clear, "clear")
        assertCommand(TracerCommand.Reset, "reset")

        // Untrace commands.
        assertCommand(
            TracerCommand.Trace(false, TraceOption.ALL, TraceTarget.PsiFinders),
            "untrace psi-finders"
        )
        assertCommand(
            TracerCommand.Trace(false, TraceOption.ALL, TraceTarget.PsiFinders),
            "untrace all psi-finders"
        )
        assertCommand(
            TracerCommand.Trace(false, TraceOption.ALL, TraceTarget.Tracer),
            "untrace tracer"
        )
        assertCommand(
            TracerCommand.Trace(false, TraceOption.CALL_COUNT, TraceTarget.Tracer),
            "untrace count tracer"
        )
        assertCommand(
            TracerCommand.Trace(
                false,
                TraceOption.ALL,
                TraceTarget.Method("com.example.MyAction", "actionPerformed")
            ),
            "untrace com.example.MyAction#actionPerformed"
        )
        assertCommand(
            TracerCommand.Trace(
                false,
                TraceOption.WALL_TIME,
                TraceTarget.Method("com.example.MyAction", "actionPerformed")
            ),
            "untrace wall-time com.example.MyAction#actionPerformed"
        )

        // Trace commands.
        assertCommand(
            TracerCommand.Trace(true, TraceOption.ALL, TraceTarget.PsiFinders),
            "trace psi-finders"
        )
        assertCommand(
            TracerCommand.Trace(true, TraceOption.WALL_TIME, TraceTarget.PsiFinders),
            "trace wall-time psi-finders"
        )
        assertCommand(
            TracerCommand.Trace(true, TraceOption.ALL, TraceTarget.Tracer),
            "trace tracer"
        )
        assertCommand(
            TracerCommand.Trace(true, TraceOption.CALL_COUNT, TraceTarget.Tracer),
            "trace count tracer"
        )
        assertCommand(
            TracerCommand.Trace(
                true,
                TraceOption.ALL,
                TraceTarget.Method("com.example.MyAction", "actionPerformed")
            ),
            "trace com.example.MyAction#actionPerformed"
        )
        assertCommand(
            TracerCommand.Trace(
                true,
                TraceOption.ALL,
                TraceTarget.Method("com.example.MyAction", "actionPerformed")
            ),
            "trace all com.example.MyAction#actionPerformed"
        )
    }
}