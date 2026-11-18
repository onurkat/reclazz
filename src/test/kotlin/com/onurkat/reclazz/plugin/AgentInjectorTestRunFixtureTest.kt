/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.onurkat.reclazz.plugin.agent.AgentInjector
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import java.io.File

class AgentInjectorTestRunFixtureTest : BasePlatformTestCase() {
    private lateinit var saved: ReclazzSettings.State
    override fun setUp() {
        super.setUp()
        val settings=ReclazzSettings.getInstance(project);saved=settings.state.copy()
        settings.loadState(ReclazzSettings.State())
    }
    override fun tearDown() {
        try { ReclazzSettings.getInstance(project).loadState(saved) } finally { super.tearDown() }
    }
    private fun junit() = JUnitConfiguration("JUnit fixture",project,JUnitConfigurationType().configurationFactories[0])
    fun testApplicationStillReceivesAgent() {
        ReclazzSettings.getInstance(project).state.apply { enabled=true;autoDetectJdk=false }
        val injector=AgentInjector { File("/synthetic/reclazz-agent.jar") }
        val config=ApplicationConfiguration("Application fixture",project)
        assertTrue(injector.isApplicableFor(config))
        val params=JavaParameters();injector.updateJavaParameters(config,params,null)
        assertTrue(params.vmParametersList.list.any { it.startsWith("-javaagent:") })
    }
    fun testJUnitIsSkippedByDefaultWithoutChangingParameters() {
        ReclazzSettings.getInstance(project).state.apply { enabled=true;autoDetectJdk=false }
        val injector=AgentInjector { error("skipped tests must not locate the agent") }
        val config=junit();val params=JavaParameters();params.vmParametersList.add("-Xmx256m")
        assertFalse(ReclazzSettings.State().injectIntoTests)
        assertFalse(injector.isApplicableFor(config))
        injector.updateJavaParameters(config,params,null)
        assertEquals(listOf("-Xmx256m"),params.vmParametersList.list)
    }
    fun testJUnitOptInReceivesAgent() {
        ReclazzSettings.getInstance(project).state.apply { enabled=true;injectIntoTests=true;autoDetectJdk=false }
        val injector=AgentInjector { File("/synthetic/reclazz-agent.jar") };val config=junit()
        assertTrue(injector.isApplicableFor(config))
        val params=JavaParameters();injector.updateJavaParameters(config,params,null)
        assertTrue(params.vmParametersList.list.any { it.startsWith("-javaagent:") })
    }
    fun testDisabledProjectSkipsApplicationAndJUnitEvenWithOptIn() {
        ReclazzSettings.getInstance(project).state.apply { enabled=false;injectIntoTests=true }
        val injector=AgentInjector { error("disabled project must not locate the agent") }
        for(config in listOf(ApplicationConfiguration("Application fixture",project),junit())) {
            assertFalse(injector.isApplicableFor(config))
            val params=JavaParameters();params.vmParametersList.add("-Xmx256m")
            injector.updateJavaParameters(config,params,null)
            assertEquals(listOf("-Xmx256m"),params.vmParametersList.list)
        }
    }

}
