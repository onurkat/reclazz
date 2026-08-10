/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.agent

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.onurkat.reclazz.plugin.notifications.ReloadNotifications
import com.onurkat.reclazz.plugin.reload.ReloadManager
import com.onurkat.reclazz.plugin.settings.ReclazzSettings

class AttachAction : AnAction("Attach Reclazz to Running Server") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        attachInteractively(project)
    }

    internal fun attachToProcess(info: JvmProcessInfo, project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Attaching Reclazz to ${info.pid}...",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Loading agent into JVM ${info.pid}..."
                val result = JvmAttacher.attach(info.pid, project)

                ApplicationManager.getApplication().invokeLater {
                    when (result) {
                        is AttachResult.Success -> {
                            ReloadNotifications.info(
                                project,
                                "Reclazz",
                                "Agent attached to ${info.displayName} (PID ${result.pid}). Connecting..."
                            )
                            ReloadManager.getInstance(project).connectToAgent()
                        }
                        is AttachResult.AlreadyAttached -> {
                            ReloadNotifications.info(
                                project,
                                "Reclazz",
                                "Agent is already loaded in PID ${result.pid}. Reconnecting..."
                            )
                            ReloadManager.getInstance(project).connectToAgent()
                        }
                        is AttachResult.Error -> {
                            ReloadNotifications.error(project, "Reclazz", result.message)
                        }
                    }
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            ReclazzSettings.getInstance(project).state.enabled
    }

    companion object {
        /**
         * List attachable JVMs and attach to one, asking the user when there
         * is more than one candidate.
         *
         * Lives here rather than inside [actionPerformed] so the Settings
         * page can start the same flow directly. Invoking an action's
         * `actionPerformed` from client code violates the platform's
         * `@ApiStatus.OverrideOnly` contract (the Plugin Verifier reports
         * it), and synthesising an AnActionEvent for that call needed an
         * API already scheduled for removal.
         */
        fun attachInteractively(project: Project) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val processes = JvmAttacher.listAttachableProcesses()

                ApplicationManager.getApplication().invokeLater {
                    if (processes.isEmpty()) {
                        ReloadNotifications.warn(
                            project,
                            "Reclazz",
                            "No attachable Java process found. " +
                            "Start the server first, then try again."
                        )
                        return@invokeLater
                    }

                    if (processes.size == 1) {
                        AttachAction().attachToProcess(processes.first(), project)
                        return@invokeLater
                    }

                    JBPopupFactory.getInstance()
                        .createPopupChooserBuilder(processes)
                        .setTitle("Select Java Process")
                        .setMovable(true)
                        .setItemChosenCallback { selected ->
                            AttachAction().attachToProcess(selected, project)
                        }
                        .createPopup()
                        .showInFocusCenter()
                }
            }
        }
    }
}
