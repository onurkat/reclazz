/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The status bar widget is the plugin's most-seen surface: unlike the tool
 * window and the settings page, it is on screen the whole time the IDE is.
 * It was also the least considered, and both of its defects took the same
 * shape, a method left at its default.
 *
 *  - `getClickConsumer` returned null, so the one always-visible thing
 *    Reclazz owns could say "Not connected" and do nothing when clicked.
 *    In this platform a status bar item that ignores a click reads as
 *    broken rather than as deliberate.
 *  - `isAvailable` was not overridden at all, so it defaulted to true and
 *    the widget occupied the status bar of every project, including ones
 *    where Reclazz is switched off. The tool window scopes itself on the
 *    same setting; the two disagreeing was the actual bug.
 *
 * Both regress silently. Nothing fails to compile, no test that exercises
 * behaviour goes red, and neither is visible in a diff unless you already
 * know to look. They are properties of the compiled methods, so this reads
 * the compiled methods.
 */
class StatusWidgetSurfaceTest {

    private val factory = "com/onurkat/reclazz/plugin/ui/ReloadStatusWidgetFactory"
    private val widget = "com/onurkat/reclazz/plugin/ui/ReloadStatusWidget"

    /**
     * Not merely "is overridden": a body of `return null` would satisfy that
     * while restoring the dead end exactly. The click has to go somewhere,
     * which at bytecode level means it calls something.
     */
    @Test
    fun `clicking the widget does something`() {
        val method = methodOf(widget, "getClickConsumer")
            ?: fail("ReloadStatusWidget.getClickConsumer is gone; the widget " +
                    "would fall back to ignoring clicks")

        assertTrue(!returnsOnlyNull(method),
            "getClickConsumer returns a bare null, so the widget reports a " +
            "state and offers no way to act on it. Return a Consumer that " +
            "opens the Reclazz tool window.")
    }

    /** And specifically somewhere useful, rather than any call at all. */
    @Test
    fun `the click opens the tool window`() {
        val reached = callsFrom(widget, "getClickConsumer")
        assertTrue(reached.any { it.contains("ToolWindow", ignoreCase = true) },
            "the click handler should reach ToolWindowManager. Calls found: $reached")
    }

    /**
     * The scoping half. A user who has never enabled Reclazz in a project
     * should not be paying for it with permanent status bar real estate.
     */
    @Test
    fun `the widget is only offered where Reclazz is enabled`() {
        val method = methodOf(factory, "isAvailable")
            ?: fail("ReloadStatusWidgetFactory does not override isAvailable, " +
                    "so it defaults to true and the widget appears in every " +
                    "project, including ones where Reclazz is off")

        val reached = callsFrom(factory, "isAvailable")
        assertTrue(reached.any { it.contains("ReclazzSettings") },
            "isAvailable must decide from ReclazzSettings, the same source " +
            "the tool window uses. Calls found: $reached")
        assertTrue(!returnsOnlyNull(method), "isAvailable returns a constant")
    }

    /**
     * Availability derived from a setting is only correct if the platform is
     * told to re-read it. Without this the widget appears or disappears at
     * the next restart, which is a worse experience than not scoping at all:
     * the user unticks a box and nothing happens.
     */
    @Test
    fun `toggling the setting refreshes the widget`() {
        val callers = listOf(
            "com/onurkat/reclazz/plugin/ReclazzActivation" to "enable",
            "com/onurkat/reclazz/plugin/settings/ReclazzConfigurable" to "apply",
        )
        for ((owner, name) in callers) {
            val reached = callsFrom(owner, name)
            assertTrue(reached.any { it.contains("refreshAvailability") },
                "$owner.$name changes the enabled setting but never calls " +
                "ReloadStatusWidgetFactory.refreshAvailability, so the status " +
                "bar keeps showing the old answer until the IDE restarts")
        }
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    private fun methodOf(owner: String, name: String): MethodNode? =
        classes[owner]?.methods?.firstOrNull { it.name == name }

    private fun callsFrom(owner: String, name: String): List<String> {
        val method = methodOf(owner, name) ?: return emptyList()
        val direct = method.instructions.toArray()
            .filterIsInstance<MethodInsnNode>()
            .map { "${it.owner}.${it.name}" }

        // A lambda body is not in the method that returns it. Kotlin puts it
        // either in a synthetic static `<method>$lambda$N` alongside it,
        // reached by invokedynamic, or in a `Class$method$N` of its own. Both
        // happen in this plugin, so both are followed one hop.
        val siblings = classes[owner]?.methods.orEmpty()
            .filter { it.name.startsWith("$name\$lambda") }
        val nested = classes.keys.filter { it.startsWith("$owner\$$name\$") }
            .flatMap { classes.getValue(it).methods }

        val indirect = (siblings + nested).flatMap { m ->
            m.instructions.toArray().filterIsInstance<MethodInsnNode>()
                .map { "${it.owner}.${it.name}" }
        }
        return direct + indirect
    }

    /** True when the body is nothing but `return null`. */
    private fun returnsOnlyNull(method: MethodNode): Boolean {
        val real = method.instructions.toArray().filter {
            it.opcode != -1 // skip labels, line numbers and frames
        }
        return real.size == 2 &&
                real[0].opcode == Opcodes.ACONST_NULL &&
                (real[1] as? InsnNode)?.opcode == Opcodes.ARETURN
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
