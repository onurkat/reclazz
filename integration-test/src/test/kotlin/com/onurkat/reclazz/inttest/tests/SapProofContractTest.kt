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
    } finally { server.stop(0) }
}
