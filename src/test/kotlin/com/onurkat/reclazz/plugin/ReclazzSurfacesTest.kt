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
 * Reclazz shows two things that exist only where it is enabled: the tool
 * window and the status bar item. Every defect this covers came from those
 * two being treated as unrelated, and each took the same shape, a method
 * left at its platform default.
 *
 *  - `getClickConsumer` returned null, so the one always-visible thing
 *    Reclazz owns could say "Not connected" and do nothing when clicked.
 *    In this platform a status bar item that ignores a click reads as
 *    broken rather than as deliberate.
 *  - `isAvailable` was not overridden at all, so it defaulted to true and
 *    the widget occupied the status bar of every project, including ones
 *    where Reclazz is switched off, while the tool window scoped itself
 *    correctly. One plugin, two answers to one question.
 *  - Neither is re-asked by the platform on its own, so scoping them
 *    without a refresh would mean unticking the box changes nothing until
 *    the IDE restarts.
 *
 * All of it regresses silently. Nothing fails to compile, no behavioural
 * test goes red, and none of it is visible in a diff unless you already
 * know to look, so this reads the compiled methods instead.
 *
 * A live-IDE fixture cannot stand in here: the test Application installs
 * `ToolWindowHeadlessManagerImpl`, which registers no tool windows at all,
 * so every question below would come back the same whether the code was
 * right or wrong.
 */
class ReclazzSurfacesTest {

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
    fun `toggling the setting refreshes both surfaces`() {
        val callers = listOf(
            "com/onurkat/reclazz/plugin/ReclazzActivation" to "enable",
            "com/onurkat/reclazz/plugin/settings/ReclazzConfigurable" to "apply",
        )
        for ((owner, name) in callers) {
            val reached = callsFrom(owner, name)
            assertTrue(reached.any { it.contains("ReclazzSurfaces.refresh") },
                "$owner.$name changes the enabled setting but never calls " +
                "ReclazzSurfaces.refresh, so the status bar and the tool " +
                "window keep showing the old answer until the IDE restarts")
        }
    }

    /**
     * The refresh has to cover both, which is the whole reason it is one
     * function. A version that updated only the status bar would leave the
     * tool window stale and recreate the disagreement in the other direction.
     */
    @Test
    fun `the refresh covers the tool window as well as the widget`() {
        val reached = callsFrom("com/onurkat/reclazz/plugin/ui/ReclazzSurfaces", "refresh")

        assertTrue(reached.any { it.contains("StatusBarWidgetsManager.updateWidget") },
            "refresh must re-ask for the status bar item. Calls: $reached")
        assertTrue(reached.any { it.endsWith("ToolWindow.setAvailable") },
            "refresh must re-ask for the tool window too, otherwise enabling " +
            "Reclazz mid-session leaves the panel missing until restart. " +
            "Calls: $reached")
    }

    /**
     * ToolWindow.setAvailable asserts it is on the event thread, so a call
     * from anywhere else throws instead of doing nothing, which is the kind
     * of failure that only shows up in someone else's IDE.
     */
    @Test
    fun `the refresh gets itself onto the event thread`() {
        val reached = callsFrom("com/onurkat/reclazz/plugin/ui/ReclazzSurfaces", "refresh")
        assertTrue(reached.any { it.contains("isDispatchThread") },
            "refresh touches EDT-only API and must check the thread first")
        assertTrue(reached.any { it.contains("invokeLater") },
            "refresh must reschedule onto the EDT rather than assume it")
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
