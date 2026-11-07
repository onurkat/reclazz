/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.ObservableConsoleView
import com.intellij.openapi.editor.colors.EditorColorsManager
import java.awt.Color
import java.awt.Font
import kotlin.math.pow
import kotlin.test.assertNotEquals as expectNotEquals
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.onurkat.reclazz.plugin.reload.ReloadManager
import com.onurkat.reclazz.plugin.reload.AgentEvent
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import com.onurkat.reclazz.plugin.support.ScriptedStatusServer
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import com.onurkat.reclazz.plugin.ui.ReloadLogPanel
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals as expectEquals
import kotlin.test.assertTrue as expectTrue

class ReloadLogPanelFixtureTest : BasePlatformTestCase() {
    private lateinit var panel: ReloadLogPanel
    private lateinit var manager: ReloadManager

    private val levels = listOf("CONNECTED", "INFO", "WARN", "COMPILE", "ERROR",
        "RELOAD", "STRUCTURAL_RELOAD", "OK", "DISCONNECTED", "FUTURE_LEVEL")

    override fun setUp() {
        super.setUp()
        manager = ReloadManager.getInstance(project)
        panel = ReloadLogPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.component // Initialize the real console editor before reading its rendered text.
    }

    fun testMixedLevelsVisibleInConsoleAreExportedInOrder() {
        levels.forEachIndexed { index, level -> manager.postLocalMessage(level, "event-$index") }
        drainEvents()
        val exported = panel.exportLog()
        expectEquals(levels.mapIndexed { index, level -> "[$level] event-$index" },
            rows(exported).map { it.substringAfter("] ") })
        val console = console()
        console.flushDeferredText()
        val warning = rows(exported).single { it.contains("[WARN]") }
        expectTrue(console.text.contains(warning), "The displayed warning and exported entry must agree")
        expectTrue(exported.contains("latest 500"), "Export must state its retention bound")
    }

    fun testRetainsOnlyLatest500MixedEventsInArrivalOrder() {
        for (index in 0 until 505) post(index)
        drainEvents()
        expectEquals((5 until 505).toList(), ids(panel.exportLog()))
        val exportedRows = rows(panel.exportLog())
        expectEquals((5 until 505).map { "[${levels[it % levels.size]}] event-$it" },
            exportedRows.map { it.substringAfter("] ") })
    }

    fun testExportsStayCompleteOrderedSnapshotsWhileEventsArrive() {
        for (index in 0 until 500) post(index)
        drainEvents()
        val original = panel.exportLog()
        val barrier = CyclicBarrier(2)
        val reader = Executors.newSingleThreadExecutor()
        try {
            val snapshots = reader.submit<List<String>> {
                val captured = mutableListOf<String>()
                repeat(10) {
                    barrier.await(10, TimeUnit.SECONDS)
                    repeat(10) {
                        val snapshot = panel.exportLog()
                        val entries = ids(snapshot)
                        expectEquals(500, entries.size)
                        expectEquals((entries.first()..entries.last()).toList(), entries)
                        captured.add(snapshot)
                    }
                    barrier.await(10, TimeUnit.SECONDS)
                }
                captured
            }
            repeat(10) { round ->
                barrier.await(10, TimeUnit.SECONDS)
                for (index in 500 + round * 100 until 600 + round * 100) post(index)
                drainEvents()
                barrier.await(10, TimeUnit.SECONDS)
            }
            val captured = snapshots.get(10, TimeUnit.SECONDS)
            expectEquals(100, captured.size)
            expectEquals((0 until 500).toList(), ids(original))
            expectEquals((1000 until 1500).toList(), ids(panel.exportLog()))
            // Later eviction cannot turn a saved snapshot into a partial or reordered view.
            captured.forEach { snapshot ->
                val entries = ids(snapshot)
                expectEquals(500, entries.size)
                expectEquals((entries.first()..entries.last()).toList(), entries)
            }
        } finally {
            reader.shutdownNow()
            expectTrue(reader.awaitTermination(10, TimeUnit.SECONDS), "Export reader must terminate")
        }
    }

