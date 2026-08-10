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
import com.onurkat.reclazz.plugin.hybris.JdkDetector
import com.onurkat.reclazz.plugin.settings.ReclazzSettings

class AgentInjector : RunConfigurationExtension() {

    private val log = Logger.getInstance(AgentInjector::class.java)

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

        // Find bundled agent JAR from plugin install directory
        val agentJar = AgentJarLocator.findAgentJar() ?: return

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

        log.info("Reclazz agent injected: ${agentJar.absolutePath}")
        if (jdkInfo != null) {
            log.info("JDK detected: ${jdkInfo.displayName} — ${jdkInfo.capabilityDescription}")
        }
    }

}
