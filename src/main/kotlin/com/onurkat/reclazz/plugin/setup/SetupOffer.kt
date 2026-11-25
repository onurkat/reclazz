/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.plugin.setup

import com.intellij.openapi.project.Project
import com.onurkat.reclazz.plugin.PluginPresence
import com.onurkat.reclazz.plugin.agent.AgentJarLocator
import com.onurkat.reclazz.plugin.hybris.HybrisAgentInstaller
import com.onurkat.reclazz.plugin.hybris.HybrisProjectDetector
import com.onurkat.reclazz.plugin.hybris.SetupCheck
import com.onurkat.reclazz.plugin.settings.ReclazzAppState
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal object SetupOffer {
    data class Offer(val welcome: Boolean, val dialog: Boolean)

    fun artifact(path: Path?): String? {
        if (path == null) return null
        return try {
            val a = Files.readAttributes(path, BasicFileAttributes::class.java)
            listOf(path.toAbsolutePath().normalize(), a.size(), a.lastModifiedTime(), a.creationTime(), a.fileKey()).joinToString("|")
        } catch (_: java.io.IOException) { null } catch (_: SecurityException) { null }
    }

    fun onOpen(project: Project): Offer {
        val plugin = PluginPresence.enabledDescriptor(AgentJarLocator.RECLAZZ_PLUGIN_ID.idString)
        val settings = ReclazzSettings.getInstance(project).state
        val home = HybrisProjectDetector.findHybrisHome(project)
        val version = plugin?.version.orEmpty()
        val complete = if (home == null) settings.enabled else {
            val findings = SetupCheck.check(SetupCheck.Input(home, version))
            val facts = SetupFacts(enabled = settings.enabled, pluginVersion = version, hybrisHome = home, findings = findings)
            SetupFlow.evaluate(facts).first().status.finished && HybrisAgentInstaller.driftedProperties(project).isEmpty()
        }
        return decide(ReclazzAppState.getInstance().state, settings, version, artifact(plugin?.pluginPath), complete)
    }

    @Synchronized
    fun decide(app: ReclazzAppState.State, project: ReclazzSettings.State, version: String, identity: String?, complete: Boolean): Offer {
        val fresh = app.setupVersion == null
        val updated = !fresh && app.setupVersion != version
        val reinstalled = !fresh && !updated && identity != null && app.setupArtifact != null && app.setupArtifact != identity
        if (reinstalled) app.setupGeneration++
        app.setupVersion = version
        if (identity != null || updated) app.setupArtifact = identity
        app.welcomeShown = true
        if (complete) project.dismissedSetupGeneration = null
        val incomplete = !complete && project.dismissedSetupGeneration != app.setupGeneration
        return Offer(fresh || updated || reinstalled, ((fresh || reinstalled) && identity != null) || incomplete)
    }
}
