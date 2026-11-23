/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.setup

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.openapi.util.Disposer
import com.onurkat.reclazz.plugin.ui.ReclazzSurfaces

/** Opens the setup wizard: the "Setup" tab of the Reclazz tool window, available even while Reclazz is off. */
object SetupWizard {
    const val TAB = "Setup"
    private val OPEN = Key.create<Boolean>("reclazz.setup.open")

    fun isOpen(project: Project) = project.getUserData(OPEN) == true

    fun open(project: Project) {
        if (project.isDisposed) return
        project.putUserData(OPEN, true)
        val window = ToolWindowManager.getInstance(project).getToolWindow(ReclazzSurfaces.TOOL_WINDOW_ID) ?: return
        window.setAvailable(true)
        window.show { select(project, window) }
        select(project, window)
    }

    private fun select(project: Project, window: ToolWindow) {
        val manager = window.contentManager
        val existing = manager.findContent(TAB) ?: addTab(project, window)
        manager.setSelectedContent(existing, true)
    }

    /** Adds the Setup tab first in the tool window and returns it. */
    fun addTab(project: Project, window: ToolWindow): com.intellij.ui.content.Content {
        val content = ContentFactory.getInstance().createContent(null, TAB, false)
        val panel = SetupPanel(project)
        content.component = panel.component
        Disposer.register(content, panel)
        window.contentManager.addContent(content, 0)
        return content
    }
}

/** Tools > Set Up Reclazz. */
class OpenSetupWizardAction : AnAction("Set Up Reclazz"), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun actionPerformed(e: AnActionEvent) { e.project?.let(SetupWizard::open) }
}
