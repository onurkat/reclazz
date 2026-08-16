/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.reload

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Service(Service.Level.PROJECT)
class ReloadManager(private val project: Project) : Disposable {

    private val log = Logger.getInstance(ReloadManager::class.java)

    @Volatile
    private var statusClient: AgentStatusClient? = null
    private val eventListeners = CopyOnWriteArrayList<(AgentEvent) -> Unit>()
    @Volatile
    private var reconnectScheduler: ScheduledExecutorService? = null
    private val connecting = AtomicBoolean(false)

    private val _reloadCount = AtomicInteger(0)
    val reloadCount: Int get() = _reloadCount.get()

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun addEventListener(listener: (AgentEvent) -> Unit) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: (AgentEvent) -> Unit) {
        eventListeners.remove(listener)
    }

    /**
     * Connect to a running agent.
     *
     * [onResult] is how a user-triggered reconnect learns what happened. The
     * work is asynchronous and used to end silently when there was no port
     * file, so the action that started it could only ever say it was trying.
     * Someone whose server was not running got "Reconnecting to agent..." and
     * then nothing, for as long as they cared to wait.
     *
     * Startup passes nothing and stays quiet, which is right: finding no agent
     * when a project opens is the ordinary case, not news.
     */
    @JvmOverloads
    fun connectToAgent(onResult: ((ConnectResult) -> Unit)? = null) {
        if (!connecting.compareAndSet(false, true)) {
            onResult?.invoke(ConnectResult.ALREADY_IN_PROGRESS)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val port = readPortFile()
                if (port == null) {
                    connecting.set(false)
                    onResult?.invoke(ConnectResult.NO_AGENT_FOUND)
                    return@executeOnPooledThread
                }
                // Disconnect on pooled thread — stopReconnect() may block up to 2s
                disconnectFromAgent()
                statusClient = AgentStatusClient(
                    port = port,
                    onEvent = { event -> handleEvent(event) },
                    onDisconnect = {
                        isConnected = false
                        notifyListeners(AgentEvent("DISCONNECTED", "Agent disconnected", ""))
                        scheduleReconnect()
                    }
                )
                statusClient?.connect()
                onResult?.invoke(ConnectResult.CONNECTED)
            } catch (e: Exception) {
                log.warn("Failed to connect to agent: ${e.message}")
                onResult?.invoke(ConnectResult.FAILED)
            } finally {
                connecting.set(false)
            }
        }
    }

    /** What a reconnect actually did, so the caller can say so. */
    enum class ConnectResult { CONNECTED, NO_AGENT_FOUND, FAILED, ALREADY_IN_PROGRESS }

    fun disconnectFromAgent() {
        stopReconnect()
        statusClient?.disconnectVoluntarily()
        statusClient = null
        isConnected = false
    }

    private fun scheduleReconnect() {
        stopReconnect()
        reconnectScheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "Reclazz-Reconnect").apply { isDaemon = true }
        }
        reconnectScheduler?.scheduleWithFixedDelay({
            if (isConnected) return@scheduleWithFixedDelay
            val port = readPortFile()
            if (port != null) {
                log.info("Agent port file found, reconnecting...")
                connectToAgent()
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    private fun stopReconnect() {
        reconnectScheduler?.let { scheduler ->
            scheduler.shutdownNow()
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        reconnectScheduler = null
    }

    private fun handleEvent(event: AgentEvent) {
        when (event.level) {
            "CONNECTED" -> {
                isConnected = true
                lastError = null
                log.info("Connected to Reclazz agent")
            }
            "RELOAD", "STRUCTURAL_RELOAD" -> {
                // Both light (method-body) and heavy (structural / companion
                // class) reloads bump the same counter — the user just wants
                // to see "something reloaded".
                _reloadCount.incrementAndGet()
                lastError = null
            }
            "ERROR" -> {
                lastError = event.message
            }
        }
        notifyListeners(event)
    }

    /**
     * Write a line into the Reclazz tool window without an agent behind it.
     *
     * Some of what Reclazz has to say is context rather than an event: which
     * JDK you are on and what that means for structural reload does not need
     * to interrupt anything, and it belongs where you are already looking
     * when you care. It used to arrive as a balloon, which had to be
     * dismissed and, for a while, could not be.
     */
    fun postLocalMessage(level: String, message: String) {
        notifyListeners(AgentEvent(level, message, ""))
    }

    /**
     * Ask the running agent why a class did not reload. The answer arrives as
     * ordinary log lines, in the tool window, alongside the reloads it is
     * being compared against.
     *
     * @return false when no agent is connected to ask
     */
    fun diagnose(className: String): Boolean {
        return statusClient?.send("DIAGNOSE $className") ?: false
    }

    private fun notifyListeners(event: AgentEvent) {
        for (listener in eventListeners) {
            try {
                listener(event)
            } catch (e: Exception) {
                log.warn("Event listener error: ${e.message}")
            }
        }
    }

    // Note: there is an inherent TOCTOU between isPortOpen() and the actual connection.
    // If the port is reclaimed between check and connect, AgentStatusClient will retry.
    private fun readPortFile(): Int? {
        val settings = ReclazzSettings.getInstance(project).state

        // Priority 1: Explicit port configured in settings
        if (settings.agentPort in VALID_PORT_RANGE) {
            val port = settings.agentPort
            return if (isPortOpen(port)) port else {
                log.info("Configured port $port not open — agent may not be running")
                null
            }
        }

        // Priority 2: Custom port file path from settings
        // Priority 3: Default .idea/reclazz/agent.port (used when the plugin
        //             auto-injected the agent into a Run Configuration)
        // Priority 4: <hybris>/.reclazz/agent.port (Hybris-detected default
        //             written by the agent when the user added a bare
        //             -javaagent line to wrapper.conf with no args)
        // Priority 5: <project>/.reclazz/agent.port (non-Hybris fallback)
        val portFilePaths = mutableListOf<java.nio.file.Path>()
        if (settings.portFilePath.isNotBlank()) {
            portFilePaths.add(Paths.get(settings.portFilePath))
        }
        val basePath = project.basePath
        if (basePath != null) {
            portFilePaths.add(Paths.get(basePath, ".idea", "reclazz", "agent.port"))
            // Also check the agent's own default discovery location.
            val hybrisHome = com.onurkat.reclazz.plugin.hybris.HybrisProjectDetector.findHybrisHome(project)
            if (hybrisHome != null) {
                portFilePaths.add(hybrisHome.resolve(".reclazz").resolve("agent.port"))
            }
            portFilePaths.add(Paths.get(basePath, ".reclazz", "agent.port"))
        }

        for (portFile in portFilePaths) {
            try {
                // Read atomically — Files.readString handles non-existence by throwing,
                // avoiding the TOCTOU between exists() and readString().
                val content = try {
                    Files.readString(portFile).trim()
                } catch (_: java.nio.file.NoSuchFileException) {
                    continue
                }
                val port = content.toIntOrNull() ?: continue
                if (port in VALID_PORT_RANGE) {
                    return if (isPortOpen(port)) port else {
                        log.info("Port file $portFile found but port $port not open — agent may not be running")
                        null
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private fun isPortOpen(port: Int): Boolean {
        return try {
            Socket("127.0.0.1", port).use { true }
        } catch (_: Exception) {
            false
        }
    }

    override fun dispose() {
        stopReconnect()
        disconnectFromAgent()
    }

    companion object {
        val VALID_PORT_RANGE = 1..65535

        fun getInstance(project: Project): ReloadManager =
            project.getService(ReloadManager::class.java)
    }
}
