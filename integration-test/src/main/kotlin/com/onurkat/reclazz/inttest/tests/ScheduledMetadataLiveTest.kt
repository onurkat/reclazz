package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Native registration and actual tick cadence must both follow the annotation save. */
class ScheduledMetadataLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):BatchLiveTest("Spring scheduled metadata lifecycle",c,a,h) {
    override fun scenario() {
        val started=System.nanoTime();val nonce=UUID.randomUUID().toString();val params=mapOf("nonce" to nonce)
        val path="$endpoint/scheduled-metadata";val relative="src/com/onurkat/reclazztest/services/ProbeScheduledMetadataJob.java"
        var phase=0;var context:List<String>?=null;var lastTotal=0L
        fun number(n:JsonNode,key:String):Long {check(n[key]?.isIntegralNumber==true){"Missing scheduled $key: $n"};return n[key].asLong()}
        fun scope(n:JsonNode) {
            check(n.path("nonce").asText()==nonce&&n.path("active").isBoolean&&n.path("active").asBoolean()){"Wrong scheduled run: $n"}
        }
        fun registration(n:JsonNode,delay:Long) {
            check(n["ownerContext"]?.isTextual==true&&n["ownerContext"].asText().isNotBlank()) {"Missing scheduling owner"}
            val identity=listOf(n["ownerContext"].asText(),number(n,"contextIdentity").toString())
            if(context==null)context=identity else check(context==identity){"Scheduling context changed"}
            check(number(n,"ownedTasks")==1L&&n["tasks"]?.isArray==true&&n["tasks"].size()==1){"Duplicate/missing owned registration: $n"}
            val task=n["tasks"][0]
            check(task.path("method").asText()=="com.onurkat.reclazztest.services.ProbeScheduledMetadataJob.tick"&&
                task.path("fixedDelay").isBoolean&&task.path("fixedDelay").asBoolean()&&
                task.path("sameReceiver").isBoolean&&task.path("sameReceiver").asBoolean()&&number(task,"intervalMs")==delay){"Wrong scheduled metadata/receiver: $n"}
        }
        fun observe(delay:Long) {
            val start=System.nanoTime();phase++
            // Resolve the real registration first; starting a window never creates a timer.
            json(httpVerifier.poll(minOf(10_000L,config.eventTimeoutMs),"Scheduled metadata $delay",{httpVerifier.get("$path/read",params,it)}) {
                it.statusCode==200&&runCatching {val n=json(it);scope(n);registration(n,delay);true}.getOrDefault(false)
            })
            val armed=json(post("$path/phase",params+mapOf("phase" to phase.toString())));scope(armed);registration(armed,delay)
            val mark=number(armed,"windowStart");val receiver=number(armed,"receiver")
            check(number(armed,"phase")==phase.toLong()&&mark>0&&receiver>0){"Wrong scheduled phase admission"}
            val low=if(delay==300L)240.0 else 800.0;val high=if(delay==300L)550.0 else 1500.0
            val proof=json(httpVerifier.poll(minOf(10_000L,config.eventTimeoutMs),"Scheduled cadence phase $phase",{httpVerifier.get("$path/read",params,it)}) {raw ->
                if(raw.statusCode!=200)false else {
                    val n=json(raw);scope(n);registration(n,delay)
                    check(number(n,"phase")==phase.toLong()&&number(n,"windowStart")==mark&&number(n,"receiver")==receiver){"Stale scheduling window/receiver: $n"}
                    val total=number(n,"total");check(total>=lastTotal){"Scheduling counter reversed"};lastTotal=total
                    val samples=n["samples"];check(samples?.isArray==true){"Missing actual ticks"}
                    var previousSequence=0L;var previousNano=mark;var stable=0
                    for(sample in samples) {
                        val sequence=number(sample,"sequence");val nano=number(sample,"nano")
                        check(sequence>previousSequence&&sequence<=total&&nano>previousNano&&number(sample,"receiver")==receiver&&number(sample,"phase")==phase.toLong()){"Stale/repeated/retired tick: $sample"}
                        if(previousSequence>0) {
                            val ms=(nano-previousNano)/1_000_000.0
                            stable=if(ms in low..high)stable+1 else 0
                        }
                        previousSequence=sequence;previousNano=nano
                    }
                    stable>=3
                }
            })
            println("SCHEDULED_METADATA_PHASE $phase delay=$delay elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)} proof=$proof")
        }
        withRestore({
            val begin=json(post("$path/begin",params));scope(begin);registration(begin,1000)
            observe(1000)
            javaEdit(relative,replaceOnce(original(relative).toString(Charsets.UTF_8),"fixedDelay = 1000","fixedDelay = 300"),{observe(1000)}) {observe(300)}
        }) {
            val clean=json(post("$path/cleanup",params))
            check(clean.path("nonce").asText()==nonce&&clean.path("active").isBoolean&&!clean.path("active").asBoolean()&&
                number(clean,"samples")==0L&&number(clean,"retainedReceivers")==0L&&number(clean,"total")==0L){"Owned scheduler observations remain: $clean"}
            registration(clean,1000);println("SCHEDULED_METADATA_CLEANUP $clean")
        }
        println("SCHEDULED_METADATA_OUTCOME REAL_CADENCE_ROUND_TRIP elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)}")
    }
}
