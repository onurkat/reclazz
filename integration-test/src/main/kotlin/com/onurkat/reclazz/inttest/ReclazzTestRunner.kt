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
    println("  Fixture:    reclazztest")
    println("  Port file:  ${java.nio.file.Path.of(config.portFile).fileName}")
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
    val allTests = listOf(
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
        InterceptorMappingAddLiveTest(config, agentClient, httpVerifier),
        InterceptorMappingEditLiveTest(config, agentClient, httpVerifier), // Batch A discovery; never a known-failure allowance
        SpringXmlPropertyLiveTest(config, agentClient, httpVerifier),
        SpringXmlRefLiveTest(config, agentClient, httpVerifier),
        SpringXmlRecreateLiveTest(config, agentClient, httpVerifier),
        SpringXmlNewBeanLiveTest(config, agentClient, httpVerifier),
        OccFieldSetLiveTest(config, agentClient, httpVerifier),
        OrikaPropertyRollbackLiveTest(config, agentClient, httpVerifier),
        OccRequestMappingLiveTest(config, agentClient, httpVerifier),
        OccPriorityLiveTest(config, agentClient, httpVerifier),
        PlatformConfigLiveTest(config, agentClient, httpVerifier),
        LocalizationLiveTest(config, agentClient, httpVerifier),
        ProcessXmlLiveTest(config, agentClient, httpVerifier),
        NewProcessFileFirstLiveTest(config, agentClient, httpVerifier),
        NewProcessBeanFirstLiveTest(config, agentClient, httpVerifier),
        CronJobLiveTest(config, agentClient, httpVerifier),
        SapEventLiveTest(config, agentClient, httpVerifier),
        ImpexRemoveRefusalLiveTest(config, agentClient, httpVerifier),
        PopulatorOccLiveTest(config, agentClient, httpVerifier),
        ValidatorBodyLiveTest(config, agentClient, httpVerifier),
        ValidatorParameterLiveTest(config, agentClient, httpVerifier),
    )
    // RECLAZZ_TEST_ONLY=ConstructorChangeTest,CacheableConditionTest re-runs named scenarios on a fresh server.
    val only = System.getenv("RECLAZZ_TEST_ONLY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    val tests = if (only.isEmpty()) allTests else allTests.filter { it::class.simpleName in only }
    if (only.isNotEmpty() && tests.size != only.size) {
        System.err.println("RECLAZZ_TEST_ONLY names unknown scenarios: " + (only - tests.map { it::class.simpleName }.toSet()))
        exitProcess(1)
    }

    val baseline = FixtureBaseline(config, agentClient, httpVerifier)
    val batchBaseline = if(tests.any {it is BatchLiveTest}) BatchFixtureBaseline(config) else null
    try {
        for ((index, test) in tests.withIndex()) {
            println("\n[${index + 1}/${tests.size}] Running: ${test.name}")
            var restored = false
            val result = try {
                batchBaseline?.checkBytes()
                baseline.restore()
                restored = true
                test.run()
            } catch (e: Exception) {
                TestResult(test.name, TestStatus.ERROR, 0, e.message ?: e.toString())
            }
            report.add(result)
            println("  Result: ${result.status} ${result.notes}")
            if (!restored) break // subsequent scenarios have no acknowledged baseline
        }
    } finally {
        try { baseline.restore() }
        catch (e: Exception) { report.add(TestResult("Final restoration", TestStatus.ERROR, 0, e.message ?: e.toString())) }
        try { baseline.checkBytes() }
        catch (e: Exception) { report.add(TestResult("Fixture bytes", TestStatus.ERROR, 0, e.message ?: e.toString())) }
        try { batchBaseline?.checkBytes() }
        catch (e: Exception) { report.add(TestResult("Batch A fixture bytes", TestStatus.ERROR, 0, e.message ?: e.toString())) }
        agentClient.disconnect()
        report.print()
    }

    exitProcess(if (report.hasFailures()) 1 else 0)
}


/** Always read committed fixture bytes, never snapshot live-edited working files. */
fun committedFixture(relative: String): ByteArray {
    require(!relative.startsWith("/") && relative.split('/').none { it == ".." })
    val process = ProcessBuilder("git", "--no-optional-locks", "show", "HEAD:reclazztest/$relative").start()
    val bytes = process.inputStream.readBytes()
    check(process.waitFor() == 0) { "Cannot read HEAD fixture $relative; run from the repository root" }
    return bytes
}

class FixtureBaseline(private val config: TestConfig, private val client: AgentEventClient, private val http: HttpVerifier) {
    private val relative = listOf("TestService", "HelperService", "CacheTestService", "SchedulerTestService", "EventTestService", "TestDao", "TestStatus")
        .map { "src/com/onurkat/reclazztest/services/$it.java" } + listOf(
            "src/com/onurkat/reclazztest/interceptors/TestValidateInterceptor.java",
            "web/src/com/onurkat/reclazztest/controllers/TestController.java", "resources/impex/test-data.impex")
    private val originals = relative.associate { java.nio.file.Path.of(config.extPath, it) to committedFixture(it) }
    fun restore() {
        val since = client.cursor()
        val changed = originals.filterKeys { it.toString().endsWith(".java") }.mapNotNull { (path, bytes) ->
            if (writeChanged(path, bytes)) path else null
        }
        val failures = mutableListOf<String>()
        try { awaitSources(client, changed, since, config.eventTimeoutMs) }
        catch (e: Exception) { failures.add(e.message ?: e.toString()) }
        try { ImpexBarrier(config, client, http, originals.getValue(java.nio.file.Path.of(config.impexDir, "test-data.impex"))).baseline() }
        catch (e: Exception) { failures.add(e.message ?: e.toString()) }
        check(failures.isEmpty()) { "Baseline restore failed: ${failures.joinToString("; ")}" }
    }
    fun checkBytes() {
        for ((path, bytes) in originals) check(java.nio.file.Files.readAllBytes(path).contentEquals(bytes)) {
            "Fixture was not restored byte-identical to HEAD: ${java.nio.file.Path.of(config.extPath).relativize(path)}"
        }
    }
}
