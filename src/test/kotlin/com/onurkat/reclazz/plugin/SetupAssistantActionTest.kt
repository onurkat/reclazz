/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.onurkat.reclazz.plugin.agent.McpLauncherStaging
import com.onurkat.reclazz.plugin.settings.AiAssistantConnector
import com.onurkat.reclazz.plugin.settings.JsonPsiMcpEditor
import com.onurkat.reclazz.plugin.settings.McpClient
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import com.onurkat.reclazz.plugin.setup.SetupAction
import com.onurkat.reclazz.plugin.setup.SetupActionRunner
import com.onurkat.reclazz.plugin.support.SyntheticProject
import java.nio.file.Files

class SetupAssistantActionTest : BasePlatformTestCase() {
    fun testOnlyASuccessfulConnectionClearsTheAssistantSkip() {
        SyntheticProject(project, false).use { fixture ->
            val home = Files.createDirectory(fixture.home.resolve("assistant-home"))
            val staged = McpLauncherStaging.Staged(home.resolve("staged"), "1.0")
            val connector = AiAssistantConnector(fixture.project, home, stage = { staged }, plan = { staged },
                jsonEditor = { JsonPsiMcpEditor(project) })
            val runner = SetupActionRunner(fixture.project, connector)
            val state = ReclazzSettings.getInstance(fixture.project).state
            val prior = state.setupSkips
            try {
                state.setupSkips = "other"
                runner.run(SetupAction.SkipAssistant)
                assertEquals("other;assistant", state.setupSkips)
                val configFile = fixture.home.resolve(".mcp.json")
                Files.writeString(configFile, "{invalid")
                assertTrue(runCatching { runner.run(SetupAction.ConnectAssistant(McpClient.CLAUDE)) }.isFailure)
                assertEquals("other;assistant", state.setupSkips)
                Files.writeString(configFile, "{}")
                assertEquals(true, runner.run(SetupAction.ConnectAssistant(McpClient.CLAUDE))?.second)
                assertTrue(connector.connected(McpClient.CLAUDE))
                assertEquals("other", state.setupSkips)
            } finally {
                state.setupSkips = prior
            }
        }
    }
}
