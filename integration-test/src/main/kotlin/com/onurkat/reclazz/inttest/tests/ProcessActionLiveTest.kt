package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.util.UUID

/** The native process engine, including a process held across the save, executes the action. */
class ProcessActionLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):BatchLiveTest("Process action body and in-flight process",c,a,h) {
    override fun scenario() {
        val path="$endpoint/process-action"
        val owned=mutableListOf<String>()
        var graphVersion:String?=null
        fun scope(n:JsonNode,nonce:String,identity:JsonNode?=null) {
            check(n.path("nonce").asText()==nonce && n.path("code").asText()=="reclazz-action-$nonce" &&
                n.path("pk").isTextual && n.path("pk").asText().isNotBlank()) {"Wrong owned process: $n"}
            check(n.path("version").isTextual && n.path("version").asText().isNotBlank() && n.path("version").asText()!="null") {"Missing pinned version: $n"}
            if(graphVersion==null)graphVersion=n["version"].asText()
            check(n["version"].asText()==graphVersion) {"Process definition changed during body edit: $n"}
            if(identity!=null)check(n["pk"]==identity["pk"] && n["code"]==identity["code"] && n["version"]==identity["version"]) {"Held process identity changed: $n"}
        }
        fun held():Pair<String,JsonNode> {
            val nonce=UUID.randomUUID().toString();owned+=nonce
            val start=json(post("$path/start",mapOf("nonce" to nonce)));scope(start,nonce)
            val n=pollJson("$path/read",mapOf("nonce" to nonce)) {
                scope(it,nonce,start)
                check(it.path("result").asText().isEmpty()) {"Action ran before release: $it"}
                it.path("state").asText()=="RUNNING" && it.path("actions").isArray && it["actions"].size()==1 && it["actions"][0].asText()=="held"
            }
            return nonce to n
        }
        fun finish(run:Pair<String,JsonNode>,expected:String,label:String) {
            val (nonce,identity)=run
            val release=json(post("$path/release",mapOf("nonce" to nonce)))
            check(release.path("nonce").asText()==nonce && release.path("released").isBoolean && release["released"].asBoolean())
            val proof=pollJson("$path/read",mapOf("nonce" to nonce)) {
                it.path("state").asText() in setOf("SUCCEEDED","FAILED","ERROR")
            }
            scope(proof,nonce,identity)
            check(proof.path("state").asText()=="SUCCEEDED") {"Native action failed: $proof"}
            check(proof.path("result").asText()==expected) {"Stale persisted action result: expected $expected: $proof"}
            println("PROCESS_ACTION_PHASE $label expected=$expected proof=$proof")
        }
        withRestore({
            finish(held(),"action-v1","baseline")
            val a=held()
            println("PROCESS_ACTION_HELD before-edit proof=${a.second}")
            val relative="src/com/onurkat/reclazztest/batch/ProbeProcessAction.java"
            javaEdit(relative,replaceOnce(original(relative).toString(Charsets.UTF_8),"action-v1","action-v2"),
                {finish(held(),"action-v1","restored-C")}) {
                val current=json(get("$path/read",mapOf("nonce" to a.first)));scope(current,a.first,a.second)
                check(current.path("state").asText()=="RUNNING" && current.path("result").asText().isEmpty() &&
                    current.path("actions").isArray && current["actions"].size()==1 && current["actions"][0].asText()=="held") {"A did not remain waiting across edit: $current"}
                println("PROCESS_ACTION_HELD after-edit proof=$current")
                finish(a,"action-v2","held-A")
                finish(held(),"action-v2","fresh-B")
            }
        }) {
            restoreAll(*owned.map {nonce -> {
                val clean=post("$path/cleanup",mapOf("nonce" to nonce));requireOwnedCleanup(clean)
                check(json(clean).path("nonce").asText()==nonce)
                println("PROCESS_ACTION_CLEANUP ${clean.body}")
            }}.toTypedArray())
        }
        println("PROCESS_ACTION_OUTCOME PERSISTED_ACTION_ROUND_TRIP")
    }
}
