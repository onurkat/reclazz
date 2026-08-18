/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Attaching to a running JVM is being taken away, on a published schedule.
 *
 * JEP 451 warns from Java 21 and will disallow dynamic agent loading in a
 * future release; a server started with `-XX:-EnableDynamicAgentLoading`
 * refuses today. Measured against a JVM started that way, the attach fails
 * with:
 *
 * ```
 * AgentLoadException: Failed to load agent library: Dynamic agent loading is
 * not enabled. Use -XX:+EnableDynamicAgentLoading to launch target VM.
 * ```
 *
 * That went to the generic branch, so what reached the developer was "Failed to
 * attach to PID 1234: Failed to load agent library: Dynamic agent loading is
 * not enabled...", one useful sentence wrapped in two that add nothing.
 *
 * The JVM's sentence also stops exactly where the next question starts: where
 * does the flag go. That depends on how the server was launched, and on SAP
 * Commerce the answer is a file Reclazz already writes its own agent line into,
 * which puts it in a position to say so.
 */
class AttachDiagnosticsTest {

    private fun attacherSource(): String =
        File("src/main/kotlin/com/onurkat/reclazz/plugin/agent/JvmAttacher.kt").readText()

    @Test
    fun `the refusal has its own branch`() {
        assertTrue(
            attacherSource().contains("Dynamic agent loading is not enabled"),
            "without it the message falls through to the generic wrapper",
        )
    }

    @Test
    fun `it says where the flag goes on both platforms`() {
        val source = attacherSource()
        assertTrue(
            source.contains("95-local.properties"),
            "on SAP Commerce the flag belongs in the file Reclazz already writes into",
        )
        assertTrue(
            source.contains("run configuration"),
            "and on Spring Boot in the run configuration's VM options",
        )
        assertTrue(
            source.contains("-XX:+EnableDynamicAgentLoading"),
            "the flag has to be copyable straight out of the message",
        )
    }

    /**
     * Attaching is the fallback. A server started with the agent already on its
     * command line is untouched by any of this, and saying so turns a dead end
     * into a choice.
     */
    @Test
    fun `it points out that starting with the agent avoids this entirely`() {
        assertTrue(
            attacherSource().contains("already on the command line"),
            "there is a way around this, not only a flag to add",
        )
    }
}
