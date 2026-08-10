/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.http

import okhttp3.OkHttpClient
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

    fun get(path: String, queryParams: Map<String, String> = emptyMap()): HttpResult {
        // Callers pass either an absolute URL (config.testEndpointBase already
        // contains baseUrl) or a bare path — only prefix the latter.
        val absolute = if (path.startsWith("http")) path else "$baseUrl$path"
        val urlBuilder = StringBuilder(absolute)
        if (queryParams.isNotEmpty()) {
            urlBuilder.append("?")
            urlBuilder.append(queryParams.entries.joinToString("&") { "${it.key}=${it.value}" })
        }

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            HttpResult(response.code, response.body?.string() ?: "")
        }
    }
}
