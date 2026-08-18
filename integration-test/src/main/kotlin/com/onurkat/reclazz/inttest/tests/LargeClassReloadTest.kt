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

    /**
     * Twenty-five endpoints added in one save. The point is that a large
     * structural reload neither fails nor disturbs what already worked, and
     * now also that the new endpoints answer: the companion branch used to
     * check /ping and note the new ones were "invisible — documented", which
     * stopped being true when handler methods added by a reload started being
     * mapped. Asking for the last of the twenty-five tests both things at once.
     */
    override fun run(): TestResult =
        writeAndVerify(
            targetPath = "${config.webSrcDir}/com/onurkat/reclazztest/controllers/TestController.java",
            templateName = "TestController_large.java.txt",
            httpPath = "${config.testEndpointBase}/large25",
            expectedBody = "large-25",
        )
}
