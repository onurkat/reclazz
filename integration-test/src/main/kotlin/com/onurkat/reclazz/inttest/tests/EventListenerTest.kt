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

class EventListenerTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("Event listener", config, agentClient, httpVerifier) {

    override fun run(): TestResult {
        val start = System.currentTimeMillis()
        return try {
            agentClient.clearEvents()
            writeTemplate(
                "${config.srcDir}/com/onurkat/reclazztest/services/EventTestService.java",
                "EventTestService_v2.java.txt"
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

            // Trigger event and check result
            val httpResult = httpVerifier.get("${config.testEndpointBase}/event")
            if (httpResult.body.startsWith("handled-v2:")) {
                result(start, TestStatus.PASS)
            } else {
                result(start, TestStatus.FAIL, "Expected 'handled-v2:...' prefix, got '${httpResult.body}'")
            }
        } catch (e: Exception) {
            result(start, TestStatus.ERROR, e.message ?: e.toString())
        }
    }
}
