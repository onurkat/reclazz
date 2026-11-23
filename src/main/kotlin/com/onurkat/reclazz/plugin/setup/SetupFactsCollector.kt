/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.setup

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.onurkat.reclazz.plugin.PluginPresence
import com.onurkat.reclazz.plugin.agent.AgentJarLocator
import com.onurkat.reclazz.plugin.hybris.HybrisProjectDetector
import com.onurkat.reclazz.plugin.hybris.SetupCheck
import com.onurkat.reclazz.plugin.settings.AiAssistantConnector
import com.onurkat.reclazz.plugin.settings.McpClient
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import java.nio.file.Files
import java.util.jar.JarFile

/** Gathers [SetupFacts] for a project. Runs off the event thread; never writes anything. */
internal object SetupFactsCollector {

    /** What the panel itself observed since it was opened (ant runs). */
    data class Observed(
        val antRunning: Boolean = false,
        val antFailure: String? = null,
        val antRan: Boolean = false,
    )

    fun collect(project: Project, observed: Observed, connector: AiAssistantConnector = AiAssistantConnector(project)): SetupFacts {
        ProgressManager.checkCanceled()
        val settings = ReclazzSettings.getInstance(project).state.copy()
        val home = HybrisProjectDetector.findHybrisHomeNow(project)
        val version = pluginVersion()
        val assistants = McpClient.entries.map { AssistantFact(it, connector.detected(it), connector.connected(it)) }
        var facts = SetupFacts(
            enabled = settings.enabled,
            pluginVersion = version,
            antRunning = observed.antRunning,
            antFailure = observed.antFailure,
            antRan = observed.antRan,
            assistants = assistants,
            assistantSkipped = "assistant" in settings.setupSkips.split(';'),
        )
        if (home != null) {
            val watch = settings.watchExtensions.split(';').map(String::trim).filter(String::isNotEmpty).toSet()
            facts = facts.copy(
                hybrisHome = home,
                findings = SetupCheck.check(SetupCheck.Input(home, version, watch, settings.autoCompile), checkCancelled = ProgressManager::checkCanceled),
                antReady = Files.isRegularFile(home.resolve("bin/platform").resolve(if (SystemInfo.isWindows) "setantenv.bat" else "setantenv.sh")),
                // Read here, off the event thread: it reads several properties files and the JDK release file.
                installPreview = try { com.onurkat.reclazz.plugin.hybris.HybrisAgentInstaller.preview(project) } catch (_: Exception) { null },
            )
        }
        return facts
    }

    /** Set only by tests whose IDE has no installed plugin descriptor. */
    @Volatile internal var pluginVersionForTests: String? = null

    fun pluginVersion(): String {
        pluginVersionForTests?.let { return it }
        val bundled = AgentJarLocator.findAgentJar()
        return try { bundled?.let { JarFile(it).use { jar -> jar.manifest?.mainAttributes?.getValue("Implementation-Version") } } }
        catch (_: java.io.IOException) { null }
            ?: PluginPresence.enabledDescriptor(AgentJarLocator.RECLAZZ_PLUGIN_ID.idString)?.version.orEmpty()
    }
}
