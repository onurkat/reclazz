/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.onurkat.reclazz.plugin.settings.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals as eq
import kotlin.test.assertTrue as yes
import kotlin.test.assertFailsWith

class McpClientConfigsTest : BasePlatformTestCase() {
    private val entry=McpEntry("/home/test IDE/reclazz-mcp",listOf("--project-dir","/projects/space \\\" quote"))
    private fun json(text: String,key: String="mcpServers")=JsonPsiMcpEditor(project).merge(text,key,entry)
    private fun toml(text: String)=TomlPsiMcpEditor(project).merge(text,entry)
    fun testEachJsonClientKeepsOtherPropertiesAndComments() {
        for(client in listOf(McpClient.CLAUDE,McpClient.CURSOR,McpClient.VSCODE)) {
            val key=client.serversKey!!
            val before="""{
                "unknown": [1, 2],
                "$key": {
                    // retained server comment
                    "other": { "command" : "unchanged" },
                    "reclazz": {"command":"old"}
                },
                "tail": {"untouched":true}
            }""".trimIndent()
            val old="\"reclazz\": {\"command\":\"old\"}"
            val actual=json(before,key)
            eq(before.replace(old,"\"reclazz\":"+entry.json()),actual)
            val twice=JsonPsiMcpEditor(project).merge(actual,key,entry.copy(command="/new"))
            eq(actual.replace("\"reclazz\":"+entry.json(),"\"reclazz\":"+entry.copy(command="/new").json()),twice)
        }
    }
    fun testJsonInsertionWithCommentsAndTrailingComma() {
        for(before in listOf("{}","{\"unknown\":true}","{\"mcpServers\":{ /* keep */ }}","{\"mcpServers\":{\"other\":{}, /* keep */}}")) {
            val out=try { json(before) } catch(e: Exception) { throw AssertionError("Synthetic input: $before",e) };yes(out.contains(entry.json()));yes(!before.contains("keep")||out.contains("/* keep */"))
            eq(out,json(out))
        }
    }
    fun testJsonRefusesSyntaxDuplicateAndNonObject() {
        for(bad in listOf("{", "[]", "{\"mcpServers\":false}","{\"mcpServers\":{},\"mcpServers\":{}}","{\"mcpServers\":{\"x\":{},\"x\":{}}}"))
            assertFailsWith<IllegalArgumentException>(bad) { json(bad) }
    }
    fun testTomlRemovesWholeOldEnvAndPreservesOtherBytes() {
        val prefix="# before\n[other]\nvalue = [1, 2] # keep\n\n"
        val suffix="\n# other comment\n[mcp_servers.other]\ncommand   = 'unchanged'\n"
        val before=prefix+"[mcp_servers.reclazz]\ncommand = 'old'\n\n[mcp_servers.reclazz.env]\nRECLAZZ_JAVA = '/deleted-java'\n"+suffix
        val out=toml(before)
        yes(out.startsWith(prefix));yes(out.endsWith(suffix));yes(!out.contains("RECLAZZ_JAVA"));yes(!out.contains("deleted-java"));yes(out.contains(entry.toml()))
        val again=TomlPsiMcpEditor(project).merge(out,entry.copy(command="/new"))
        yes(again.startsWith(prefix));yes(again.endsWith(suffix));yes(!again.contains("deleted-java"));yes(again.contains("command = \"/new\""))
    }
    fun testTomlPseudoheaderInsideMultilineStringIsUntouched() {
        val before="description = \"\"\"\n[mcp_servers.reclazz]\nnot a table\n\"\"\"\n# keep\n"
        val out=toml(before);yes(out.startsWith(before));yes(out.endsWith(entry.toml()))
    }
    fun testTomlRefusesAmbiguousAndInvalidForms() {
        val bad=listOf(
            "[mcp_servers.\"reclazz\"]\ncommand='old'\n",
            "mcp_servers.reclazz.command = 'old'\n",
            "[mcp_servers]\nreclazz = { command = 'old' }\n",
            "[mcp_servers.reclazz]\ncommand='one'\n[mcp_servers.reclazz]\ncommand='two'\n",
            "[mcp_servers.reclazz.env]\nA='orphan'\n",
            "[[mcp_servers.reclazz]]\ncommand='old'\n",
            "[broken\n",
            "x=1\nx=2\n")
        for(text in bad)assertFailsWith<IllegalArgumentException>(text) { toml(text) }
    }
    fun testTomlArrayAndEscapedValuesRoundTrip() {
        val before="[other]\nargs = ['a', 'b']\n# keep\n"
        val out=TomlPsiMcpEditor(project).merge(before,entry.copy(env=mapOf("A" to "line\nslash\\quote\"")))
        yes(out.startsWith(before));yes(out.contains("[mcp_servers.reclazz.env]"))
        eq(out,TomlPsiMcpEditor(project).merge(out,entry.copy(env=mapOf("A" to "line\nslash\\quote\""))).trimEnd()+"\n")
    }
    fun testConfirmedWritesBackUpAndRejectChangedPreview() {
        val dir=Files.createTempDirectory("mcp-configs-")
        try {
            for(client in McpClient.values()) {
                val file=client.path(dir.resolve(client.name),dir.resolve("home"),false)
                Files.createDirectories(file.parent)
                val original=if(client==McpClient.CODEX)"# retained\n[other]\nx=1\n" else "{\"other\":42}"
                Files.writeString(file,original)
                val preview=McpClientConfigs.preview(file) { if(client==McpClient.CODEX)toml(it) else json(it,client.serversKey!!) }
                eq(original,Files.readString(file))
                val backup=preview.confirm()!!;eq(original,Files.readString(backup));eq(preview.after,Files.readString(file))
                val stale=McpClientConfigs.preview(file) { it };Files.writeString(file,"external edit")
                assertFailsWith<IllegalStateException> { stale.confirm() };eq("external edit",Files.readString(file))
                val count=Files.list(file.parent).use { it.count() }
                assertFailsWith<IllegalArgumentException> { McpClientConfigs.preview(file) { if(client==McpClient.CODEX)toml("[bad") else json("{") } }
                eq(count,Files.list(file.parent).use { it.count() })
            }
        } finally { dir.toFile().deleteRecursively() }
    }
    fun testProjectCodexNeverOffersGlobalCliAndFilesAreScoped() {
        eq(entry.toml(),McpClientConfigs.copySetup(McpClient.CODEX,false,entry,false))
        yes(McpClientConfigs.copySetup(McpClient.CODEX,true,entry,false).startsWith("codex mcp add reclazz -- "))
        eq(Path.of("/project/.codex/config.toml"),McpClient.CODEX.path(Path.of("/project"),Path.of("/home"),false))
        eq(Path.of("/home/.codex/config.toml"),McpClient.CODEX.path(Path.of("/project"),Path.of("/home"),true))
    }
    fun testOptionalServicesAreRegistered() {
        yes(project.getService(JsonMcpEditor::class.java)!=null)
        yes(project.getService(TomlMcpEditor::class.java)!=null)
    }
}
