/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.reload

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.actionSystem.ToggleAction
import com.onurkat.reclazz.plugin.notifications.ReloadNotifications

/**
 * Holds automatic reload while the code is half-written: a large refactor, a
 * branch switch, a rebase. Saves made meanwhile are kept by the agent and
 * applied on resume, so nothing is lost and nothing half-done reaches the
 * running server.
 *
 * A session switch, not a setting: a stored checkbox is forgotten, and the
 * next morning reload "stops working". The status bar says Paused for as long
 * as it lasts.
 */
class PauseReloadAction : ToggleAction("Pause Automatic Reload", "Keep saved changes waiting until you resume", AllIcons.Actions.Pause), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return ReloadManager.getInstance(project).pauseState?.paused == true
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        if (!ReloadManager.getInstance(project).setReloadPaused(state)) {
            ReloadNotifications.warn(project, "Reclazz",
                "Not connected to an agent, so there is nothing to pause. Start your " +
                    "application with Reclazz, or use Tools > Attach Reclazz to Running Server.")
        }
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project
        val manager = project?.let { ReloadManager.getInstance(it) }
        val paused = manager?.pauseState?.paused == true
        e.presentation.isEnabled = manager?.isConnected == true
        e.presentation.text = if (paused) "Resume Automatic Reload" else "Pause Automatic Reload"
        e.presentation.icon = if (paused) AllIcons.Actions.Resume else AllIcons.Actions.Pause
    }
}
