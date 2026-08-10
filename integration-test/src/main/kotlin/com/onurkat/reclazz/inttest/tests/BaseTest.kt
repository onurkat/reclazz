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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

abstract class BaseTest(
    val name: String,
    protected val config: TestConfig,
    protected val agentClient: AgentEventClient,
    protected val httpVerifier: HttpVerifier,
) {
    abstract fun run(): TestResult

    fun writeTemplate(targetPath: String, templateName: String) {
        val template = javaClass.classLoader.getResourceAsStream("templates/$templateName")
            ?: error("Template not found: templates/$templateName")
        val content = template.bufferedReader().readText()
        val target = Path.of(targetPath)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
        println("    Wrote: $targetPath")
    }

    protected fun writeAndVerify(
        targetPath: String,
        templateName: String,
        httpPath: String,
        expectedBody: String? = null,
        expectedStatus: Int = 200,
        queryParams: Map<String, String> = emptyMap(),
        bodyCheck: ((String) -> Boolean)? = null,
        additionalWrites: List<Pair<String, String>> = emptyList(),
    ): TestResult {
        val start = System.currentTimeMillis()
        return try {
            agentClient.clearEvents()

            // Write the modified template
            writeTemplate(targetPath, templateName)

            // Write any additional files
            for ((path, template) in additionalWrites) {
                writeTemplate(path, template)
            }

            // Wait for OUR file's compile — filtered by file name so a
            // stray event from another change can't satisfy the wait.
            val targetFileName = File(targetPath).name
            val targetClassName = targetFileName.removeSuffix(".java")
            val compileEvent = agentClient.waitForCompile(config.eventTimeoutMs, targetFileName)
            if (compileEvent == null) {
                return result(start, TestStatus.FAIL, "Timeout waiting for COMPILE event ($targetFileName)")
            }

            // Wait for OUR class's reload
            val reloadEvent = agentClient.waitForReload(config.eventTimeoutMs, targetClassName)
            if (reloadEvent == null) {
                return result(start, TestStatus.FAIL, "Timeout waiting for RELOAD event ($targetClassName)")
            }

            // Wait for Spring reloaders to settle
            Thread.sleep(config.settleDelayMs)

            // HTTP verify
            val httpResult = httpVerifier.get(httpPath, queryParams)

            if (httpResult.statusCode != expectedStatus) {
                return result(start, TestStatus.FAIL,
                    "Expected HTTP $expectedStatus, got ${httpResult.statusCode} (body: ${httpResult.body})")
            }

            if (expectedBody != null && httpResult.body != expectedBody) {
                return result(start, TestStatus.FAIL,
                    "Expected body '$expectedBody', got '${httpResult.body}'")
            }

            if (bodyCheck != null && !bodyCheck(httpResult.body)) {
                return result(start, TestStatus.FAIL,
                    "Body check failed: '${httpResult.body}'")
            }

            result(start, TestStatus.PASS)
        } catch (e: Exception) {
            result(start, TestStatus.ERROR, e.message ?: e.toString())
        }
    }

    protected fun result(startMs: Long, status: TestStatus, notes: String = ""): TestResult {
        return TestResult(name, status, System.currentTimeMillis() - startMs, notes)
    }

    /** Tag a passing result as asserting documented companion-mode semantics. */
    protected fun TestResult.withCompanionNote(note: String): TestResult =
        if (status == TestStatus.PASS) copy(notes = "companion mode: $note") else this
}
