package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.util.UUID

class SapEventLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):BatchLiveTest("Native EventService publish",c,a,h) {
    override fun scenario() {
        val relative="src/com/onurkat/reclazztest/batch/NativeProbeListener.java"
        val control=UUID.randomUUID().toString()
        fun verify(nonce:String,version:String) {
            pollJson("$endpoint/native/event",mapOf("nonce" to nonce)) { it["version"].asText()==version && it["calls"].asInt()==1 }
        }
        fun publish(version:String) {
            val nonce=UUID.randomUUID().toString()
            withRestore({check(post("$endpoint/native/event",mapOf("nonce" to nonce)).statusCode==200);verify(nonce,version)}) {
                val cleaned=json(post("$endpoint/native/event-cleanup",mapOf("nonce" to nonce)))
                check(cleaned["calls"].asInt()==0 && cleaned["version"].asText()=="absent")
            }
        }
        withRestore({
            check(post("$endpoint/native/event",mapOf("nonce" to control)).statusCode==200);verify(control,"event-v1")
            javaEdit(relative,replaceOnce(original(relative).toString(Charsets.UTF_8),"event-v1","event-v2"),
                {publish("event-v1");verify(control,"event-v1")}) {publish("event-v2");verify(control,"event-v1")}
        }) {val cleaned=json(post("$endpoint/native/event-cleanup",mapOf("nonce" to control)));check(cleaned["calls"].asInt()==0)}
    }
}
