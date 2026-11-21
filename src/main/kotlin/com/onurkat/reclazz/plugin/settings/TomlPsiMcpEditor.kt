/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.settings

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import org.toml.lang.psi.*

/** PSI identifies real headers/keys, including strings that merely resemble headers. */
class TomlPsiMcpEditor(private val project: Project) : TomlMcpEditor {
    private val base=listOf("mcp_servers","reclazz")
    private fun TomlKey.path(): List<String> = segments.map { requireNotNull(it.name) }
    private fun List<String>.inEntry() = size>=2 && take(2)==base
    private fun parse(text: String): TomlFile {
        val file=PsiFileFactory.getInstance(project).createFileFromText("config.toml",TomlFileType,text) as TomlFile
        require(PsiTreeUtil.findChildOfType(file,PsiErrorElement::class.java)==null) { "TOML has syntax errors; no config was written" }
        val headers=mutableSetOf<List<String>>()
        val allKeys=mutableSetOf<List<String>>()
        fun entries(prefix: List<String>, items: List<TomlKeyValue>, global: Boolean) {
            val local=mutableSetOf<List<String>>()
            for(item in items) {
                val key=item.key.path();val full=prefix+key
                require(local.add(key)) { "Duplicate TOML key" }
                if(global) {
                    require(allKeys.none { old -> old==full || old.size<full.size && full.take(old.size)==old || full.size<old.size && old.take(full.size)==full }) { "Conflicting TOML keys" }
                    allKeys.add(full)
                }
                if(full.inEntry() && !prefix.inEntry()) throw IllegalArgumentException("Inline/dotted reclazz entry requires manual setup")
            }
        }
        entries(emptyList(),file.children.filterIsInstance<TomlKeyValue>(),true)
        for(owner in file.children.filterIsInstance<TomlHeaderOwner>()) {
            val key=requireNotNull(owner.header.key).path()
            if(owner is TomlTable) {
                require(headers.add(key)) { "Duplicate TOML table" }
                require(allKeys.none { old -> old==key || old.size<key.size && key.take(old.size)==old }) { "Conflicting TOML table" }
                entries(key,owner.entries,true)
            } else if(owner is TomlArrayTable) {
                require(!key.inEntry()) { "Array-table reclazz entry requires manual setup" }
                entries(key,owner.entries,false)
            }
        }
        return file
    }
    private fun ranges(file: TomlFile): List<Pair<Int,Int>> {
        val tables=file.children.filterIsInstance<TomlTable>().filter { requireNotNull(it.header.key).path().inEntry() }
        if(tables.isEmpty())return emptyList()
        require(tables.count { it.header.key!!.path()==base }==1) { "A standard reclazz parent table is required" }
        return tables.flatMap { table ->
            val key=table.header.key!!.path()
            require(table.header.text=="["+key.joinToString(".")+"]" && key.all { it.matches(Regex("[A-Za-z0-9_-]+")) }) { "Quoted or nonstandard reclazz header requires manual setup" }
            require(table.entries.all { it.key.segments.size==1 }) { "Dotted reclazz keys require manual setup" }
            (listOf(table.header)+table.entries).map { it.textRange.startOffset to it.textRange.endOffset }
        }.sortedBy { it.first }
    }
    override fun merge(text: String, entry: McpEntry): String {
        val file=parse(text);val ranges=ranges(file)
        val newline=if(text.contains("\r\n"))"\r\n" else "\n"
        val block=entry.toml().replace("\n",newline)
        val after=if(ranges.isEmpty()) text+(if(text.isNotEmpty() && !text.endsWith("\n"))newline else "")+block else {
            var out=text
            for((index,range) in ranges.withIndex().toList().asReversed())
                out=out.replaceRange(range.first,range.second,if(index==0)block else "")
            out
        }
        val checked=parse(after);ranges(checked)
        val actual=checked.children.filterIsInstance<TomlTable>().filter { it.header.key!!.path().inEntry() }
            .associate { it.header.key!!.path() to it.entries.associate { kv -> kv.key.path() to kv.value?.text } }
        val expected=parse(block).children.filterIsInstance<TomlTable>()
            .associate { it.header.key!!.path() to it.entries.associate { kv -> kv.key.path() to kv.value?.text } }
        require(actual==expected) { "MCP TOML subtree validation failed" }
        return after
    }
}
