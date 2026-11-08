/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.http.HttpResult
import com.onurkat.reclazz.inttest.report.TestResult
import com.onurkat.reclazz.inttest.report.TestStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A source file's exact declared owner, never guessed from event effects text. */
fun sourceOwner(path: Path): String {
    val source = Files.readString(path)
    val pkg = Regex("\\bpackage\\s+([\\w.]+)\\s*;").find(source)?.groupValues?.get(1)
        ?: error("No package declaration in ${path.fileName}")
    return "$pkg.${path.fileName.toString().removeSuffix(".java")}"
}
fun writeChanged(path: Path, bytes: ByteArray): Boolean {
    if (Files.exists(path) && Files.readAllBytes(path).contentEquals(bytes)) return false
    Files.createDirectories(path.parent); Files.write(path, bytes); return true
}
fun awaitSources(client: AgentEventClient, paths: List<Path>, since: Long, timeoutMs: Long) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    for (path in paths) {
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)
        check(client.waitForSourceReload(remaining, path.fileName.toString(), sourceOwner(path), since) != null) {
            "Timeout waiting for COMPILE then RELOAD of ${sourceOwner(path)} after event $since; observed " +
                client.getEvents().filter { it.sequence > since && it.level in setOf("COMPILE", "RELOAD", "STRUCTURAL_RELOAD", "ERROR") }
                    .takeLast(30).joinToString { event ->
                        val prefix = when (event.level) {
                            "COMPILE" -> event.message.substringBefore(" (")
                            "ERROR" -> event.message.take(500)
                            "STRUCTURAL_RELOAD" -> "Structural reload: " + event.message.removePrefix("Structural reload: ").takeWhile { it != ' ' && it != ':' }
                            else -> "Reloaded " + event.message.removePrefix("Reloaded ").takeWhile { it != ' ' && it != ':' }
                        }
                        "${event.sequence}:${event.level}:$prefix"
                    }
        }
    }
}

abstract class BaseTest(val name: String, protected val config: TestConfig,
    protected val agentClient: AgentEventClient, protected val httpVerifier: HttpVerifier) {
    abstract fun run(): TestResult
    fun writeTemplate(targetPath: String, templateName: String): Boolean {
        val bytes = javaClass.classLoader.getResourceAsStream("templates/$templateName")?.use { it.readBytes() }
            ?: error("Template not found: templates/$templateName")
        val changed = writeChanged(Path.of(targetPath), bytes)
        if (changed) println("    Wrote: $targetPath")
        return changed
    }
    protected fun reloadTemplate(targetPath: String, templateName: String) {
        val since = agentClient.cursor()
        if (writeTemplate(targetPath, templateName)) awaitSources(agentClient, listOf(Path.of(targetPath)), since, config.eventTimeoutMs)
    }
    protected fun pollGet(path: String, expectedStatus: Int = 200, expectedBody: String? = null,
        queryParams: Map<String,String> = emptyMap(), bodyCheck: ((String) -> Boolean)? = null): HttpResult =
        httpVerifier.poll(config.eventTimeoutMs, "GET $path", { httpVerifier.get(path, queryParams, it) }) {
            it.statusCode == expectedStatus && (expectedBody == null || it.body == expectedBody) && (bodyCheck == null || bodyCheck(it.body))
        }
    protected fun writeAndVerify(targetPath: String, templateName: String, httpPath: String,
        expectedBody: String? = null, expectedStatus: Int = 200, queryParams: Map<String,String> = emptyMap(),
        bodyCheck: ((String) -> Boolean)? = null, additionalWrites: List<Pair<String,String>> = emptyList()): TestResult {
        val start = System.currentTimeMillis()
        return try {
            val since = agentClient.cursor()
            val changed = (listOf(targetPath to templateName) + additionalWrites).mapNotNull { (path, template) ->
                if (writeTemplate(path, template)) Path.of(path) else null
            }
            awaitSources(agentClient, changed, since, config.eventTimeoutMs)
            pollGet(httpPath, expectedStatus, expectedBody, queryParams, bodyCheck)
            result(start, TestStatus.PASS)
        } catch (e: Exception) { result(start, TestStatus.FAIL, e.message ?: e.toString()) }
    }
    protected fun result(startMs: Long, status: TestStatus, notes: String = "") = TestResult(name, status, System.currentTimeMillis() - startMs, notes)
    protected fun TestResult.withCompanionNote(note: String): TestResult =
        if (status == TestStatus.PASS) copy(notes = "companion mode: $note") else this
}
