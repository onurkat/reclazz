/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.onurkat.reclazz.plugin.support.SyntheticProject
import com.onurkat.reclazz.plugin.agent.McpLauncherStaging
import com.onurkat.reclazz.plugin.settings.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.*

class McpAuditRegressionTest : BasePlatformTestCase() {
    private fun onEdt(action:()->Unit) { if(SwingUtilities.isEventDispatchThread())action() else SwingUtilities.invokeAndWait(action) }
    private fun fixture(action:(SyntheticProject,Path)->Unit) {
        SyntheticProject(project,false).use { fixture ->
            val home=Files.createTempDirectory("mcp-audit-home-")
            try { action(fixture,home) } finally { home.toFile().deleteRecursively() }
        }
    }
    private fun panel(f:SyntheticProject,home:Path,publish:()->McpLauncherStaging.Staged = { McpLauncherStaging.Staged(home.resolve(".reclazz/mcp/1.0-build"),"1.0") },copy:(String)->Unit = {},planned:McpLauncherStaging.Staged=McpLauncherStaging.Staged(home.resolve(".reclazz/mcp/1.0-build"),"1.0")):ConnectAiAssistantPanel {
        lateinit var panel:ConnectAiAssistantPanel
        McpPanelTestUi.edt { panel=ConnectAiAssistantPanel(f.project,home,{publish()},{JsonPsiMcpEditor(project)},{TomlPsiMcpEditor(project)},copy,{planned}) }
        McpPanelTestUi.await(panel)
        return panel
    }
    private fun click(p:ConnectAiAssistantPanel,button:javax.swing.JButton)=McpPanelTestUi.act(p) { button.doClick() }
    fun testProjectCannotSelectHome()=fixture { f,home ->
        val p=panel(f,home);p.client.selectedItem=McpClient.CODEX;p.directory.text=home.toString()
        click(p,p.previewButton);assertFalse(p.connectButton.isEnabled,"Project scope must not target global home config")
        assertFalse(Files.exists(home.resolve(".codex/config.toml")))
    }
    fun testProjectSymlinkCannotReadGlobalCursorConfig()=fixture { f,home ->
        Files.createDirectories(home.resolve(".cursor"));val global=home.resolve(".cursor/mcp.json");Files.writeString(global,"{}")
        Files.createSymbolicLink(f.home.resolve(".cursor"),home.resolve(".cursor"))
        val p=panel(f,home);p.client.selectedItem=McpClient.CURSOR
        click(p,p.previewButton);assertFalse(p.connectButton.isEnabled,"Symlinked config parent must be refused")
        assertEquals("{}",Files.readString(global))
    }
    fun testPreviewDoesNotStageAnyFiles()=fixture { f,home ->
        val count=AtomicInteger();val cache=home.resolve(".reclazz/mcp")
        val p=panel(f,home,{count.incrementAndGet();Files.createDirectories(cache);McpLauncherStaging.Staged(cache.resolve("1.0-build"),"1.0")})
        click(p,p.previewButton);assertEquals(0,count.get(),"Preview must not publish");assertFalse(Files.exists(cache))
    }
    fun testCopyNeedsExplicitPublicationConfirmation()=fixture { f,home ->
        val count=AtomicInteger();var clipboard=""
        val p=panel(f,home,{count.incrementAndGet();McpLauncherStaging.Staged(home.resolve(".reclazz/mcp/1.0-build"),"1.0")},{clipboard=it})
        click(p,p.copyButton);assertEquals(0,count.get(),"First Copy only proposes the write");assertEquals("",clipboard)
        click(p,p.copyButton);assertEquals(1,count.get());assertTrue(clipboard.startsWith("claude mcp add"))
    }
    fun testPublishingDoesNotRunOnEdt()=fixture { f,home ->
        val called=AtomicBoolean();val edt=AtomicBoolean()
        val p=panel(f,home,{called.set(true);edt.set(SwingUtilities.isEventDispatchThread());McpLauncherStaging.Staged(home.resolve(".reclazz/mcp/1.0-build"),"1.0")})
        click(p,p.previewButton);click(p,p.connectButton)
        assertTrue(called.get());assertFalse(edt.get(),"Disk publication must be off EDT")
    }
    private fun versions(home:Path):List<McpLauncherStaging.Staged> = listOf("1.9","1.10","1.11","1.12").mapIndexed { i,version ->
        val jar=McpLauncherStagingTest.jar(home.resolve("$version.jar"),version)
        McpLauncherStaging.stage(jar,jar,version,McpLauncherStagingTest.javaPath(),home).also {
            Files.setLastModifiedTime(it.directory,java.nio.file.attribute.FileTime.fromMillis(1000L+i))
        }
    }
    fun testDowngradeCleanupKeepsTheRunningPluginsVersion()=fixture { f,home ->
        val dirs=versions(home);val current=dirs.first()
        val p=panel(f,home,{current},planned=current);click(p,p.previewButton)
        click(p,p.cleanupPreviewButton);click(p,p.cleanupDeleteButton)
        assertTrue(Files.isDirectory(current.directory),"A downgrade must retain this IDE's current launcher")
    }
    fun testCopiedReferenceSurvivesBeforeTheUserPastesConfig()=fixture { f,home ->
        val jar=McpLauncherStagingTest.jar(home.resolve("1.0.jar"),"1.0")
        val copied=McpLauncherStaging.stage(jar,jar,"1.0",McpLauncherStagingTest.javaPath(),home)
        Files.setLastModifiedTime(copied.directory,java.nio.file.attribute.FileTime.fromMillis(1))
        val p=panel(f,home,{copied},planned=copied);click(p,p.copyButton);click(p,p.copyButton)
        val newer=versions(home)
        val other=panel(f,home,{newer.last()},planned=newer.last());click(other,other.previewButton);click(other,other.cleanupPreviewButton);click(other,other.cleanupDeleteButton)
        assertTrue(Files.isDirectory(copied.directory),"Copied setup must protect its version before a config file exists")
    }
    fun testWindowsCopyRefusesShellExpansionCharacters() {
        for(c in listOf("$","`","&","^")) {
            val entry=McpEntry("C:\\bin\\reclazz-mcp.cmd",listOf("--project-dir","C:\\project$c"))
            assertFailsWith<IllegalArgumentException>("Unsafe Windows copy: $c") { McpClientConfigs.copySetup(McpClient.CLAUDE,false,entry,true) }
        }
    }
    fun testScopeIsRecheckedAfterPreviewBeforeAnyConfigWrite()=fixture { f,home ->
        val parent=f.home.toRealPath().resolve(".cursor");Files.createDirectories(parent)
        val file=parent.resolve("mcp.json");Files.writeString(file,"{}")
        val scope=McpClientConfigs.Scope(f.home.toRealPath())
        val preview=McpClientConfigs.preview(file,scope) { it+"\n" }
        Files.delete(file);Files.delete(parent)
        val global=home.resolve(".cursor");Files.createDirectories(global);Files.writeString(global.resolve("mcp.json"),"{}")
        Files.createSymbolicLink(parent,global)
        assertFailsWith<IllegalArgumentException> { preview.confirm() }
        assertEquals("{}",Files.readString(global.resolve("mcp.json")))
        Files.list(global).use { assertEquals(1,it.count()) }
    }
    fun testSlowPlanningLeavesEdtResponsiveAndDetectionRunsOnlyOnce()=fixture { f,home ->
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val count=AtomicInteger();val staged=McpLauncherStaging.Staged(home.resolve(".reclazz/mcp/1.0-build"),"1.0")
        lateinit var p:ConnectAiAssistantPanel
        McpPanelTestUi.edt {
            p=ConnectAiAssistantPanel(f.project,home,{staged},{JsonPsiMcpEditor(project)},{TomlPsiMcpEditor(project)},{},
                { assertFalse(SwingUtilities.isEventDispatchThread());entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS));staged },
                { assertFalse(SwingUtilities.isEventDispatchThread());count.incrementAndGet();false })
        }
        McpPanelTestUi.await(p)
        try {
            McpPanelTestUi.edt { p.previewButton.doClick() }
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            McpPanelTestUi.edt {
                assertTrue(p.busy);assertFalse(p.connectButton.isEnabled)
                repeat(20) { p.client.renderer.getListCellRendererComponent(javax.swing.JList<McpClient>(),McpClient.CLAUDE,0,false,false) }
            }
            assertEquals(McpClient.values().size,count.get())
        } finally { release.countDown();McpPanelTestUi.await(p);McpPanelTestUi.edt { p.close() } }
        assertTrue(p.connectButton.isEnabled,p.status.text)
    }
    fun testWindowsConnectRefusesUnsafePathsBeforePublication()=fixture { f,home ->
        val count=AtomicInteger();val staged=McpLauncherStaging.Staged(home.resolve(".reclazz/mcp/1.0-build"),"1.0")
        lateinit var p:ConnectAiAssistantPanel
        McpPanelTestUi.edt { p=ConnectAiAssistantPanel(f.project,home,{count.incrementAndGet();staged},
            {JsonPsiMcpEditor(project)},{TomlPsiMcpEditor(project)},{},{staged},windows=true) }
        McpPanelTestUi.await(p)
        try {
            for(char in listOf("&","%","^","!")) {
                val child=f.home.resolve("project $char unsafe");Files.createDirectories(child)
                McpPanelTestUi.act(p) { p.directory.text=child.toString();p.previewButton.doClick() }
                assertFalse(p.connectButton.isEnabled,p.status.text)
                assertTrue(p.status.text.contains("Windows launcher paths"),p.status.text)
            }
            assertEquals(0,count.get());assertFalse(Files.exists(home.resolve(".reclazz")))
        } finally { McpPanelTestUi.edt { p.close() } }
    }
    fun testBackupNameContainsAnActualTimestamp() {
        val d=Files.createTempDirectory("mcp-backup-")
        try {
            val file=d.resolve(".mcp.json");Files.writeString(file,"{}")
            val backup=McpClientConfigs.preview(file) { it+"\n" }.confirm()!!
            assertTrue(Regex("\\.mcp\\.json\\.reclazz-backup-[0-9]{8}-[0-9]{6}.*").matches(backup.fileName.toString()),backup.fileName.toString())
        } finally { d.toFile().deleteRecursively() }
    }
}
