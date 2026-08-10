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

class CacheEvictionTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("Cache eviction", config, agentClient, httpVerifier) {

    override fun run(): TestResult {
        val start = System.currentTimeMillis()
        return try {
            // Pre-populate the cache
            httpVerifier.get("${config.testEndpointBase}/cache", mapOf("key" to "abc"))

            // Now change the service
            agentClient.clearEvents()
            writeTemplate(
                "${config.srcDir}/com/onurkat/reclazztest/services/CacheTestService.java",
                "CacheTestService_v2.java.txt"
            )

            val compileEvent = agentClient.waitForCompile(config.eventTimeoutMs)
            if (compileEvent == null) {
                return result(start, TestStatus.FAIL, "Timeout waiting for COMPILE event")
            }

            val reloadEvent = agentClient.waitForReload(config.eventTimeoutMs)
            if (reloadEvent == null) {
                return result(start, TestStatus.FAIL, "Timeout waiting for RELOAD event")
            }

            Thread.sleep(config.settleDelayMs)

            val httpResult = httpVerifier.get("${config.testEndpointBase}/cache", mapOf("key" to "abc"))
            if (httpResult.body == "cached-v2:abc") {
                result(start, TestStatus.PASS)
            } else {
                result(start, TestStatus.FAIL, "Expected 'cached-v2:abc', got '${httpResult.body}'")
            }
        } catch (e: Exception) {
            result(start, TestStatus.ERROR, e.message ?: e.toString())
        }
    }
}
