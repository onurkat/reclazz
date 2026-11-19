/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult
import com.onurkat.reclazz.inttest.report.TestStatus

/** Initialize a real javac table before adding a previously unused enum case. */
class EnumSwitchMapLiveTest(config: TestConfig, agentClient: AgentEventClient, httpVerifier: HttpVerifier) :
    BaseTest("Enum switch-map case added", config, agentClient, httpVerifier) {
    override fun run(): TestResult {
        val start = System.currentTimeMillis()
        return try {
            val service = "${config.srcDir}/com/onurkat/reclazztest/services/TestService.java"
            reloadTemplate(service, "TestService_switchmap_v1.java.txt")
            pollGet("${config.testEndpointBase}/greeting", expectedBody = "open-v1|closed-v1|other-v1")
            reloadTemplate(service, "TestService_switchmap_v2.java.txt")
            pollGet("${config.testEndpointBase}/greeting", expectedBody = "open-v2|closed-v2|archived-v2")
            result(start, TestStatus.PASS)
        } catch (failure: Exception) { result(start, TestStatus.FAIL, failure.message ?: failure.toString()) }
    }
}
