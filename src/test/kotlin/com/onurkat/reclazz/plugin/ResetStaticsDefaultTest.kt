/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.onurkat.reclazz.plugin.agent.AgentJarLocator
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import com.onurkat.reclazz.plugin.support.SyntheticProject
import kotlin.test.assertFalse as expectFalse
import kotlin.test.assertTrue as expectTrue

/** R31b (owner 2026-11-22): resetStatics is on by default for IDE launches, off for SAP Commerce, and a choice always wins. */
class ResetStaticsDefaultTest : BasePlatformTestCase() {
    fun testUnsetIsOnForAnOrdinaryProject() {
        val args = AgentJarLocator.buildAgentArgs(project, ReclazzSettings.State()).split(',')
        expectTrue("resetStatics=true" in args, args.toString())
    }
    fun testUnsetIsOffForSapCommerceAndAnExplicitChoiceWins() {
        SyntheticProject(project, sap = true).use { layout ->
            expectFalse("resetStatics=true" in AgentJarLocator.buildAgentArgs(layout.project, ReclazzSettings.State()).split(','))
            expectTrue("resetStatics=true" in AgentJarLocator.buildAgentArgs(layout.project, ReclazzSettings.State(resetStatics = true)).split(','))
        }
        expectFalse("resetStatics=true" in AgentJarLocator.buildAgentArgs(project, ReclazzSettings.State(resetStatics = false)).split(','))
    }
    /** R31b audit F2: attach never inherits the launch default; an explicit choice still applies. */
    fun testAttachKeepsResetStaticsOffUnlessChosen() {
        expectFalse("resetStatics=true" in com.onurkat.reclazz.plugin.agent.JvmAttacher.buildAttachArgs(project, ReclazzSettings.State()).split(','))
        expectTrue("resetStatics=true" in com.onurkat.reclazz.plugin.agent.JvmAttacher.buildAttachArgs(project, ReclazzSettings.State(resetStatics = true)).split(','))
    }
}
