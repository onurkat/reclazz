/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.onurkat.reclazz.plugin.agent.McpLauncherStaging
import com.onurkat.reclazz.plugin.settings.*
import com.onurkat.reclazz.plugin.support.SyntheticProject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals as eq
import kotlin.test.assertTrue as yes
import kotlin.test.assertFalse as no

class ConnectAiAssistantPanelTest : BasePlatformTestCase() {
    private fun fixture(editors:Boolean=true,copy:(String)->Unit={},action:(ConnectAiAssistantPanel,Path,Path)->Unit) {
        SyntheticProject(project,false).use { f ->
            val home=Files.createTempDirectory("mcp-ui-home-")
            val staged=McpLauncherStaging.Staged(home.resolve(".reclazz/mcp/1.0-build"),"1.0")
            lateinit var panel:ConnectAiAssistantPanel
            McpPanelTestUi.edt {
                panel=ConnectAiAssistantPanel(f.project,home,{staged},
                    { if(editors)JsonPsiMcpEditor(project) else null },{ if(editors)TomlPsiMcpEditor(project) else null },copy,{staged})
            }
            McpPanelTestUi.await(panel)
            try { action(panel,f.home,home) } finally { McpPanelTestUi.edt { panel.close() };home.toFile().deleteRecursively() }
        }
    }
    fun testPreviewDoesNotWriteAndConnectRequiresExplicitClick()=fixture { panel,dir,_ ->
        no(panel.connectButton.isEnabled)
        val config=dir.resolve(".mcp.json");no(Files.exists(config))
        McpPanelTestUi.act(panel) { panel.previewButton.doClick() };yes(panel.connectButton.isEnabled,panel.status.text);no(Files.exists(config))
        McpPanelTestUi.act(panel) { panel.connectButton.doClick() };yes(Files.exists(config));no(panel.connectButton.isEnabled)
        yes(panel.status.text.contains("Saved"),panel.status.text)
        val old=Files.readString(config)
        McpPanelTestUi.act(panel) { panel.previewButton.doClick() };McpPanelTestUi.act(panel) { panel.connectButton.doClick() }
        Files.list(dir).use { files -> yes(files.anyMatch { it.fileName.toString().startsWith(".mcp.json.reclazz-backup-") && Files.readString(it)==old }) }
    }
    fun testLongPreviewDoesNotExpandDialogWidth()=fixture { panel,_,_ ->
        val width=panel.preferredSize.width
        repeat(2) { McpPanelTestUi.act(panel) { panel.previewButton.doClick() };McpPanelTestUi.act(panel) { panel.connectButton.doClick() } }
        eq(width,panel.preferredSize.width)
        fun areas(c:java.awt.Component):List<javax.swing.JTextArea> = (if(c is javax.swing.JTextArea)listOf(c) else emptyList())+
            (if(c is java.awt.Container)c.components.flatMap { areas(it) } else emptyList())
        yes(areas(panel).all { it.lineWrap })
    }
    fun testMissingOptionalEditorsRemainCopyOnly() {
        var clipboard=""
        fixture(false,{clipboard=it}) { panel,dir,_ ->
            McpPanelTestUi.act(panel) { panel.previewButton.doClick() };no(panel.connectButton.isEnabled);yes(panel.status.text.contains("JSON support is unavailable"),panel.status.text)
            McpPanelTestUi.act(panel) { panel.copyButton.doClick() };eq("",clipboard)
            McpPanelTestUi.act(panel) { panel.copyButton.doClick() };yes(clipboard.startsWith("claude mcp add --scope project"));no(Files.exists(dir.resolve(".mcp.json")))
            McpPanelTestUi.act(panel) { panel.client.selectedItem=McpClient.CODEX;panel.copyButton.doClick() }
            McpPanelTestUi.act(panel) { panel.copyButton.doClick() };yes(clipboard.startsWith("[mcp_servers.reclazz]"));no(clipboard.contains("codex mcp add"))
            McpPanelTestUi.act(panel) { panel.previewButton.doClick() };no(panel.connectButton.isEnabled);yes(panel.status.text.contains("TOML support is unavailable"),panel.status.text)
        }
    }
    fun testChangingScopeInvalidatesPreviewAndGlobalRequiresChoice()=fixture { panel,_,home ->
        McpPanelTestUi.act(panel) { panel.client.selectedItem=McpClient.CODEX }
        no(panel.global.isSelected);McpPanelTestUi.act(panel) { panel.previewButton.doClick() };yes(panel.connectButton.isEnabled,panel.status.text)
        McpPanelTestUi.act(panel) { panel.global.doClick() };no(panel.connectButton.isEnabled)
        McpPanelTestUi.act(panel) { panel.previewButton.doClick() };yes(panel.status.text.contains("Global (all projects)"),panel.status.text)
        no(Files.exists(home.resolve(".codex/config.toml")))
    }
}
