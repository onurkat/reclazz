/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult

class LargeClassReloadTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("Large class reload", config, agentClient, httpVerifier) {

    override fun run(): TestResult =
        if (config.enhancedMode) {
            writeAndVerify(
                targetPath = "${config.webSrcDir}/com/onurkat/reclazztest/controllers/TestController.java",
                templateName = "TestController_large.java.txt",
                httpPath = "${config.testEndpointBase}/large25",
                expectedBody = "large-25",
            )
        } else {
            // Companion mode: the 25 new endpoints are reflection-invisible.
            // The real assertion here is that a LARGE structural reload
            // neither fails nor breaks existing behavior.
            writeAndVerify(
                targetPath = "${config.webSrcDir}/com/onurkat/reclazztest/controllers/TestController.java",
                templateName = "TestController_large.java.txt",
                httpPath = "${config.testEndpointBase}/ping",
                expectedStatus = 200,
            ).withCompanionNote("large reload applied, existing endpoints intact (new ones invisible — documented)")
        }
}
