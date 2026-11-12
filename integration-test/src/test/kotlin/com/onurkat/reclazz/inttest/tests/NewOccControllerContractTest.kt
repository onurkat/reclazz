package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEvent
import com.onurkat.reclazz.inttest.http.HttpResult

fun main() {
    var checks=0;val nonce="0".repeat(31)+"1";val request="0".repeat(31)+"2"
    val data="""{"sourceNonce":"$nonce","requestNonce":"$request"}"""
    requireNewOccResponse(HttpResult(200,data),nonce,request);checks++
    for(response in listOf(HttpResult(404,data),HttpResult(500,data),HttpResult(200,"{}"),HttpResult(200,data.replace(nonce,request)),HttpResult(200,data.replace(request,nonce)))) {
        check(runCatching {requireNewOccResponse(response,nonce,request)}.isFailure);checks++
    }
    val simple="ReclazzNewOcc$nonce";val owner="com.onurkat.reclazztest.occ.$simple";val bean="reclazzC22$nonce"
    val registered=AgentEvent("OK","New bean registered: '$bean' ($owner), dependencies injected, ready to serve.","",1)
    val mapped=AgentEvent("OK","Spring MVC mappings registered for the new controller $simple.","",2)
    check(newOccRegistration(listOf(registered),bean,owner));checks++
    check(newOccMappings(listOf(mapped),simple));checks++
    for(events in listOf(emptyList(),listOf(registered.copy(level="WARN")),listOf(registered.copy(message=registered.message.replace(owner,owner+"Other"))),listOf(AgentEvent("RELOAD","Reloaded $owner","",3)))) {check(!newOccRegistration(events,bean,owner));checks++}
    for(events in listOf(emptyList(),listOf(mapped.copy(level="WARN")),listOf(mapped.copy(message=mapped.message.replace(simple,simple+"Other"))),listOf(registered))) {check(!newOccMappings(events,simple));checks++}
    val absent="""{"nonce":"$nonce","jvmStart":123,"beanCount":0,"handlerCount":0,"contexts":[]}"""
    requireNewOccFacts(batchJson.readTree(absent),nonce,123,false);checks++
    val facts="""{"nonce":"$nonce","jvmStart":123,"beanCount":1,"handlerCount":1,"contexts":[{"localBean":true,"context":"de.hybris.platform.spring.ctx.TenantIgnoreXmlWebApplicationContext","definingLoaderMatches":false,"handlers":[{"mappingClass":"de.hybris.platform.commercewebservices.core.request.mapping.handler.CommerceHandlerMapping","handlerClass":"$owner","bean":"$bean","path":"/reclazz-c22-$nonce"}]}]}"""
    requireNewOccFacts(batchJson.readTree(facts),nonce,123,true);checks++
    for(text in listOf(facts.replace("definingLoaderMatches\":false","definingLoaderMatches\":true"),facts.replace("localBean\":true","localBean\":false"),facts.replace("ctx.TenantIgnoreXmlWebApplicationContext","ctx.CloseAwareApplicationContext"),facts.replace("jvmStart\":123","jvmStart\":456"),facts.replace("beanCount\":1","beanCount\":0"),facts.replace("handlerCount\":1","handlerCount\":0"),facts.replace("handler.CommerceHandlerMapping","handler.FakeMapping"),facts.replace(owner,owner+"Other"),facts.replace(bean,bean+"Other"))) {check(runCatching {requireNewOccFacts(batchJson.readTree(text),nonce,123,true)}.isFailure);checks++}
    check(runCatching {requireNewOccFacts(batchJson.readTree(facts),nonce,123,false)}.isFailure);checks++
    requireDefaultMembership(committedDefaultMembership.toList());check(committedDefaultMembership.size==62&&"NewOccControllerEvidenceTest" in committedDefaultMembership);checks++
    println("NEW_OCC_CONTROLLER_CONTRACT $checks checks PASS")
}
