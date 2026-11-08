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

class SyntaxErrorRecoveryTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("Syntax error recovery", config, agentClient, httpVerifier) {

    override fun run(): TestResult {
        val start = System.currentTimeMillis()
        return try {
            // Step 1: Write broken code
            val brokenSince = agentClient.cursor()
            writeTemplate(
                "${config.srcDir}/com/onurkat/reclazztest/services/TestService.java",
                "TestService_syntaxError.java.txt"
            )

            // Wait for compile ERROR
            val errorEvent = agentClient.waitForError(config.eventTimeoutMs, brokenSince)
            if (errorEvent == null) {
                return result(start, TestStatus.FAIL, "Expected ERROR event for syntax error")
            }
            println("    Got expected compile error")

            // Step 2: Fix the code
            val fixedSince = agentClient.cursor()
            writeTemplate(
                "${config.srcDir}/com/onurkat/reclazztest/services/TestService.java",
                "TestService_ping_v2.java.txt"
            )

            awaitSources(agentClient, listOf(java.nio.file.Path.of("${config.srcDir}/com/onurkat/reclazztest/services/TestService.java")), fixedSince, config.eventTimeoutMs)

            val httpResult = pollGet("${config.testEndpointBase}/ping", expectedBody = "pong-v2")
            if (httpResult.body == "pong-v2") {
                result(start, TestStatus.PASS)
            } else {
                result(start, TestStatus.FAIL, "Expected 'pong-v2', got '${httpResult.body}'")
            }
        } catch (e: Exception) {
            result(start, TestStatus.ERROR, e.message ?: e.toString())
        }
    }
}
