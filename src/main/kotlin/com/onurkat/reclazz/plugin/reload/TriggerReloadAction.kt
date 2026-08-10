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
 * Action to manually trigger a reconnection to the Reclazz agent.
 */
class TriggerReloadAction : AnAction("Reconnect to Reclazz Agent") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = ReloadManager.getInstance(project)
        manager.connectToAgent()
        ReloadNotifications.info(project, "Reclazz", "Reconnecting to agent...")
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            ReclazzSettings.getInstance(project).state.enabled
    }
}
