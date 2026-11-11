package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class RemoveInterceptorLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier,
    private val withheldSave:Path?=System.getenv("RECLAZZ_REMOVE_WITHHOLD_EDIT")?.let {Path.of(it)},
    private val omitRemove:Boolean=System.getenv("RECLAZZ_REMOVE_OMIT_NATIVE")=="true")
    :BatchLiveTest("Native RemoveInterceptor remove",c,a,h) {
    override fun scenario() {
        val relative="src/com/onurkat/reclazztest/batch/ProbeRemoveInterceptor.java"
        val path=Path.of(config.extPath,relative);val baseline=original(relative)
        check(Files.readAllBytes(path).contentEquals(baseline)) {"Dirty Remove baseline"}
        val id=UUID.randomUUID().toString();val run="reclazz-remove-$id";val params=mapOf("run" to run)
        val url="$endpoint/remove";var registry:Int?=null;var pks=listOf<String>();var control=""
        val versions=linkedMapOf<Int,String>();var completed=0;var seeded=false;var success=false
        fun owned(n:JsonNode):JsonNode {check(n["run"]?.asText()==run) {"Wrong Remove run: $n"};return n}
        fun integer(n:JsonNode,k:String,v:Int) {check(n[k]?.isIntegralNumber==true && n[k].asInt()==v) {"Remove $k mismatch: $n"}}
        fun yes(n:JsonNode,k:String) {check(n[k]?.isBoolean==true && n[k].asBoolean()) {"Remove $k false: $n"}}
        fun calls(n:JsonNode) {
            val steps=n["steps"];check(steps?.isObject==true && steps.size()==versions.size) {"Remove steps mismatch: $n"}
            for((step,v) in versions) {val x=steps[step.toString()] ?: error("Missing remove proof: $n");integer(x,"calls",1);check(x["version"]?.asText()==v) {"Remove callback version mismatch: $n"}}
        }
        fun count(rows:Int) {
            val n=owned(json(get("$url/count",params)));integer(n,"ownedRemaining",rows);integer(n,"globalRemaining",rows)
            integer(n,"heldCount",versions.size);integer(n,"recorderRemaining",versions.size);integer(n,"scopeCount",0);calls(n)
        }
        fun ready(v:String) {
            val n=pollJson("$url/ready",params) {owned(it);it["version"]?.asText()==v}
            yes(n,"sameRegistry");yes(n,"mappingMatches");integer(n,"targetCount",1)
            check(n["registryClass"]?.asText()=="de.hybris.platform.servicelayer.interceptor.impl.DefaultInterceptorRegistry")
            check(n["registryIdentity"]?.isIntegralNumber==true);val r=n["registryIdentity"].asInt()
            check(registry==null || registry==r) {"Remove registry replaced"};registry=r
        }
        fun remove(step:Int,v:String) {
            ready(v);versions[step]=v
            val n=owned(json(post("$url/remove",params+mapOf("step" to "$step","omit" to (omitRemove && step==1).toString()))))
            integer(n,"step",step);check(n["pk"]?.asText()==pks[step] && n["nonce"]?.asText()=="$run-s$step") {"Wrong removed row: $n"}
            calls(n);yes(n,"recordedSame");yes(n,"isRemoved")
            val persisted=owned(json(get("$url/persisted",params+mapOf("step" to "$step"))))
            integer(persisted,"step",step);check(persisted["pk"]?.asText()==pks[step]) {"Wrong persisted PK"}
            integer(persisted,"targetRemaining",0);integer(persisted,"controlRemaining",1)
            check(persisted["controlPk"]?.asText()==control && persisted["controlNonce"]?.asText()=="$run-control") {"Remove control changed: $persisted"}
            yes(persisted,"recorderUnchanged");calls(persisted);completed++;count(4-completed)
            println("    Remove native callback proof: $n");println("    Remove committed absence proof: $persisted")
        }
        withRestore({
            count(0);ready("v1")
            val g=owned(json(post("$url/guard",params)))
            check(g["nonces"]?.isArray==true && g["nonces"].map {it.asText()}==listOf("reclazz-dao-$id-a-1","reclazz-dyn-$id-row","reclazz-schema-$id","reclazz-prep-$id-seed","reclazz-init-$id-s0-default-v1","reclazz-load-$id-target")) {"Remove guard changed: $g"}
            integer(g,"removed",6);integer(g,"recorderRemaining",0);count(0);println("    Remove native unscoped guard: $g")
            val seed=owned(json(post("$url/seed",params)));check(seed["pks"]?.isArray==true)
            pks=seed["pks"].map {it.asText()};control=seed["controlPk"]?.asText().orEmpty()
            check(pks.size==3 && pks.toSet().size==3 && pks.all {it.isNotEmpty()} && control.isNotEmpty() && control !in pks)
            seeded=true;count(4);remove(0,"v1")
            val changed=replaceOnce(baseline.toString(Charsets.UTF_8),"return \"v1\";","return \"v2\";").toByteArray()
            fixtureEdit(relative,changed,{file,bytes ->
                val cursor=agentClient.cursor()
                if(withheldSave!=null && bytes.contentEquals(changed)) {
                    check(!withheldSave.toAbsolutePath().normalize().startsWith(Path.of(config.extPath).toAbsolutePath().normalize()))
                    Files.write(withheldSave,bytes);awaitSources(agentClient,listOf(file),cursor,config.eventTimeoutMs)
                } else if(writeChanged(file,bytes)) awaitSources(agentClient,listOf(file),cursor,config.eventTimeoutMs)
            },{ready("v1");remove(2,"v1")}) {ready("v2");remove(1,"v2")}
            success=true
        }) {
            restoreAll({
                val n=owned(json(post("$url/cleanup",params)));yes(n,"recorderUnchanged")
                if(success) integer(n,"removed",1) else {
                    check(n["removed"]?.isIntegralNumber==true && n["removed"].asInt() in 0..4) {"Invalid Remove cleanup: $n"}
                    if(seeded) check(n["removed"].asInt()<=4-completed)
                }
                versions.clear();count(0);println("    Remove cleanup proof: $n")
            },{check(Files.readAllBytes(path).contentEquals(baseline)) {"Remove source not restored"}})
        }
    }
}
