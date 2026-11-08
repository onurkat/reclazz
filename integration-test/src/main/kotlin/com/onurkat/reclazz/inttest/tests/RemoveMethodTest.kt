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
            // A direct source call cannot be recompiled after deleting its target.
            // This probe still invokes the original runtime method, and must work
            // before removal; its reflection path remains compilable in later batches.
            reloadTemplate("${config.webSrcDir}/com/onurkat/reclazztest/controllers/TestController.java", "TestController_removeMethodProbe.java.txt")
            pollGet("${config.testEndpointBase}/removable", expectedBody = "removable-v1")
            reloadTemplate(
                "${config.srcDir}/com/onurkat/reclazztest/services/TestService.java",
                "TestService_removeMethod.java.txt"
            )

            // Enhanced mode cannot find the removed method. Companion mode invokes
            // the existing physical entry, which must throw NoSuchMethodError.
            // Both must fail the endpoint instead of serving the old body.
            val httpResult = httpVerifier.poll(config.eventTimeoutMs, "Removed method endpoint",
                { httpVerifier.get("${config.testEndpointBase}/removable", budgetMs = it) }) { it.statusCode == 404 || it.statusCode == 500 }
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
