/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
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
    @Test
    fun compiledToolWindowFactoryDoesNotOverrideInternalIdeDefaults() {
        val path = "com/onurkat/reclazz/plugin/ui/ReloadToolWindowFactory.class"
        val input = javaClass.classLoader.getResourceAsStream(path)
            ?: error("Compiled tool-window factory is missing")
        val node = input.use { stream -> ClassNode().also { ClassReader(stream).accept(it, ClassReader.SKIP_FRAMES) } }
        // These IDE defaults are internal on our supported 233–252 targets.
        // Compiler compatibility bridges are overrides too, although no source names them.
        val internalDefaults = setOf("getAnchor", "getIcon", "manage")
        val overrides = node.methods.filter { it.name in internalDefaults }.map { it.name + it.desc }
        assertTrue(overrides.isEmpty(), "Compiler introduced internal IDE overrides: ${overrides.joinToString()}")
    }
}
