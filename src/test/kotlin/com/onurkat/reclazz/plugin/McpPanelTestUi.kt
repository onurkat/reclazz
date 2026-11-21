/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.plugin

import com.intellij.testFramework.PlatformTestUtil
import com.onurkat.reclazz.plugin.settings.ConnectAiAssistantPanel
import javax.swing.SwingUtilities
import kotlin.test.assertFalse

internal object McpPanelTestUi {
    fun edt(action:()->Unit) { if(SwingUtilities.isEventDispatchThread())action() else SwingUtilities.invokeAndWait(action) }
    fun await(panel:ConnectAiAssistantPanel) {
        val end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        var busy=true
        while(busy && System.nanoTime()<end) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            edt { busy=panel.busy }
            if(busy)Thread.sleep(10)
        }
        assertFalse(busy,"Assistant panel did not finish: ${panel.status.text}")
    }
    fun act(panel:ConnectAiAssistantPanel,action:()->Unit) { edt(action);await(panel) }
}
