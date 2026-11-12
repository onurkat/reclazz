package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.util.UUID

/** Owned core-visible parsing code through real OCC, not the native formatter SPI. */
class OccDateParsingLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier,
    private val withholdSave:Boolean=false):BatchLiveTest("Owned OCC request date parsing",c,a,h) {
    override fun scenario() {
        val relative="src/com/onurkat/reclazztest/batch/ProbeOccDateParser.java"
        val run="reclazz-date-${UUID.randomUUID()}"
        val input="2026-11-12T03:04:05+02:30"
        val v1=1794443645000L;val v2=v1+1000L
        var identity:List<String>?=null
        fun params(phase:String,invalid:Boolean=false)=mapOf("token" to "$run-$phase","timestamp" to java.net.URLEncoder.encode(if(invalid) "malformed-owned-timestamp" else input,Charsets.UTF_8))
        fun common(n:JsonNode,phase:String) {
            check(n["token"]?.asText()=="$run-$phase"){"Wrong date request token"}
            for(key in listOf("webContext","sameServingContext","localBean","sameBean","converterContract"))
                check(n[key]?.isBoolean==true&&n[key].asBoolean()){"Date $key false"}
            check(n["parserClass"]?.asText()=="com.onurkat.reclazztest.batch.ProbeOccDateParser"){"Wrong date parser class"}
            check(n["occPath"]?.asText()=="/occ"){"Wrong serving OCC path"}
            check(n["targetCalls"]?.isIntegralNumber==true&&n["targetCalls"].asLong()==1L){"Date target not invoked exactly once"}
            check(n["controlEpoch"]?.isIntegralNumber==true&&n["controlEpoch"].asLong()==v1){"Date control changed"}
            for(key in listOf("contextId","parserLoader","contextLoader","locale","zone"))check(n[key]?.isTextual==true&&n[key].asText().isNotBlank()){"Missing date $key"}
            for(key in listOf("contextIdentity","jvmStart"))check(n[key]?.isIntegralNumber==true){"Missing date $key"}
            val stable=listOf("contextId","contextIdentity","jvmStart","locale","zone").map {n[it].asText()}
            if(identity==null)identity=stable else check(identity==stable){"Date context/environment changed"}
        }
        fun verify(epoch:Long,phase:String) {
            val n=pollJson("$occ/reclazzdate",params(phase)) {it["epoch"]?.isIntegralNumber==true&&it["epoch"].asLong()==epoch}
            common(n,phase);println("    OCC date proof $phase: $n")
            val bad=get("$occ/reclazzdate",params("$phase-invalid",true))
            check(bad.statusCode==400){"Invalid date status changed: ${bad.statusCode}"}
            val invalid=batchJson.readTree(bad.body);common(invalid,"$phase-invalid")
            check(invalid["error"]?.asText()=="invalid-timestamp"&&!invalid.has("epoch")){"Invalid date accepted"}
        }
        verify(v1,"baseline")
        Thread.sleep(minOf(150L,config.eventTimeoutMs))
        // One request after a bounded wait, without a source save: no second observation window.
        val withheld=json(get("$occ/reclazzdate",params("withheld")));common(withheld,"withheld")
        check(withheld["epoch"]?.isIntegralNumber==true&&withheld["epoch"].asLong()!=v2){"WITHHELD_SAVE_FALSE_PASS"}
        check(withheld["epoch"].asLong()==v1){"Withheld date changed"}
        println("OCC_DATE_WITHHELD_RED v2 absent; source not saved")
        if(withholdSave) {
            withRestore({verify(v2,"v2")},{verify(v1,"restored")})
        } else {
            javaEdit(relative,replaceOnce(original(relative).toString(Charsets.UTF_8)," + 0L)"," + 1000L)"),
                {verify(v1,"restored")}) {verify(v2,"v2")}
        }
        println("OCC_DATE_OUTCOME OWNED_REQUEST_PARSER_ROUND_TRIP")
    }
}
