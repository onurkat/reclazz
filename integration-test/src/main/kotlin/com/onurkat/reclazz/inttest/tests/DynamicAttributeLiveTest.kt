package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Persisted, generated models call the installed native dynamic provider. */
class DynamicAttributeLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier,
    private val withheldSave:Path? = null):BatchLiveTest("Dynamic attribute handler",c,a,h) {
    override fun scenario() {
        val relative="src/com/onurkat/reclazztest/batch/DynamicLabelHandler.java"
        val path=Path.of(config.extPath,relative)
        val baseline=original(relative)
        check(Files.readAllBytes(path).contentEquals(baseline)) {"Dirty dynamic handler baseline"}
        val run="reclazz-dyn-${UUID.randomUUID()}"
        val params=mapOf("run" to run)
        var heldIdentity:Int?=null
        var pk:String?=null
        fun owned(node:JsonNode):JsonNode {
            check(node["run"]?.asText()==run) {"Wrong dynamic run: $node"};return node
        }
        fun count(expected:Int) {
            val proof=owned(json(get("$endpoint/dynamic/count",params)))
            for(field in listOf("ownedRemaining","globalRemaining"))
                check(proof[field]?.isIntegralNumber==true && proof[field].asInt()==expected) {"Dynamic $field mismatch: $proof"}
            check(proof["nonces"]?.isArray==true && proof["nonces"].map {it.asText()}==
                (if(expected==0) emptyList<String>() else listOf("$run-row"))) {"Dynamic row ownership mismatch: $proof"}
            if(expected==0) for(field in listOf("heldRemaining","mapRemaining"))
                check(proof[field]?.isIntegralNumber==true && proof[field].asInt()==0) {"Dynamic state cleanup mismatch: $proof"}
        }
        fun read(version:String) {
            val proof=pollJson("$endpoint/dynamic/read",params) {node ->
                owned(node);node["held"]?.asText()=="dyn-$version:$run-row" && node["fresh"]?.asText()=="dyn-$version:$run-row"
            }
            check(proof["nativeDynamic"]?.isBoolean==true && proof["nativeDynamic"].asBoolean() &&
                proof["handler"]?.asText()=="reclazzDynamicLabelHandler" &&
                proof["modelClass"]?.asText()=="de.hybris.platform.core.model.ReclazzSchemaProbeModel") {"Native dynamic route missing: $proof"}
            check(proof["differentInstance"]?.isBoolean==true && proof["differentInstance"].asBoolean() &&
                proof["heldIdentity"]?.isIntegralNumber==true && proof["freshIdentity"]?.isIntegralNumber==true &&
                proof["heldIdentity"].asInt()!=proof["freshIdentity"].asInt()) {"Fresh model aliases held model: $proof"}
            val observedIdentity=proof["heldIdentity"].asInt()
            check(heldIdentity==null || heldIdentity==observedIdentity) {"Retained model identity changed: $proof"}
            heldIdentity=observedIdentity
            val observedPk=proof["pk"]?.asText().orEmpty()
            check(observedPk.isNotEmpty() && proof["freshPk"]?.asText()==observedPk && (pk==null || pk==observedPk)) {"Persisted model PK changed: $proof"}
            pk=observedPk
            println("    Dynamic generated getter proof: $proof")
            count(1)
        }
        fun set(step:Int,version:String) {
            val token="$run-t$step"
            // Exactly one side effect. Version convergence uses only pure reads.
            val proof=owned(json(post("$endpoint/dynamic/set",params+mapOf("token" to token))))
            check(proof["token"]?.asText()==token && proof["pk"]?.asText()==pk &&
                proof["value"]?.asText()=="set-$version:$token") {"Native setter callback token/version mismatch: $proof"}
            println("    Dynamic nonpersistent setter callback proof: $proof")
            count(1)
        }
        fun apply(file:Path,bytes:ByteArray) {
            val cursor=agentClient.cursor()
            if(withheldSave!=null && !bytes.contentEquals(baseline)) {
                check(!withheldSave.toAbsolutePath().normalize().startsWith(Path.of(config.extPath).toAbsolutePath().normalize())) {"RED copy must be outside watched fixtures"}
                Files.write(withheldSave,bytes)
                awaitSources(agentClient,listOf(file),cursor,config.eventTimeoutMs)
            } else if(writeChanged(file,bytes)) awaitSources(agentClient,listOf(file),cursor,config.eventTimeoutMs)
        }
        count(0)
        var seeded=false
        withRestore({
            owned(json(post("$endpoint/dynamic/seed",params)));seeded=true
            read("v1");set(1,"v1")
            val getV2=replaceOnce(baseline.toString(Charsets.UTF_8),"\"dyn-v1:\"","\"dyn-v2:\"").toByteArray()
            val bothV2=replaceOnce(getV2.toString(Charsets.UTF_8),"\"set-v1:\"","\"set-v2:\"").toByteArray()
            fixtureEdit(relative,getV2,::apply,{read("v1");set(3,"v1")}) {
                read("v2")
                apply(path,bothV2)
                read("v2");set(2,"v2")
            }
        }) {
            restoreAll({
                val proof=owned(json(post("$endpoint/dynamic/cleanup",params)))
                check(proof["removed"]?.isIntegralNumber==true && (!seeded || proof["removed"].asInt()==1)) {"Dynamic cleanup removed wrong count: $proof"}
                count(0);println("    Dynamic cleanup proof: $proof")
            },{check(Files.readAllBytes(path).contentEquals(baseline)) {"Dynamic source bytes not restored"}})
        }
    }
}
