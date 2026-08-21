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

            agentClient.clearEvents()
            writeTemplate(
                "${config.srcDir}/com/onurkat/reclazztest/services/CacheTestService.java",
                "CacheTestService_condition.java.txt"
            )

            agentClient.waitForCompile(config.eventTimeoutMs)
                ?: return result(start, TestStatus.FAIL, "Timeout waiting for COMPILE event")
            agentClient.waitForReload(config.eventTimeoutMs)
                ?: return result(start, TestStatus.FAIL, "Timeout waiting for RELOAD event")

            Thread.sleep(config.settleDelayMs)

            val first = httpVerifier.get("${config.testEndpointBase}/cache", mapOf("key" to "cond"))
            val second = httpVerifier.get("${config.testEndpointBase}/cache", mapOf("key" to "cond"))
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
