/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult

class RemoveFieldTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("Remove field", config, agentClient, httpVerifier) {

    override fun run(): TestResult = writeAndVerify(
        targetPath = "${config.srcDir}/com/onurkat/reclazztest/services/TestService.java",
        templateName = "TestService_removeField.java.txt",
        httpPath = "${config.testEndpointBase}/greeting",
        expectedBody = "no-field",
    )
}
