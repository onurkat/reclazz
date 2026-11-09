package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier

class SpringXmlNewBeanLiveTest(c: TestConfig,a: AgentEventClient,h: HttpVerifier): BatchLiveTest("Spring XML new bean",c,a,h) {
    override fun scenario() {
        val relative="resources/reclazztest-batch-spring.xml"
        val before=json(get("$endpoint/xml"));check(before.isArray && before.size()>0)
        check(before.all { !it["addedDefinition"].asBoolean() && it["added"].asText()=="absent" })
        val count=before.size()
        fun baseline() { pollJson("$endpoint/xml") { rows -> rows.size()==count && rows.withIndex().all { (i,row) ->
            !row["addedDefinition"].asBoolean() && row["added"].asText()=="absent" && row["stableIdentity"]==before[i]["stableIdentity"]
        } } }
        val changed=replaceOnce(original(relative).toString(Charsets.UTF_8),"</beans>",
            "<bean id=\"reclazzBatchAddedBean\" class=\"com.onurkat.reclazztest.batch.XmlProbe\"><property name=\"value\" value=\"added-v2\"/></bean>\n</beans>")
        fixtureEdit(relative,changed.toByteArray(),{p,b->xmlWrite(p,b,count,listOf(p.fileName.toString(),"reclazzBatch"))},
            {baseline()},beforeRestore={check(json(post("$endpoint/xml-cleanup")).size()==count)}) {
            pollJson("$endpoint/xml") { rows -> rows.size()==count && rows.withIndex().all { (i,row) ->
                row["addedDefinition"].asBoolean() && row["added"].asText()=="added-v2" && row["stableIdentity"]==before[i]["stableIdentity"]
            } }
        }
    }
}
