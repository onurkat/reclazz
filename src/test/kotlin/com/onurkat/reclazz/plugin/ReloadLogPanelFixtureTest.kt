/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.onurkat.reclazz.plugin.reload.ReloadManager
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
