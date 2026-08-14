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
        // Say what happened, not what was attempted. This used to post
        // "Reconnecting to agent..." and stop there, so someone whose server
        // was not running never found out that nothing had been found.
        manager.connectToAgent { result ->
            when (result) {
                ReloadManager.ConnectResult.CONNECTED ->
                    ReloadNotifications.info(project, "Reclazz", "Connected to the Reclazz agent.")

                ReloadManager.ConnectResult.NO_AGENT_FOUND ->
                    ReloadNotifications.warn(
                        project, "Reclazz",
                        "No running agent found. Start your server with the Reclazz agent " +
                        "attached, or use Tools > Attach Reclazz to Running Server."
                    )

                ReloadManager.ConnectResult.FAILED ->
                    ReloadNotifications.warn(
                        project, "Reclazz",
                        "Found an agent but could not connect to it. It may have just stopped."
                    )

                ReloadManager.ConnectResult.ALREADY_IN_PROGRESS -> Unit
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            ReclazzSettings.getInstance(project).state.enabled
    }
}
