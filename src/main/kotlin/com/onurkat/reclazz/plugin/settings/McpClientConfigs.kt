/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.settings

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.util.UUID
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import com.onurkat.reclazz.plugin.agent.McpLauncherStaging

/** Only these interfaces are referenced by the always-loaded Settings code. */
interface JsonMcpEditor { fun merge(text: String, serversKey: String, entry: McpEntry): String }
interface TomlMcpEditor { fun merge(text: String, entry: McpEntry): String }

data class McpEntry(val command: String, val args: List<String>, val env: Map<String,String> = emptyMap()) {
    init {
        require((listOf(command)+args+env.values).none { it.contains("${'$'}{") }) {
            "Paths containing client variable syntax cannot be registered automatically"
        }
    }
    fun validateWindowsLauncher() = McpLauncherStaging.requireWindowsSafe(listOf(command)+args+env.values)
    fun json(): String = "{\"type\":\"stdio\",\"command\":"+quote(command)+",\"args\":["+
        args.joinToString(",") { quote(it) }+"]"+
        (if(env.isEmpty()) "" else ",\"env\":{"+env.entries.joinToString(",") { quote(it.key)+":"+quote(it.value) }+"}")+"}"
    fun toml(): String = "[mcp_servers.reclazz]\ncommand = ${quote(command)}\nargs = [${args.joinToString(", ") { quote(it) }}]\n"+
        (if(env.isEmpty()) "" else "\n[mcp_servers.reclazz.env]\n"+env.entries.joinToString("") { "${quote(it.key)} = ${quote(it.value)}\n" })
    companion object {
        fun quote(value: String): String = buildString {
            append('"');for(c in value) when(c) {
                '"' -> append("\\\"");'\\' -> append("\\\\");'\b' -> append("\\b");'\t' -> append("\\t")
                '\n' -> append("\\n");'\u000c' -> append("\\f");'\r' -> append("\\r")
                else -> if(c.code<32)append("\\u%04x".format(c.code)) else append(c)
            };append('"')
        }
    }
}

enum class McpClient(val display: String, val projectFile: String, val globalFile: String?, val serversKey: String?) {
    CLAUDE("Claude Code", ".mcp.json", null,"mcpServers"),
    CURSOR("Cursor", ".cursor/mcp.json", ".cursor/mcp.json","mcpServers"),
    VSCODE("VS Code (editor-managed)", ".vscode/mcp.json", null,"servers"),
    CODEX("Codex", ".codex/config.toml", ".codex/config.toml",null);
    fun path(project: Path, home: Path, global: Boolean): Path =
        if(global) home.resolve(requireNotNull(globalFile) { "This client offers project setup only" }) else project.resolve(projectFile)
}

object McpClientConfigs {
    /** A scope is captured at preview and rechecked before every write, including parent components. */
    data class Scope(val root: Path) {
        fun validate(path: Path) {
            val base=root.toAbsolutePath().normalize()
            val target=path.toAbsolutePath().normalize()
            require(target.startsWith(base) && target!=base) { "Config is outside the selected scope" }
            require(base.toRealPath()==base && !Files.isSymbolicLink(base)) { "Config scope changed; preview again" }
            var current=base
            for(part in base.relativize(target)) {
                current=current.resolve(part)
                require(!Files.isSymbolicLink(current)) { "Config or its parent is a symlink; use the client's setup instead" }
            }
        }
    }
    data class Preview(val path: Path, val before: ByteArray?, val after: String, val scope: Scope? = null) {
        fun confirm(): Path? {
            scope?.validate(path)
            require(!Files.isSymbolicLink(path)) { "Config is a symlink; use the client's setup instead" }
            fun unchanged(): Boolean = if(before==null) !Files.exists(path) else Files.isRegularFile(path) && Files.readAllBytes(path).contentEquals(before)
            check(unchanged()) { "Config changed since preview. Preview it again before writing." }
            Files.createDirectories(path.toAbsolutePath().parent)
            scope?.validate(path)
            val timestamp=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now())
            val backup=if(before!=null) path.resolveSibling(path.fileName.toString()+".reclazz-backup-"+timestamp+"-"+UUID.randomUUID()) else null
            val temp=Files.createTempFile(path.toAbsolutePath().parent,".reclazz-mcp-",".tmp")
            try {
                Files.writeString(temp,after,UTF_8)
                if(before!=null && Files.getFileStore(path).supportsFileAttributeView("posix"))
                    Files.setPosixFilePermissions(temp,Files.getPosixFilePermissions(path))
                check(unchanged()) { "Config changed since preview. Preview it again before writing." }
                scope?.validate(path)
                if(backup!=null) Files.copy(path,backup,COPY_ATTRIBUTES)
                scope?.validate(path)
                Files.move(temp,path,ATOMIC_MOVE,REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
            return backup
        }
    }
    fun preview(path: Path, scope: Scope? = null, edit: (String)->String): Preview {
        scope?.validate(path)
        require(!Files.isSymbolicLink(path)) { "Config is a symlink; use the client's setup instead" }
        val before=if(Files.exists(path))Files.readAllBytes(path) else null
        val text=before?.let { UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(it)).toString() }.orEmpty()
        return Preview(path,before,edit(text),scope)
    }
    fun copySetup(client: McpClient, global: Boolean, entry: McpEntry, windows: Boolean): String {
        if(windows) {
            entry.validateWindowsLauncher()
            require((listOf(entry.command)+entry.args+entry.env.values).none { it.any { c -> c=='$' || c=='`' } }) {
                "Windows Copy cannot safely quote dollar signs or backticks in both PowerShell and cmd; choose a safe directory"
            }
        }
        if(client==McpClient.CODEX && !global)return entry.toml()
        fun shell(s: String): String = if(windows) {
            require(s.none { it=='%' || it=='!' || it=='\r' || it=='\n' }) { "Choose a Windows path without shell expansion characters" }
            "\""+s.replace("\"","\\\"")+"\""
        } else "'"+s.replace("'","'\\''")+"'"
        val prefix=when(client) {
            McpClient.CLAUDE -> "claude mcp add --scope project --transport stdio reclazz -- "
            McpClient.CODEX -> { require(global);"codex mcp add reclazz -- " }
            else -> return "{\"${client.serversKey}\":{\"reclazz\":"+entry.json()+"}}"
        }
        return prefix+(listOf(entry.command)+entry.args).joinToString(" ") { shell(it) }
    }
    internal fun hash(bytes: ByteArray): String = McpLauncherStaging.digest(bytes)
}
