/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import com.onurkat.reclazz.plugin.reload.AgentEvent
import com.onurkat.reclazz.plugin.reload.ReloadManager
import java.awt.event.MouseEvent

class ReloadStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    @Volatile
    private var statusBar: StatusBar? = null
    @Volatile
    private var displayText = "Reclazz: Idle"
    @Volatile
    private var disposed = false
    @Volatile
    private var listenerInstalled = false

    private val eventListener: (AgentEvent) -> Unit = { event ->
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) updateFromEvent(event)
        }
    }

    override fun ID(): String = "ReclazzStatusWidget"

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val manager = ReloadManager.getInstance(project)
        // Attach listener first so no events are missed, then sync via invokeLater
        // so the snapshot runs after any in-flight listener dispatches.
        manager.addEventListener(eventListener)
        listenerInstalled = true
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            if (manager.isConnected) {
                displayText = "Reclazz: " + plural(manager.reloadCount, "reload")
            }
            statusBar.updateWidget(ID())
        }
    }

    override fun dispose() {
        disposed = true
        if (listenerInstalled) {
            ReloadManager.getInstance(project).removeEventListener(eventListener)
        }
    }

    override fun getText(): String = displayText

    override fun getAlignment(): Float = 0f

    override fun getTooltipText(): String {
        val manager = ReloadManager.getInstance(project)
        return if (manager.isConnected) {
            "Reclazz: Connected — " + plural(manager.reloadCount, "reload")
        } else {
            "Reclazz: Not connected"
        }
    }

    /**
     * Opens the Reclazz tool window.
     *
     * It used to return null, so the one surface that is always on screen
     * reported a state and offered nowhere to go from it. "Not connected" in
     * particular invites a click, and swallowing it reads as a broken widget
     * rather than a deliberate one.
     */
    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        if (project.isDisposed) return@Consumer
        ToolWindowManager.getInstance(project)
            .getToolWindow("Reclazz")
            ?.activate(null)
    }

    private fun updateFromEvent(event: AgentEvent) {
        val manager = ReloadManager.getInstance(project)
        displayText = when (event.level) {
            "CONNECTED" -> "Reclazz: Connected"
            "DISCONNECTED" -> "Reclazz: Idle"
            "RELOAD", "STRUCTURAL_RELOAD" -> "Reclazz: " + plural(manager.reloadCount, "reload")
            "OK" -> if (event.message.startsWith("Hot-swapped:")) "Reclazz: " + plural(manager.reloadCount, "reload") else displayText
            "ERROR" -> "Reclazz: Error"
            else -> displayText
        }
        statusBar?.updateWidget(ID())
    }
}
