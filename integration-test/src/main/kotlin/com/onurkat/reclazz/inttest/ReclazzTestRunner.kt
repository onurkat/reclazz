/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestReport
import com.onurkat.reclazz.inttest.report.TestResult
import com.onurkat.reclazz.inttest.report.TestStatus
import com.onurkat.reclazz.inttest.tests.*
import kotlin.system.exitProcess

fun main() {
    println("=== Reclazz Integration Test Runner ===")
    println()

    val config = try {
        TestConfig()
    } catch (e: Exception) {
        System.err.println("Configuration error: ${e.message}")
        System.err.println()
        System.err.println("Required environment variables:")
        System.err.println("  RECLAZZ_TEST_EXT_PATH  - Path to reclazztest extension in Hybris")
        System.err.println("  RECLAZZ_TEST_PORT_FILE - Path to agent port file")
        System.err.println()
        System.err.println("Optional:")
        System.err.println("  RECLAZZ_TEST_BASE_URL    - Base URL (default: https://localhost:9002)")
        System.err.println("  RECLAZZ_TEST_EVENT_TIMEOUT - Event timeout ms (default: 30000)")
        System.err.println("  RECLAZZ_TEST_SETTLE_DELAY  - Settle delay ms (default: 2000)")
        exitProcess(1)
    }

    println("Config:")
    println("  Base URL:   ${config.baseUrl}")
    println("  Ext path:   ${config.extPath}")
    println("  Port file:  ${config.portFile}")
    println()

    // Connect to agent
    println("Connecting to agent...")
    val agentClient = try {
        AgentEventClient(config.portFile).also { it.connect() }
    } catch (e: Exception) {
        System.err.println("Failed to connect to agent: ${e.message}")
        exitProcess(1)
    }

    val httpVerifier = HttpVerifier(config.baseUrl, config.httpTimeoutMs)
    val report = TestReport()

    // Build test list
    val tests = listOf(
        MethodBodyChangeTest(config, agentClient, httpVerifier),       // 1
        AddMethodTest(config, agentClient, httpVerifier),              // 2
        RemoveMethodTest(config, agentClient, httpVerifier),           // 3
        AddFieldTest(config, agentClient, httpVerifier),               // 4
        RemoveFieldTest(config, agentClient, httpVerifier),            // 5
        ChangeMethodSignatureTest(config, agentClient, httpVerifier),  // 6
        MultiClassReloadTest(config, agentClient, httpVerifier),       // 7
        ConstructorChangeTest(config, agentClient, httpVerifier),      // 8
        BeanRefreshTest(config, agentClient, httpVerifier),            // 9
        MvcReScanTest(config, agentClient, httpVerifier),              // 10
        CacheEvictionTest(config, agentClient, httpVerifier),          // 11
        SchedulerReloadTest(config, agentClient, httpVerifier),        // 12
        EventListenerTest(config, agentClient, httpVerifier),          // 13
        InterceptorReloadTest(config, agentClient, httpVerifier),      // 14
        ImpexAutoImportTest(config, agentClient, httpVerifier),        // 15
        HibernateL2CacheTest(config, agentClient, httpVerifier),       // 16
        RapidSuccessiveChangesTest(config, agentClient, httpVerifier), // 17
        SyntaxErrorRecoveryTest(config, agentClient, httpVerifier),    // 18
        LargeClassReloadTest(config, agentClient, httpVerifier),       // 19
        AnnotationChangeTest(config, agentClient, httpVerifier),       // 20
        LambdaReloadTest(config, agentClient, httpVerifier),           // 21
        CacheableConditionTest(config, agentClient, httpVerifier),     // 22
        EnumTailRemoveTest(config, agentClient, httpVerifier),         // 23
    )

    // V1 template restore mappings
    val v1Templates = mapOf(
        "${config.srcDir}/com/onurkat/reclazztest/services/TestService.java"
                to "TestService_v1.java.txt",
        "${config.srcDir}/com/onurkat/reclazztest/services/HelperService.java"
                to "HelperService_v1.java.txt",
        "${config.srcDir}/com/onurkat/reclazztest/services/CacheTestService.java"
                to "CacheTestService_v1.java.txt",
        "${config.srcDir}/com/onurkat/reclazztest/services/SchedulerTestService.java"
                to "SchedulerTestService_v1.java.txt",
        "${config.srcDir}/com/onurkat/reclazztest/services/EventTestService.java"
                to "EventTestService_v1.java.txt",
        "${config.srcDir}/com/onurkat/reclazztest/services/TestDao.java"
                to "TestDao_v1.java.txt",
        "${config.srcDir}/com/onurkat/reclazztest/services/TestStatus.java"
                to "TestStatus_v1.java.txt",
        "${config.srcDir}/com/onurkat/reclazztest/interceptors/TestValidateInterceptor.java"
                to "TestValidateInterceptor_v1.java.txt",
        "${config.webSrcDir}/com/onurkat/reclazztest/controllers/TestController.java"
                to "TestController_v1.java.txt",
        "${config.impexDir}/test-data.impex"
                to "test-data_v1.impex.txt",
    )

    for ((index, test) in tests.withIndex()) {
        println()
        println("[${index + 1}/${tests.size}] Running: ${test.name}")

        // Restore v1 baseline
        println("  Restoring v1 baseline...")
        for ((path, template) in v1Templates) {
            test.writeTemplate(path, template)
        }

        // Wait for the baseline compile/reload storm to finish completely —
        // 10 files each take ~0.5s to compile, so a fixed 2s sleep left
        // in-flight events that tests then mistook for their own.
        agentClient.waitForQuiet(quietMs = 3_000, maxWaitMs = 90_000)
        agentClient.clearEvents()

        // Run test
        val result: TestResult = try {
            test.run()
        } catch (e: Exception) {
            TestResult(test.name, TestStatus.ERROR, 0, e.message ?: e.toString())
        }

        report.add(result)
        val statusLabel = when (result.status) {
            TestStatus.PASS -> "\u001B[32mPASS\u001B[0m"
            TestStatus.FAIL -> "\u001B[31mFAIL\u001B[0m"
            TestStatus.ERROR -> "\u001B[31mERROR\u001B[0m"
            TestStatus.SKIP -> "\u001B[33mSKIP\u001B[0m"
        }
        println("  Result: $statusLabel ${if (result.notes.isNotEmpty()) "- ${result.notes}" else ""}")
    }

    // Restore v1 baseline after all tests
    println()
    println("Restoring v1 baseline...")
    for ((path, template) in v1Templates) {
        val templateStream = object {}.javaClass.classLoader.getResourceAsStream("templates/$template")
        if (templateStream != null) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(path), templateStream.bufferedReader().readText())
        }
    }

    agentClient.disconnect()
    report.print()

    exitProcess(if (report.hasFailures()) 1 else 0)
}
