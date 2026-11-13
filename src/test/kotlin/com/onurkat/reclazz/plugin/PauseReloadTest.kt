/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.onurkat.reclazz.plugin.reload.AgentEvent
import com.onurkat.reclazz.plugin.reload.ReloadManager
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import com.onurkat.reclazz.plugin.support.ScriptedStatusServer
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals as expectEquals
import kotlin.test.assertFalse as expectFalse
import kotlin.test.assertTrue as expectTrue

class PauseReloadTest : BasePlatformTestCase() {

    fun testPauseAndResumeFollowTheAgentsAnswer() {
        val folder = Files.createTempDirectory("reclazz-pause-")
        val portFile = folder.resolve("agent.port")
        val settings = ReclazzSettings.getInstance(project)
        val original = settings.state.copy()
        val manager = ReloadManager.getInstance(project)
        val received = CopyOnWriteArrayList<AgentEvent>()
        val listener: (AgentEvent) -> Unit = { received.add(it) }
        manager.addEventListener(listener)
        try {
            ScriptedStatusServer(portFile).use { server ->
                expectFalse(manager.setReloadPaused(true), "Nothing to pause without an agent")
                settings.state.agentPort = server.port
                manager.connectToAgent()
                // Connecting asks for the pause state, which lives in the agent.
                waitFor { manager.pauseState != null }
                expectEquals(ReloadManager.PauseState(false, 0, 1), manager.pauseState)
                expectTrue(received.none { it.level == "PAUSE" }, "A status answer is not announced")

                expectTrue(manager.setReloadPaused(true))
                waitFor { received.any { it.level == "PAUSE" } }
                expectEquals(3, manager.pauseState?.waiting)
                expectEquals("Automatic reload paused. Saved changes wait until you resume.",
                    received.last { it.level == "PAUSE" }.message)

                expectTrue(manager.setReloadPaused(false))
                waitFor { received.count { it.level == "PAUSE" } == 2 }
                expectFalse(manager.pauseState!!.paused)
                expectEquals("Automatic reload resumed. Applying 1 waiting change.",
                    received.last { it.level == "PAUSE" }.message)
                expectTrue(received.none { it.message.startsWith("RELOAD_STATE") }, "Raw replies stay internal")
            }
        } finally {
            manager.removeEventListener(listener)
            manager.disconnectFromAgent()
            expectEquals(null, manager.pauseState, "Disconnecting forgets the remote state")
            settings.loadState(original)
            Files.deleteIfExists(portFile)
            Files.deleteIfExists(folder)
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for the agent's answer" }
            Thread.sleep(20)
        }
    }
}
