/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.agent

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.onurkat.reclazz.plugin.hybris.HybrisProjectDetector
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

object AgentJarLocator {

    private val log = Logger.getInstance(AgentJarLocator::class.java)

    fun findAgentJar(): File? {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("com.onurkat.reclazz"))
        if (plugin == null) {
            log.warn("Reclazz plugin not found in PluginManager")
            return null
        }

        val pluginPath = plugin.pluginPath
        val agentJar = pluginPath.resolve("agent").resolve("reclazz-agent.jar").toFile()

        if (!agentJar.exists()) {
            log.warn("Agent JAR not found at: ${agentJar.absolutePath}")
            return null
        }

        return agentJar
    }

    /**
     * A copy of the agent jar at a path that outlives the IDE installation
     * it came from.
     *
     * The bundled jar lives under the IDE's plugin directory, and that path
     * carries the IDE version:
     *
     *     ~/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins/reclazz/agent/...
     *
     * A SAP Commerce install writes that path into properties the server
     * starts from, where it then long outlives the IDE build it names.
     * Upgrading the IDE, or uninstalling the plugin, leaves the server
     * pointing at a jar that no longer exists — and a `-javaagent` that
     * cannot be opened does not degrade to "no hot-reload", it aborts JVM
     * startup:
     *
     *     Error opening zip file or JAR manifest missing : .../reclazz-agent.jar
     *     Error occurred during initialization of VM
     *     agent library failed Agent_OnLoad: instrument
     *
     * A routine IDE upgrade would stop the server from booting at all, with
     * nothing connecting the two events. So the server is pointed at a copy
     * in the user's home directory instead: outside the IDE, outside the
     * project (a project-local copy would land in their repository), and
     * refreshed here whenever the bundled jar changes so plugin updates
     * still reach it.
     */
    fun stableAgentJar(): File? {
        val bundled = findAgentJar() ?: return null
        val target = Paths.get(System.getProperty("user.home"), ".reclazz", "agent", "reclazz-agent.jar")

        try {
            Files.createDirectories(target.parent)
            val current = target.toFile()
            val upToDate = current.exists() &&
                    current.length() == bundled.length() &&
                    current.lastModified() >= bundled.lastModified()
            if (!upToDate) {
                // Copy via a temporary file and move it into place: a server
                // may be running against the existing jar, and replacing the
                // file by moving keeps that JVM on its own inode.
                val tmp = target.resolveSibling("reclazz-agent.jar.tmp")
                Files.copy(bundled.toPath(), tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                try {
                    Files.move(tmp, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                log.info("Refreshed agent jar at $target")
            }
            return target.toFile()
        } catch (e: Exception) {
            log.warn("Could not stage the agent jar at $target: ${e.message}")
            return null
        }
    }

    fun buildAgentArgs(project: Project, settings: ReclazzSettings.State): String {
        val args = mutableListOf<String>()

        // Platform-specific: Hybris home
        val hybrisHome = HybrisProjectDetector.findHybrisHome(project)
        if (hybrisHome != null) {
            args.add("hybrisHome=$hybrisHome")
            // Hybris-specific settings
            if (settings.watchExtensions.isNotBlank()) {
                args.add("watchExtensions=${settings.watchExtensions}")
            }
            if (settings.autoCompile) args.add("autoCompile=true")
            if (settings.autoImpex) args.add("autoImpex=true")
        } else {
            // Non-Hybris: pass IntelliJ's module compiler outputs as watchDirs
            // so the agent watches what the IDE actually compiles to, regardless
            // of build tool layout (Maven, Gradle, custom, Tanuki Wrapper, etc.)
            val outputDirs = collectModuleOutputDirs(project)
            if (outputDirs.isNotEmpty()) {
                args.add("watchDirs=${outputDirs.joinToString(";")}")
            }
        }

        // Common settings
        if (settings.verbose) args.add("verbose=true")
        args.add("debounceMs=${settings.debounceMs}")
        args.add("startupDelaySec=${settings.startupDelaySeconds}")

        if (settings.excludePatterns.isNotBlank()) {
            args.add("excludePatterns=${settings.excludePatterns}")
        }

        // Port file for agent to write its actual port after binding.
        val basePath = project.basePath
        if (basePath != null) {
            val portFilePath = Paths.get(basePath, ".idea", "reclazz", "agent.port")
            try {
                Files.createDirectories(portFilePath.parent)
            } catch (_: Exception) {}
            args.add("statusPort=0")
            args.add("portFile=$portFilePath")
        }

        return args.joinToString(",")
    }

    private fun collectModuleOutputDirs(project: Project): List<String> {
        return ReadAction.compute<List<String>, RuntimeException> {
            val dirs = LinkedHashSet<String>()
            for (module in ModuleManager.getInstance(project).modules) {
                val ext = CompilerModuleExtension.getInstance(module) ?: continue
                ext.compilerOutputPath?.path?.let { dirs.add(it) }
                ext.compilerOutputPathForTests?.path?.let { dirs.add(it) }
            }
            dirs.toList()
        }
    }
}
