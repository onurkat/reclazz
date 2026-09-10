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

            val compileEvent = agentClient.waitForCompile(config.eventTimeoutMs, "TestValidateInterceptor.java")
            if (compileEvent == null) {
                return result(start, TestStatus.FAIL, "Timeout waiting for COMPILE event")
            }

            val reloadEvent = agentClient.waitForReload(config.eventTimeoutMs, "TestValidateInterceptor")
            if (reloadEvent == null) {
                return result(start, TestStatus.FAIL, "Timeout waiting for RELOAD event")
            }

            Thread.sleep(config.settleDelayMs)

            val nonce = java.util.UUID.randomUUID().toString()
            val httpResult = httpVerifier.post("${config.testEndpointBase}/interceptor-save", mapOf("nonce" to nonce))
            val failure = interceptorSaveFailure(httpResult, nonce)
            if (failure == null) {
                result(start, TestStatus.PASS, "modelService.save validated once; test transaction rolled back")
            } else {
                result(start, TestStatus.FAIL, failure)
            }
        } catch (e: Exception) {
            result(start, TestStatus.ERROR, e.message ?: e.toString())
        }
    }
}
