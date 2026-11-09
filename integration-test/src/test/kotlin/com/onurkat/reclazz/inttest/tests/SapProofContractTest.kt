package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.http.HttpResult
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

fun main() {
    val nonce = "unique-test-save"
    val expected = "validated-v2:reclazz-probe-$nonce|calls=1"
    val response = AtomicReference(HttpResult(200, expected))
    val received = AtomicReference("")
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/interceptor-save") { exchange ->
        received.set(exchange.requestMethod + " " + exchange.requestBody.bufferedReader().readText())
        val result = response.get()
        val bytes = result.body.toByteArray()
        exchange.sendResponseHeaders(result.statusCode, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
        val client = HttpVerifier("http://127.0.0.1:${server.address.port}", 2000)
        val cases = listOf(
            HttpResult(200, expected) to true,
            HttpResult(500, expected) to false,
            HttpResult(404, "") to false,
            HttpResult(200, "none") to false,
            HttpResult(200, expected.replace("v2", "v1")) to false,
            HttpResult(200, expected.replace(nonce, "older-save")) to false,
            HttpResult(200, expected.replace("calls=1", "calls=2")) to false,
            HttpResult(200, expected.replace("calls=1", "calls=0")) to false,
        )
        for ((value, pass) in cases) {
            response.set(value)
            val actual = client.post("/interceptor-save", mapOf("nonce" to nonce))
            check(received.get() == "POST nonce=$nonce")
            check((interceptorSaveFailure(actual, nonce) == null) == pass) { "Wrong verdict for $value" }
        }
        println("PASS: 8 real HTTP proof cases (status, version, nonce, exact invocation count)")
        val mappingBody="nonce=$nonce;base=validated-v1:reclazz-probe-$nonce|calls=1;addedCalls=1;calledByCurrent=true;ownedRecords=0;bean=true;definition=true;registered=true;ownedRegistered=1;total=5;xmlOwners=2;ownedDefinitions=2"
        val mappingCases=listOf(
            HttpResult(200,mappingBody) to true,
            HttpResult(500,mappingBody) to false,
            HttpResult(200,mappingBody.replace("addedCalls=1","addedCalls=0")) to false,
            HttpResult(200,mappingBody.replace("addedCalls=1","addedCalls=2")) to false,
            HttpResult(200,mappingBody.replace("registered=true","registered=false")) to false,
            HttpResult(200,mappingBody.replace("calledByCurrent=true","calledByCurrent=false")) to false,
            HttpResult(200,mappingBody.replace("ownedRecords=0","ownedRecords=1")) to false,
            HttpResult(200,mappingBody.replace("nonce=$nonce","nonce=stale")) to false,
            HttpResult(200,mappingBody+";addedCalls=0") to false,
            HttpResult(200,mappingBody.replace("ownedDefinitions=2","ownedDefinitions=1")) to false,
            HttpResult(200,mappingBody.replace("xmlOwners=2","xmlOwners=0").replace("ownedDefinitions=2","ownedDefinitions=0")) to false)
        for((value,pass) in mappingCases) {
            response.set(value)
            val actual=client.post("/interceptor-save",mapOf("nonce" to nonce))
            check((mappingSaveFailure(actual,nonce)==null)==pass) { "Wrong mapping verdict: $value" }
        }
        println("PASS: 11 real HTTP mapping proof cases (actual count, registry identity, owned records, nonce, every XML owner)")
        val editBody=mappingBody+";editedBean=true;mappingIdentity=42;mappingType=GenericItem;oldCalls=0;newCalls=1;oldRegistered=0;newRegistered=1"
        val editCases=listOf(
            editBody to true,
            editBody.replace("oldCalls=0","oldCalls=1") to false,
            editBody.replace("newCalls=1","newCalls=0") to false,
            editBody.replace("newCalls=1","newCalls=2") to false,
            editBody.replace("oldRegistered=0","oldRegistered=1") to false,
            editBody.replace("newRegistered=1","newRegistered=0") to false,
            editBody.replace("mappingIdentity=42","mappingIdentity=43") to false,
            editBody.replace("mappingType=GenericItem","mappingType=Product") to false)
        for((body,pass) in editCases) {
            response.set(HttpResult(200,body))
            val actual=client.post("/interceptor-save",mapOf("nonce" to nonce))
            check((mappingEditFailure(actual,nonce,"GenericItem",true,"42")==null)==pass) { "Wrong edit verdict: $body" }
        }
        println("PASS: 8 real HTTP edit proof cases (old/new calls, old/new registration, stable identity and final type)")
        val proof="sameRegistry=true;modelTotal=5;contextTotal=5;xmlOwners=2;ownerMatches=2"
        val identityCases=listOf(HttpResult(200,proof) to true, HttpResult(500,proof) to false,
            HttpResult(200,proof.replace("sameRegistry=true","sameRegistry=false")) to false,
            HttpResult(200,proof.replace("ownerMatches=2","ownerMatches=1")) to false,
            HttpResult(200,proof.replace("xmlOwners=2","xmlOwners=0")) to false,
            HttpResult(200,"sameRegistry=true") to false,
            HttpResult(200,proof+";sameRegistry=true") to false)
        for((value,pass) in identityCases) {
            response.set(value)
            val actual=client.post("/interceptor-save",mapOf("nonce" to nonce))
            check((registryIdentityFailure(actual)==null)==pass) { "Wrong identity verdict: $value" }
        }
        println("PASS: 7 real HTTP registry identity proof cases (all owners, status, missing/duplicate fields)")

    } finally { server.stop(0) }
}
