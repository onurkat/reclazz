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
        // The endpoint answers on both runtimes now. On a JVM with enhanced
        // redefinition the method is really on the controller and the mapping
        // scan finds it; on a stock JDK it lives in the companion, where
        // reflection cannot reach, so Reclazz hands the scan a small class
        // carrying a copy of the method and delegating to it.
        writeAndVerify(
            targetPath = "${config.webSrcDir}/com/onurkat/reclazztest/controllers/TestController.java",
            templateName = "TestController_addMethod.java.txt",
            httpPath = "${config.testEndpointBase}/new-endpoint",
            expectedBody = "new-endpoint-active",
        )
}