    fun testSolrOutcomeLinesAreClassifiedWithoutTouchingCountsOrLedger() {
        val folder = Files.createTempDirectory("reclazz-status-replay-")
        val portFile = folder.resolve("agent.port")
        val settings = ReclazzSettings.getInstance(project)
        val original = settings.state.copy()
        val received = java.util.concurrent.CopyOnWriteArrayList<AgentEvent>()
        val connected = CountDownLatch(1)
        val replayed = CountDownLatch(1)
        val listener: (AgentEvent) -> Unit = {
            received.add(it)
            if (it.level == "CONNECTED") connected.countDown()
            if (it.message == "Synthetic replay complete") replayed.countDown()
        }
        val before = manager.reloadCount
        val rendered = mutableListOf<Pair<String, ConsoleViewContentType>>()
        console().addChangeListener(object : ObservableConsoleView.ChangeListener {
            override fun textAdded(text: String, type: ConsoleViewContentType) { rendered.add(text to type) }
        }, testRootDisposable)
        manager.addEventListener(listener)
        try {
            ScriptedStatusServer(portFile).use { server ->
                settings.state.agentPort = server.port
                manager.connectToAgent()
                expectTrue(connected.await(5, TimeUnit.SECONDS), "Synthetic client must connect")
                expectTrue(manager.sessionReport(), "HEALTH triggers only the test server's scripted replay")
                expectTrue(replayed.await(5, TimeUnit.SECONDS), "All scripted lines must reach the real manager")
                drainEvents()
                val console = console()
                console.flushDeferredText()
                val exported = panel.exportLog()
                val solr = received.filter { it.message.startsWith("SAP Solr ") }
                expectEquals(3, solr.size)
                expectEquals(listOf("INFO", "INFO", "INFO"), solr.map { it.level })
                expectEquals(ConsoleViewContentType.NORMAL_OUTPUT, panel.contentType(solr[0]))
                expectNotEquals(ConsoleViewContentType.NORMAL_OUTPUT, panel.contentType(solr[1]))
                expectEquals(ConsoleViewContentType.ERROR_OUTPUT, panel.contentType(solr[2]))
                for (event in solr) {
                    expectTrue(exported.contains("[INFO] " + event.message), "Raw text and wire level must be retained")
                    val offset = console.text.indexOf(event.message)
                    expectTrue(offset >= 0)
                    expectTrue(rendered.any { (text, type) ->
                        text.contains(event.message) && type == panel.contentType(event)
                    }, "Actual console print type must match the outcome: " + event.message)
                }
                expectEquals(before + 1, manager.reloadCount, "Only the scripted class RELOAD increments the count")
                expectEquals(null, manager.lastError, "Asynchronous INFO indexing failure must not become class ERROR")
                expectEquals(ScriptedStatusServer.script.map { it.first },
                    received.filter { it.level != "CONNECTED" }.map { it.level })
            }
            for (message in listOf("Other provider: FAILED (x)", "SAP Solr a: FINISHED documents=1", "SAP Solr a: FAILED_EXTRA (x)")) {
                expectEquals(ConsoleViewContentType.NORMAL_OUTPUT, panel.contentType(AgentEvent("INFO", message, "")))
            }
        } finally {
            manager.removeEventListener(listener)
            manager.disconnectFromAgent()
            settings.loadState(original)
            Files.deleteIfExists(portFile)
            Files.deleteIfExists(folder)
        }
    }

    fun testSolrCautionsAreVisibleInDefaultLightAndDarkSchemes() {
        val colors = EditorColorsManager.getInstance()
        val original = colors.globalScheme
        try {
            for (name in listOf(EditorColorsManager.DEFAULT_SCHEME_NAME, "Darcula")) {
                val scheme = checkNotNull(colors.getScheme(name)) { "Missing bundled scheme $name" }
                colors.setGlobalScheme(scheme)
                val normal = ConsoleViewContentType.NORMAL_OUTPUT.attributes.foregroundColor ?: scheme.defaultForeground
                val unverified = panel.contentType(AgentEvent("INFO", "SAP Solr synthetic/config/type: FINISHED_UNVERIFIED documents=2", ""))
                val failed = panel.contentType(AgentEvent("INFO", "SAP Solr synthetic/config/type: FAILED (native-operation-failed).", ""))
                val warning = unverified.attributes
                val foreground = checkNotNull(warning.foregroundColor) { "$name: warning must specify a foreground" }
                val errorForeground = checkNotNull(failed.attributes.foregroundColor)
                val background = scheme.getColor(ConsoleViewContentType.CONSOLE_BACKGROUND_KEY) ?: scheme.defaultBackground
                expectNotEquals(normal.rgb, foreground.rgb, "$name: caution must differ from ordinary text")
                expectNotEquals(normal.rgb, errorForeground.rgb, "$name: failure must differ from ordinary text")
                expectNotEquals(foreground.rgb, errorForeground.rgb, "$name: caution and failure must differ")
                expectTrue(warning.fontType and Font.BOLD != 0, "$name: warning also needs a non-colour distinction")
                val light = luminance(foreground)
                val dark = luminance(background)
                val contrast = (maxOf(light, dark) + 0.05) / (minOf(light, dark) + 0.05)
                expectTrue(contrast >= 4.5, "$name: warning contrast $contrast must be at least 4.5")
                println("Reclazz scheme=$name normal=${normal.rgb.toUInt().toString(16)} " +
                    "warning=${foreground.rgb.toUInt().toString(16)} error=${errorForeground.rgb.toUInt().toString(16)} " +
                    "background=${background.rgb.toUInt().toString(16)} warningContrast=$contrast bold=true")
                expectEquals(unverified, panel.contentType(AgentEvent("WARN", "Synthetic warning", "")))
                expectEquals(ConsoleViewContentType.ERROR_OUTPUT, failed)
            }
        } finally { colors.setGlobalScheme(original) }
    }

    private fun luminance(color: Color): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }

    private fun post(index: Int) = manager.postLocalMessage(levels[index % levels.size], "event-$index")

    private fun drainEvents() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

    private fun rows(export: String): List<String> = export.lineSequence()
        .dropWhile { it != "---" }.drop(1).filter { it.isNotEmpty() }.toList()

    private fun ids(export: String): List<Int> = rows(export).map { it.substringAfterLast("event-").toInt() }

    private fun console(): ConsoleViewImpl {
        val field = ReloadLogPanel::class.java.getDeclaredField("consoleView")
        field.isAccessible = true
        return field.get(panel) as ConsoleViewImpl
    }
}
