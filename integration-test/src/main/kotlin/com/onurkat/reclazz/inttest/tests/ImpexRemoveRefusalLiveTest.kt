package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult
import com.onurkat.reclazz.inttest.report.TestStatus

class ImpexRemoveRefusalLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):BatchLiveTest("ImpEx REMOVE refusal",c,a,h) {
    override fun run():TestResult=if(json(get("$endpoint/gates"))["impexAllowRemove"].asBoolean())
        result(System.currentTimeMillis(),TestStatus.SKIP,"requires impexAllowRemove=false; destructive trial not attempted") else super.run()
    override fun scenario() {
        val file="reclazz-batch-refusal.impex";val relative="resources/impex/$file"
        fun verify() {pollJson("$endpoint/impex-refusal") {
            it["titleCount"].asInt()==1 && it["title"].asText()=="Reclazz Test Title v1" && it["baitCount"].asInt()==0
        }}
        verify()
        val changed="REMOVE Title;code[unique=true]\n;reclazzTestTitle\nINSERT_UPDATE Title;code[unique=true];name[lang=en]\n;reclazzBatchRemoveBait;should-not-exist\n"
        val prefix="ImpEx not imported: $file has a REMOVE or unresolved macro header at line 1."
        fixtureEdit(relative,changed.toByteArray(),{p,b->
            val refusal=b.toString(Charsets.UTF_8).startsWith("REMOVE ")
            resourceWrite(p,b,listOf(file),{it.level==(if(refusal) "WARN" else "INFO") &&
                if(refusal) it.message.startsWith(prefix) else it.message=="ImpEx file is empty, skipping: $file"},
                {refusal && it.level=="WARN" && it.message.startsWith(prefix)})
        },{verify()}) {verify()}
    }
}
