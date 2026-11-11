package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class LoadInterceptorLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier,
    private val withheldSave:Path?=System.getenv("RECLAZZ_LOAD_WITHHOLD_EDIT")?.let {Path.of(it)},
    private val reuseHeld:Boolean=System.getenv("RECLAZZ_LOAD_REUSE_HELD")=="true")
    :BatchLiveTest("Native LoadInterceptor load",c,a,h) {
    override fun scenario() {
        val relative="src/com/onurkat/reclazztest/batch/ProbeLoadInterceptor.java"
        val path=Path.of(config.extPath,relative);val baseline=original(relative)
        check(Files.readAllBytes(path).contentEquals(baseline)) {"Dirty Load baseline"}
        val id=UUID.randomUUID().toString();val run="reclazz-load-$id";val params=mapOf("run" to run)
        val url="$endpoint/load";var registry:Int?=null;var pk="";var control="";var seeded=false
        val versions=linkedMapOf<Int,String>()
        fun owned(n:JsonNode):JsonNode {check(n["run"]?.asText()==run) {"Wrong Load run: $n"};return n}
        fun integer(n:JsonNode,k:String,v:Int) {check(n[k]?.isIntegralNumber==true && n[k].asInt()==v) {"Load $k mismatch: $n"}}
        fun yes(n:JsonNode,k:String) {check(n[k]?.isBoolean==true && n[k].asBoolean()) {"Load $k false: $n"}}
        fun calls(n:JsonNode) {
            val steps=n["steps"];check(steps?.isObject==true && steps.size()==versions.size) {"Load steps mismatch: $n"}
            for((step,v) in versions) {val x=steps[step.toString()] ?: error("Missing load proof: $n");integer(x,"calls",1);check(x["version"]?.asText()==v) {"Load callback version mismatch: $n"}}
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
            check(registry==null || registry==r) {"Load registry replaced"};registry=r
        }
        fun model(n:JsonNode,step:Int) {
            owned(n);integer(n,"step",step);check(n["pk"]?.asText()==pk && n["nonce"]?.asText()=="$run-target") {"Wrong loaded row: $n"}
            check(n["isNew"]?.isBoolean==true && !n["isNew"].asBoolean()) {"Load was not persisted: $n"};calls(n)
        }
        fun warm() {
            val n=owned(json(post("$url/warm",params)));model(n,0);yes(n,"warmSame");yes(n,"warmUnchanged")
            println("    Load old attached warm proof: $n")
        }
        fun persisted() {
            val n=owned(json(get("$url/persisted",params)))
            check(n["pk"]?.asText()==pk && n["controlPk"]?.asText()==control && n["nonce"]?.asText()=="$run-target" && n["controlNonce"]?.asText()=="$run-control") {"Load changed persisted data: $n"}
            yes(n,"recorderUnchanged");calls(n);println("    Load persisted guard proof: $n")
        }
        fun load(step:Int,v:String) {
            ready(v);versions[step]=v
            val n=owned(json(post("$url/load",params+mapOf("step" to "$step","reuse" to (reuseHeld && step==1).toString()))))
            model(n,step);yes(n,"recordedSame");yes(n,"freshDistinct");yes(n,"warmSame");yes(n,"warmUnchanged")
            count(2);persisted();println("    Load native fresh and warm proof: $n")
        }
        withRestore({
            count(0);ready("v1")
            val g=owned(json(post("$url/guard",params)))
            check(g["nonces"]?.isArray==true && g["nonces"].map {it.asText()}==listOf("reclazz-dao-$id-a-1","reclazz-dyn-$id-row","reclazz-schema-$id","reclazz-prep-$id-seed","reclazz-init-$id-s0-default-v1")) {"Load guard changed: $g"}
            integer(g,"recorderRemaining",0);count(0);println("    Load native unscoped guard: $g")
            val seed=owned(json(post("$url/seed",params)));pk=seed["pk"]?.asText().orEmpty();control=seed["controlPk"]?.asText().orEmpty()
            check(pk.isNotEmpty() && control.isNotEmpty() && pk!=control);seeded=true;count(2);load(0,"v1")
            val changed=replaceOnce(baseline.toString(Charsets.UTF_8),"return \"v1\";","return \"v2\";").toByteArray()
            fixtureEdit(relative,changed,{file,bytes ->
                val cursor=agentClient.cursor()
                if(withheldSave!=null && bytes.contentEquals(changed)) {
                    check(!withheldSave.toAbsolutePath().normalize().startsWith(Path.of(config.extPath).toAbsolutePath().normalize()))
                    Files.write(withheldSave,bytes);awaitSources(agentClient,listOf(file),cursor,config.eventTimeoutMs)
                } else if(writeChanged(file,bytes)) awaitSources(agentClient,listOf(file),cursor,config.eventTimeoutMs)
            },{ready("v1");warm();load(2,"v1");persisted()}) {
                ready("v2");warm();load(1,"v2")
            }
        }) {
            restoreAll({
                val n=owned(json(post("$url/cleanup",params)))
                integer(n,"removed",if(seeded) 2 else 0);versions.clear();count(0)
                println("    Load cleanup proof: $n")
            },{check(Files.readAllBytes(path).contentEquals(baseline)) {"Load source not restored"}})
        }
    }
}
