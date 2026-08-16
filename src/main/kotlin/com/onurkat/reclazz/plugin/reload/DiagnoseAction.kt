/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.reload

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.onurkat.reclazz.plugin.notifications.ReloadNotifications
import com.onurkat.reclazz.plugin.settings.ReclazzSettings

/**
 * Asks the agent why the class in the editor did not reload.
 *
 * The question comes up when nothing happened at all, which is exactly when
 * the log has nothing to show: the build did not run, the class is built
 * somewhere Reclazz does not watch, the bytes came out identical, or the JVM
 * has not loaded the class yet. Those are facts the agent holds and the IDE
 * cannot see, so it is asked rather than guessed at.
 */
class DiagnoseAction : AnAction("Why Didn't My Class Reload?") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val className = classNameOf(file)

        if (className == null) {
            ReloadNotifications.warn(
                project, "Reclazz",
                "Open the .java or .kt file of the class you want explained, then run this again."
            )
            return
        }

        val manager = ReloadManager.getInstance(project)
        if (!manager.diagnose(className)) {
            ReloadNotifications.warn(
                project, "Reclazz",
                "Not connected to an agent, so there is nothing to ask. Start your " +
                    "application with Reclazz, or use Tools > Attach Reclazz to Running Server."
            )
            return
        }
        manager.postLocalMessage("INFO", "Asked the agent about $className…")
    }

    /**
     * The file name is enough: the agent matches a simple name against what it
     * has, and a package read from the IDE would only add a way to be wrong
     * about a file that has just been moved.
     */
    private fun classNameOf(file: VirtualFile?): String? {
        if (file == null || file.isDirectory) return null
        val name = file.name
        return when {
            name.endsWith(".java") -> name.removeSuffix(".java")
            name.endsWith(".kt") -> name.removeSuffix(".kt")
            name.endsWith(".class") -> name.removeSuffix(".class")
            else -> null
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            ReclazzSettings.getInstance(project).state.enabled
    }
}
