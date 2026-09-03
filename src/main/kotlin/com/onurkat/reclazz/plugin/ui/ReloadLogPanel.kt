/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.onurkat.reclazz.plugin.reload.AgentEvent
import com.onurkat.reclazz.plugin.reload.ReloadManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import javax.swing.JComponent

class ReloadLogPanel(private val project: Project) : Disposable {

    private val consoleView: ConsoleView = TextConsoleBuilderFactory.getInstance()
        .createBuilder(project)
        .console

    private val reloadHistory = ArrayDeque<ReloadEntry>(500)
    private val maxHistorySize = 500

    val component: JComponent
        get() = consoleView.component

    private val eventListener: (AgentEvent) -> Unit = { event ->
        ApplicationManager.getApplication().invokeLater {
            appendEvent(event)
        }
    }

    init {
        val manager = ReloadManager.getInstance(project)
        manager.addEventListener(eventListener)
        Disposer.register(this, consoleView)

        if (manager.isConnected) {
            consoleView.print("[Reclazz] Agent connected (" + plural(manager.reloadCount, "reload") + ")\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        } else {
            consoleView.print("[Reclazz] Waiting for agent connection...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        }
    }

    private fun appendEvent(event: AgentEvent) {
        val contentType = when (event.level) {
            "ERROR" -> ConsoleViewContentType.ERROR_OUTPUT
            "WARN" -> ConsoleViewContentType.LOG_WARNING_OUTPUT
            "OK", "RELOAD", "STRUCTURAL_RELOAD" -> ConsoleViewContentType.NORMAL_OUTPUT
            "CONNECTED" -> ConsoleViewContentType.SYSTEM_OUTPUT
            "DISCONNECTED" -> ConsoleViewContentType.ERROR_OUTPUT
            else -> ConsoleViewContentType.NORMAL_OUTPUT
        }

        val prefix = when (event.level) {
            "CONNECTED" -> "[CONNECTED] "
            "DISCONNECTED" -> "[DISCONNECTED] "
            "ERROR" -> "[ERROR] "
            "WARN" -> "[WARN] "
            "OK" -> "[OK] "
            "RELOAD" -> "[SWAP] "
            "STRUCTURAL_RELOAD" -> "[STRC] "
            "COMPILE" -> "[COMP] "
            else -> "[${event.level}] "
        }

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        consoleView.print("[$timestamp] $prefix${event.message}\n", contentType)

        // Track reload history
        if (event.level == "RELOAD" || event.level == "STRUCTURAL_RELOAD"
                || event.level == "ERROR" || event.level == "OK") {
            synchronized(reloadHistory) {
                if (reloadHistory.size >= maxHistorySize) {
                    reloadHistory.pollFirst()
                }
                reloadHistory.addLast(ReloadEntry(timestamp, event.level, event.message))
            }
        }
    }

    /**
     * Export the reload log as plain text.
     */
    fun exportLog(): String {
        val entries = synchronized(reloadHistory) { reloadHistory.toList() }
        val sb = StringBuilder()
        sb.appendLine("Reclazz - Log Export")
        sb.appendLine("Project: ${project.name}")
        sb.appendLine("Exported: ${LocalDateTime.now()}")
        sb.appendLine("---")
        for (entry in entries) {
            sb.appendLine("[${entry.timestamp}] [${entry.level}] ${entry.message}")
        }
        return sb.toString()
    }

    override fun dispose() {
        ReloadManager.getInstance(project).removeEventListener(eventListener)
    }

    data class ReloadEntry(val timestamp: String, val level: String, val message: String)
}
