package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

internal fun requireFacadeData(node:JsonNode,name:String,stable:JsonNode?=null) {
    check(node.path("name").asText()==name) {"Wrong converted native name"}
    check(node.path("manufacturer").asText()=="stable-manufacturer")
    check(node.path("purchasable").isBoolean&&node.path("purchasable").asBoolean())
    check(node.path("configurable").isBoolean&&!node.path("configurable").asBoolean())
    if(stable!=null) {
        fun fields(n:JsonNode)=n.deepCopy<ObjectNode>().apply {remove("name")}
        check(fields(node)==fields(stable)) {"Unrelated converted fields changed"}
    }
}
internal fun requireFacadeFacts(node:JsonNode,run:String) {
    check(node.path("run").asText()==run&&node.path("sessionRestored").isBoolean&&node.path("sessionRestored").asBoolean())
    val names=mapOf("target-one" to "facade-v1","target-two" to "facade-v2","control" to "control-stable")
    check(node.path("products").fieldNames().asSequence().toSet()==names.keys)
    for((key,name) in names)check(node.path("products").path(key).path("name").asText()==name)
    val pks=names.keys.map {node.path("products").path(it).path("pk").asText()}
    check(pks.toSet().size==3&&pks.all {it.toLongOrNull()?.let {n->n>0}==true})
    for(key in listOf("catalogPk","versionPk"))check(node.path(key).asText().toLongOrNull()?.let {it>0}==true)
    check(node.path("jvmStart").isIntegralNumber&&node.path("jvmStart").asLong()>0)
    for(key in listOf("target","control")) {
        val chain=node.path(key)
        check(chain.path("facade").asText()=="de.hybris.platform.commercefacades.product.impl.DefaultProductFacade")
        val service=if(key=="target")"com.onurkat.reclazztest.batch.ProbeFacadeProductService" else "de.hybris.platform.product.impl.DefaultProductService"
        check(chain.path("service").asText()==service)
        check(chain.path("converter").asText()=="de.hybris.platform.converters.impl.AbstractPopulatingConverter")
        check(chain.path("populators").isArray&&chain.path("populators").map {it.asText()}==listOf("de.hybris.platform.commercefacades.product.converters.populator.ProductBasicPopulator"))
    }
}
internal fun requireFacadeCleanup(node:JsonNode,run:String) {
    check(node.path("run").asText()==run&&node.path("sessionRestored").isBoolean&&node.path("sessionRestored").asBoolean())
    for(key in listOf("products","versions","catalogs","ownedRemaining"))check(node.path(key).isIntegralNumber&&node.path(key).asInt()==0) {"Owned facade fixture remains: $node"}
}

class FacadeChainLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):
    BatchLiveTest("Native facade dependent service chain",c,a,h) {
    override fun scenario() {
        val run="reclazz-facade-${UUID.randomUUID()}";val params=mapOf("run" to run);val url="$endpoint/facade"
        requireFacadeCleanup(json(get("$url/count",params)),run)
        val relative="src/com/onurkat/reclazztest/batch/ProbeFacadeProductService.java";val source=Path.of(config.extPath,relative);val baseline=original(relative)
        check(Files.readAllBytes(source).contentEquals(baseline))
        val changed=replaceOnce(baseline.toString(Charsets.UTF_8),"code + \"-one\"","code + \"-two\"")
        withRestore({
            val seed=json(post("$url/seed",params));requireFacadeFacts(seed,run)
            val first=json(get("$url/target",params));requireFacadeData(first,"facade-v1")
            val control=json(get("$url/control",params));requireFacadeData(control,"control-stable")
            fun phase(name:String) {
                val actual=json(get("$url/target",params));requireFacadeData(actual,name,first)
                val actualControl=json(get("$url/control",params));requireFacadeData(actualControl,"control-stable",control);check(actualControl==control)
                val facts=json(get("$url/facts",params));requireFacadeFacts(facts,run);check(facts==seed) {"Native chain, persisted identities/names, JVM or session changed"}
                println("FACADE_PHASE $name target=$actual control=$actualControl facts=$facts")
            }
            phase("facade-v1")
            val red=runCatching {requireFacadeData(first,"facade-v2",first)}.exceptionOrNull()
            check(red is IllegalStateException&&red.message=="Wrong converted native name") {"Withheld service edit did not reject actual v1 native response"}
            println("FACADE_WITHHELD_RED expected=facade-v2 observed=facade-v1")
            javaEdit(relative,changed,{phase("facade-v1")}) {phase("facade-v2")}
        }) {
            restoreAll({
                if(!Files.readAllBytes(source).contentEquals(baseline)) {
                    val mark=agentClient.cursor();writeChanged(source,baseline);awaitSources(agentClient,listOf(source),mark,config.eventTimeoutMs)
                }
                check(Files.readAllBytes(source).contentEquals(baseline))
            },{
                val cleanup=json(post("$url/cleanup",params));requireFacadeCleanup(cleanup,run);requireFacadeCleanup(json(get("$url/count",params)),run)
                println("FACADE_CLEANUP $cleanup")
            })
        }
    }
}
