package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class InitDefaultsInterceptorLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier,
    private val withheldSave:Path? = System.getenv("RECLAZZ_INIT_DEFAULTS_WITHHOLD_EDIT")?.let {Path.of(it)})
    :BatchLiveTest("Native InitDefaultsInterceptor create",c,a,h) {
    override fun scenario() {
        val relative="src/com/onurkat/reclazztest/batch/ProbeInitDefaultsInterceptor.java"
        val path=Path.of(config.extPath,relative);val baseline=original(relative)
        check(Files.readAllBytes(path).contentEquals(baseline)) {"Dirty InitDefaults baseline"}
        val id=UUID.randomUUID().toString();val run="reclazz-init-$id";val params=mapOf("run" to run)
        val url="$endpoint/init-defaults";var registry:Int?=null
        val identities=mutableMapOf<Int,Int>();val pks=mutableMapOf<Int,String>();val versions=mutableMapOf<Int,String>()
        fun owned(n:JsonNode):JsonNode {check(n["run"]?.asText()==run) {"Wrong InitDefaults run: $n"};return n}
        fun integer(n:JsonNode,key:String,value:Int) {check(n[key]?.isIntegralNumber==true && n[key].asInt()==value) {"InitDefaults $key mismatch: $n"}}
        fun calls(n:JsonNode) {
            val steps=n["steps"];check(steps?.isObject==true && steps.size()==versions.size) {"InitDefaults steps mismatch: $n"}
            for((step,v) in versions) {val proof=steps[step.toString()] ?: error("Missing create proof: $n")
                integer(proof,"calls",1);check(proof["version"]?.asText()==v) {"Wrong callback version: $n"}}
        }
        fun count(rows:Int,held:Int) {
            val n=owned(json(get("$url/count",params)))
            integer(n,"ownedRemaining",rows);integer(n,"globalRemaining",rows);integer(n,"heldCount",held)
            integer(n,"scopeCount",0);integer(n,"recorderRemaining",held);calls(n)
        }
        fun ready(v:String) {
            val n=pollJson("$url/ready",params) {owned(it);it["version"]?.asText()==v}
            check(n["sameRegistry"]?.isBoolean==true && n["sameRegistry"].asBoolean() &&
                n["mappingMatches"]?.isBoolean==true && n["mappingMatches"].asBoolean() &&
                n["registryClass"]?.asText()=="de.hybris.platform.servicelayer.interceptor.impl.DefaultInterceptorRegistry") {"InitDefaults registry mismatch: $n"}
            integer(n,"targetCount",1);check(n["registryIdentity"]?.isIntegralNumber==true)
            val identity=n["registryIdentity"].asInt();check(registry==null || registry==identity) {"Replaced registry: $n"};registry=identity
            integer(n,"heldCount",versions.size);integer(n,"scopeCount",0)
        }
        fun held(step:Int,isNew:Boolean,n:JsonNode=owned(json(get("$url/held",params+mapOf("step" to "$step"))))) {
            owned(n);integer(n,"step",step)
            check(n["nonce"]?.asText()=="$run-s$step-default-${versions.getValue(step)}") {"InitDefaults create/held value mismatch: $n"}
            check(n["isNew"]?.isBoolean==true && n["isNew"].asBoolean()==isNew) {"Create/save conflated: $n"}
            check(n["identity"]?.isIntegralNumber==true)
            val identity=n["identity"].asInt();check(identities[step]==null || identities[step]==identity) {"Held model replaced: $n"};identities[step]=identity
            if(!isNew) {val pk=n["pk"]?.asText().orEmpty();check(pk.isNotEmpty() && (pks[step]==null || pks[step]==pk));pks[step]=pk}
            calls(n)
        }
        fun read(step:Int) {
            val n=owned(json(get("$url/read",params+mapOf("step" to "$step"))))
            integer(n,"step",step);check(n["pk"]?.asText()==pks.getValue(step) &&
                n["nonce"]?.asText()=="$run-s$step-default-${versions.getValue(step)}" &&
                n["detachedRead"]?.isBoolean==true && n["detachedRead"].asBoolean()) {"InitDefaults detached persistence mismatch: $n"}
            calls(n);println("    InitDefaults native persisted proof: $n")
        }
        fun create(step:Int,v:String) {
            ready(v);versions[step]=v
            val n=owned(json(post("$url/create",params+mapOf("step" to "$step"))))
            held(step,true,n);count(pks.size,versions.size)
            println("    InitDefaults native before-save proof: $n")
        }
        fun save(step:Int) {
            held(step,true);held(step,false,owned(json(post("$url/save",params+mapOf("step" to "$step")))))
            check(pks.values.toSet().size==pks.size) {"Duplicate persisted PK"};read(step);count(pks.size,versions.size)
        }
        withRestore({
            count(0,0)
            val g=owned(json(post("$url/guard",params)))
            check(g["nonces"]?.isArray==true && g["nonces"].map {it.asText()}==listOf("reclazz-dao-$id-a-1","reclazz-dyn-$id-row","reclazz-schema-$id","reclazz-prep-$id-seed")) {"InitDefaults unscoped guard failed: $g"}
            integer(g,"recorderRemaining",0);count(0,0);println("    InitDefaults native unscoped guard: $g")
            create(0,"v1");create(1,"v1");save(1)
            val changed=replaceOnce(baseline.toString(Charsets.UTF_8),"return \"v1\";","return \"v2\";").toByteArray()
            fixtureEdit(relative,changed,{file,bytes ->
                val cursor=agentClient.cursor()
                if(withheldSave!=null && bytes.contentEquals(changed)) {
                    check(!withheldSave.toAbsolutePath().normalize().startsWith(Path.of(config.extPath).toAbsolutePath().normalize())) {"RED path must be unwatched"}
                    Files.write(withheldSave,bytes);awaitSources(agentClient,listOf(file),cursor,config.eventTimeoutMs)
                } else if(writeChanged(file,bytes)) awaitSources(agentClient,listOf(file),cursor,config.eventTimeoutMs)
            },{
                create(3,"v1");save(3)
                for(step in pks.keys) {held(step,false);read(step)}
            }) {
                ready("v2");held(0,true);save(0);read(1);create(2,"v2");save(2)
            }
        }) {
            restoreAll({
                val n=owned(json(post("$url/cleanup",params)))
                check(n["removed"]?.isIntegralNumber==true && n["removed"].asInt()==pks.size) {"Wrong removed count: $n"}
                versions.clear();count(0,0);println("    InitDefaults cleanup proof: $n")
            },{check(Files.readAllBytes(path).contentEquals(baseline)) {"InitDefaults source not restored"}})
        }
    }
}
