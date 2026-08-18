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

    /**
     * A handler method added by a reload has to answer on the next request, on
     * an ordinary JDK as well as on an enhanced one.
     *
     * The companion branch used to check that /ping still worked and note that
     * the new endpoint was "invisible (documented)". That stopped being true
     * when AddedEndpointAdapter was written, and the test went on reporting a
     * limitation the product no longer has: measured live on Spring Boot with a
     * stock JDK 21, the agent says "Handler methods added by this reload are
     * mapped" and the new path answers 200. A suite that under-reports is as
     * misleading as one that over-reports, so both modes now ask the same
     * question.
     */
    override fun run(): TestResult =
        writeAndVerify(
            targetPath = "${config.webSrcDir}/com/onurkat/reclazztest/controllers/TestController.java",
            templateName = "TestController_addMethod.java.txt",
            httpPath = "${config.testEndpointBase}/new-endpoint",
            expectedStatus = 200,
        )
}
