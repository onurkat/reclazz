package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpResult
import com.onurkat.reclazz.inttest.http.HttpVerifier

internal fun mappingEditFailure(response: HttpResult, nonce: String, type: String, edited: Boolean, identity: String): String? {
    mappingSaveFailure(response, nonce)?.let { return it }
    val fields=mappingFields(response.body)
    val expected=mapOf("mappingIdentity" to identity,"mappingType" to type,"editedBean" to "true",
        "oldCalls" to if(edited) "0" else "1", "newCalls" to if(edited) "1" else "0",
        "oldRegistered" to if(edited) "0" else "1", "newRegistered" to if(edited) "1" else "0")
    return if(expected.all { (key,value)->fields[key]==value }) null else "Stale XML mapping edit: ${response.body}"
}

/** Same native mapping identity across actual ref and typeCode XML setters; each proof saves once. */
class InterceptorMappingEditLiveTest(config: TestConfig, agentClient: AgentEventClient, httpVerifier: HttpVerifier)
    : InterceptorMappingAddLiveTest(config,agentClient,httpVerifier,"Interceptor mapping edit") {
    override val setupTemplate = "MappingEdit_spring_v2.xml.txt"
    override fun verifyMapping() {
        val state=httpVerifier.get("$endpoint/state",budgetMs=config.eventTimeoutMs)
        check(state.statusCode==200) { "Mapping baseline HTTP ${state.statusCode}" }
        val identity=mappingFields(state.body).getValue("mappingIdentity")
        fun save(type: String, edited: Boolean) {
            awaitRequestedState(if (edited) "reclazzEditedMappingInterceptor" else "reclazzAddedMappingInterceptor",type)
            val nonce=java.util.UUID.randomUUID().toString()
            val response=httpVerifier.post("$endpoint/save",mapOf("nonce" to nonce),config.eventTimeoutMs)
            println("    Mapping edit proof: HTTP ${response.statusCode}: ${response.body}")
            mappingEditFailure(response,nonce,type,edited,identity)?.let { error(it) }
            verifyRegistry()
        }
        save("Product",false)
        writeXml(template("MappingEdit_spring_v3.xml.txt"))
        save("Product",true)
        writeXml(template("MappingEdit_spring_v4.xml.txt"))
        save("GenericItem",true)
    }
}
