/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult

class MvcReScanTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("MVC re-scan", config, agentClient, httpVerifier) {

    override fun run(): TestResult =
        if (config.enhancedMode) {
            writeAndVerify(
                targetPath = "${config.webSrcDir}/com/onurkat/reclazztest/controllers/TestController.java",
                templateName = "TestController_addMethod.java.txt",
                httpPath = "${config.testEndpointBase}/new-endpoint",
                expectedStatus = 200,
            )
        } else {
            // Companion mode: MVC re-scan reads the original class via
            // reflection and cannot see nestmate methods — documented.
            // Existing mappings must keep working after the re-scan though.
            writeAndVerify(
                targetPath = "${config.webSrcDir}/com/onurkat/reclazztest/controllers/TestController.java",
                templateName = "TestController_addMethod.java.txt",
                httpPath = "${config.testEndpointBase}/ping",
                expectedStatus = 200,
            ).withCompanionNote("existing mappings intact after re-scan; new one invisible (documented)")
        }
}
