/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.onurkat.reclazz.plugin.notifications.ReloadNotifications
import com.onurkat.reclazz.plugin.settings.ReclazzSettings

class ReloadToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val logPanel = ReloadLogPanel(project)
        val content = ContentFactory.getInstance().createContent(logPanel.component, "Reload Log", false)
        Disposer.register(content, logPanel)
        toolWindow.contentManager.addContent(content)

        // Add export log action to the tool window title bar
        toolWindow.setTitleActions(listOf(
            object : AnAction("Export Log") {
                override fun actionPerformed(e: AnActionEvent) {
                    // Deprecated from 2025.1, and it stays. The replacement,
                    // the non-vararg FileSaverDescriptor(String, String), was
                    // added in 2025.2 and does not exist at 2023.3, which is
                    // our declared sinceBuild: calling it would turn a
                    // deprecation warning into a NoSuchMethodError on the
                    // oldest IDE we claim to support.
                    //
                    // Checked rather than assumed: dropping the extension
                    // argument here compiles to exactly the same call, because
                    // at 2023.3 the vararg constructor is the only one there
                    // is, and the verifier reported the same deprecation
                    // afterwards. Revisit when sinceBuild moves past 252.
                    @Suppress("DEPRECATION")
                    val descriptor = FileSaverDescriptor("Export Reclazz Log", "Save reload log to file", "txt")
                    val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
                    val result = dialog.save("reclazz-reload-log.txt") ?: return
                    try {
                        result.file.writeText(logPanel.exportLog())
                        ReloadNotifications.info(project, "Reclazz", "Log exported to ${result.file.name}")
                    } catch (ex: Exception) {
                        ReloadNotifications.error(project, "Reclazz", "Failed to export log: ${ex.message}")
                    }
                }
            }
        ))
    }

    override fun shouldBeAvailable(project: Project): Boolean {
        return ReclazzSettings.getInstance(project).state.enabled
    }
}
