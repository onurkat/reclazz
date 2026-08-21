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
 * Removing the last enum constant on a running JVM.
 *
 * The tail is the one place an enum can shrink without renumbering anything:
 * OPEN and CLOSED keep their ordinals, values() stops returning ARCHIVED, and
 * valueOf("ARCHIVED") throws, which is what removal means. The baseline
 * restore before the next scenario adds ARCHIVED back as a plain append, so
 * the round trip exercises both directions of the tail.
 */
class EnumTailRemoveTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("Enum tail removal", config, agentClient, httpVerifier) {

    override fun run(): TestResult {
        val start = System.currentTimeMillis()
        return try {
            // The full enum answers first, so a stale cache cannot fake a pass.
            val before = httpVerifier.get("${config.testEndpointBase}/enum-status")
            if (before.body != "OPEN:0,CLOSED:1,ARCHIVED:2,ARCHIVED=OK") {
                return result(start, TestStatus.FAIL,
                    "Baseline enum is not in its expected state: '${before.body}'")
            }

            agentClient.clearEvents()
            writeTemplate(
                "${config.srcDir}/com/onurkat/reclazztest/services/TestStatus.java",
                "TestStatus_tailremoved.java.txt"
            )

            agentClient.waitForCompile(config.eventTimeoutMs, "TestStatus.java")
                ?: return result(start, TestStatus.FAIL, "Timeout waiting for COMPILE event")
            agentClient.waitForReload(config.eventTimeoutMs, "TestStatus")
                ?: return result(start, TestStatus.FAIL, "Timeout waiting for RELOAD event")

            Thread.sleep(config.settleDelayMs)

            val after = httpVerifier.get("${config.testEndpointBase}/enum-status")
            if (after.body == "OPEN:0,CLOSED:1,ARCHIVED=GONE") {
                result(start, TestStatus.PASS)
            } else {
                result(start, TestStatus.FAIL,
                    "Expected 'OPEN:0,CLOSED:1,ARCHIVED=GONE', got '${after.body}'")
            }
        } catch (e: Exception) {
            result(start, TestStatus.ERROR, e.message ?: e.toString())
        }
    }
}
