/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.reload

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.onurkat.reclazz.plugin.notifications.ReloadNotifications
import com.onurkat.reclazz.plugin.settings.ReclazzSettings

/**
 * Asks the agent how the session is going.
 *
 * The other two questions on this menu are about a particular thing: why that
 * class did not reload, and what a restart would still change. Neither of them
 * answers the plainest one. A developer whose saves feel slow has no number of
 * their own, only the one in the README, measured on somebody else's machine.
 * A developer who thinks nothing is reloading cannot tell a watcher that never
 * saw their directory from reloads landing on code they are not exercising, and
 * the difference between those two is an afternoon.
 *
 * So: how long the agent has been up, what it has reloaded and what it failed
 * to, what a save costs at the median and at the 95th, how many directories it
 * is watching and how many it could not, and when anything last changed.
 */
class SessionReportAction : AnAction("How Is Reclazz Doing?") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = ReloadManager.getInstance(project)

        if (!manager.sessionReport()) {
            ReloadNotifications.warn(
                project, "Reclazz",
                "Not connected to an agent, so there is nothing to ask. Start your " +
                    "application with Reclazz, or use Tools > Attach Reclazz to Running Server."
            )
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            ReclazzSettings.getInstance(project).state.enabled
    }
}
