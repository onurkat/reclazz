/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A user reported a Reclazz notification they could not delete. It went away
 * after restarting the IDE and not before.
 *
 * The platform starts a balloon's fade-out timer from
 * `frameActivateBalloonListener`, which runs it immediately when the
 * application is already active and otherwise waits for the next activation
 * event. A balloon posted during startup can land between the two: the frame
 * is coming up, `isActive()` is still false, and no further activation event
 * follows. The timer never starts, so the balloon never fades, and while it
 * is still live its entry cannot be removed from the event log. Intermittent,
 * and gone after a restart, which is what the report described.
 *
 * Two things keep it from coming back. Notifications wait for the
 * application to be active, which puts the platform on the branch that
 * starts the timer straight away. And the JDK capability message, which is
 * context rather than an event and was the one being reported, is written to
 * the tool window instead of thrown at the user as a balloon.
 */
class NotificationDismissalTest {

    private val notifications = "com/onurkat/reclazz/plugin/notifications/ReloadNotifications"
    private val manager = "com/onurkat/reclazz/plugin/reload/ReloadManager"

    // ── Balloons wait for an active application ───────────────────────────

    @Test
    fun `notifications are not posted while the application is inactive`() {
        val fromNotify = callsFrom(notifications, "notify")
        assertTrue(fromNotify.any { it.endsWith("ReloadNotifications.whenApplicationIsActive") },
            "notify must route through the guard rather than posting directly. " +
            "Calls: $fromNotify")

        val guard = callsFrom(notifications, "whenApplicationIsActive")
        assertTrue(guard.any { it.contains("isActive") },
            "the guard must ask whether the IDE is the active application; that " +
            "is what decides whether the platform starts the fade-out timer. " +
            "Calls: $guard")
    }

    @Test
    fun `an inactive application is waited on rather than skipped`() {
        val calls = callsFrom(notifications, "whenApplicationIsActive") +
                callsFrom(notifications, "notify")

        assertTrue(calls.any { it.contains("ApplicationActivationListener") || it.contains("subscribe") },
            "waiting means subscribing to activation, not dropping the message. " +
            "Calls: $calls")
        assertTrue(calls.any { it.contains("disconnect") },
            "the subscription must be torn down once it has fired, otherwise " +
            "every notification leaves a listener behind. Calls: $calls")
    }

    // ── The reported message is no longer a balloon ───────────────────────

    @Test
    fun `the JDK capability message goes to the tool window`() {
        val calls = callsFrom(notifications, "notifyJdkDetection")

        assertTrue(calls.any { it.endsWith("ReloadNotifications.toolWindow") },
            "the JDK message is context, not an event; it belongs in the tool " +
            "window. This is the notification a user reported as undeletable. " +
            "Calls: $calls")
        assertTrue(calls.none { it.endsWith("ReloadNotifications.info") },
            "no part of JDK detection should still post an information balloon. " +
            "Calls: $calls")
    }

    /**
     * GraalVM is the exception and stays a balloon: it says hot reload may not
     * work at all, which is worth interrupting for.
     */
    @Test
    fun `a JDK that cannot hot reload still warns`() {
        val calls = callsFrom(notifications, "notifyJdkDetection")
        assertTrue(calls.any { it.endsWith("ReloadNotifications.warn") },
            "the GraalVM case must remain a warning. Calls: $calls")
    }

    @Test
    fun `the tool window route actually reaches the log`() {
        val calls = callsFrom(notifications, "toolWindow")
        assertTrue(calls.any { it.endsWith("ReloadManager.postLocalMessage") },
            "toolWindow must go through the manager the panel listens to. " +
            "Calls: $calls")

        val post = classes[manager]?.methods?.firstOrNull { it.name == "postLocalMessage" }
            ?: fail("ReloadManager.postLocalMessage is gone; the tool window route is broken")
        assertTrue(post.instructions.toArray().filterIsInstance<MethodInsnNode>()
            .any { it.name == "notifyListeners" },
            "postLocalMessage must feed the same listeners agent events use")
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    private fun callsFrom(owner: String, name: String): List<String> {
        val node = classes[owner] ?: return emptyList()
        val direct = node.methods.filter { it.name == name }.flatMap { m ->
            m.instructions.toArray().filterIsInstance<MethodInsnNode>()
                .map { "${it.owner}.${it.name}" }
        }
        val siblings = node.methods.filter { it.name.startsWith("$name\$lambda") }
        val nested = classes.keys.filter { it.startsWith("$owner\$$name\$") }
            .flatMap { classes.getValue(it).methods }
        val indirect = (siblings + nested).flatMap { m ->
            m.instructions.toArray().filterIsInstance<MethodInsnNode>()
                .map { "${it.owner}.${it.name}" }
        }
        // The activation listener compiles to an anonymous class of its own.
        val anon = classes.keys.filter { it.startsWith("$owner\$") && it != owner }
            .filter { classes.getValue(it).interfaces.any { i -> i.contains("ApplicationActivationListener") } }
            .flatMap { classes.getValue(it).methods }
            .flatMap { m ->
                m.instructions.toArray().filterIsInstance<MethodInsnNode>()
                    .map { "${it.owner}.${it.name}" }
            }
        val ifaces = classes.keys.filter { it.startsWith("$owner\$") }
            .flatMap { classes.getValue(it).interfaces }
        return direct + indirect + anon + ifaces
    }

    @Suppress("unused")
    private fun stringsIn(owner: String): List<String> =
        classes[owner]?.methods.orEmpty().flatMap { m ->
            m.instructions.toArray().filterIsInstance<LdcInsnNode>()
                .mapNotNull { it.cst as? String }
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
