package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier

/** A warmed native DataMapper must stop calling an added accessor after source restoration. */
class OrikaPropertyRollbackLiveTest(c: TestConfig,a: AgentEventClient,h: HttpVerifier): BatchLiveTest("OCC property rollback",c,a,h) {
    override fun scenario() {
        val source="src/com/onurkat/reclazztest/batch/OrikaRollbackData.java"
        val target="src/com/onurkat/reclazztest/batch/OrikaRollbackWsDTO.java"
        val path="$occ/reclazzprobe/orika-rollback"
        fun baseline() {
            repeat(2) { pollJson(path,mapOf("fields" to "code")) { it["code"]?.asText()=="orika-code" && !it.has("orikaStage") } }
        }
        baseline()
        val changedTarget=replaceOnce(original(target).toString(Charsets.UTF_8),"    private String code;", """    private String code;
    private String orikaStage;
    public String getOrikaStage() { return orikaStage; }
    public void setOrikaStage(String value) { orikaStage = value; }""")
        val changedSource=replaceOnce(original(source).toString(Charsets.UTF_8),"    public String getCode()", "    public String getOrikaStage() { return \"stage-v2\"; }\n    public String getCode()")
        javaEdit(target,changedTarget,{baseline()}) {
            javaEdit(source,changedSource,{baseline()}) {
                repeat(3) { pollJson(path,mapOf("fields" to "code,orikaStage")) { it["code"]?.asText()=="orika-code" && it["orikaStage"]?.asText()=="stage-v2" } }
            }
        }
        baseline()
    }
}
