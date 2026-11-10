package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class PrepareInterceptorLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier,
    private val withheldSave:Path? = System.getenv("RECLAZZ_PREPARE_WITHHOLD_EDIT")?.let {Path.of(it)}):BatchLiveTest("Native PrepareInterceptor save",c,a,h) {
    override fun scenario() {
        val relative="src/com/onurkat/reclazztest/batch/ProbePrepareInterceptor.java"
        val path=Path.of(config.extPath,relative);val baseline=original(relative)
        check(Files.readAllBytes(path).contentEquals(baseline)) {"Dirty Prepare source baseline"}
        val id=UUID.randomUUID().toString();val run="reclazz-prep-$id";val params=mapOf("run" to run)
        var registry:Int?=null;var targetPk:String?=null;var controlPk:String?=null
        fun owned(node:JsonNode):JsonNode {check(node["run"]?.asText()==run) {"Wrong Prepare run: $node"};return node}
        fun ready(version:String) {
            val proof=pollJson("$endpoint/prepare/ready",params) {owned(it);it["version"]?.asText()==version}
            check(proof["sameRegistry"]?.isBoolean==true && proof["sameRegistry"].asBoolean() &&
                proof["registryClass"]?.asText()=="de.hybris.platform.servicelayer.interceptor.impl.DefaultInterceptorRegistry" &&
                proof["mappingMatches"]?.isBoolean==true && proof["mappingMatches"].asBoolean() &&
                proof["targetCount"]?.isIntegralNumber==true && proof["targetCount"].asInt()==1 &&
                proof["registryIdentity"]?.isIntegralNumber==true) {"Native Prepare mapping/registry mismatch: $proof"}
            val identity=proof["registryIdentity"].asInt()
            check(registry==null || registry==identity) {"Prepare registry was replaced: $proof"};registry=identity
        }
        fun count(expected:Int) {
            val proof=owned(json(get("$endpoint/prepare/count",params)))
            for(field in listOf("ownedRemaining","globalRemaining"))
                check(proof[field]?.isIntegralNumber==true && proof[field].asInt()==expected) {"Prepare $field mismatch: $proof"}
            if(expected==0) check(proof["recorderRemaining"]?.isIntegralNumber==true && proof["recorderRemaining"].asInt()==0 &&
                proof["steps"]?.isObject==true && proof["steps"].size()==0) {"Prepare recorder not empty: $proof"}
        }
        fun read(step:Int,version:String) {
            val proof=owned(json(get("$endpoint/prepare/read",params)))
            val rows=proof["rows"];check(rows?.isArray==true && rows.size()==2) {"Prepare persisted rows missing: $proof"}
            val control=rows.singleOrNull {it["nonce"]?.asText()=="$run-control"} ?: error("Prepare control changed: $proof")
            val target=rows.singleOrNull {it!==control} ?: error("Prepare target missing: $proof")
            check(target["nonce"]?.asText()==if(step==0) "$run-seed" else "$run-s$step-prepared-$version") {"Prepare persisted value stale: $proof"}
            val tp=target["pk"]?.asText().orEmpty();val cp=control["pk"]?.asText().orEmpty()
            check(tp.isNotEmpty() && cp.isNotEmpty() && tp!=cp && (targetPk==null || targetPk==tp) && (controlPk==null || controlPk==cp)) {"Prepare PK changed: $proof"}
            targetPk=tp;controlPk=cp
            check(proof["steps"]?.isObject==true && proof["steps"].size()==step) {"Prepare unexpected step: $proof"}
            for(i in 1..step) {
                val result=proof["steps"][i.toString()];val expected=if(i==2) "v2" else "v1"
                check(result?.get("calls")?.isIntegralNumber==true && result["calls"].asInt()==1 && result["version"]?.asText()==expected) {"Prepare callback count/version mismatch: $proof"}
            }
            count(2);println("    Prepare native persisted proof: $proof")
        }
        fun save(step:Int,version:String) {
            ready(version)
            val proof=owned(json(post("$endpoint/prepare/save",params+mapOf("step" to step.toString(),"pk" to targetPk!!))))
            check(proof["step"]?.isIntegralNumber==true && proof["step"].asInt()==step && proof["pk"]?.asText()==targetPk) {"Wrong Prepare save acknowledgement: $proof"}
            read(step,version)
        }
        count(0)
        val guards=owned(json(post("$endpoint/prepare/guard",params)))
        check(guards["nonces"]?.isArray==true && guards["nonces"].map {it.asText()}==
            listOf("reclazz-dao-$id-a-1","reclazz-dyn-$id-row","reclazz-schema-$id") &&
            guards["recorderRemaining"]?.isIntegralNumber==true && guards["recorderRemaining"].asInt()==0) {"Prepare guard changed other fixtures: $guards"}
        count(0);println("    Prepare native DAO/dynamic/items nonce guard: $guards")
        var seeded=false
        withRestore({
            owned(json(post("$endpoint/prepare/seed",params)));seeded=true
            ready("v1");read(0,"v1");save(1,"v1")
            val changed=replaceOnce(baseline.toString(Charsets.UTF_8),"return \"v1\";","return \"v2\";").toByteArray()
            fixtureEdit(relative,changed,{file,bytes ->
                val cursor=agentClient.cursor()
                if(withheldSave!=null && bytes.contentEquals(changed)) {
                    check(!withheldSave.toAbsolutePath().normalize().startsWith(Path.of(config.extPath).toAbsolutePath().normalize())) {"RED copy must be unwatched"}
                    Files.write(withheldSave,bytes)
                    awaitSources(agentClient,listOf(file),cursor,config.eventTimeoutMs)
                } else if(writeChanged(file,bytes)) awaitSources(agentClient,listOf(file),cursor,config.eventTimeoutMs)
            },{save(3,"v1")}) {save(2,"v2")}
        }) {
            restoreAll({
                val proof=owned(json(post("$endpoint/prepare/cleanup",params)))
                check(proof["removed"]?.isIntegralNumber==true && (!seeded || proof["removed"].asInt()==2)) {"Prepare cleanup removed wrong count: $proof"}
                count(0);println("    Prepare cleanup proof: $proof")
            },{check(Files.readAllBytes(path).contentEquals(baseline)) {"Prepare source bytes not restored"}})
        }
    }
}
