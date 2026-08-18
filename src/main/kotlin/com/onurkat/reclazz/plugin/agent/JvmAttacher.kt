/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import com.sun.tools.attach.VirtualMachine
import com.sun.tools.attach.VirtualMachineDescriptor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import com.intellij.openapi.progress.ProgressManager

data class JvmProcessInfo(
    val pid: String,
    val displayName: String
) {
    override fun toString(): String = "[$pid] $displayName"
}

sealed class AttachResult {
    data class Success(val pid: String) : AttachResult()
    data class AlreadyAttached(val pid: String) : AttachResult()
    data class Error(val message: String, val cause: Throwable? = null) : AttachResult()
}

object JvmAttacher {

    private val log = Logger.getInstance(JvmAttacher::class.java)

    /** Patterns matched against the main class / command line shown by VirtualMachine.list() */
    private val DISPLAY_NAME_PATTERNS = listOf(
        "org.tanukisoftware.wrapper.WrapperSimpleApp",
        "de.hybris.bootstrap.loader.Loader"
    )

    fun listAttachableProcesses(): List<JvmProcessInfo> {
        return try {
            val candidates = VirtualMachine.list()
            val results = mutableListOf<JvmProcessInfo>()

            for (desc in candidates) {
                // Fast path: check displayName (main class + args)
                if (isKnownDisplayName(desc.displayName())) {
                    results.add(JvmProcessInfo(
                        pid = desc.id(),
                        displayName = summarizeDisplayName(desc.displayName())
                    ))
                    continue
                }

                // Slow path: attach briefly to check system properties
                val processInfo = probeSystemProperties(desc)
                if (processInfo != null) {
                    results.add(processInfo)
                }
            }
            results
        } catch (e: Exception) {
            log.warn("Failed to list JVMs: ${e.message}")
            emptyList()
        }
    }

    private fun probeSystemProperties(desc: VirtualMachineDescriptor): JvmProcessInfo? {
        return try {
            val vm = VirtualMachine.attach(desc)
            try {
                val props = vm.systemProperties
                val platformHome = props.getProperty("platform.home")
                val hybrisBinDir = props.getProperty("HYBRIS_BIN_DIR")
                when {
                    platformHome != null -> JvmProcessInfo(
                        pid = desc.id(),
                        displayName = "SAP Commerce (platform: $platformHome)"
                    )
                    hybrisBinDir != null -> JvmProcessInfo(
                        pid = desc.id(),
                        displayName = "SAP Commerce (bin: $hybrisBinDir)"
                    )
                    else -> null
                }
            } finally {
                vm.detach()
            }
        } catch (_: Exception) {
            // Can't attach (permission, different user, etc.) — skip
            null
        }
    }

    fun attach(pid: String, project: Project): AttachResult {
        val agentJar = AgentJarLocator.findAgentJar()
            ?: return AttachResult.Error("Agent JAR not found. Is the Reclazz plugin installed correctly?")

        val settings = ReclazzSettings.getInstance(project).state
        val agentArgs = AgentJarLocator.buildAgentArgs(project, settings)

        // Delete stale port file before attaching so we don't connect to a dead port
        val basePath = project.basePath ?: return AttachResult.Error("Project has no base path")
        val portFilePath = Paths.get(basePath, ".idea", "reclazz", "agent.port")
        try {
            Files.deleteIfExists(portFilePath)
        } catch (_: Exception) {}

        return try {
            val vm = VirtualMachine.attach(pid)
            try {
                vm.loadAgent(agentJar.absolutePath, agentArgs)
            } finally {
                vm.detach()
            }

            // Wait for the port file to appear (agent writes it on startup)
            waitForPortFile(portFilePath, timeoutMs = 10_000)

            AttachResult.Success(pid)
        } catch (e: Exception) {
            val message = e.message ?: e.toString()
            when {
                message.contains("already loaded", ignoreCase = true) ||
                message.contains("Agent_OnAttach", ignoreCase = true) ->
                    AttachResult.AlreadyAttached(pid)

                message.contains("well-known file", ignoreCase = true) ||
                message.contains("permission", ignoreCase = true) ||
                message.contains("denied", ignoreCase = true) ->
                    AttachResult.Error(
                        "Cannot attach to PID $pid — permission denied. " +
                        "Make sure IntelliJ and the target JVM are running as the same user.",
                        e
                    )

                // JEP 451 is closing this door. Java 21 warns, Java 25 still
                // allows it by default, and a future release disallows it;
                // a server already started with -XX:-EnableDynamicAgentLoading
                // refuses today. The JVM's own sentence is good but stops at
                // naming the flag, and the developer's next question is where
                // to put it, which depends on how their server starts.
                message.contains("Dynamic agent loading is not enabled", ignoreCase = true) ->
                    AttachResult.Error(
                        "PID $pid was started with dynamic agent loading disabled, so no agent " +
                        "can be attached to it while it runs. Add -XX:+EnableDynamicAgentLoading " +
                        "to how that JVM starts and restart it: on SAP Commerce that is " +
                        "tomcat.javaoptions in config/dev/props/95-local.properties, which is " +
                        "the file Reclazz already writes its agent line into; on Spring Boot it " +
                        "is the VM options of the run configuration. Starting the server with " +
                        "the agent already on the command line needs none of this.",
                        e
                    )

                else -> AttachResult.Error("Failed to attach to PID $pid: $message", e)
            }
        }
    }

    private fun isKnownDisplayName(displayName: String): Boolean {
        return DISPLAY_NAME_PATTERNS.any { displayName.contains(it) }
    }

    private fun summarizeDisplayName(displayName: String): String {
        return when {
            displayName.contains("WrapperSimpleApp") -> "SAP Commerce (Tanuki Wrapper)"
            displayName.contains("de.hybris.bootstrap.loader.Loader") -> "SAP Commerce (Bootstrap Loader)"
            displayName.contains("platform.home") -> {
                val match = Regex("-Dplatform\\.home=([^\\s]+)").find(displayName)
                val home = match?.groupValues?.get(1) ?: "unknown"
                "SAP Commerce (platform: $home)"
            }
            else -> displayName.take(80)
        }
    }

    private fun waitForPortFile(portFilePath: Path, timeoutMs: Long): Boolean {
        val indicator = ProgressManager.getInstance().progressIndicator
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            indicator?.checkCanceled()
            if (Files.exists(portFilePath)) {
                val content = try { Files.readString(portFilePath).trim() } catch (_: Exception) { "" }
                if (content.toIntOrNull() != null) return true
            }
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }
}
