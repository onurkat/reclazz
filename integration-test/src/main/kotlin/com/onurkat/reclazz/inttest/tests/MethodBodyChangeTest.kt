/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult

class MethodBodyChangeTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("Method body change", config, agentClient, httpVerifier) {

    override fun run(): TestResult = writeAndVerify(
        targetPath = "${config.srcDir}/com/onurkat/reclazztest/services/TestService.java",
        templateName = "TestService_ping_v2.java.txt",
        httpPath = "${config.testEndpointBase}/ping",
        expectedBody = "pong-v2",
    )
}
