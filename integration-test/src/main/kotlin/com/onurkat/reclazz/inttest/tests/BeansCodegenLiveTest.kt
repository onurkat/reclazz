package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Opt-in: two platform ant builds, generated-class reloads and actual native OCC mapping. */
class BeansCodegenLiveTest(c: TestConfig, a: AgentEventClient, h: HttpVerifier) : BatchLiveTest("Beans XML codegen to OCC",c,a,h) {
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
            poll("FULL") {
                it["code"]?.asText()=="v0" && it["label"]?.asText()=="label0" &&
                    if(added) it["batchValue"]?.asText()=="codegen-v2" else !it.has("batchValue")
            }
        }
        poll("BASIC") {
            it["code"]?.asText()=="v0" && !it.has("label") && !it.has("batchValue")
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
            if("batchValue" in Files.readString(generated(owner))) return "Generated source is not baseline: $owner"
        }
        mappingOwners=owners("reclazztestocc-web-spring.xml").size()
        verify(false) // warm both types and the native mapper before taking the write cursor
        return null
    }
    override fun run():TestResult = runGatedProfile(name,::preflight,::scenario)
    override fun scenario() {
        val started=System.nanoTime();deadline=started+TimeUnit.MINUTES.toNanos(6)
        val baselineGenerated=owners.associateWith {Files.readAllBytes(generated(it))}
        val ownerCount=mappingOwners
        val beansPath=Path.of(config.extPath,beans)
        val controllerPath=Path.of(config.extPath,controller)
        val mappingPath=Path.of(config.extPath,mapping)
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
        val edited=xml.replace("<property name=\"label\" type=\"String\"/>","<property name=\"label\" type=\"String\"/>\n        <property name=\"batchValue\" type=\"String\"/>").toByteArray()
        var applied=false
        withRestore({
            regenerate(edited,true);applied=true
            for(owner in owners) {
                check("setBatchValue" in Files.readString(generated(owner))) {"Generated accessor absent: $owner"}
                check("batchValue" in Files.readAllBytes(compiled(owner)).toString(Charsets.ISO_8859_1)) {"Compiled accessor absent: $owner"}
            }
            javaWrite(replaceOnce(original(controller).toString(Charsets.UTF_8),"data.setLabel(\"label0\");","data.setLabel(\"label0\");\n        data.setBatchValue(\"codegen-v2\");").toByteArray())
            xmlWrite(mappingPath,replaceOnce(original(mapping).toString(Charsets.UTF_8),"key=\"FULL\" value=\"code,label\"","key=\"FULL\" value=\"code,label,batchValue\"").toByteArray(),ownerCount,timeoutMs=minOf(left(),config.eventTimeoutMs))
            verify(true)
        }) {
            // Dependencies restore before removing generated accessors; always try every cleanup.
            deadline=minOf(started+TimeUnit.MINUTES.toNanos(12),System.nanoTime()+TimeUnit.MINUTES.toNanos(6))
            restoreAll({javaWrite(original(controller))},
                {xmlWrite(mappingPath,original(mapping),ownerCount,timeoutMs=minOf(left(),config.eventTimeoutMs))},
                {regenerate(original(beans),applied || owners.any {"batchValue" in Files.readAllBytes(compiled(it)).toString(Charsets.ISO_8859_1)})},
                {verify(false)},
                {for(relative in listOf(beans,controller,mapping)) check(Files.readAllBytes(Path.of(config.extPath,relative)).contentEquals(original(relative))) {"Codegen fixture not restored: $relative"}},
                {for(owner in owners) {
                    check(withoutGenerationDate(Files.readAllBytes(generated(owner)))==withoutGenerationDate(baselineGenerated.getValue(owner))) {"Generated source not baseline: $owner"}
                    check("batchValue" !in Files.readAllBytes(compiled(owner)).toString(Charsets.ISO_8859_1)) {"Generated class still has batchValue: $owner"}
                }})
        }
    }
}
