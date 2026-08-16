/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult

class AddFieldTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("Add field", config, agentClient, httpVerifier) {

    override fun run(): TestResult =
        // A field initializer is compiled into the constructor, and the bean is
        // recreated by the reload, so the object answering this request ran that
        // constructor and holds 42. It read 0 for as long as the redefinition
        // carrying the new constructor body was refused, which is fixed.
        //
        // Objects the reload did not recreate still hold the JVM default. That
        // is a different case and the agent says so on every structural reload.
        writeAndVerify(
            targetPath = "${config.srcDir}/com/onurkat/reclazztest/services/TestService.java",
            templateName = "TestService_addField.java.txt",
            httpPath = "${config.testEndpointBase}/greeting",
            expectedBody = "hello:42",
        )
}
