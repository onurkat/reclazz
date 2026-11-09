/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.inttest.client

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class AgentEvent(val level: String, val message: String, val timestamp: String, val sequence: Long = 0)

class AgentEventClient(portFile: String) {
    private val port = Files.readString(Path.of(portFile)).trim().toInt()
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private val running = AtomicBoolean(false)
    private val events = mutableListOf<AgentEvent>()
    private val monitor = java.lang.Object()
    private val totalReceived = AtomicLong()

    fun connect() {
        socket = Socket("127.0.0.1", port)
        reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
        running.set(true)
        Thread({
            try {
                while (running.get()) {
                    val line = reader?.readLine() ?: break
                    val event = parseEvent(line) ?: continue
                    if (event.level == "HEARTBEAT") continue
                    synchronized(monitor) {
                        events.add(event.copy(sequence = totalReceived.incrementAndGet()))
                        monitor.notifyAll()
                    }
                }
            } catch (e: Exception) {
                if (running.get()) System.err.println("AgentEventClient read error: ${e.message}")
            }
        }, "AgentEventClient-reader").apply { isDaemon = true; start() }
        check(waitForEvent("CONNECTED", 5000) != null) { "Agent did not acknowledge connection" }
        println("  Connected to agent on port $port")
    }

    fun disconnect() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) { }
        try { reader?.close() } catch (_: Exception) { }
    }
    fun cursor(): Long = synchronized(monitor) { totalReceived.get() }
    fun clearEvents() = synchronized(monitor) { events.clear() }
    fun getEvents(): List<AgentEvent> = synchronized(monitor) { events.toList() }

    private fun await(timeoutMs: Long, since: Long, matches: (AgentEvent) -> Boolean): AgentEvent? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0))
        synchronized(monitor) {
            while (true) {
                events.find { it.sequence > since && matches(it) }?.let { return it }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return null
                monitor.wait(TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1, 100))
            }
        }
    }
    fun waitForEvent(levelPrefix: String, timeoutMs: Long, messageContains: String? = null, since: Long = 0): AgentEvent? =
        await(timeoutMs, since) { it.level.startsWith(levelPrefix) && (messageContains == null || it.message.contains(messageContains)) }

    fun waitForCompile(timeoutMs: Long, fileName: String? = null, since: Long = 0): AgentEvent? =
        await(timeoutMs, since) { it.level == "COMPILE" && (fileName == null ||
                it.message == "Compiled $fileName" || it.message.startsWith("Compiled $fileName (")) }

    fun waitForReload(timeoutMs: Long, classNamePart: String? = null, since: Long = 0): AgentEvent? =
        await(timeoutMs, since) {
            val prefix = when (it.level) { "RELOAD" -> "Reloaded "; "STRUCTURAL_RELOAD" -> "Structural reload: "; else -> return@await false }
            if (!it.message.startsWith(prefix)) false
            else {
                val owner = it.message.removePrefix(prefix)
                (classNamePart == null || owner.takeWhile { char -> char != ' ' && char != ':' } == classNamePart) &&
                        !owner.substringAfter(' ', "").startsWith("(inner class)")
            }
        }

    fun waitForSourceReload(timeoutMs: Long, fileName: String, owner: String, since: Long): AgentEvent? {
        val start = System.nanoTime()
        val compiled = waitForCompile(timeoutMs, fileName, since) ?: return null
        val remaining = timeoutMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        val reloaded = waitForReload(remaining.coerceAtLeast(0), owner, compiled.sequence) ?: return null
        val failure = synchronized(monitor) {
            events.firstOrNull { it.sequence > compiled.sequence && it.sequence < reloaded.sequence &&
                it.level == "ERROR" && it.message.startsWith("Failed to refresh Spring bean for $owner:") }
        }
        check(failure == null) { failure!!.message }
        return reloaded
    }
    fun waitForError(timeoutMs: Long, since: Long = 0): AgentEvent? = waitForEvent("ERROR", timeoutMs, since = since)

    fun waitForSpringXml(timeoutMs: Long, fileName: String, since: Long, allowNoChanges: Boolean = false): AgentEvent? = await(timeoutMs, since) {
        val prefix = "Spring XML " + Regex.escape(fileName) + "(?: \\[context \\d+/\\d+\\])?"
        it.level == "INFO" && (Regex("$prefix reloaded: \\d+ changes? applied.*").matches(it.message) ||
            (allowNoChanges && Regex("$prefix, parsed OK, no live-applicable changes detected").matches(it.message)))
    }

    /** Serialized fixture writes drain one canonical final outcome per known XML owner. */
    fun waitForSpringXmlOutcomes(timeoutMs: Long, fileName: String, since: Long, expectedOwners: Int): List<AgentEvent> {
        require(expectedOwners > 0) { "XML owner count must be positive" }
        val prefix = "Spring XML " + Regex.escape(fileName) + "(?: \\[context \\d+/\\d+\\])?"
        val terminal = Regex("(?:$prefix reloaded: \\d+ changes? applied(?:, \\d+ require restart)?|$prefix, parsed OK, no live-applicable changes detected)")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0))
        synchronized(monitor) {
            while (true) {
                val seen = events.filter { it.sequence > since && it.level == "INFO" && terminal.matches(it.message) }
                if (seen.size >= expectedOwners) return seen.take(expectedOwners)
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) {
                    "Spring XML $fileName timed out after event $since: ${seen.size}/$expectedOwners owner outcomes; seen: " +
                        seen.joinToString { "${it.sequence}:${it.message}" }
                }
                monitor.wait(TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1, 100))
            }
        }
    }

    fun waitForImpex(timeoutMs: Long, fileName: String, since: Long): AgentEvent? = await(timeoutMs, since) {
        (it.level == "OK" && it.message == "ImpEx imported: $fileName") ||
            (it.level in setOf("WARN", "ERROR") &&
                (it.message.startsWith("ImpEx not imported: $fileName ") ||
                 it.message.startsWith("Failed to import ImpEx $fileName:") ||
                 (it.message.startsWith("ImpEx import had errors:") && it.message.contains(" in $fileName.")) ||
                 it.message.startsWith("ImpEx not imported: platform tenant unavailable")))
    }

    private fun parseEvent(json: String): AgentEvent? = runCatching {
        AgentEvent(extractJsonString(json, "level") ?: return null,
            extractJsonString(json, "message") ?: "", extractJsonString(json, "timestamp") ?: "")
    }.getOrNull()

    private fun extractJsonString(json: String, key: String): String? {
        val raw = Regex("\"$key\":\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(json)?.groupValues?.get(1) ?: return null
        val out = StringBuilder(); var index = 0
        while (index < raw.length) {
            val char = raw[index++]
            if (char != '\\') out.append(char)
            else when (val escaped = raw[index++]) {
                'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                'b' -> out.append('\b'); 'f' -> out.append('\u000C')
                'u' -> { out.append(raw.substring(index, index + 4).toInt(16).toChar()); index += 4 }
                else -> out.append(escaped)
            }
        }
        return out.toString()
    }
}
