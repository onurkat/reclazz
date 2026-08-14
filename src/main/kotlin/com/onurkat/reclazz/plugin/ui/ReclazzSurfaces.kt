/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.onurkat.reclazz.plugin.settings.ReclazzSettings

/**
 * The parts of the IDE that exist only where Reclazz is enabled.
 *
 * There are two, the tool window and the status bar item, and they are the
 * same decision seen twice. Keeping them apart is what produced the bug
 * this replaces: the tool window scoped itself on the setting, the status
 * bar item did not, and the same plugin gave two answers to one question.
 * Adding a third surface later and remembering to wire it in both places
 * would fail the same way, so the decision is made once, here.
 *
 * Both surfaces also share the problem of being asked only once. The
 * platform reads `shouldBeAvailable` when a project opens and the widget
 * factory's `isAvailable` when it builds the status bar, and neither is
 * revisited on its own. Without this, unticking the box does nothing until
 * the IDE restarts, which is a worse experience than not scoping at all.
 */
object ReclazzSurfaces {

    /** Matches the id in plugin.xml. */
    const val TOOL_WINDOW_ID = "Reclazz"

    fun refresh(project: Project) {
        if (project.isDisposed) return

        // ToolWindow.setAvailable asserts it is on the event thread. Every
        // caller today is a user action and already there, but this is
        // called from wherever the setting happens to change.
        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            app.invokeLater({ refresh(project) }, project.disposed)
            return
        }

        val enabled = ReclazzSettings.getInstance(project).state.enabled

        project.getService(StatusBarWidgetsManager::class.java)
            ?.updateWidget(ReloadStatusWidgetFactory::class.java)

        // Registration does not depend on shouldBeAvailable: the platform
        // registers the tool window either way and uses the flag only for
        // the initial state, so the window is here to be switched back on.
        // Checked against the bytecode of both 2023.3 and 2025.1, and null
        // is still handled because a headless IDE registers nothing.
        ToolWindowManager.getInstance(project)
            .getToolWindow(TOOL_WINDOW_ID)
            ?.setAvailable(enabled)
    }
}
