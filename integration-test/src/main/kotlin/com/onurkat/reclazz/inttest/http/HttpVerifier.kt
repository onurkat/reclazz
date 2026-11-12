/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.http

import okhttp3.OkHttpClient
import okhttp3.FormBody
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class HttpResult(
    val statusCode: Int,
    val body: String
)

class HttpVerifier(private val baseUrl: String, timeoutMs: Long) {

    private val client: OkHttpClient

    init {
        // Trust all certs (Hybris uses self-signed)
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())

        client = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    fun post(path: String, parameters: Map<String, String> = emptyMap(), budgetMs: Long? = null): HttpResult {
        val absolute = if (path.startsWith("http")) path else "$baseUrl$path"
        val form = FormBody.Builder()
        parameters.forEach { (key, value) -> form.add(key, value) }
        val request = Request.Builder().url(absolute).post(form.build()).build()
        val call = client.newCall(request)
        if (budgetMs != null) call.timeout().timeout(budgetMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        return call.execute().use { response ->
            HttpResult(response.code, response.body?.string() ?: "")
        }
    }

    fun get(path: String, queryParams: Map<String, String> = emptyMap(), budgetMs: Long? = null, headers: Map<String, String> = emptyMap()): HttpResult {
        // Callers pass either an absolute URL (config.testEndpointBase already
        // contains baseUrl) or a bare path, only prefix the latter.
        val absolute = if (path.startsWith("http")) path else "$baseUrl$path"
        val urlBuilder = StringBuilder(absolute)
        if (queryParams.isNotEmpty()) {
            urlBuilder.append("?")
            urlBuilder.append(queryParams.entries.joinToString("&") { "${it.key}=${it.value}" })
        }

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()

        val call = client.newCall(request)
        if (budgetMs != null) call.timeout().timeout(budgetMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        return call.execute().use { response ->
            HttpResult(response.code, response.body?.string() ?: "")
        }
    }

    /** CMS render proof needs the actual MIME header from the same response. */
    fun getWithContentType(path: String, queryParams: Map<String, String> = emptyMap(), budgetMs: Long? = null): Pair<HttpResult, String?> {
        val absolute = if (path.startsWith("http")) path else "$baseUrl$path"
        val query = if (queryParams.isEmpty()) "" else "?" + queryParams.entries.joinToString("&") { "${it.key}=${it.value}" }
        val call = client.newCall(Request.Builder().url(absolute + query).get().build())
        if (budgetMs != null) call.timeout().timeout(budgetMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        return call.execute().use { response ->
            HttpResult(response.code, response.body?.string() ?: "") to response.header("Content-Type")
        }
    }

    /** The remaining call budget is shared with the poll deadline, not added to it. */
    fun poll(timeoutMs: Long, description: String, request: (Long) -> HttpResult, accepts: (HttpResult) -> Boolean): HttpResult {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var last: HttpResult? = null
        var lastError: String? = null
        while (true) {
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) error("$description timed out; last HTTP ${last?.statusCode}: ${last?.body}; last error: $lastError")
            try {
                last = request(remaining)
                if (accepts(last)) return last
            } catch (e: Exception) { lastError = e.message ?: e.javaClass.simpleName }
            val pause = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceIn(0, 100)
            if (pause > 0) Thread.sleep(pause)
        }
    }

}
