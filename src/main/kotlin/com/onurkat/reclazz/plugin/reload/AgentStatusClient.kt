/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.reload

import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.StreamTokenizer
import java.io.StringReader
import java.net.Socket
import java.net.SocketTimeoutException

data class AgentEvent(
    val level: String,
    val message: String,
    val timestamp: String,
    val type: String = "",
    val version: Int = 0
)

class AgentStatusClient(
    private val port: Int,
    private val onEvent: (AgentEvent) -> Unit,
    private val onDisconnect: () -> Unit
) {
    private val log = Logger.getInstance(AgentStatusClient::class.java)

    @Volatile
    private var running = false

    @Volatile
    private var voluntary = false
    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var thread: Thread? = null

    fun connect() {
        if (running) return
        running = true
        voluntary = false

        thread = Thread({
            connectWithRetry()
        }, "Reclazz-StatusClient").apply {
            isDaemon = true
            start()
        }
    }

    fun disconnect() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        thread?.interrupt()
    }

    /**
     * Disconnect without firing onDisconnect callback.
     * Used when the caller intentionally tears down the connection.
     */
    fun disconnectVoluntarily() {
        voluntary = true
        disconnect()
    }

    val isConnected: Boolean
        get() {
            val s = socket
            return running && s != null && s.isConnected && !s.isClosed
        }

    private fun connectWithRetry() {
        var retryDelay = 1000L
        val maxRetryDelay = 10000L
        var wasConnected = false

        while (running) {
            try {
                Socket("127.0.0.1", port).use { s ->
                    s.soTimeout = 30_000
                    socket = s
                    retryDelay = 1000L
                    wasConnected = true

                    BufferedReader(InputStreamReader(s.getInputStream())).use { reader ->
                        while (running) {
                            val line = try {
                                reader.readLine() ?: break
                            } catch (_: SocketTimeoutException) {
                                log.info("Socket read timeout — agent may be unresponsive")
                                break
                            }
                            val event = parseEvent(line) ?: continue

                            if (event.level == "CONNECTED" && event.version > 0) {
                                log.info("Agent protocol version: ${event.version}")
                            }

                            if (event.level == "HEARTBEAT") continue

                            onEvent(event)
                        }
                    }
                }
            } catch (_: Exception) {
                if (!running) break
            } finally {
                socket = null
            }

            if (wasConnected && !voluntary) {
                onDisconnect()
                wasConnected = false
            }

            if (!running) break

            try {
                Thread.sleep(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(maxRetryDelay)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun parseEvent(json: String): AgentEvent? {
        return try {
            val fields = parseJsonObject(json)
            val level = fields["level"] ?: return null
            val message = fields["message"] ?: return null
            val timestamp = fields["timestamp"] ?: ""
            val type = fields["type"] ?: ""
            val version = fields["version"]?.toIntOrNull() ?: 0
            AgentEvent(level, message, timestamp, type, version)
        } catch (e: Exception) {
            log.debug("Failed to parse agent event: ${e.message} — payload: ${json.take(200)}")
            null
        }
    }

    private fun parseJsonObject(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val tokenizer = StreamTokenizer(StringReader(json))
        tokenizer.ordinaryChar('{'.code)
        tokenizer.ordinaryChar('}'.code)
        tokenizer.ordinaryChar(':'.code)
        tokenizer.ordinaryChar(','.code)
        tokenizer.quoteChar('"'.code)

        if (tokenizer.nextToken() == '{'.code) {
            while (true) {
                val keyToken = tokenizer.nextToken()
                if (keyToken == '}'.code || keyToken == StreamTokenizer.TT_EOF) break
                if (keyToken != '"'.code) continue
                val key = tokenizer.sval ?: continue

                if (tokenizer.nextToken() != ':'.code) continue

                val valToken = tokenizer.nextToken()
                when (valToken) {
                    '"'.code -> result[key] = tokenizer.sval ?: ""
                    StreamTokenizer.TT_NUMBER -> result[key] = tokenizer.nval.toLong().toString()
                    StreamTokenizer.TT_WORD -> result[key] = tokenizer.sval ?: ""
                    else -> continue
                }

                val sep = tokenizer.nextToken()
                if (sep == '}'.code || sep == StreamTokenizer.TT_EOF) break
            }
        }
        return result
    }
}
