/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.settings

import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.json.JsonLexer
import com.intellij.json.JsonTokenSets
import com.intellij.json.JsonElementTypes
import com.intellij.psi.TokenType
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil

/** Loaded only by the optional JSON descriptor. Edits text ranges, never reformats the file. */
class JsonPsiMcpEditor(private val project: Project) : JsonMcpEditor {
    private fun parse(text: String): JsonObject {
        val file=JsonElementGenerator(project).createDummyFile(text)
        require(PsiTreeUtil.findChildOfType(file,PsiErrorElement::class.java)==null) { "JSON has syntax errors; no config was written" }
        val root=file.children.filterIsInstance<JsonObject>().singleOrNull()
            ?: throw IllegalArgumentException("MCP config must be a JSON object")
        for(obj in listOf(root)+PsiTreeUtil.findChildrenOfType(root,JsonObject::class.java))
            require(obj.propertyList.map { it.name }.distinct().size==obj.propertyList.size) { "Duplicate JSON key; no config was written" }
        return root
    }
    override fun merge(text: String, serversKey: String, entry: McpEntry): String {
        val source=text.ifBlank { "{}" }
        val root=parse(source)
        val servers=root.findProperty(serversKey)
        fun insert(obj: JsonObject, property: String): String {
            val end=obj.textRange.endOffset-1
            val last=obj.propertyList.lastOrNull()
            val tail=source.substring(last?.textRange?.endOffset ?: obj.textRange.startOffset+1,end)
            val lexer=JsonLexer();lexer.start(tail)
            var commas=0
            while(lexer.tokenType!=null) {
                val type=lexer.tokenType
                if(type==JsonElementTypes.COMMA)commas++
                else require(type==TokenType.WHITE_SPACE || JsonTokenSets.JSON_COMMENTARIES.contains(type)) { "Ambiguous JSON object tail" }
                lexer.advance()
            }
            require(commas<=1 && (last!=null || commas==0)) { "Ambiguous JSON object tail" }
            val comma=if(last!=null && commas==0) "," else ""
            return source.replaceRange(end,end,comma+"\n"+property+"\n")
        }
        val after=if(servers==null) insert(root,McpEntry.quote(serversKey)+":{\"reclazz\":"+entry.json()+"}") else {
            val obj=servers.value as? JsonObject ?: throw IllegalArgumentException("$serversKey must be an object")
            val old=obj.findProperty("reclazz")
            if(old==null) insert(obj,"\"reclazz\":"+entry.json())
            else source.replaceRange(old.textRange.startOffset,old.textRange.endOffset,"\"reclazz\":"+entry.json())
        }
        val verified=parse(after)
        require((verified.findProperty(serversKey)?.value as? JsonObject)?.findProperty("reclazz")?.value?.text==entry.json()) { "MCP entry validation failed" }
        return after
    }
}
