package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier

class OccFieldSetLiveTest(c: TestConfig,a: AgentEventClient,h: HttpVerifier): BatchLiveTest("OCC field set",c,a,h) {
    override fun scenario() {
        val relative="resources/occ/v2/reclazztestocc/web/spring/reclazztestocc-web-spring.xml"
        val count=owners("reclazztestocc-web-spring.xml").size()
        fun verify(edited: Boolean) {
            pollJson("$endpoint/resource-owners",mapOf("file" to "reclazztestocc-web-spring.xml")) { rows ->
                rows.size()==count && rows.all { it["basic"].asText()==if(edited) "code,label" else "code" }
            }
            pollJson("$occ/reclazzprobe",mapOf("fields" to "BASIC")) {
                it["code"]?.asText()=="v0" && if(edited) it["label"]?.asText()=="label0" else !it.has("label")
            }
            pollJson("$occ/reclazzprobe",mapOf("fields" to "DEFAULT")) { it["code"]?.asText()=="v0" && it["label"]?.asText()=="label0" }
        }
        verify(false)
        val changed=replaceOnce(original(relative).toString(Charsets.UTF_8),"key=\"BASIC\" value=\"code\"","key=\"BASIC\" value=\"code,label\"")
        fixtureEdit(relative,changed.toByteArray(),{p,b->xmlWrite(p,b,count,listOf(p.fileName.toString(),"reclazzProbeWsDTOFieldSetLevelMapping"))},{verify(false)}) {verify(true)}
    }
}
