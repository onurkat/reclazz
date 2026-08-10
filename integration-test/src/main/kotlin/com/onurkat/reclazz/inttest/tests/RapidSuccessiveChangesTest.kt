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

class RapidSuccessiveChangesTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("Rapid successive changes", config, agentClient, httpVerifier) {

    override fun run(): TestResult {
        val start = System.currentTimeMillis()
        return try {
            agentClient.clearEvents()

            // Write v2 then v3 in rapid succession (200ms apart)
            writeTemplate(
                "${config.srcDir}/com/onurkat/reclazztest/services/TestService.java",
                "TestService_ping_v2.java.txt"
            )
            Thread.sleep(200)
            writeTemplate(
                "${config.srcDir}/com/onurkat/reclazztest/services/TestService.java",
                "TestService_ping_v3.java.txt"
            )

            // Wait for compile and reload
            val compileEvent = agentClient.waitForCompile(config.eventTimeoutMs)
            if (compileEvent == null) {
                return result(start, TestStatus.FAIL, "Timeout waiting for COMPILE event")
            }

            val reloadEvent = agentClient.waitForReload(config.eventTimeoutMs)
            if (reloadEvent == null) {
                return result(start, TestStatus.FAIL, "Timeout waiting for RELOAD event")
            }

            Thread.sleep(config.settleDelayMs)

            val httpResult = httpVerifier.get("${config.testEndpointBase}/ping")
            if (httpResult.body == "pong-v3") {
                result(start, TestStatus.PASS)
            } else {
                result(start, TestStatus.FAIL, "Expected 'pong-v3', got '${httpResult.body}'")
            }
        } catch (e: Exception) {
            result(start, TestStatus.ERROR, e.message ?: e.toString())
        }
    }
}
