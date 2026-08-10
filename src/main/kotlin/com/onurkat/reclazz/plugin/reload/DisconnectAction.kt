/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.reload

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.onurkat.reclazz.plugin.notifications.ReloadNotifications

class DisconnectAction : AnAction("Disconnect from Reclazz Agent") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = ReloadManager.getInstance(project)
        // Run off EDT — stopReconnect() may block briefly
        ApplicationManager.getApplication().executeOnPooledThread {
            manager.disconnectFromAgent()
        }
        ReloadNotifications.info(project, "Reclazz", "Disconnected from agent")
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            ReloadManager.getInstance(project).isConnected
    }
}
