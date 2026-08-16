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
 * Asks the agent what a restart would still change.
 *
 * Reclazz says so as each one happens, once, in a log that keeps moving. An
 * hour later the developer is looking at a static field that reads null with no
 * way back to the line that explained it, and the usual ending is either a long
 * debugging session or a restart out of superstition. Both are the thing this
 * tool exists to avoid.
 */
class PendingRestartAction : AnAction("What Still Needs a Restart?") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = ReloadManager.getInstance(project)

        if (!manager.pendingRestarts()) {
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
