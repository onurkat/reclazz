package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEvent
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpResult
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

internal fun requireNewOccResponse(response:HttpResult,sourceNonce:String,requestNonce:String) {
    check(response.statusCode==200) {"New native OCC controller did not answer200: HTTP${response.statusCode}"}
    val node=batchJson.readTree(response.body)
    check(node.path("sourceNonce").asText()==sourceNonce&&node.path("requestNonce").asText()==requestNonce) {"Wrong new-controller nonce"}
}
internal fun newOccRegistration(events:List<AgentEvent>,bean:String,owner:String):Boolean = events.any {
    it.level=="OK"&&it.message.startsWith("New bean registered: '$bean' ($owner),")
}
internal fun newOccMappings(events:List<AgentEvent>,simple:String):Boolean = events.any {
    it.level=="OK"&&it.message=="Spring MVC mappings registered for the new controller $simple."
}
internal fun requireNewOccFacts(facts:JsonNode,nonce:String,start:Long,present:Boolean) {
    check(facts.path("nonce").asText()==nonce&&facts.path("jvmStart").isIntegralNumber&&facts.path("jvmStart").asLong()==start)
    check(facts.path("beanCount").isIntegralNumber&&facts.path("handlerCount").isIntegralNumber)
    if(!present) {check(facts.path("beanCount").asInt()==0&&facts.path("handlerCount").asInt()==0);return}
    check(facts.path("beanCount").asInt()>0&&facts.path("handlerCount").asInt()>0)
    val rows=facts.path("contexts");check(rows.isArray)
    val owners=rows.filter {it.path("localBean").asBoolean()}
    check(owners.isNotEmpty()&&owners.all {it.path("context").asText()=="de.hybris.platform.spring.ctx.TenantIgnoreXmlWebApplicationContext"&&it.path("definingLoaderMatches").isBoolean&&!it.path("definingLoaderMatches").asBoolean()}) {"New controller registered outside native OCC scan owners"}
    val handlers=rows.flatMap {it.path("handlers").toList()}
    check(handlers.isNotEmpty()&&handlers.all {it.path("mappingClass").asText()=="de.hybris.platform.commercewebservices.core.request.mapping.handler.CommerceHandlerMapping"&&it.path("handlerClass").asText()=="com.onurkat.reclazztest.occ.ReclazzNewOcc$nonce"&&it.path("bean").asText()=="reclazzC22$nonce"})
}

class NewOccControllerEvidenceTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):
    BatchLiveTest("New OCC controller after startup",c,a,h) {
    override fun scenario() {
        val nonce=UUID.randomUUID().toString().replace("-","");val requestNonce=UUID.randomUUID().toString().replace("-","")
        val simple="ReclazzNewOcc$nonce";val owner="com.onurkat.reclazztest.occ.$simple";val bean="reclazzC22$nonce"
        val source=Path.of(config.extPath,"src/com/onurkat/reclazztest/occ/$simple.java")
        val output=Path.of(config.extPath,"classes/com/onurkat/reclazztest/occ/$simple.class")
        val route="$occ/reclazz-c22-$nonce";val params=mapOf("requestNonce" to requestNonce)
        fun facts()=json(get("$endpoint/new-occ/facts",mapOf("nonce" to nonce)))
        val initialFacts=facts();val start=initialFacts.path("jvmStart").asLong();check(start>0);requireNewOccFacts(initialFacts,nonce,start,false)
        check(!Files.exists(source)&&!Files.exists(output)) {"New controller already exists before save"}
        val occControl=json(get("$occ/reclazzprobe"));check(occControl.path("code").asText()=="v0"&&occControl.path("label").asText()=="label0")
        val probeControl=json(get("$endpoint/gates"))
        fun controls() {check(json(get("$occ/reclazzprobe"))==occControl);check(json(get("$endpoint/gates"))==probeControl)}
        val absent=get(route,params);check(absent.statusCode==404)
        check(runCatching {requireNewOccResponse(absent,nonce,requestNonce)}.isFailure)
        println("NEW_OCC_WITHHELD_RED HTTP404 nonce=$nonce initialFacts=$initialFacts")
        val template=javaClass.classLoader.getResourceAsStream("templates/c22/ReclazzNewOccController.java.txt")!!.use {it.readBytes().toString(Charsets.UTF_8)}
        val code=template.replace("__NONCE__",nonce);var compiled=false;val mark=agentClient.cursor()
        withRestore({
            Files.writeString(source,code)
            compiled=agentClient.waitForCompile(config.eventTimeoutMs,source.fileName.toString(),mark)!=null
            check(compiled) {"New controller COMPILE receipt absent"}
            agentClient.waitForEvent("OK",config.eventTimeoutMs,"New bean registered: '$bean' ($owner)",mark)
            agentClient.waitForEvent("OK",config.eventTimeoutMs,"Spring MVC mappings registered for the new controller $simple.",mark)
            val events=agentClient.getEvents().filter {it.sequence>mark}
            println("NEW_OCC_REGISTRATION compiled=$compiled registered=${newOccRegistration(events,bean,owner)} mapped=${newOccMappings(events,simple)} events=$events")
            val after=facts();println("NEW_OCC_NATIVE_FACTS $after")
            val actual=httpVerifier.poll(5000,"New native OCC controller",{budget->httpVerifier.get(route,params,budget)}) {response->runCatching {requireNewOccResponse(response,nonce,requestNonce)}.isSuccess}
            requireNewOccResponse(actual,nonce,requestNonce);check(newOccRegistration(events,bean,owner)&&newOccMappings(events,simple))
            requireNewOccFacts(after,nonce,start,true);controls();println("NEW_OCC_ADDED HTTP200 body=${actual.body} sameJvm=$start")
        }) {
            restoreAll({
                // Source deletion is observed separately from native registration; no synthetic unregister.
                if(Files.exists(source)) {check(Files.readString(source)==code);Files.delete(source)}
                val afterDelete=get(route,params);println("NEW_OCC_AFTER_DELETE HTTP${afterDelete.statusCode} body=${afterDelete.body} facts=${facts()}")
                controls()
            },{
                // COMPILE means the native compiler has written this exact class; no writer is interrupted.
                if(compiled)Files.deleteIfExists(output)
                check(!Files.exists(source)&&(!compiled||!Files.exists(output)))
                println("NEW_OCC_FILES_RESTORED sourceAbsent=${!Files.exists(source)} classAbsent=${!Files.exists(output)}")
            })
        }
    }
}
