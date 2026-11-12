package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

internal fun requireRestrictionSeed(seed:JsonNode,run:String) {
    check(seed.path("run").asText()==run&&seed.path("sessionRestored").isBoolean&&seed.path("sessionRestored").asBoolean()) {"Restriction seed/session mismatch"}
    val expected=setOf("selected-a-one","selected-a-two","selected-b-one","selected-b-two","other-a-one","other-a-two","other-b-one","other-b-two")
    check(seed.path("products").fieldNames().asSequence().toSet()==expected)
    val products=seed.path("products").elements().asSequence().map {it.asText()}.toSet()
    check(products.size==8&&products.all {it.toLongOrNull()?.let {n->n>0}==true})
    for(field in listOf("users","restrictions"))check(seed.path(field).fieldNames().asSequence().toSet()==setOf("a","b")&&seed.path(field).elements().asSequence().map {it.asText()}.toSet().size==2)
    check(seed.path("catalogs").fieldNames().asSequence().toSet()==setOf("selected","other")&&seed.path("catalogs")["selected"]!=seed.path("catalogs")["other"])
    check(seed.path("adminAll").isArray&&seed.path("adminAll").map {it.asText()}.sorted()==products.sorted())
    check(seed.path("control").isArray&&seed.path("control").size()==1&&seed.path("control")[0].asText().toLong()>0)
}
internal fun requireRestrictionResult(node:JsonNode,seed:JsonNode,run:String,actor:String,version:String,suffix:String) {
    check(node.path("run").asText()==run&&node.path("actor").asText()==actor&&node.path("version").asText()==version)
    check(node.path("userPk")==seed.path("users").path(actor)&&node.path("catalogPk")==seed.path("catalogs").path(version)) {"Wrong native user/catalog"}
    for(field in listOf("enabled","sessionRestored"))check(node.path(field).isBoolean&&node.path(field).asBoolean()) {"Restriction/session gate failed"}
    check(node.path("restrictionPks").isArray&&node.path("restrictionPks").map {it.asText()}.contains(seed.path("restrictions").path(actor).asText()))
    check(node.path("control")==seed.path("control")) {"Unrelated control changed"}
    check(node.path("products").isArray&&node.path("products").map {it.asText()}==listOf(seed.path("products").path("$version-$actor-$suffix").asText())) {"Wrong restricted native PK set"}
}

class SearchRestrictionLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):
    BatchLiveTest("Native SearchRestriction user and catalog visibility",c,a,h) {
    override fun scenario() {
        val run="reclazz-restriction-${UUID.randomUUID()}";val params=mapOf("run" to run)
        val url="$endpoint/restriction"
        val gates=json(get("$url/gates"));check(profileGateReason(gates,mapOf("restrictionsConfigured" to true,"nativeTypes" to true,"nativeAdmin" to true))==null) {"Native restriction gates unavailable"}
        fun remaining():JsonNode {
            val result=json(get("$url/count",params));check(result.path("run").asText()==run&&result.path("sessionRestored").isBoolean&&result.path("sessionRestored").asBoolean())
            check(result.path("ownedRemaining").isIntegralNumber&&result.path("ownedRemaining").asInt()==0) {"Owned restriction fixture remains: $result"};return result
        }
        remaining()
        val relative="src/com/onurkat/reclazztest/batch/ProbeSearchRestrictionDao.java";val source=Path.of(config.extPath,relative);val baseline=original(relative)
        check(Files.readAllBytes(source).contentEquals(baseline))
        val changed=replaceOnce(baseline.toString(Charsets.UTF_8),"run + \"-%-one\"","run + \"-%-two\"")
        withRestore({
            val seed=json(post("$url/seed",params));requireRestrictionSeed(seed,run)
            fun phase(suffix:String) {
                for(actor in listOf("a","b"))for(version in listOf("selected","other")) {
                    val result=json(get("$url/find",params+mapOf("actor" to actor,"version" to version)))
                    requireRestrictionResult(result,seed,run,actor,version,suffix)
                    println("RESTRICTION_QUERY $suffix $result")
                }
                val facts=json(get("$url/facts",params));requireRestrictionSeed(facts,run);check(facts==seed) {"Persisted/admin/control identity changed"}
                println("RESTRICTION_PHASE $suffix admin=8 sessionRestored=true")
            }
            phase("one")
            val actual=json(get("$url/find",params+mapOf("actor" to "a","version" to "selected")))
            val red=runCatching {requireRestrictionResult(actual,seed,run,"a","selected","two")}.exceptionOrNull()
            check(red is IllegalStateException&&red.message=="Wrong restricted native PK set") {"Withheld DAO edit did not reject native one PK"}
            println("RESTRICTION_WITHHELD_RED expected=two observed=one")
            javaEdit(relative,changed,{phase("one")}) {phase("two")}
        }) {
            restoreAll({
                if(!Files.readAllBytes(source).contentEquals(baseline)) {
                    val mark=agentClient.cursor();writeChanged(source,baseline);awaitSources(agentClient,listOf(source),mark,config.eventTimeoutMs)
                }
                check(Files.readAllBytes(source).contentEquals(baseline))
            },{
                val cleanup=json(post("$url/cleanup",params));check(cleanup.path("ownedRemaining").isIntegralNumber&&cleanup.path("ownedRemaining").asInt()==0&&cleanup.path("sessionRestored").asBoolean())
                println("RESTRICTION_CLEANUP ${remaining()}")
            })
        }
    }
}
