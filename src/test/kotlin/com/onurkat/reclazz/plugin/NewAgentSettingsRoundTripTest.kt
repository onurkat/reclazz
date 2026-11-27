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
    private fun nonDefaults() = ReclazzSettings.State(enabled = true, injectIntoTests = true, autoDetectJdk = false,
        autoImpex = true, autoSolrReindex = true, autoUpdateRunningSystem = true, impexAllowRemove = true, backofficeConfigReset = false,
        reloadBoundary = "request", structuralReload = false, resetStatics = true, excludeClasses = "com.example.Legacy*;com.example.Old")

    fun testUnicodeOptionsReachTheDirectAndInstalledLaunchers() {
        SyntheticProject(project, sap = true).use { layout ->
            val state = ReclazzSettings.State(excludeClasses = "com.example.Çalışma;com.example.東京")
            for (includeHome in listOf(true, false)) {
                val argument = AgentJarLocator.buildAgentArgs(layout.project, state, includeHybrisHome = includeHome)
                expectTrue(argument.startsWith("reclazz-utf8:") && argument.all { it.code < 128 }, argument)
                val original = java.util.Base64.getUrlDecoder().decode(argument.substringAfter(':')).toString(Charsets.UTF_8)
                expectTrue(original.contains("excludeClasses=" + state.excludeClasses), original)
                expectTrue(original.contains("portFile=" + layout.home.resolve(".idea/reclazz/agent.port")), original)
            }
        }
    }

    fun testDefaultLaunchLeavesDebounceAdaptive() {
        SyntheticProject(project, sap = true).use { layout ->
            val args = AgentJarLocator.buildAgentArgs(layout.project, ReclazzSettings.State(autoCompile = true))
            expectFalse(args.split(',').any { it.startsWith("debounceMs=") }, args)
        }
    }

    fun testDebounceMigrationPreservesOverridesIncludingNewExplicit500() {
        val component = ReclazzSettings()
        fun restore(state: ReclazzSettings.State) {
            component.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(state), ReclazzSettings.State::class.java))
        }
        fun option(): String? = AgentJarLocator.buildAgentArgs(project, component.state)
            .split(',').singleOrNull { it.startsWith("debounceMs=") }

        restore(ReclazzSettings.State(debounceMs = 500))
        expectEquals(null, option())
        restore(ReclazzSettings.State(debounceMs = 250))
        expectEquals("debounceMs=250", option())
        expectEquals(null, component.state.debounceMs)
        restore(component.state)
        expectEquals("debounceMs=250", option())
        restore(ReclazzSettings.State(fixedDebounceMs = 500))
        expectEquals("debounceMs=500", option())
        restore(ReclazzSettings.State())
        expectEquals(null, option())
    }

    fun testNewSettingsRoundTripAndReachAllThreeLaunchPaths() {
        SyntheticProject(project, sap = true).use { layout ->
            val state = nonDefaults()
            val restored = XmlSerializer.deserialize(XmlSerializer.serialize(state), ReclazzSettings.State::class.java)
            expectEquals(state, restored)
            val settings = ReclazzSettings.getInstance(project)
            settings.loadState(restored)
            val expected = setOf("autoSolrReindex=true", "autoUpdateRunningSystem=true", "impexAllowRemove=true", "backofficeConfigReset=false",
                "reloadBoundary=request", "structuralReload=false", "resetStatics=true", "excludeClasses=com.example.Legacy*;com.example.Old")
            val args = AgentJarLocator.buildAgentArgs(layout.project, settings.state)
            expectTrue(args.contains("hybrisHome=${layout.home}"), "direct IDE JVM may have no SAP startup properties")
            expectFalse(args.contains("injectIntoTests"), "IDE test preference is not an agent option")
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
                expectTrue(attach.contains("hybrisHome=${layout.home}"), "attach must retain its known project home")
                expectTrue(attach.split(',').containsAll(expected - "reloadBoundary=request"))
                expectFalse(attach.contains("reloadBoundary="))
                expectTrue(events.single().message.contains("Dynamic attach uses immediate reload"))
                expectEquals("request", settings.state.reloadBoundary, "Attaching must not rewrite the project preference")
            } finally { manager.removeEventListener(listener) }
            val result = HybrisAgentInstaller.install(layout.project) { jar }
            expectTrue(result is HybrisAgentInstaller.Result.Success, result.toString())
            val installed = HybrisPropertyFiles.readProperty(layout.home.resolve("config/local.properties"), "tomcat.javaoptions")!!
            val managed=args.split(',').filterNot { it.startsWith("hybrisHome=") }.joinToString(",")
            expectEquals(HybrisAgentInstaller.agentArgument("${jar.absolutePath}=$managed"), installed)
            expectFalse(installed.contains("hybrisHome="))
        }
    }

    fun testSapOptionsAreNotSentForGenericProjectAndRemoveRequiresAutoImpex() {
        SyntheticProject(project, sap = false).use { layout ->
            val args = AgentJarLocator.buildAgentArgs(layout.project, nonDefaults())
            for (key in listOf("autoSolrReindex", "autoUpdateRunningSystem", "impexAllowRemove", "autoImpex", "hybrisHome", "backofficeConfigReset")) {
                expectFalse(args.contains("$key="), args)
            }
            expectTrue(args.contains("structuralReload=false"))
            expectTrue(args.contains("reloadBoundary=request"))
        }
        SyntheticProject(project, sap = true).use { layout ->
            expectFalse(AgentJarLocator.buildAgentArgs(layout.project, nonDefaults().copy(autoImpex = false)).contains("impexAllowRemove="))
            val defaults = AgentJarLocator.buildAgentArgs(layout.project, ReclazzSettings.State())
            for (key in listOf("autoSolrReindex", "autoUpdateRunningSystem", "impexAllowRemove", "reloadBoundary", "structuralReload", "resetStatics", "excludeClasses", "backofficeConfigReset")) {
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
