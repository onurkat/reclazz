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

class RemoveMethodTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("Remove method", config, agentClient, httpVerifier) {

    override fun run(): TestResult {
        val start = System.currentTimeMillis()
        return try {
            agentClient.clearEvents()

            writeTemplate(
                "${config.srcDir}/com/onurkat/reclazztest/services/TestService.java",
                "TestService_removeMethod.java.txt"
            )

            val compileEvent = agentClient.waitForCompile(config.eventTimeoutMs)
            if (compileEvent == null) {
                return result(start, TestStatus.FAIL, "Timeout waiting for COMPILE event")
            }

            val reloadEvent = agentClient.waitForReload(config.eventTimeoutMs, "TestService")
            if (reloadEvent == null) {
                return result(start, TestStatus.FAIL, "Timeout waiting for RELOAD event")
            }

            Thread.sleep(config.settleDelayMs)

            val httpResult = httpVerifier.get("${config.testEndpointBase}/removable")
            if (!config.enhancedMode) {
                // Companion mode: existing callers keep the previous
                // implementation of removed methods (documented) — the
                // endpoint must still answer with the old body.
                return if (httpResult.statusCode == 200 && httpResult.body == "removable-v1") {
                    result(start, TestStatus.PASS,
                        "companion mode: removed method keeps old impl for existing callers (documented)")
                } else {
                    result(start, TestStatus.FAIL,
                        "companion expectation violated: got ${httpResult.statusCode}: ${httpResult.body}")
                }
            }

            // Enhanced mode: calling removed method should result in error/404
            if (httpResult.statusCode == 404 || httpResult.statusCode == 500) {
                result(start, TestStatus.PASS)
            } else {
                result(start, TestStatus.FAIL,
                    "Expected 404/500 for removed method, got ${httpResult.statusCode}: ${httpResult.body}")
            }
        } catch (e: Exception) {
            result(start, TestStatus.ERROR, e.message ?: e.toString())
        }
    }
}
