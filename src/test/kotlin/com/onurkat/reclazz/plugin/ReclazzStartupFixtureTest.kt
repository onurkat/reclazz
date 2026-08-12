/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.Notifications
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.onurkat.reclazz.plugin.settings.ReclazzAppState
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Startup, run against a real IDE.
 *
 * [StartupMustNotShowDialogTest] reads bytecode and answers one question:
 * can startup reach a modal dialog. It cannot tell you whether startup
 * throws, whether it ever returns, or whether the extension points we
 * declare in plugin.xml actually exist. Those only show up with the
 * plugin loaded into a live Application, which is what this fixture is.
 *
 * The fixture does load our own plugin.xml; that was checked before these
 * tests were written, because a test that silently exercises none of your
 * code is worse than no test.
 */
class ReclazzStartupFixtureTest : BasePlatformTestCase() {

    private val received = mutableListOf<Notification>()

    override fun setUp() {
        super.setUp()
        // Application-level state outlives a single test method inside the
        // same Application, so a test that ran earlier would otherwise decide
        // whether this one sees a first run.
        ReclazzAppState.getInstance().state.welcomeShown = false
        ReclazzStartup.resetSessionStateForTests()

        received.clear()
        project.messageBus.connect(testRootDisposable)
            .subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    received += notification
                }
            })
    }

    /**
     * A notification group has to be declared in plugin.xml under exactly the
     * id the code asks for. Get that wrong and nothing complains until a user
     * installs the plugin and the first notification throws. The ids live as
     * string constants in Kotlin and as attributes in XML, with nothing
     * tying them together, so this is the thing that ties them.
     */
    fun testNotificationGroupsAreRegistered() {
        val manager = NotificationGroupManager.getInstance()
        for (id in listOf("Reclazz", "Reclazz Welcome")) {
            assertNotNull(
                "Notification group '$id' is used in code but not registered in plugin.xml",
                manager.getNotificationGroup(id),
            )
        }
    }

    /** It must finish, and it must not throw. Both have to hold on a plain project. */
    fun testStartupCompletesAndDoesNotThrow() {
        runBlocking {
            withTimeout(60_000) { ReclazzStartup().execute(project) }
        }
    }

    /**
     * The introduction is once per installation. Running startup again, as
     * happens whenever a second project is opened, must not repeat it.
     */
    fun testWelcomeIsAnnouncedOnceOnly() {
        runBlocking { withTimeout(60_000) { ReclazzStartup().execute(project) } }
        assertEquals(
            "expected exactly one welcome on first run, got ${titles()}",
            1, welcomeCount(),
        )

        runBlocking { withTimeout(60_000) { ReclazzStartup().execute(project) } }
        assertEquals(
            "the welcome repeated on a later run: ${titles()}",
            1, welcomeCount(),
        )
    }

    /** Reclazz ships off; nothing may turn it on without being asked. */
    fun testStartupLeavesReclazzOff() {
        ReclazzSettings.getInstance(project).state.enabled = false
        runBlocking { withTimeout(60_000) { ReclazzStartup().execute(project) } }
        assertFalse(
            "startup enabled Reclazz on its own",
            ReclazzSettings.getInstance(project).state.enabled,
        )
    }

    /** And the path the user takes from the notification does turn it on. */
    fun testActivationTurnsReclazzOn() {
        ReclazzSettings.getInstance(project).state.enabled = false
        ReclazzActivation.enable(project)
        assertTrue(
            "the accept path left Reclazz off",
            ReclazzSettings.getInstance(project).state.enabled,
        )
    }

    private fun welcomeCount() = received.count { it.title.contains("installed") }

    private fun titles() = received.joinToString(", ") { "'${it.title}'" }
}
