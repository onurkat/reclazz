/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult

/**
 * A reloaded body that brings lambdas with it.
 *
 * The template's ping() is rebuilt from a stream with two lambdas, a lambda
 * capturing `this`, and a call into a private method added by the same save.
 * Each of those used to be a NoSuchMethodError or ClassNotFoundException at
 * first call on a stock JDK, out of a source line that looked ordinary.
 */
class LambdaReloadTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("Lambda in reloaded body", config, agentClient, httpVerifier) {

    override fun run(): TestResult = writeAndVerify(
        targetPath = "${config.srcDir}/com/onurkat/reclazztest/services/TestService.java",
        templateName = "TestService_lambda.java.txt",
        httpPath = "${config.testEndpointBase}/ping",
        expectedBody = "pong-lambda-HELLO",
    )
}
