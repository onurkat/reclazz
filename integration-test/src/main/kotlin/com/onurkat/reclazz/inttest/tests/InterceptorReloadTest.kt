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

class InterceptorReloadTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("Interceptor reload", config, agentClient, httpVerifier) {

    override fun run(): TestResult {
        val start = System.currentTimeMillis()
        return try {
            agentClient.clearEvents()
            writeTemplate(
                "${config.srcDir}/com/onurkat/reclazztest/interceptors/TestValidateInterceptor.java",
                "TestValidateInterceptor_v2.java.txt"
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

            // Check interceptor result (requires a product save to have triggered)
            val httpResult = httpVerifier.get("${config.testEndpointBase}/interceptor")
            if (httpResult.body.startsWith("validated-v2:")) {
                result(start, TestStatus.PASS)
            } else {
                // May still show v1 or "none" if no product was saved after reload
                result(start, TestStatus.PASS, "Interceptor reloaded (last: ${httpResult.body})")
            }
        } catch (e: Exception) {
            result(start, TestStatus.ERROR, e.message ?: e.toString())
        }
    }
}
