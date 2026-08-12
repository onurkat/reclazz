/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A plugin must not open a modal dialog while the IDE is starting.
 * `DialogWrapper.show()` parks the event thread until something clicks the
 * dialog, and during startup nothing will: the IDE is simply frozen.
 *
 * Reclazz 1.0.1 shipped exactly that. Marketplace automated review ran an
 * IDE with the plugin installed, hung for its full ten minute budget, and
 * reported a second failure about a missing trial widget which was only
 * the same stuck IDE seen from another test. Neither the Plugin Verifier
 * nor a source-level denylist can see this: it is a property of the call
 * graph, so this test walks the call graph.
 *
 * ## What it checks
 *
 * Starting at every `ProjectActivity` we register, follow method calls
 * into our own code and fail if any of them reaches a dialog-showing
 * method. The reported failure carries the path, because "something in
 * startup shows a dialog" is not an actionable message.
 *
 * ## What it deliberately does not follow
 *
 * Only invocation edges are followed, never object construction. That is
 * what separates the two cases that matter:
 *
 *  - `withContext(EDT) { WelcomeDialog.show(...) }` written inside the
 *    activity compiles to `ReclazzStartup$execute$N`, an inner class of
 *    the root, so its body is walked and the dialog is found. This is the
 *    bug that shipped.
 *  - A lambda handed to `NotificationAction.createSimpleExpiring` is a
 *    class the walk only ever reaches by construction, so its body is not
 *    walked. That is correct: the code runs when a user clicks, and a
 *    dialog opened because someone asked for one is fine.
 *
 * The blind spot that leaves: a lambda that runs during startup but is
 * written in some class other than the activity's own. Nothing in the
 * plugin does that today, and the alternative, following construction
 * edges, flags every notification action we have.
 */
class StartupMustNotShowDialogTest {

    private val ourPackage = "com/onurkat/reclazz/"
    private val projectActivity = "com/intellij/openapi/startup/ProjectActivity"

    /** owner to method names that block on a modal dialog. */
    private val forbidden = mapOf(
        "com/intellij/openapi/ui/DialogWrapper" to setOf("show", "showAndGet"),
        "com/intellij/openapi/ui/Messages" to setOf(
            "showYesNoDialog", "showOkCancelDialog", "showInputDialog",
            "showMessageDialog", "showErrorDialog", "showWarningDialog",
        ),
    )

    @Test
    fun `no dialog is reachable from a startup activity`() {
        val classes = loadCompiledClasses()
        assertTrue(classes.isNotEmpty(), "no compiled plugin classes found to analyse")

        val activities = classes.values.filter { projectActivity in it.interfaces }
        assertTrue(
            activities.isNotEmpty(),
            "found no ProjectActivity; this test would silently pass forever. " +
                    "If startup moved to another extension point, point this test at it."
        )

        val offences = activities.flatMap { activity ->
            // The activity plus the lambdas the compiler lifted out of it.
            val roots = classes.values.filter {
                it.name == activity.name || it.name.startsWith(activity.name + "$")
            }
            roots.flatMap { root -> root.methods.mapNotNull { pathToDialog(classes, root, it) } }
        }

        if (offences.isNotEmpty()) {
            fail(
                "A modal dialog is reachable from IDE startup, which freezes the IDE " +
                        "until it is clicked:\n" +
                        offences.joinToString("\n\n") { path -> path.joinToString("\n  -> ") }
            )
        }
    }

    /** Breadth-first search; returns the first path that reaches a dialog. */
    private fun pathToDialog(
        classes: Map<String, ClassNode>,
        root: ClassNode,
        entry: MethodNode,
    ): List<String>? {
        val start = "${root.name}.${entry.name}"
        val queue = ArrayDeque(listOf(listOf(start)))
        val seen = mutableSetOf(start)

        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val (ownerName, methodName) = path.last().split(".", limit = 2).let { it[0] to it[1] }
            val owner = classes[ownerName] ?: continue
            val method = owner.methods.firstOrNull { it.name == methodName } ?: continue

            for (insn in method.instructions) {
                if (insn !is MethodInsnNode) continue

                if (opensDialog(classes, insn.owner, insn.name)) {
                    return path + "${insn.owner}.${insn.name}()  <-- opens a modal dialog"
                }

                // Follow calls into our own code only. Platform internals are
                // not ours to police, and following them would never end.
                if (!insn.owner.startsWith(ourPackage)) continue
                val next = "${insn.owner}.${insn.name}"
                if (seen.add(next)) queue.addLast(path + next)
            }
        }
        return null
    }

    /**
     * Kotlin emits `dialog.show()` with the static receiver type as the owner,
     * so a call to our own `WelcomeDialog.show()` names WelcomeDialog and never
     * mentions DialogWrapper. Walking up the supertypes we compiled is what
     * makes this test see it; matching the owner name alone does not, which is
     * how the first version of this test passed against the very bug it exists
     * to catch.
     */
    private fun opensDialog(classes: Map<String, ClassNode>, owner: String, name: String): Boolean {
        if (forbidden[owner]?.contains(name) == true) return true

        var current = classes[owner]
        while (current != null) {
            val superName = current.superName ?: return false
            if (forbidden[superName]?.contains(name) == true) return true
            current = classes[superName]
        }
        return false
    }

    private fun loadCompiledClasses(): Map<String, ClassNode> {
        val roots = listOf(
            File("build/classes/kotlin/main"),
            File("build/classes/java/main"),
        ).filter { it.isDirectory }

        return roots.asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "class" }
            .map { file ->
                ClassNode().also { ClassReader(file.readBytes()).accept(it, ClassReader.SKIP_FRAMES) }
            }
            .associateBy { it.name }
    }
}
