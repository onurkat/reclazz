/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.setup

import com.intellij.openapi.project.Project
import com.onurkat.reclazz.plugin.hybris.HybrisAgentInstaller
import com.onurkat.reclazz.plugin.settings.AiAssistantConnector
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import com.onurkat.reclazz.plugin.ui.ReclazzSurfaces

/**
 * The wizard actions that change settings or files, without any UI, so the panel and the live acceptance test run the
 * same code. Returns the message to show (and whether it is good news), or null when there is nothing to say. Writes run
 * on the caller's thread; the panel calls the file-writing ones off the event thread.
 */
internal class SetupActionRunner(private val project: Project, private val connector: AiAssistantConnector = AiAssistantConnector(project)) {
    private fun settings() = ReclazzSettings.getInstance(project).state

    /** True for the actions handled here; ant server and Check again are run by the panel. */
    fun handles(action: SetupAction) = action is SetupAction.TurnOn || action is SetupAction.Install ||
        action is SetupAction.Update || action is SetupAction.SkipAssistant || action is SetupAction.ConnectAssistant

    fun writesFiles(action: SetupAction) = action is SetupAction.Install || action is SetupAction.Update || action is SetupAction.ConnectAssistant

    fun run(action: SetupAction): Pair<String, Boolean>? = when (action) {
        SetupAction.TurnOn -> { turnOn(); null }
        is SetupAction.Install, is SetupAction.Update -> { turnOn(); install() }
        SetupAction.SkipAssistant -> {
            settings().setupSkips = (settings().setupSkips.split(';').filter(String::isNotBlank) + "assistant").distinct().joinToString(";")
            null
        }
        is SetupAction.ConnectAssistant -> {
            val base = requireNotNull(project.basePath) { "Open an IDE project first" }
            val prepared = connector.prepare(action.client, false, base)
            val config = connector.preview(action.client, prepared)
            val backup = connector.connect(prepared, config)
            val name = action.client.display.substringBefore(" (")
            "Saved ${config.path}" + (backup?.let { " (backup: ${it.fileName})" } ?: "") + ". Restart $name to load Reclazz." to true
        }
        else -> null
    }

    private fun turnOn() {
        if (!settings().enabled) { settings().enabled = true; ReclazzSurfaces.refresh(project) }
    }

    private fun install(): Pair<String, Boolean> = when (val result =
        stageAgentForTests?.let { HybrisAgentInstaller.install(project, it) } ?: HybrisAgentInstaller.install(project)) {
        is HybrisAgentInstaller.Result.Success -> result.message to true
        is HybrisAgentInstaller.Result.Error -> result.message to false
    }

    companion object {
        /** Set only by tests whose IDE has no bundled Java agent; supplies the jar the install writes into the properties. */
        @Volatile internal var stageAgentForTests: (() -> java.io.File?)? = null
    }
}
