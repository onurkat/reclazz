/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult

class AddMethodTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("Add new method", config, agentClient, httpVerifier) {

    override fun run(): TestResult =
        if (config.enhancedMode) {
            writeAndVerify(
                targetPath = "${config.webSrcDir}/com/onurkat/reclazztest/controllers/TestController.java",
                templateName = "TestController_addMethod.java.txt",
                httpPath = "${config.testEndpointBase}/new-endpoint",
                expectedBody = "new-endpoint-active",
            )
        } else {
            // Companion mode (standard JVM): the new method lives on a hidden
            // nestmate — Spring MVC's reflection scan cannot see it, so the
            // endpoint must NOT appear. Documented limitation, asserted as such.
            writeAndVerify(
                targetPath = "${config.webSrcDir}/com/onurkat/reclazztest/controllers/TestController.java",
                templateName = "TestController_addMethod.java.txt",
                httpPath = "${config.testEndpointBase}/new-endpoint",
                expectedStatus = 404,
            ).withCompanionNote("new endpoint stays 404 — reflection limit (documented)")
        }
}
