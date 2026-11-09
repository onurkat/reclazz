package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier

class OccRequestMappingLiveTest(c: TestConfig,a: AgentEventClient,h: HttpVerifier): BatchLiveTest("OCC request mapping",c,a,h) {
    override fun scenario() {
        val relative="src/com/onurkat/reclazztest/occ/ReclazzProbeOccController.java"
        fun baseline() {
            pollJson("$occ/reclazzprobe") { it["code"]?.asText()=="v0" }
            pollGet("$occ/reclazzprobe/edited",expectedStatus=404)
        }
        baseline()
        val changed=replaceOnce(replaceOnce(original(relative).toString(Charsets.UTF_8),"@GetMapping\n","@GetMapping(\"/edited\")\n"),
            "data.setCode(\"v0\");","data.setCode(\"route-v2\");")
        javaEdit(relative,changed,{baseline()}) {
            pollJson("$occ/reclazzprobe/edited") { it["code"]?.asText()=="route-v2" && it["label"]?.asText()=="label0" }
            pollGet("$occ/reclazzprobe",expectedStatus=404)
        }
    }
}
