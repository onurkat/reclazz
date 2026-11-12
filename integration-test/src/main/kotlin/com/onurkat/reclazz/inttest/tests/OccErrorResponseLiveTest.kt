package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpResult
import com.onurkat.reclazz.inttest.http.HttpVerifier

internal fun requireOccError(response:HttpResult,type:String) {
    check(response.statusCode==400) {"Native OCC error status: ${response.statusCode}"}
    val expected=batchJson.createObjectNode().apply {
        putArray("errors").addObject().put("type",type).put("message","The application has encountered an error")
    }
    check(batchJson.readTree(response.body)==expected) {"Wrong native OCC error: ${response.body}"}
}
internal fun requireOccErrorFacts(n:JsonNode) {
    check(n.path("success").asText()=="reclazz-c67-stable")
    for(key in listOf("sameBean","sameServingContext","localResolver"))check(n.path(key).isBoolean&&n.path(key).asBoolean()) {"OCC error fact $key false"}
    for(key in listOf("jvmStart","serviceIdentity","contextIdentity"))check(n.path(key).isIntegralNumber&&n.path(key).asLong()>0) {"Missing OCC $key"}
    check(n.path("resolver").asText()=="de.hybris.platform.webservicescommons.resolver.RestExceptionResolver")
    check(n.path("factory").asText()=="de.hybris.platform.webservicescommons.errors.factory.impl.DefaultWebserviceErrorFactory")
    check(n.path("converter").asText()=="de.hybris.platform.webservicescommons.errors.converters.ExceptionConverter")
}
internal fun requireOccErrorControls(success:JsonNode,control:HttpResult,baseline:JsonNode) {
    requireOccErrorFacts(success);check(success==baseline) {"OCC success/context/JVM changed"}
    requireOccError(control,"ReclazzOccControlError")
}

/** Body-only throw selection; the native resolver builds every observed error response. */
class OccErrorResponseLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier,
    private val withholdSave:Boolean=false):BatchLiveTest("Native OCC exception error response",c,a,h) {
    override fun scenario() {
        val relative="src/com/onurkat/reclazztest/batch/ProbeOccErrorService.java"
        fun read(path:String)=httpVerifier.get("$occ/reclazzerror/$path",budgetMs=config.eventTimeoutMs,headers=mapOf("Accept" to "application/json"))
        val baseline=json(read("success"));requireOccErrorFacts(baseline)
        fun verify(type:String,phase:String) {
            val response=read("target");requireOccError(response,type)
            val success=json(read("success"));val control=read("control")
            requireOccErrorControls(success,control,baseline)
            println("OCC_ERROR_NATIVE $phase HTTP${response.statusCode} body=${response.body} controlHTTP=${control.statusCode} control=${control.body} facts=$success")
        }
        verify("ReclazzOccFirstError","baseline")
        val withheld=read("target");requireOccError(withheld,"ReclazzOccFirstError")
        check(runCatching {requireOccError(withheld,"ReclazzOccSecondError")}.isFailure) {"WITHHELD_SAVE_FALSE_PASS"}
        println("OCC_ERROR_WITHHELD_RED actualHTTP=${withheld.statusCode} actualBody=${withheld.body}")
        if(withholdSave)withRestore({verify("ReclazzOccSecondError","v2")},{verify("ReclazzOccFirstError","restored")})
        else javaEdit(relative,replaceOnce(original(relative).toString(Charsets.UTF_8),
            "throw new ReclazzOccFirstException(", "throw new ReclazzOccSecondException("),
            {verify("ReclazzOccFirstError","restored")}) {verify("ReclazzOccSecondError","v2")}
        println("OCC_ERROR_OUTCOME NATIVE_THROW_TYPE_ROUND_TRIP")
    }
}
