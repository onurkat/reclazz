/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Opt-in: two platform ant builds, generated-class reloads and actual native OCC mapping. */
class BeansCodegenHeldSourceLiveTest(c: TestConfig, a: AgentEventClient, h: HttpVerifier) : BatchLiveTest("Held Java retry after beans codegen",c,a,h) {
    private val beans = "resources/reclazztest-beans.xml"
    private val controller = "src/com/onurkat/reclazztest/occ/ReclazzProbeOccController.java"
    private val mapping = "resources/occ/v2/reclazztestocc/web/spring/reclazztestocc-web-spring.xml"
    private val owners = listOf("com.onurkat.reclazztest.data.ReclazzProbeData", "com.onurkat.reclazztest.dto.ReclazzProbeWsDTO")
    private var home: Path? = null
    private var mappingOwners = 0
    private var deadline = 0L
    private fun left(): Long = TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()).also { check(it>0) {"Codegen profile deadline"} }
    private fun generated(owner:String) = home!!.resolve("bin/platform/bootstrap/gensrc/"+owner.replace('.','/')+".java")
    private fun compiled(owner:String):Path = listOf("modelclasses","classes").map {
        home!!.resolve("bin/platform/bootstrap/$it/"+owner.replace('.','/')+".class")
    }.firstOrNull(Files::isRegularFile) ?: error("No generated class for $owner")
    private fun withoutGenerationDate(bytes:ByteArray) = bytes.toString(Charsets.UTF_8).replace(Regex("(?m)^ \\* --- Generated at[^\\n]*\\n"), "")
    private fun verify(added:Boolean) {
        fun poll(fields:String,accepts:(com.fasterxml.jackson.databind.JsonNode)->Boolean) = json(httpVerifier.poll(
            minOf(left(),config.eventTimeoutMs),"Codegen OCC $fields",{httpVerifier.get("$occ/reclazzprobe",mapOf("fields" to fields),it)}) {
                it.statusCode==200 && runCatching {accepts(json(it))}.getOrDefault(false)
            })
        repeat(3) {
            poll(if(added) "code,label,heldRetryValue" else "FULL") {
                it["code"]?.asText()=="v0" && it["label"]?.asText()=="label0" &&
                    if(added) it["heldRetryValue"]?.asText()=="held-retry-v1" else !it.has("heldRetryValue")
            }
        }
        poll("BASIC") {
            it["code"]?.asText()=="v0" && !it.has("label") && !it.has("heldRetryValue")
        }
    }
    private fun preflight():String? {
        deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(config.eventTimeoutMs)
        val gates=json(get("$endpoint/gates"))
        profileGateReason(gates, linkedMapOf("agentArgsFound" to true, "agentOptionsMatchConfig" to true,
            "resetStatics" to false,"autoSolrReindex" to false,"impexAllowRemove" to false,
            "autoCompile" to true,"autoUpdateRunningSystem" to false,"codegenExtensionLoaded" to true,"codegenGeneratedRoots" to true))?.let {return it}
        home=System.getenv("RECLAZZ_TEST_HYBRIS_HOME")?.let {Path.of(it).toRealPath()}
            ?: return "RECLAZZ_TEST_HYBRIS_HOME missing for serialized codegen profile"
        for(relative in listOf("bin/platform/setantenv.sh","bin/platform/apache-ant/bin/ant"))
            if(!Files.isRegularFile(home!!.resolve(relative))) return "Codegen prerequisite missing: $relative"
        if(!Files.isSameFile(home!!.resolve("bin/custom/reclazztest"),Path.of(config.extPath))) return "Fixture does not belong to this codegen install"
        for(relative in listOf(beans,controller,mapping))
            if(!Files.readAllBytes(Path.of(config.extPath,relative)).contentEquals(original(relative))) return "Dirty codegen fixture: $relative"
        for(owner in owners) {
            if(!Files.isRegularFile(generated(owner))) return "Generated source missing for $owner"
            compiled(owner)
            if("heldRetryValue" in Files.readString(generated(owner))) return "Generated source is not baseline: $owner"
        }
        mappingOwners=owners("reclazztestocc-web-spring.xml").size()
        verify(false) // warm both types and the native mapper before taking the write cursor
        return null
    }
    override fun run():TestResult = runGatedProfile(name,::preflight,::scenario)
    private class NotProven(reason:String) : IllegalStateException("NOT-PROVEN: $reason")
    override fun scenario() {
        for(attempt in 1..2) {
            try { combinedSave(); return } catch(e: NotProven) {
                if(attempt==2) throw e
                println("    ${e.message}; attempt $attempt restored before retrying setup once")
            }
        }
    }
    private fun combinedSave() {
        val started=System.nanoTime();deadline=started+TimeUnit.MINUTES.toNanos(6)
        val baselineGenerated=owners.associateWith {Files.readAllBytes(generated(it))}
        val beansPath=Path.of(config.extPath,beans)
        val controllerPath=Path.of(config.extPath,controller)
        fun javaWrite(bytes:ByteArray) {val mark=agentClient.cursor();if(writeChanged(controllerPath,bytes)) awaitSources(agentClient,listOf(controllerPath),mark,left())}
        fun regenerate(bytes:ByteArray, requireApply:Boolean) {
            val mark=agentClient.cursor()
            if(!writeChanged(beansPath,bytes)) return
            while(true) {
                left()
                val receipt=codegenReceipt(agentClient.getEvents(),mark,if(requireApply) owners.toSet() else emptySet())
                if(receipt!=null) {
                    println("    Codegen terminal event=${receipt.terminal.sequence}; write cursor=$mark")
                    receipt.applies.forEach {println("    Generated apply ${it.message.substringBefore(" (")} event=${it.sequence}; ant event=${receipt.terminal.sequence}")}
                    return
                }
                Thread.sleep(50)
            }
        }
        val xml=original(beans).toString(Charsets.UTF_8)
        check(xml.windowed("<property name=\"label\" type=\"String\"/>".length).count {it=="<property name=\"label\" type=\"String\"/>"}==2)
        val edited=xml.replace("<property name=\"label\" type=\"String\"/>","<property name=\"label\" type=\"String\"/>\n        <property name=\"heldRetryValue\" type=\"String\"/>").toByteArray()
        var applied=false
        withRestore({
            val mark=agentClient.cursor()
            // One save step; neither write waits for Java compilation or ant.
            check(writeChanged(beansPath,edited))
            check(writeChanged(controllerPath,replaceOnce(original(controller).toString(Charsets.UTF_8),
                "data.setLabel(\"label0\");","data.setLabel(\"label0\");\n        data.setHeldRetryValue(\"held-retry-v1\");").toByteArray()))
            val held=agentClient.waitForEvent("INFO",minOf(left(),30000),"Holding 1 source files",mark)
                ?: throw NotProven("ant won before the Java source was held")
            val failure=agentClient.getEvents().firstOrNull {it.sequence>mark && it.sequence<held.sequence && it.level=="ERROR" && it.message=="Compilation failed:"}
            check(failure!=null) {"Held source has no preceding compile failure"}
            val terminal=agentClient.waitForEvent("OK",left(),"Codegen regen+compile done for reclazztest, DTOs ready",held.sequence)
                ?: error("Held source never reached successful codegen admission")
            applied=true
            if(agentClient.getEvents().any {it.sequence>held.sequence && it.sequence<terminal.sequence &&
                it.level=="COMPILE" && it.message.startsWith("Compiled ${controllerPath.fileName}")})
                throw NotProven("another Java batch compiled the held source before final codegen admission")
            println("    HELD-PATH failure=${failure.sequence} holding=${held.sequence} codegen=${terminal.sequence}; Java writes=1")
            val retry=agentClient.waitForEvent("INFO",minOf(left(),30000),"Codegen finished; compiling ",terminal.sequence)
                ?: error("Codegen completed but held source was not automatically retried; no further Java save")
            check(Regex("Codegen finished; compiling [1-9][0-9]* held source files again").matches(retry.message)) {"Invalid retry batch: ${retry.message}"}
            awaitSources(agentClient,listOf(controllerPath),retry.sequence,left())
            val seen=agentClient.getEvents().filter {it.sequence>terminal.sequence}
            check(seen.count {it.message.startsWith("Codegen finished; compiling ")}==1) {"More than one retry for one successful codegen"}
            println("    HELD-PATH retry=${retry.sequence} (${retry.message}); controller applied without another Java write")
            for(owner in owners) {
                check("setHeldRetryValue" in Files.readString(generated(owner))) {"Generated accessor absent: $owner"}
                check("heldRetryValue" in Files.readAllBytes(compiled(owner)).toString(Charsets.ISO_8859_1)) {"Compiled accessor absent: $owner"}
            }
            verify(true)
        }) {
            // Dependencies restore before removing generated accessors; always try every cleanup.
            deadline=minOf(started+TimeUnit.MINUTES.toNanos(12),System.nanoTime()+TimeUnit.MINUTES.toNanos(6))
            restoreAll({javaWrite(original(controller))},
                {regenerate(original(beans),applied || owners.any {"heldRetryValue" in Files.readAllBytes(compiled(it)).toString(Charsets.ISO_8859_1)})},
                {verify(false)},
                {for(relative in listOf(beans,controller,mapping)) check(Files.readAllBytes(Path.of(config.extPath,relative)).contentEquals(original(relative))) {"Codegen fixture not restored: $relative"}},
                {for(owner in owners) {
                    check(withoutGenerationDate(Files.readAllBytes(generated(owner)))==withoutGenerationDate(baselineGenerated.getValue(owner))) {"Generated source not baseline: $owner"}
                    check("heldRetryValue" !in Files.readAllBytes(compiled(owner)).toString(Charsets.ISO_8859_1)) {"Generated class still has heldRetryValue: $owner"}
                }})
        }
    }
}
