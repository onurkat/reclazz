/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult
import com.onurkat.reclazz.inttest.report.TestStatus

/**
 * An edited @Cacheable annotation takes effect, not just the edited body.
 *
 * Spring caches the parsed cache operations under the method, which a
 * redefinition does not change, so a condition="false" used to leave the old
 * always-cache behaviour serving while the source said otherwise. The proof
 * is two calls after the reload answering differently: with caching disabled
 * the counter moves on every call.
 */
class CacheableConditionTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("Cacheable condition change", config, agentClient, httpVerifier) {

    override fun run(): TestResult {
        val start = System.currentTimeMillis()
        return try {
            // Warm the cache so stale metadata would definitely serve from it.
            httpVerifier.get("${config.testEndpointBase}/cache", mapOf("key" to "cond"))

            reloadTemplate(
                "${config.srcDir}/com/onurkat/reclazztest/services/CacheTestService.java",
                "CacheTestService_condition.java.txt"
            )

            var second = com.onurkat.reclazz.inttest.http.HttpResult(0, "")
            val first = httpVerifier.poll(config.eventTimeoutMs, "Cache condition pair", { budget ->
                val began = System.nanoTime()
                val value = httpVerifier.get("${config.testEndpointBase}/cache", mapOf("key" to "cond"), budget)
                val remaining = budget - java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
                second = httpVerifier.get("${config.testEndpointBase}/cache", mapOf("key" to "cond"), remaining.coerceAtLeast(1))
                value
            }) { it.statusCode == 200 && second.statusCode == 200 && it.body.startsWith("nocache-") && it.body != second.body }

            when {
                !first.body.startsWith("nocache-") ->
                    result(start, TestStatus.FAIL,
                        "Expected the reloaded body, got '${first.body}'")
                first.body == second.body ->
                    result(start, TestStatus.FAIL,
                        "condition=\"false\" is not live: two calls answered identically "
                            + "('${first.body}'), so the old cache metadata still serves")
                else -> result(start, TestStatus.PASS)
            }
        } catch (e: Exception) {
            result(start, TestStatus.ERROR, e.message ?: e.toString())
        }
    }
}
