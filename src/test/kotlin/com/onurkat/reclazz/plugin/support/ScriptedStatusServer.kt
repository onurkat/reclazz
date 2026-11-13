/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.support

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Synthetic protocol replay only. No application/IDE control and no real agent commands executed. */
class ScriptedStatusServer(private val portFile: Path) : AutoCloseable {
    private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort
    private val clients = CopyOnWriteArrayList<Socket>()
    @Volatile var paused = false
    private val heartbeat = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Synthetic-Heartbeat").apply { isDaemon = true } }

    init {
        Files.createDirectories(portFile.toAbsolutePath().parent)
        Files.writeString(portFile, port.toString())
        Thread({
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    clients.add(client)
                    send(client, event("CONNECTED", "Synthetic replay (not a running application)", connected = true))
                    Thread({
                        try {
                            client.getInputStream().bufferedReader(Charsets.UTF_8).use { input ->
                                while (true) {
                                    val command = input.readLine() ?: break
                                    if (command == "HEALTH") replay(client)
                                    RELOAD_COMMAND.matchEntire(command)?.let { match ->
                                        // Answers like the agent's StatusServer: requester-only RELOAD_STATE.
                                        val (action, token) = match.destructured
                                        if (action == "pause") paused = true
                                        if (action == "resume") paused = false
                                        val waiting = if (paused) 2 else 0
                                        send(client, event("INFO", "RELOAD_STATE $token $action paused=$paused " +
                                            "pendingClasses=$waiting pendingActions=1 buildHold=none"))
                                    }
                                }
                            }
                        } catch (_: Exception) {
                        } finally { clients.remove(client); client.close() }
                    }, "Synthetic-Client").apply { isDaemon = true }.start()
                } catch (_: Exception) { /* Closing the listener terminates accept. */ }
            }
        }, "Synthetic-Status").apply { isDaemon = true }.start()
        heartbeat.scheduleAtFixedRate({ clients.forEach { send(it, event("HEARTBEAT", "")) } }, 1, 1, TimeUnit.SECONDS)
    }

    fun replay() = clients.forEach { replay(it) }
    private fun replay(client: Socket) = script.forEach { (level, message) -> send(client, event(level, message)) }
    private fun send(client: Socket, line: String) {
        try {
            synchronized(client) {
                client.getOutputStream().write((line + "\n").toByteArray(Charsets.UTF_8))
                client.getOutputStream().flush()
            }
        } catch (_: Exception) { clients.remove(client); client.close() }
    }

    override fun close() {
        heartbeat.shutdownNow()
        server.close()
        clients.forEach { it.close() }
        clients.clear()
        if (Files.isRegularFile(portFile) && Files.readString(portFile) == port.toString()) Files.deleteIfExists(portFile)
    }

    companion object {
        val script = listOf(
            "ERROR" to "Synthetic ERROR colour sample (test data; no application failure)",
            "RELOAD" to "Reloaded com.example.SearchResolver (12ms)",
            "INFO" to "SAP process example reloaded from example-process.xml: processes started from now on use the edited definition; processes already started keep the version they started with.",
            "WARN" to "SAP process definition example-process.xml MODIFIED: not applied; restart required. native process validation failed",
            "INFO" to "SAP Solr synthetic/config/type: FINISHED partial properties=1 documents=2 durationMs=12; finished without native errors; field freshness is not verified.",
            "INFO" to "SAP Solr synthetic/config/type: FINISHED_UNVERIFIED whole-type documents=2 durationMs=14; native provider errors may have been skipped.",
            "INFO" to "SAP Solr synthetic/config/type: FAILED (native-operation-failed). Reclazz did not confirm a reindex of stored documents; validate and explicitly reindex affected documents if needed.",
            "INFO" to "com.example.Widget.accept(Ljava/lang/Object;)V: added @SocketEvent handler. Widget views composed after this reload bind it in the default MVC controller. Already open views keep their old bindings; compose a new view to use it.",
            "WARN" to "com.example.Widget: added Backoffice @SocketEvent actions are not registered in existing widgets; restart required",
            "WARN" to "A removed method detected in com.example.Widget: [accept]. A caller that still uses it now gets NoSuchMethodError, as it would after a restart.",
            "WARN" to "com.example.Widget.invalid(Ljava/lang/Object;Ljava/lang/Object;)V: cockpitng's default controller binds only public @SocketEvent methods with at most one parameter; this handler is not bound.",
            "INFO" to "com.example.Widget.staticHandler(Ljava/lang/Object;)V: added static @SocketEvent handler; native binding accepts this shape, but Reclazz has not verified it after a reload. Reopen the view and check it, or restart.",
            "INFO" to "RELOAD_STATE synthetic status paused=true pendingClasses=1 pendingActions=0 buildHold=none",
            "INFO" to "Automatic reload: paused=true pendingClasses=1 pendingActions=0 buildHold=none",
            "INFO" to "One thing from this session needs a restart:",
            "INFO" to "  example-process.xml: SAP process definition was not applied: native process validation failed (just now)",
            "INFO" to "Everything else you changed is already live.",
            "INFO" to "Loaded and prepared by the agent, so a reload of it would be applied.",
            "INFO" to "Synthetic replay complete"
        )

        private val RELOAD_COMMAND = Regex("RELOAD (pause|resume|status) request=([A-Za-z0-9_-]{1,64})")

        private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
        private fun event(level: String, message: String, connected: Boolean = false): String =
            "{\"level\":\"$level\",\"message\":\"${escape(message)}\",\"timestamp\":\"${Instant.now()}\"" +
                // No agent version, so the replay does not depend on the release number.
                (if (connected) ",\"version\":1" else "") + "}"

        /** Run on a test classpath with a SYNTHETIC project's port file; HEALTH replays the table. */
        @JvmStatic fun main(args: Array<String>) {
            require(args.size == 1) { "Supply the synthetic project's port-file path" }
            ScriptedStatusServer(Path.of(args.single())).use { replay ->
                println("Synthetic status replay on 127.0.0.1:${replay.port}. HEALTH replays; Enter exits.")
                readlnOrNull()
            }
        }
    }
}
