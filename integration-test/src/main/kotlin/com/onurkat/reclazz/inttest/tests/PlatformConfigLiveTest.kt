package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEvent
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.nio.file.Path

abstract class PropertyLiveTest(name:String,c:TestConfig,a:AgentEventClient,h:HttpVerifier):BatchLiveTest(name,c,a,h) {
    protected fun properties(path:Path,bytes:ByteArray,key:String) = resourceWrite(path,bytes,listOf(key),
        {it.level=="INFO" && it.message.startsWith("Applied ") && key in it.message})
}
class PlatformConfigLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):PropertyLiveTest("Platform config",c,a,h) {
    override fun scenario() {
        val key="reclazztest.batch.value";val relative="project.properties"
        fun verify(value:String) {pollJson("$endpoint/config") {it["value"].asText()==value && it["stable"].asText()=="stable-v1"}}
        verify("config-v1")
        val changed=replaceOnce(original(relative).toString(Charsets.UTF_8),"$key=config-v1","$key=config-v2")
        fixtureEdit(relative,changed.toByteArray(),{p,b->properties(p,b,key)},{verify("config-v1")}) {verify("config-v2")}
    }
}
class OccPriorityLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):PropertyLiveTest("OCC priority winner",c,a,h) {
    override fun scenario() {
        val key="reclazztest.occ.winnerA.priority";val relative="project.properties"
        fun verify(value:String) {
            pollGet("$occ/reclazzwinner",expectedBody=value)
            pollJson("$occ/reclazzprobe") {it["code"]?.asText()=="v0" && it["label"]?.asText()=="label0"}
        }
        verify("B")
        val changed=replaceOnce(original(relative).toString(Charsets.UTF_8),"$key=10","$key=30")
        fixtureEdit(relative,changed.toByteArray(),{p,b->
            val mark=agentClient.cursor();properties(p,b,key)
            check(agentClient.waitForEvent("OK",config.eventTimeoutMs,"OCC handler priorities read again for",mark)!=null)
        },{verify("B")}) {verify("A")}
    }
}
