/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * The plugin and the agent agree on the arguments between them by convention,
 * and nothing checks that agreement at build time: the plugin writes a string
 * and the agent parses it.
 *
 * An argument the agent does not know is not an error there. It is dropped,
 * quietly, and the setting the user switched on in the IDE simply does nothing.
 * That is the kind of failure this project keeps having to hunt down, so the
 * two lists are compared here instead.
 */
class AgentArgumentContractTest {

    @Test
    fun `every argument the plugin sends is one the agent parses`() {
        val sent = keysSentByThePlugin()
        val understood = keysTheAgentKnows()

        assertTrue(sent.isNotEmpty(), "no arguments found in the plugin; the parse below is wrong")
        assertTrue(understood.isNotEmpty(), "no known keys found in the agent; the parse above is wrong")

        val ignored = sent - understood
        assertTrue(
            ignored.isEmpty(),
            "the agent would drop these without a word, and the setting behind them " +
                "would do nothing: $ignored",
        )
    }

    /**
     * The multi-value arguments are joined one way and split another, and a
     * mismatch there costs every directory after the first.
     */
    @Test
    fun `directories are joined the way the agent splits them`() {
        val plugin = source("src/main/kotlin/com/onurkat/reclazz/plugin/agent/AgentJarLocator.kt").readText()
        val agent = source("agent/src/main/java/com/onurkat/reclazz/agent/AgentConfig.java").readText()

        assertTrue(
            plugin.contains("""watchDirs=${'$'}{outputDirs.joinToString(";")}"""),
            "the plugin joins watch directories with a semicolon",
        )
        assertTrue(
            agent.contains("""params.get("watchDirs").split(";")"""),
            "so the agent has to split on one",
        )
    }

    private fun keysSentByThePlugin(): Set<String> {
        val text = source("src/main/kotlin/com/onurkat/reclazz/plugin/agent/AgentJarLocator.kt").readText()
        return Regex("""args\.add\("([a-zA-Z]+)=""").findAll(text)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun keysTheAgentKnows(): Set<String> {
        val text = source("agent/src/main/java/com/onurkat/reclazz/agent/AgentConfig.java").readText()
        val block = text.substringAfter("KNOWN_KEYS = Set.of(").substringBefore(");")
        return Regex(""""([a-zA-Z]+)"""").findAll(block)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun source(repoRelative: String): File {
        val direct = File(repoRelative)
        if (direct.isFile) return direct
        val fromModule = File("../$repoRelative")
        check(fromModule.isFile) { "cannot find $repoRelative" }
        return fromModule
    }
}
