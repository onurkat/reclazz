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
import com.onurkat.reclazz.inttest.committedFixture
import java.nio.file.Files
import java.nio.file.Path

/** Serialized saves use existing terminal messages plus actual database visibility. */
class ImpexBarrier(private val config: TestConfig, private val client: AgentEventClient,
    private val http: HttpVerifier, private val original: ByteArray) {
    private val file = Path.of(config.impexDir, "test-data.impex")
    private val endpoint = "${config.testEndpointBase}/impex-title"
    private val baselineTitle = "Reclazz Test Title v1"

    fun apply(bytes: ByteArray) {
        val since = client.cursor()
        if (!writeChanged(file, bytes)) return
        val outcome = client.waitForImpex(config.eventTimeoutMs, file.fileName.toString(), since)
            ?: error("Timeout waiting for terminal ImpEx outcome for ${file.fileName} after $since")
        check(outcome.level == "OK") { outcome.message }
    }
    fun visible(title: String) = http.poll(config.eventTimeoutMs, "ImpEx expected '$title'",
        { http.get(endpoint, budgetMs = it) }) { it.statusCode == 200 && it.body == title }

    fun baseline() {
        try {
            apply(original)
            val current = http.get(endpoint, budgetMs = config.eventTimeoutMs)
            if (current.statusCode != 200 || current.body != baselineTitle) {
                apply(original + "\n# Reclazz fixture baseline verification\n".toByteArray())
                visible(baselineTitle)
            }
        } finally {
            // Restore bytes even when the import or visibility check failed.
            apply(original)
        }
        visible(baselineTitle)
    }
}

class ImpexAutoImportTest(config: TestConfig, agentClient: AgentEventClient, httpVerifier: HttpVerifier)
    : BaseTest("ImpEx auto-import", config, agentClient, httpVerifier) {
    override fun run(): TestResult {
        val start = System.currentTimeMillis()
        val barrier = ImpexBarrier(config, agentClient, httpVerifier, committedFixture("resources/impex/test-data.impex"))
        var failure: Exception? = null
        try {
            barrier.baseline()
            val modified = javaClass.classLoader.getResourceAsStream("templates/test-data_v2.impex.txt")!!.use { it.readBytes() }
            barrier.apply(modified)
            barrier.visible("Reclazz Test Title v2")
        } catch (e: Exception) { failure = e }
        finally {
            try { barrier.baseline() }
            catch (e: Exception) {
                failure = IllegalStateException(listOfNotNull(failure?.message, "ImpEx restore: ${e.message}").joinToString("; "), failure ?: e)
            }
        }
        return if (failure == null) result(start, TestStatus.PASS)
        else result(start, TestStatus.FAIL, failure.message ?: failure.toString())
    }
}
