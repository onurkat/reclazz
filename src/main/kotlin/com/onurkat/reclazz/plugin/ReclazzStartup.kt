/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.onurkat.reclazz.plugin.agent.AgentJarLocator
import com.onurkat.reclazz.plugin.hybris.HybrisAgentInstaller
import com.onurkat.reclazz.plugin.hybris.HybrisProjectDetector
import com.onurkat.reclazz.plugin.hybris.JdkDetector
import com.onurkat.reclazz.plugin.notifications.ReloadNotifications
import com.onurkat.reclazz.plugin.reload.ReloadManager
import com.onurkat.reclazz.plugin.setup.SetupOffer
import com.onurkat.reclazz.plugin.setup.SetupWizard
import org.jetbrains.annotations.TestOnly
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import java.util.concurrent.ConcurrentHashMap

class ReclazzStartup : ProjectActivity {

    private val log = Logger.getInstance(ReclazzStartup::class.java)

    // Track which projects have already shown the JDK notification this IDE session
    companion object {
        private val notifiedProjects = ConcurrentHashMap.newKeySet<String>()

        // Projects already offered the "Reclazz is off here" action this session
        private val offeredProjects = ConcurrentHashMap.newKeySet<String>()

        // Projects already warned that their JVM options drifted this session
        private val driftReported = ConcurrentHashMap.newKeySet<String>()

        /**
         * These sets exist to make each of these things happen once per IDE
         * session. Tests share one Application across methods, so without a
         * reset the second test to run inherits the first one's answers.
         */
        @TestOnly
        fun resetSessionStateForTests() {
            notifiedProjects.clear()
            offeredProjects.clear()
            driftReported.clear()
        }
    }

    override suspend fun execute(project: Project) {
        val settings = ReclazzSettings.getInstance(project)
        val isHybris = HybrisProjectDetector.isHybrisProject(project)

        // Always try to discover and connect to a running agent, even when
        // the plugin is "disabled" in settings. The status widget is purely
        // informational, if the agent is running externally (e.g. attached
        // via wrapper.conf), the user wants to see its status regardless of
        // whether they've toggled the plugin's own injection features on.
        // Only the agent-injection / auto-compile features are gated by
        // settings.enabled.
        val manager = ReloadManager.getInstance(project)
        manager.connectToAgent()

        // Keep the staged agent in step with the plugin. Only ever refreshes a
        // copy that already exists, and only says anything when it changed.
        if (AgentJarLocator.refreshStagedAgentJar()) {
            val version = PluginPresence.enabledDescriptor(AgentJarLocator.RECLAZZ_PLUGIN_ID.idString)?.version ?: "a new version"
            ReloadNotifications.agentJarRefreshed(project, version)
        }

        val projectKey = project.basePath ?: project.name
        if (offeredProjects.add(projectKey)) {
            val offer = SetupOffer.onOpen(project)
            if (offer.welcome) ReloadNotifications.welcome(project, isHybris, settings.state.enabled)
            if (offer.dialog) SetupWizard.open(project)
        }
        if (!settings.state.enabled) return

        if (isHybris) {
            log.info("SAP Commerce project detected: ${project.name}")

            // A configured agent jar that no longer exists stops the server
            // from booting at all, so it outranks everything else here.
            val missingJar = HybrisAgentInstaller.missingAgentJar(project)
            if (missingJar != null && driftReported.add("missing:${project.basePath ?: project.name}")) {
                ReloadNotifications.notifyAgentJarMissing(project, missingJar)
            }

            // Reclazz's properties file wins over the user's, so if they
            // edited their own JVM options since installing, their change is
            // being silently ignored. Say so once per project per session.
            val drifted = HybrisAgentInstaller.driftedProperties(project)
            if (drifted.isNotEmpty() && driftReported.add(project.basePath ?: project.name)) {
                ReloadNotifications.notifyOptionsDrifted(project, drifted)
            }
        } else {
            log.info("Reclazz enabled for project: ${project.name}")
        }

        // Run JDK detection, only notify once per project per IDE session
        if (settings.state.autoDetectJdk) {
            val jdkInfo = JdkDetector.detect(project)
            if (jdkInfo != null) {
                log.info("JDK detected: ${jdkInfo.displayName}")
                val projectKey = project.basePath ?: project.name
                if (notifiedProjects.add(projectKey)) {
                    ReloadNotifications.notifyJdkDetection(project, jdkInfo)
                }
            } else {
                log.warn("Could not detect project JDK")
            }
        }
    }
}
