/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.agent

import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.diagnostic.Logger
import com.onurkat.reclazz.plugin.notifications.ReloadNotifications
import com.onurkat.reclazz.plugin.hybris.HybrisAgentInstaller
import com.onurkat.reclazz.plugin.hybris.JdkDetector
import com.onurkat.reclazz.plugin.settings.ReclazzSettings

class AgentInjector : RunConfigurationExtension() {

    private val log = Logger.getInstance(AgentInjector::class.java)

    /**
     * Projects already told the jar is missing. Once each: a developer who
     * runs their application five times has one broken installation, not five
     * of them.
     */
    private val missingJarReported = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean {
        return ReclazzSettings.getInstance(configuration.project).state.enabled
    }

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?
    ) {
        val project = configuration.project
        val settings = ReclazzSettings.getInstance(project).state

        // Find bundled agent JAR from plugin install directory.
        //
        // Not finding it used to return here and say nothing, which left the
        // developer with an application that started normally, a Reclazz tool
        // window saying "Waiting for agent connection..." for as long as they
        // cared to wait, and the only evidence in idea.log. The jar goes
        // missing for ordinary reasons: a partial update, a plugin directory
        // an antivirus quarantined, an installation copied between machines.
        // Reclazz's whole argument is that it says what did not happen, and
        // this was the one place it did not.
        val agentJar = AgentJarLocator.findAgentJar()
        if (agentJar == null) {
            if (missingJarReported.add(project.locationHash)) {
                ReloadNotifications.warn(
                    project, "Reclazz could not find its agent",
                    "The agent jar is not where this plugin was installed, so " +
                    "${configuration.name} is running without it and nothing will hot-reload. " +
                    "Reinstalling Reclazz puts the jar back. The path it looked at is in " +
                    "idea.log, under AgentJarLocator."
                )
            }
            return
        }

        // Detect JDK capabilities
        val jdkInfo = if (settings.autoDetectJdk) JdkDetector.detect(project) else null

        // Build agent args
        val agentArgs = AgentJarLocator.buildAgentArgs(project, settings)

        // Add -javaagent
        params.vmParametersList.add("-javaagent:${agentJar.absolutePath}=$agentArgs")

        // Add JDK-specific flags
        if (jdkInfo != null) {
            for (arg in jdkInfo.recommendedJvmArgs) {
                if (!params.vmParametersList.parameters.contains(arg)) {
                    params.vmParametersList.add(arg)
                }
            }
        }

        // From JDK 24 the JVM warns about the sun.misc.Unsafe access an enum
        // append needs, and JDK 26 refuses it by default; this flag restores
        // it. It is gated on the JVM this configuration actually starts
        // (params.jdk, which may be an alternate JRE rather than the project
        // SDK), because launchers before JDK 23 fail outright on the option:
        // measured on SapMachine 21, "Unrecognized option ... Could not
        // create the Java Virtual Machine". No resolvable JDK means no flag.
        val runtimeJdk = params.jdk?.let { JdkDetector.featureVersionOf(it) }
        if (runtimeJdk != null && runtimeJdk >= 24) {
            val flag = HybrisAgentInstaller.UNSAFE_ACCESS_FLAG
            if (!params.vmParametersList.parameters.contains(flag)) {
                params.vmParametersList.add(flag)
            }
        }

        log.info("Reclazz agent injected: ${agentJar.absolutePath}")
        if (jdkInfo != null) {
            log.info("JDK detected: ${jdkInfo.displayName} — ${jdkInfo.capabilityDescription}")
        }
    }

}
