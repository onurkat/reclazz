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
        if (config.enhancedMode) {
            writeAndVerify(
                targetPath = "${config.srcDir}/com/onurkat/reclazztest/services/TestService.java",
                templateName = "TestService_addField.java.txt",
                httpPath = "${config.testEndpointBase}/greeting",
                expectedBody = "hello:42",
            )
        } else {
            // Companion mode: the new field exists and is readable from
            // hot-compiled code, but field INITIALIZERS live in the
            // constructor which cannot re-run for structural diffs — the
            // field reads as its JVM default (0) until code assigns it.
            writeAndVerify(
                targetPath = "${config.srcDir}/com/onurkat/reclazztest/services/TestService.java",
                templateName = "TestService_addField.java.txt",
                httpPath = "${config.testEndpointBase}/greeting",
                expectedBody = "hello:0",
            ).withCompanionNote("new field readable with JVM default; initializer needs ctor (documented)")
        }
}
