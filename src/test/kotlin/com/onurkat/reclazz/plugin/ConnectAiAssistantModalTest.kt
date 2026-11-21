/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.impl.LaterInvocator
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.onurkat.reclazz.plugin.agent.McpLauncherStaging
import com.onurkat.reclazz.plugin.settings.ConnectAiAssistantPanel
import com.onurkat.reclazz.plugin.settings.McpClient
import com.onurkat.reclazz.plugin.support.SyntheticProject
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.*

/** Exercises the production modality queue; the separate sandbox gate owns visible native windows. */
class ConnectAiAssistantModalTest : BasePlatformTestCase() {
    private inner class Modal(private val content:JComponent=JPanel()):DialogWrapper(project,false) {
        init { init() }
        override fun createCenterPanel()=content
        fun within(action:()->Unit) {
            // The headless DialogWrapper peer's show() immediately closes it. Enter the exact
            // production modal queue with this wrapper instead, retaining both nested entities.
            LaterInvocator.enterModal(this)
            try { action() } finally { LaterInvocator.leaveModal(this);close(CANCEL_EXIT_CODE) }
        }
    }
    private fun nestedDetection(fail:Boolean) {
        SyntheticProject(project,false).use { fixture ->
            val home=Files.createTempDirectory("mcp-modal-home-")
            val release=CountDownLatch(1);val detected=CountDownLatch(1)
            try {
                McpPanelTestUi.edt {
                    Modal().within {
                        val parent=ModalityState.current()
                        val staged=McpLauncherStaging.Staged(home.resolve(".reclazz/mcp/1.0-build"),"1.0")
                        val panel=ConnectAiAssistantPanel(fixture.project,home,{staged},detect={
                            assertFalse(SwingUtilities.isEventDispatchThread())
                            check(release.await(10,TimeUnit.SECONDS));detected.countDown()
                            if(fail)throw java.io.IOException("synthetic detection failed")
                            false
                        })
                        try {
                            assertTrue(panel.busy)
                            Modal(panel).within {
                                assertNotSame(parent,ModalityState.current(),"Connect must be a higher modality than construction in Settings")
                                val pulse=AtomicBoolean()
                                ApplicationManager.getApplication().invokeLater({pulse.set(true)},ModalityState.any())
                                release.countDown()
                                assertTrue(detected.await(5,TimeUnit.SECONDS),"detection actually ran")
                                McpPanelTestUi.await(panel)
                                assertTrue(pulse.get(),"the EDT remains responsive inside the nested modal state")
                                assertTrue(panel.client.isEnabled);assertTrue(panel.directory.isEnabled)
                                assertTrue(panel.previewButton.isEnabled);assertTrue(panel.copyButton.isEnabled)
                                assertFalse(panel.connectButton.isEnabled,"Connect still requires an explicit valid preview")
                                if(fail)assertContains(panel.status.text,"synthetic detection failed")
                                else {
                                    val rendered=panel.client.renderer.getListCellRendererComponent(javax.swing.JList<McpClient>(),McpClient.CLAUDE,0,false,false)
                                    assertFalse((rendered as javax.swing.JLabel).text.contains("checking"))
                                }
                                assertFalse(Files.exists(fixture.home.resolve(".mcp.json")))
                                assertFalse(Files.exists(home.resolve(".reclazz")),"opening and detection publish nothing")
                            }
                        } finally { release.countDown();panel.close() }
                    }
                }
            } finally { release.countDown();home.toFile().deleteRecursively() }
        }
    }
    fun testDetectionCompletesInsideModalDialogOpenedFromSettings()=nestedDetection(false)
    fun testDetectionFailureReenablesControlsInsideNestedModalDialog()=nestedDetection(true)
}
