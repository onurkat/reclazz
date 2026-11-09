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
            fun identity() {
                val proof = httpVerifier.get("${config.testEndpointBase}/mapping-probe/registry-proof", budgetMs=config.eventTimeoutMs)
                println("    Registry identity proof: HTTP ${proof.statusCode}: ${proof.body}")
                registryIdentityFailure(proof)?.let { error(it) }
            }
            identity()
            reloadTemplate(
                "${config.srcDir}/com/onurkat/reclazztest/interceptors/TestValidateInterceptor.java",
                "TestValidateInterceptor_v2.java.txt"
            )

            identity()
            val nonce = java.util.UUID.randomUUID().toString()
            // Exactly one actual model save per nonce; rollback cannot undo external side effects.
            // The class's correlated reload is the readiness barrier for this single shot.
            val httpResult = httpVerifier.post("${config.testEndpointBase}/interceptor-save", mapOf("nonce" to nonce), config.eventTimeoutMs)
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
