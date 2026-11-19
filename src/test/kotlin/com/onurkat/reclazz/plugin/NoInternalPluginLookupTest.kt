/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

class NoInternalPluginLookupTest {
    @Test
    fun missingAgentPluginWarningNamesTheCurrentDescriptorLookup() {
        val text = File("src/main/kotlin/com/onurkat/reclazz/plugin/agent/AgentJarLocator.kt").readText()
        assertTrue(text.contains("No enabled Reclazz plugin descriptor available via PluginPresence"))
        assertTrue(!text.contains("not found in PluginManager"))
    }

    @Test
    fun `plugin sources avoid the lookup that is internal on 262`() {
        val root = File("src/main/kotlin")
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "no plugin sources found to check")
        val forbidden = Regex("\\bfindEnabledPlugin\\s*\\(")
        val hits = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (forbidden.containsMatchIn(line.substringBefore("//")))
                    "${file.relativeTo(root)}:${index + 1}" else null
            }
        }
        assertTrue(hits.isEmpty(), "Internal plugin lookup at: ${hits.joinToString()}")
    }
}
