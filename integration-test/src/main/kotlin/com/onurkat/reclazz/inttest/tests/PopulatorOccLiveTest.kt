package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier

class PopulatorOccLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):BatchLiveTest("Populator through OCC",c,a,h) {
    override fun scenario() {
        val relative="src/com/onurkat/reclazztest/batch/ProbePopulator.java"
        fun verify(value:String) {pollJson("$occ/reclazzpopulator") {it["code"]?.asText()==value && it["label"]?.asText()=="stable-label"}}
        verify("populator-v1")
        javaEdit(relative,replaceOnce(original(relative).toString(Charsets.UTF_8),"populator-v1","populator-v2"),
            {verify("populator-v1")}) {verify("populator-v2")}
    }
}
