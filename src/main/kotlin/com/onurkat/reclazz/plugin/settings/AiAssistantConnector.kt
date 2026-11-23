/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.settings

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.onurkat.reclazz.plugin.PluginPresence
import com.onurkat.reclazz.plugin.agent.AgentJarLocator
import com.onurkat.reclazz.plugin.agent.McpLauncherStaging
import com.onurkat.reclazz.plugin.hybris.HybrisProjectDetector
import java.nio.file.Files
import java.nio.file.Path

/**
 * Adds the bundled Reclazz MCP server to an AI assistant's config (Claude Code, Cursor, VS Code, Codex). Shared by the
 * Settings dialog and the setup wizard so both write exactly the same entry with the same checks. No UI; every call that
 * touches the filesystem is meant for a pooled thread.
 */
internal class AiAssistantConnector(
    private val project: Project,
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val stage: (McpLauncherStaging.Staged) -> McpLauncherStaging.Staged = { stageBundled(it) },
    private val jsonEditor: () -> JsonMcpEditor? = { project.getService(JsonMcpEditor::class.java) },
    private val tomlEditor: () -> TomlMcpEditor? = { project.getService(TomlMcpEditor::class.java) },
    private val plan: () -> McpLauncherStaging.Staged = { planBundled() },
    private val detect: ((McpClient) -> Boolean)? = null,
    private val windows: Boolean = System.getProperty("os.name").startsWith("Windows"),
) {
    data class Prepared(val entry: McpEntry, val path: Path, val scope: McpClientConfigs.Scope, val staged: McpLauncherStaging.Staged)

    /** Validates the target and works out the entry; writes nothing. */
    fun prepare(client: McpClient, global: Boolean, directory: String): Prepared {
        val root = Path.of(directory); require(root.isAbsolute) { "Choose an absolute project directory" }
        val real = root.toRealPath()
        val base = Path.of(requireNotNull(project.basePath) { "Open an IDE project first" }).toRealPath()
        require(real.startsWith(base)) { "Choose this IDE project's directory or a directory inside it" }
        val sap = runReadAction { HybrisProjectDetector.findHybrisHome(project)?.toRealPath() }
        require(sap == null || sap.startsWith(real)) { "SAP Commerce home is outside the selected project. Choose its containing IDE project." }
        val version = plan()
        val args = mutableListOf("--project-dir", real.toString())
        val configured = runReadAction { ReclazzSettings.getInstance(project).state.portFilePath }
        val port = when {
            configured.isNotBlank() -> real.resolve(configured).normalize()
            sap != null -> sap.resolve(".reclazz/agent.port")
            else -> null
        }
        if (port != null) {
            require(port.startsWith(real)) { "Java agent port file must be inside the selected project" }
            args.addAll(listOf("--port-file", port.toString()))
        }
        val entry = McpEntry(version.launcher.toString(), args)
        if (windows) entry.validateWindowsLauncher()
        val file = client.path(real, home.toRealPath(), global).toAbsolutePath().normalize()
        val scope = McpClientConfigs.Scope(if (global) home.toRealPath() else real)
        scope.validate(file)
        if (!global && client.globalFile != null) {
            val globalPath = home.toRealPath().resolve(client.globalFile).normalize()
            require(file != globalPath) { "This is the global config. Select Global (all projects) explicitly." }
        }
        return Prepared(entry, file, scope, version)
    }

    /** The config file as it would be after adding the Reclazz entry; other entries are kept. */
    fun preview(client: McpClient, prepared: Prepared): McpClientConfigs.Preview =
        McpClientConfigs.preview(prepared.path, prepared.scope) { text -> runReadAction {
            if (client == McpClient.CODEX) {
                val editor = tomlEditor() ?: error("TOML support is unavailable. Use Copy config; no file was written.")
                editor.merge(text, prepared.entry)
            } else {
                val editor = jsonEditor() ?: error("JSON support is unavailable. Use Copy command / config; no file was written.")
                editor.merge(text, client.serversKey!!, prepared.entry)
            }
        } }

    /** Stages the MCP launcher and saves the previewed config (backing up the old one). Returns the backup, if any. */
    fun connect(prepared: Prepared, config: McpClientConfigs.Preview): Path? {
        prepared.scope.validate(config.path)
        publish(prepared)
        McpLauncherStaging.rememberConfig(home, config.path)
        return config.confirm()
    }

    fun publish(prepared: Prepared) {
        require(stage(prepared.staged) == prepared.staged) { "Bundled MCP changed; preview again" }
    }

    /** True when the assistant looks installed on this machine (its config, its command, or its app). */
    fun detected(client: McpClient): Boolean {
        detect?.let { return it(client) }
        val config = client.path(Path.of(project.basePath ?: "."), home, false)
        if (Files.exists(config) || client.globalFile?.let { Files.exists(home.resolve(it)) } == true) return true
        val executable = when (client) { McpClient.CLAUDE -> "claude"; McpClient.CURSOR -> "cursor"; McpClient.VSCODE -> "code"; McpClient.CODEX -> "codex" }
        val paths = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).filter { it.isNotBlank() }
        if (paths.any { Files.isExecutable(Path.of(it).resolve(executable)) || Files.isExecutable(Path.of(it).resolve("$executable.exe")) }) return true
        return when (client) {
            McpClient.CLAUDE -> Files.isExecutable(home.resolve(".local/bin/claude"))
            McpClient.CURSOR -> Files.isDirectory(Path.of("/Applications/Cursor.app"))
            McpClient.VSCODE -> Files.isDirectory(Path.of("/Applications/Visual Studio Code.app"))
            McpClient.CODEX -> false
        }
    }

    /** True when the assistant's config for this project, or its global config, already has a Reclazz entry. */
    fun connected(client: McpClient): Boolean {
        val base = project.basePath ?: return false
        val files = listOfNotNull(client.path(Path.of(base), home, false), client.globalFile?.let { home.resolve(it) })
        val entry = Regex("(\"reclazz\"\\s*:|mcp_servers\\.reclazz\\b)")
        return files.any { file ->
            try { Files.isRegularFile(file) && entry.containsMatchIn(Files.readString(file)) } catch (_: java.io.IOException) { false }
        }
    }

    companion object {
        private fun isWindows() = System.getProperty("os.name").startsWith("Windows")
        private fun bundled(expected: McpLauncherStaging.Staged?): McpLauncherStaging.Staged {
            val plugin = requireNotNull(PluginPresence.enabledDescriptor(AgentJarLocator.RECLAZZ_PLUGIN_ID.idString)) { "Reclazz plugin metadata is unavailable" }
            val path = plugin.pluginPath
            val java = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java")
            return if (expected == null) McpLauncherStaging.plan(path.resolve("mcp/reclazz-mcp.jar"), path.resolve("agent/reclazz-agent.jar"), plugin.version, java)
                else McpLauncherStaging.stage(path.resolve("mcp/reclazz-mcp.jar"), path.resolve("agent/reclazz-agent.jar"), plugin.version, java, expected = expected)
        }
        internal fun planBundled() = bundled(null)
        internal fun stageBundled(expected: McpLauncherStaging.Staged) = bundled(expected)
    }
}
