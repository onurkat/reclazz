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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class AgentEvent(
    val level: String,
    val message: String,
    val timestamp: String
)

class AgentEventClient(portFile: String) {

    private val port: Int
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private val running = AtomicBoolean(false)
    private val events = CopyOnWriteArrayList<AgentEvent>()
    /** Monotonic event counter — survives clearEvents(), used by waitForQuiet. */
    private val totalReceived = AtomicLong()

    init {
        val portPath = Path.of(portFile)
        require(Files.exists(portPath)) { "Port file not found: $portFile" }
        port = Files.readString(portPath).trim().toInt()
    }

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
                    events.add(event)
                    totalReceived.incrementAndGet()
                }
            } catch (e: Exception) {
                if (running.get()) {
                    System.err.println("AgentEventClient read error: ${e.message}")
                }
            }
        }, "AgentEventClient-reader").apply {
            isDaemon = true
            start()
        }

        // Wait for CONNECTED event
        waitForEvent("CONNECTED", 5000)
        println("  Connected to agent on port $port")
    }

    fun disconnect() {
        running.set(false)
        try { reader?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }

    fun clearEvents() {
        events.clear()
    }

    /**
     * Wait for an event whose level starts with [levelPrefix] and (when
     * given) whose message contains [messageContains]. The filter matters:
     * a baseline-restore storm emits COMPILE/RELOAD events for OTHER files,
     * and consuming one of those as "our" event verifies stale state.
     */
    fun waitForEvent(levelPrefix: String, timeoutMs: Long, messageContains: String? = null): AgentEvent? {
        fun matches(e: AgentEvent) = e.level.startsWith(levelPrefix) &&
                (messageContains == null || e.message.contains(messageContains))

        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            events.find(::matches)?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(100)
        }
    }

    fun waitForCompile(timeoutMs: Long, fileName: String? = null): AgentEvent? =
        waitForEvent("COMPILE", timeoutMs, fileName)

    /** Waits for RELOAD or STRUCTURAL_RELOAD concurrently (not sequentially). */
    fun waitForReload(timeoutMs: Long, classNamePart: String? = null): AgentEvent? {
        fun matches(e: AgentEvent) = (e.level.startsWith("RELOAD") || e.level.startsWith("STRUCTURAL_RELOAD")) &&
                (classNamePart == null || e.message.contains(classNamePart))

        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            events.find(::matches)?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(100)
        }
    }

    fun waitForError(timeoutMs: Long): AgentEvent? = waitForEvent("ERROR", timeoutMs)

    /**
     * Block until the agent has been quiet (no new events) for [quietMs],
     * or [maxWaitMs] elapses. Used to drain the baseline-restore compile
     * storm before starting a test.
     */
    fun waitForQuiet(quietMs: Long, maxWaitMs: Long) {
        val deadline = System.currentTimeMillis() + maxWaitMs
        var lastCount = totalReceived.get()
        var lastChange = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            val now = totalReceived.get()
            if (now != lastCount) {
                lastCount = now
                lastChange = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastChange >= quietMs) {
                return
            }
        }
    }

    fun getEvents(): List<AgentEvent> = events.toList()

    private fun parseEvent(json: String): AgentEvent? {
        return try {
            val level = extractJsonString(json, "level") ?: return null
            val message = extractJsonString(json, "message") ?: ""
            val timestamp = extractJsonString(json, "timestamp") ?: ""
            AgentEvent(level, message, timestamp)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = "\"$key\":\"([^\"]*)\""
        val match = Regex(pattern).find(json) ?: return null
        return match.groupValues[1]
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }
}
