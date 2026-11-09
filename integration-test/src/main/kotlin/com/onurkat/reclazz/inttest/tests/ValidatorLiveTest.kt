package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier

abstract class ValidatorLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier,private val parameter:Boolean)
    :BatchLiveTest(if(parameter) "Validator constraint parameter" else "Validator body",c,a,h) {
    override fun scenario() {
        val before=json(get("$endpoint/validation",mapOf("value" to "four")))
        check(before["provider"].asText().startsWith("org.hibernate.validator.")) { "Native provider not observed: $before" }
        fun verify(value:String,violations:Int,message:String) {
            pollJson("$endpoint/validation",mapOf("value" to value)) {
                it["violations"].asInt()==violations && it["message"].asText()==message &&
                    it["heldIdentity"]==before["heldIdentity"] && it["factoryIdentity"]==before["factoryIdentity"]
            }
        }
        fun baseline() {verify("four",1,"validator-v1:limit-v1");verify("ok",0,"none")}
        baseline()
        val relative="src/com/onurkat/reclazztest/batch/"+if(parameter) "ValidatedPayload.java" else "ProbeValidator.java"
        val baseline=original(relative).toString(Charsets.UTF_8)
        val changed=if(parameter) replaceOnce(baseline,"max = 3, message = \"limit-v1\"","max = 5, message = \"limit-v2\"")
            else replaceOnce(baseline,"validator-v1:","validator-v2:")
        javaEdit(relative,changed,{baseline()}) {
            if(parameter) {verify("four",0,"none");verify("too-long",1,"validator-v1:limit-v2")}
            else verify("four",1,"validator-v2:limit-v1")
            verify("ok",0,"none")
        }
    }
}
class ValidatorBodyLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):ValidatorLiveTest(c,a,h,false)
class ValidatorParameterLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):ValidatorLiveTest(c,a,h,true)
