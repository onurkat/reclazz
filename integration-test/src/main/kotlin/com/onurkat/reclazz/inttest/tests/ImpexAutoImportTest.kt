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

class ImpexAutoImportTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("ImpEx auto-import", config, agentClient, httpVerifier) {

    override fun run(): TestResult {
        val start = System.currentTimeMillis()
        return try {
            agentClient.clearEvents()
            writeTemplate(
                "${config.impexDir}/test-data.impex",
                "test-data_v2.impex.txt"
            )

            // ImpEx import doesn't trigger COMPILE, wait for INFO event about import
            Thread.sleep(config.settleDelayMs * 2)

            val httpResult = httpVerifier.get("${config.testEndpointBase}/impex-title")
            if (httpResult.body == "Reclazz Test Title v2") {
                result(start, TestStatus.PASS)
            } else {
                result(start, TestStatus.FAIL, "Expected 'Reclazz Test Title v2', got '${httpResult.body}'")
            }
        } catch (e: Exception) {
            result(start, TestStatus.ERROR, e.message ?: e.toString())
        }
    }
}
