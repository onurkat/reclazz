/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult

class BeanRefreshTest(
    config: TestConfig,
    agentClient: AgentEventClient,
    httpVerifier: HttpVerifier,
) : BaseTest("Bean refresh", config, agentClient, httpVerifier) {

    override fun run(): TestResult = writeAndVerify(
        targetPath = "${config.srcDir}/com/onurkat/reclazztest/services/TestService.java",
        templateName = "TestService_beanRefresh.java.txt",
        httpPath = "${config.testEndpointBase}/service",
        expectedBody = "service-v2",
    )
}
