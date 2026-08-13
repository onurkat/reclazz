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

class AnnotationChangeTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("Annotation change", config, agentClient, httpVerifier) {

    override fun run(): TestResult {
        val start = System.currentTimeMillis()
        return try {
            agentClient.clearEvents()
            writeTemplate(
                "${config.webSrcDir}/com/onurkat/reclazztest/controllers/TestController.java",
                "TestController_annotationChange.java.txt"
            )

            val compileEvent = agentClient.waitForCompile(config.eventTimeoutMs)
            if (compileEvent == null) {
                return result(start, TestStatus.FAIL, "Timeout waiting for COMPILE event")
            }

            val reloadEvent = agentClient.waitForReload(config.eventTimeoutMs, "TestController")
            if (reloadEvent == null) {
                return result(start, TestStatus.FAIL, "Timeout waiting for RELOAD event")
            }

            Thread.sleep(config.settleDelayMs)

            // Both modes expect the same thing now. This used to assert the
            // opposite in companion mode, on the belief that annotations could
            // not be swapped on a stock JVM. They can: redefineClasses accepts
            // an annotation change and reflection reports it. What was actually
            // stopping the mapping from moving was Spring caching its
            // reflection per Class, so a re-scan re-read the methods it had
            // parsed at startup. The reloader clears those caches now.

            // /pong should work now
            val pongResult = httpVerifier.get("${config.testEndpointBase}/pong")
            if (pongResult.statusCode != 200) {
                return result(start, TestStatus.FAIL, "GET /pong returned ${pongResult.statusCode}")
            }

            // /ping should be gone (404)
            val pingResult = httpVerifier.get("${config.testEndpointBase}/ping")
            if (pingResult.statusCode == 404) {
                result(start, TestStatus.PASS)
            } else {
                result(start, TestStatus.FAIL, "GET /ping should be 404, got ${pingResult.statusCode}")
            }
        } catch (e: Exception) {
            result(start, TestStatus.ERROR, e.message ?: e.toString())
        }
    }
}
