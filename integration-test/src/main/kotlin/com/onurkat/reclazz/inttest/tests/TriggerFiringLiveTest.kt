package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The admitted TriggerTask and the actual job observation must belong to the same firing. */
class TriggerFiringLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):BatchLiveTest("Native SAP Trigger firing",c,a,h) {
    override fun scenario() {
        val started=System.nanoTime()
        val nonce=UUID.randomUUID().toString();val params=mapOf("nonce" to nonce)
        val path="$endpoint/trigger"
        val relative="src/com/onurkat/reclazztest/batch/NativeProbeJob.java"
        var phase=0;var cronPk="";val triggers=mutableSetOf<String>();val tasks=mutableSetOf<String>()
        fun integer(n:JsonNode,key:String):Long {check(n[key]?.isIntegralNumber==true){"Missing trigger $key: $n"};return n[key].asLong()}
        fun text(n:JsonNode,key:String):String {check(n[key]?.isTextual==true&&n[key].asText().isNotBlank()){ "Missing trigger $key: $n" };return n[key].asText()}
        fun fire(version:String) {
            val start=System.nanoTime();phase++
            val admitted=json(post("$path/arm",params+mapOf("phase" to phase.toString())))
            check(text(admitted,"nonce")==nonce&&integer(admitted,"phase")==phase.toLong()&&text(admitted,"cronPk")==cronPk){"Wrong owned trigger admission: $admitted"}
            val trigger=text(admitted,"triggerPk");val task=text(admitted,"taskPk")
            check(triggers.add(trigger)&&tasks.add(task)&&text(admitted,"taskTriggerPk")==trigger&&text(admitted,"runner")=="triggerTaskRunner"){"Wrong native task linkage: $admitted"}
            val activation=integer(admitted,"activation");val due=integer(admitted,"executionDate")
            check(due>=activation&&integer(admitted,"admittedAt")<due&&due>System.currentTimeMillis()&&
                integer(admitted,"offsetMs")==due-activation){"Wrong native task due time: $admitted"}
            var woke=false
            val observation=json(httpVerifier.poll(minOf(30_000L,config.eventTimeoutMs),"Native trigger phase $phase",{budget ->
                val response=httpVerifier.get("$path/read",params,budget)
                if(response.statusCode==200) {
                    val n=json(response)
                    // Explicit one-time native poll wake, never a manual job invocation.
                    if(!woke&&System.currentTimeMillis()>=due&&n.path("calls").asInt()<phase) {
                        woke=true;val wake=json(httpVerifier.post("$path/wake",params,budget))
                        check(wake.path("nonce").asText()==nonce&&wake.path("phase").asInt()==phase){"Wrong wake scope"}
                    }
                }
                response
            }) {response ->
                if(response.statusCode!=200) false else {
                    val n=json(response)
                    for(key in listOf("nonce","phase","cronPk","triggerPk","taskPk","taskTriggerPk","runner","activation","executionDate","admittedAt","offsetMs"))
                        check(n[key]==admitted[key]){"Trigger admission changed: $n"}
                    val calls=integer(n,"calls");check(calls<=phase){"Duplicate owned firing: $n"}
                    if(calls==phase.toLong()) {
                        check(text(n,"version")==version&&integer(n,"firedAt")>=due){"Wrong or early native firing: $n"}
                        check(text(n,"thread").isNotBlank())
                        n.path("result").asText()=="SUCCESS"&&n.path("status").asText()=="FINISHED"
                    } else false
                }
            })
            println("TRIGGER_PHASE $phase elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)} proof=$observation")
        }
        withRestore({
            val begin=json(post("$path/begin",params))
            check(text(begin,"nonce")==nonce&&text(begin,"code")=="reclazz-trigger-$nonce"&&begin.path("engineRunning").asBoolean()){ "Wrong trigger begin: $begin" }
            cronPk=text(begin,"cronPk")
            fire("job-v1")
            javaEdit(relative,replaceOnce(original(relative).toString(Charsets.UTF_8),"job-v1","job-v2"),{fire("job-v1")}) {fire("job-v2")}
        }) {
            val clean=post("$path/cleanup",params);requireOwnedCleanup(clean)
            val n=json(clean)
            check(text(n,"nonce")==nonce&&integer(n,"calls")==0L&&integer(n,"firedAt")==0L){"Owned trigger probe not cleared: $n"}
            println("TRIGGER_CLEANUP $n")
        }
        println("TRIGGER_OUTCOME NATIVE_TASK_ROUND_TRIP elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)}")
    }
}
