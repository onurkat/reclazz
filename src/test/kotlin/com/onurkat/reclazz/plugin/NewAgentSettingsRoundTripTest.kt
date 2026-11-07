/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.JavaParameters
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import com.onurkat.reclazz.plugin.agent.AgentInjector
import com.onurkat.reclazz.plugin.agent.AgentJarLocator
import com.onurkat.reclazz.plugin.agent.JvmAttacher
import com.onurkat.reclazz.plugin.hybris.HybrisAgentInstaller
import com.onurkat.reclazz.plugin.hybris.HybrisPropertyFiles
import com.onurkat.reclazz.plugin.reload.AgentEvent
import com.onurkat.reclazz.plugin.reload.ReloadManager
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import com.onurkat.reclazz.plugin.support.SyntheticProject
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals as expectEquals
import kotlin.test.assertTrue as expectTrue
import kotlin.test.assertFalse as expectFalse

class NewAgentSettingsRoundTripTest : BasePlatformTestCase() {
    private fun nonDefaults() = ReclazzSettings.State(enabled = true, autoDetectJdk = false,
        autoImpex = true, autoSolrReindex = true, autoUpdateRunningSystem = true, impexAllowRemove = true,
        reloadBoundary = "request", structuralReload = false, excludeClasses = "com.example.Legacy*;com.example.Old")

    fun testNewSettingsRoundTripAndReachAllThreeLaunchPaths() {
        SyntheticProject(project, sap = true).use { layout ->
            val state = nonDefaults()
            val restored = XmlSerializer.deserialize(XmlSerializer.serialize(state), ReclazzSettings.State::class.java)
            expectEquals(state, restored)
            val settings = ReclazzSettings.getInstance(project)
            settings.loadState(restored)
            val expected = setOf("autoSolrReindex=true", "autoUpdateRunningSystem=true", "impexAllowRemove=true",
                "reloadBoundary=request", "structuralReload=false", "excludeClasses=com.example.Legacy*;com.example.Old")
            val args = AgentJarLocator.buildAgentArgs(layout.project, settings.state)
            expectTrue(args.split(',').containsAll(expected), args)
            val jar = File("/synthetic tools/reclazz-agent.jar")
            val params = JavaParameters()
            AgentInjector { jar }.updateJavaParameters(ApplicationConfiguration("Synthetic", layout.project), params, null)
            expectTrue(params.vmParametersList.list.contains("-javaagent:${jar.absolutePath}=$args"))
            val events = mutableListOf<AgentEvent>()
            val listener: (AgentEvent) -> Unit = { events.add(it) }
            val manager = ReloadManager.getInstance(project)
            manager.addEventListener(listener)
            try {
                val attach = JvmAttacher.buildAttachArgs(layout.project, settings.state)
                expectTrue(attach.split(',').containsAll(expected - "reloadBoundary=request"))
                expectFalse(attach.contains("reloadBoundary="))
                expectTrue(events.single().message.contains("Dynamic attach uses immediate reload"))
                expectEquals("request", settings.state.reloadBoundary, "Attaching must not rewrite the project preference")
            } finally { manager.removeEventListener(listener) }
            val result = HybrisAgentInstaller.install(layout.project) { jar }
            expectTrue(result is HybrisAgentInstaller.Result.Success, result.toString())
            val installed = HybrisPropertyFiles.readProperty(layout.home.resolve("config/local.properties"), "tomcat.javaoptions")!!
            expectEquals(HybrisAgentInstaller.agentArgument("${jar.absolutePath}=$args"), installed)
        }
    }

    fun testSapOptionsAreNotSentForGenericProjectAndRemoveRequiresAutoImpex() {
        SyntheticProject(project, sap = false).use { layout ->
            val args = AgentJarLocator.buildAgentArgs(layout.project, nonDefaults())
            for (key in listOf("autoSolrReindex", "autoUpdateRunningSystem", "impexAllowRemove", "autoImpex", "hybrisHome")) {
                expectFalse(args.contains("$key="), args)
            }
            expectTrue(args.contains("structuralReload=false"))
            expectTrue(args.contains("reloadBoundary=request"))
        }
        SyntheticProject(project, sap = true).use { layout ->
            expectFalse(AgentJarLocator.buildAgentArgs(layout.project, nonDefaults().copy(autoImpex = false)).contains("impexAllowRemove="))
            val defaults = AgentJarLocator.buildAgentArgs(layout.project, ReclazzSettings.State())
            for (key in listOf("autoSolrReindex", "autoUpdateRunningSystem", "impexAllowRemove", "reloadBoundary", "structuralReload", "excludeClasses")) {
                expectFalse(defaults.contains("$key="), defaults)
            }
        }
    }

    fun testPublicInstallerRefusesManualAgentBeforeStagingOrCreatingPortFiles() {
        SyntheticProject(project, sap = true).use { layout ->
            val file = layout.home.resolve("config/local.properties")
            val original = "tomcat.javaoptions=-javaagent:/synthetic/reclazz-agent.jar\n"
            Files.writeString(file, original)
            val result = HybrisAgentInstaller.install(layout.project) { error("Staging must not run") }
            expectTrue(result is HybrisAgentInstaller.Result.Error)
            expectEquals(original, Files.readString(file))
            expectFalse(Files.exists(layout.home.resolve(".idea")))
            expectFalse(Files.exists(file.resolveSibling("local.properties.reclazz-bak")))
        }
    }

    fun testRequestBoundaryIsNotClaimedForDynamicAttach() {
        SyntheticProject(project, sap = false).use { layout ->
            val events = mutableListOf<AgentEvent>()
            val listener: (AgentEvent) -> Unit = { events.add(it) }
            val manager = ReloadManager.getInstance(project)
            manager.addEventListener(listener)
            try {
                expectFalse(JvmAttacher.buildAttachArgs(layout.project, nonDefaults()).contains("reloadBoundary="))
                expectEquals("WARN", events.single().level)
                expectTrue(events.single().message.contains("requires -javaagent at JVM startup"))
                events.clear()
                JvmAttacher.buildAttachArgs(layout.project, nonDefaults().copy(reloadBoundary = "immediate"))
                expectTrue(events.isEmpty())
            } finally { manager.removeEventListener(listener) }
        }
    }
}
