/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.onurkat.reclazz.plugin.ui.plural
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The status bar after the first reload of a session.
 *
 * The widget is the one Reclazz surface that is on screen all day. Watched
 * live in a sandbox IDE: a single method added to a single class, the tool
 * window logged the reload, and the status bar read "Reclazz: 1 reloads".
 * The agent said the same kind of thing about the same event in the same
 * second, which is why both sides got the same fix and both got a test.
 *
 * The second test is the guard. The old wording was an interpolated string
 * with " reloads" welded to it, so it is the literal that must not come back.
 */
class StatusBarCountsReadAsEnglishTest {

    @Test
    fun `one reload is one reload`() {
        assertEquals("1 reload", plural(1, "reload"))
        assertEquals("2 reloads", plural(2, "reload"))
        assertEquals("0 reloads", plural(0, "reload"),
            "zero takes the plural: a freshly connected agent has reloaded nothing yet")
    }

    @Test
    fun `an irregular noun brings its own plural`() {
        assertEquals("1 property", plural(1, "property", "properties"))
        assertEquals("3 properties", plural(3, "property", "properties"))
    }

    @Test
    fun `no surface welds the plural s onto the count`() {
        val offenders = surfaces.flatMap { owner ->
            stringsIn(owner).filter { welded(it) }.map { "$owner: \"$it\"" }
        }

        assertTrue(offenders.isEmpty(),
            "these surfaces spell the plural into the string, so a count of one " +
            "reads as \"1 reloads\":\n" + offenders.joinToString("\n"))
    }

    private val surfaces = listOf(
        "com/onurkat/reclazz/plugin/ui/ReloadStatusWidget",
        "com/onurkat/reclazz/plugin/ui/ReloadLogPanel",
        "com/onurkat/reclazz/plugin/settings/ReclazzConfigurable",
    )

    /**
     * The plural spelled straight after a count.
     *
     * Two shapes, because a Kotlin template does not always compile to the
     * same thing. Under invokedynamic string concatenation the whole sentence
     * survives as one recipe with \u0001 standing in for each value, so the
     * count reads as "\u0001 reloads"; where the compiler emits separate
     * constants instead, the fragment simply opens with " reloads".
     *
     * Prose that merely uses the word is not this: "faster reloads" in a
     * settings label has no count in front of it, and the first pass of this
     * guard failed on two such labels.
     */
    private fun welded(text: String): Boolean =
        text.startsWith(" reloads") || text.contains("\u0001 reloads")

    private fun stringsIn(owner: String): List<String> {
        val node = classes[owner] ?: fail("$owner not found in the compiled output")
        val nested = classes.keys.filter { it.startsWith("$owner\$") }.map { classes.getValue(it) }
        return (listOf(node) + nested).flatMap { n ->
            n.methods.flatMap { m ->
                m.instructions.toArray().flatMap { insn ->
                    when (insn) {
                        is LdcInsnNode -> listOfNotNull(insn.cst as? String)
                        // Where the constant lives once the compiler builds the
                        // string with makeConcatWithConstants: in the bootstrap
                        // arguments, not in the instruction stream. Reading only
                        // LDC made the first version of this guard pass with the
                        // regression put back on purpose.
                        is InvokeDynamicInsnNode -> insn.bsmArgs.filterIsInstance<String>()
                        else -> emptyList()
                    }
                }
            }
        }
    }

    private val classes: Map<String, ClassNode> by lazy {
        listOf(File("build/classes/kotlin/main"), File("build/classes/java/main"))
            .filter { it.isDirectory }
            .asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "class" }
            .map { ClassNode().also { n -> ClassReader(it.readBytes()).accept(n, ClassReader.SKIP_FRAMES) } }
            .associateBy { it.name }
    }
}
