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
 * Three things Reclazz said to users that were not true, or not useful.
 *
 *  - The reconnect action posted "Reconnecting to agent..." and stopped
 *    there. When there was no agent to find, the connect call returned
 *    silently, so that balloon was the last thing anyone heard.
 *  - The welcome notification asserted "Reclazz is installed, and switched
 *    off" and offered a button to enable it. It is shown once per
 *    installation rather than once per project, so someone who had already
 *    switched it on met a balloon contradicting their own IDE.
 *
 * The third lives in the agent and is covered by its own test. All three are
 * strings and branches with no behaviour attached, which is exactly the kind
 * of thing that gets quietly reverted, so they are pinned here.
 */
class UserFacingMessagesTest {

    private val action = "com/onurkat/reclazz/plugin/reload/TriggerReloadAction"
    private val notifications = "com/onurkat/reclazz/plugin/notifications/ReloadNotifications"
    private val startup = "com/onurkat/reclazz/plugin/ReclazzStartup"

    // ── The reconnect action reports an outcome ───────────────────────────

    @Test
    fun `reconnect no longer announces only that it is trying`() {
        val strings = stringsIn(action)
        assertTrue(strings.none { it.contains("Reconnecting to agent") },
            "\"Reconnecting to agent...\" describes the attempt, not the result. " +
            "Found: $strings")
    }

    @Test
    fun `reconnect says what happened, including when nothing was found`() {
        val strings = stringsIn(action)

        assertTrue(strings.any { it.contains("Connected to the Reclazz agent") },
            "the success case must say so. Found: $strings")
        assertTrue(strings.any { it.contains("No running agent found") },
            "the case that used to end in silence is the one that most needs a " +
            "message. Found: $strings")
        assertTrue(strings.any { it.contains("Attach Reclazz to Running Server") },
            "telling someone nothing was found without telling them what to do " +
            "next leaves them where they started")
    }

    /**
     * The message is only reachable if the action actually asks for the
     * result, so the callback has to be threaded through the manager.
     */
    @Test
    fun `the action asks the manager for a result`() {
        val calls = callsFrom(action, "actionPerformed")
        assertTrue(calls.any { it.endsWith("ReloadManager.connectToAgent") },
            "the action must go through connectToAgent. Found: $calls")

        val resultType = classes.keys.any { it.contains("ReloadManager\$ConnectResult") }
        assertTrue(resultType, "ConnectResult is how the outcome travels back")
    }

    // ── The welcome tells the truth ───────────────────────────────────────

    @Test
    fun `the welcome does not claim to be off when it is on`() {
        val strings = stringsIn(notifications)

        assertTrue(strings.any { it.contains("switched off") },
            "the off case is still the common one and keeps its wording")
        assertTrue(strings.any { it.contains("on for this project") },
            "there must be wording for the case where it is already enabled. " +
            "Found: ${strings.filter { it.contains("Reclazz is installed") }}")
    }

    @Test
    fun `startup tells the welcome whether Reclazz is on`() {
        val calls = callsFrom(startup, "execute")
        assertTrue(calls.any { it.endsWith("ReloadNotifications.welcome") },
            "startup is what posts the welcome. Found: ${calls.take(40)}")

        val welcome = classes[notifications]?.methods?.firstOrNull { it.name == "welcome" }
            ?: fail("ReloadNotifications.welcome is gone")
        assertTrue(welcome.desc.count { it == 'Z' } >= 2,
            "welcome needs both isHybris and alreadyEnabled; a single boolean " +
            "means the enabled state is not reaching it. Descriptor: ${welcome.desc}")
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    private fun stringsIn(owner: String): List<String> {
        val node = classes[owner] ?: fail("$owner not found in the compiled output")
        val here = node.methods.flatMap { m ->
            m.instructions.toArray().filterIsInstance<LdcInsnNode>()
                .mapNotNull { it.cst as? String }
        }
        // Kotlin moves lambda bodies into sibling classes; the notification
        // text often lives there rather than in the method that posts it.
        val nested = classes.keys.filter { it.startsWith("$owner\$") }
            .flatMap { c ->
                classes.getValue(c).methods.flatMap { m ->
                    m.instructions.toArray().filterIsInstance<LdcInsnNode>()
                        .mapNotNull { it.cst as? String }
                }
            }
        return here + nested
    }

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
        return direct + indirect
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
