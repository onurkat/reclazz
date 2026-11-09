package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.onurkat.reclazz.inttest.client.AgentEvent
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpResult
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult
import com.onurkat.reclazz.inttest.report.TestStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal val batchJson = ObjectMapper()
internal fun json(response: HttpResult): JsonNode {
    check(response.statusCode == 200) { "Expected HTTP200, got ${response.statusCode}: ${response.body}" }
    return batchJson.readTree(response.body) ?: error("Empty JSON response")
}
internal fun replaceOnce(text: String, before: String, after: String): String {
    check(text.windowed(before.length).count { it == before } == 1) { "Expected exactly one immutable edit target: $before" }
    return text.replace(before, after)
}
internal fun restoreAll(vararg actions: () -> Unit) {
    var failure: Exception? = null
    for (action in actions) try { action() } catch (e: Exception) {
        if (failure == null) failure = e else failure.addSuppressed(e)
    }
    failure?.let { throw it }
}
internal fun <T> withRestore(action: () -> T, restore: () -> Unit): T {
    var failure: Exception? = null
    try { return action() } catch (e: Exception) { failure = e; throw e }
    finally { try { restore() } catch (e: Exception) { if (failure == null) throw e else failure.addSuppressed(e) } }
}
internal fun resourceFailure(events: List<AgentEvent>, scopes: List<String>, allowed: (AgentEvent) -> Boolean): String? =
    events.firstOrNull { it.level in setOf("WARN", "ERROR") && scopes.any(it.message::contains) && !allowed(it) }
        ?.let { "Unexpected ${it.level}: ${it.message}" }
internal fun requireOwnedCleanup(response: HttpResult, field: String = "ownedRemaining") {
    val proof=json(response);val count=proof[field]
    check(count!=null && count.isIntegralNumber && count.asLong()==0L) {"Owned cleanup did not prove zero $field: $proof"}
}

/** Candidate baselines are versioned templates, never snapshots of possibly dirty live sources. */
abstract class BatchLiveTest(name: String, config: TestConfig, client: AgentEventClient, http: HttpVerifier)
    : BaseTest(name, config, client, http) {
    protected val endpoint = "${config.testEndpointBase}/batch"
    protected val occ = "/occ/v2/reclazz-fixture"
    protected fun original(relative: String): ByteArray = javaClass.classLoader
        .getResourceAsStream("templates/batch/$relative")?.use { it.readBytes() }
        ?: error("Missing immutable Batch A fixture $relative")
    protected fun get(path: String, params: Map<String, String> = emptyMap()) =
        httpVerifier.get(path, params, config.eventTimeoutMs)
    protected fun post(path: String, params: Map<String, String> = emptyMap()) =
        httpVerifier.post(path, params, config.eventTimeoutMs)
    protected fun pollJson(path: String, params: Map<String, String> = emptyMap(), accepts: (JsonNode) -> Boolean): JsonNode =
        json(httpVerifier.poll(config.eventTimeoutMs, "JSON $path", { httpVerifier.get(path, params, it) }) {
            it.statusCode == 200 && runCatching { accepts(json(it)) }.getOrDefault(false)
        })
    protected fun owners(filename: String): JsonNode {
        val rows = json(get("$endpoint/resource-owners", mapOf("file" to filename)))
        check(rows.isArray && rows.size() > 0 && rows.map { it["owner"].asInt() }.toSet() == (1..rows.size()).toSet())
        return rows
    }
    protected fun xmlWrite(path: Path, bytes: ByteArray, ownerCount: Int, scopes: List<String> = listOf(path.fileName.toString())) {
        val mark = agentClient.cursor()
        if (!writeChanged(path, bytes)) return
        val outcomes = agentClient.waitForSpringXmlOutcomes(config.eventTimeoutMs, path.fileName.toString(), mark, ownerCount)
        check(outcomes.none { "require restart" in it.message }) { "XML requires restart: $outcomes" }
        resourceFailure(agentClient.getEvents().filter { it.sequence > mark && it.sequence <= outcomes.last().sequence }, scopes) {
            it.level == "WARN" && it.message.startsWith("Note: existing @Autowired consumers won't see 'reclazzBatch")
        }?.let { error(it) }
    }
    protected fun resourceWrite(path: Path, bytes: ByteArray, scopes: List<String>,
        terminal: (AgentEvent) -> Boolean, allowed: (AgentEvent) -> Boolean = { false }, expectedOutcomes: Int = 1) {
        val mark = agentClient.cursor()
        if (!writeChanged(path, bytes)) return
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.eventTimeoutMs)
        while (true) {
            val events = agentClient.getEvents().filter { it.sequence > mark }
            resourceFailure(events, scopes, allowed)?.let { error(it) }
            if (events.count(terminal) >= expectedOutcomes) return
            check(System.nanoTime() < deadline) { "No terminal outcome for $scopes; observed ${events.takeLast(12)}" }
            Thread.sleep(20)
        }
    }
    protected fun fixtureEdit(relative: String, changed: ByteArray, apply: (Path, ByteArray) -> Unit,
        afterRestore: () -> Unit, beforeRestore: () -> Unit = {}, action: () -> Unit) {
        val baseline = original(relative); val path = Path.of(config.extPath, relative)
        check(Files.readAllBytes(path).contentEquals(baseline)) { "Dirty Batch A source baseline: $relative" }
        withRestore({ apply(path, changed); action() }) {
            restoreAll(beforeRestore, { apply(path, baseline) }, afterRestore, {
                check(Files.readAllBytes(path).contentEquals(baseline)) { "Fixture bytes not restored: $relative" }
            })
        }
    }
    protected fun javaEdit(relative: String, changed: String, afterRestore: () -> Unit, action: () -> Unit) =
        fixtureEdit(relative, changed.toByteArray(), { path, bytes ->
            val mark = agentClient.cursor()
            if (writeChanged(path, bytes)) awaitSources(agentClient, listOf(path), mark, config.eventTimeoutMs)
        }, afterRestore, action = action)
    protected abstract fun scenario()
    override fun run(): TestResult {
        val start = System.currentTimeMillis()
        return try { scenario(); result(start, TestStatus.PASS) }
        catch (e: Exception) { result(start, TestStatus.FAIL, (listOf(e.message ?: e.toString()) + e.suppressed.map { "restore: ${it.message}" }).joinToString("; ")) }
    }
}
