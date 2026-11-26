/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.setup

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.sun.jna.platform.win32.Shell32Util
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Runs `ant server` in bin/platform the way SAP Commerce documents it: the platform's setantenv script first, so its own
 * bundled ant and JDK settings are used, then `ant server`, which regenerates wrapper.conf and wrapper-debug.conf from the
 * properties the agent was written to.
 */
internal class AntServerRunner(private val home: Path) {
    private var handler: OSProcessHandler? = null
    private var cancelled = false

    fun commandLine(): GeneralCommandLine {
        val platform = home.resolve("bin/platform")
        val command = if (SystemInfo.isWindows) GeneralCommandLine("cmd.exe", "/c", "setantenv.bat && ant server")
        else GeneralCommandLine("/bin/bash", "-c", ". ./setantenv.sh && ant server")
        return command.withWorkDirectory(platform.toFile())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
    }

    /** Called off the event thread. A null result means Ant completed successfully. */
    fun start(onText: (String) -> Unit, onExit: (String?) -> Unit) {
        if (SapServerProcess.running(home)) {
            onExit(SapServerProcess.warning)
            return
        }
        val output = AntBuildOutput()
        synchronized(this) {
            if (cancelled) {
                onExit("Ant was stopped.")
                return
            }
            val process = OSProcessHandler(commandLine())
            process.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    output.append(event.text, outputType)
                    onText(event.text)
                }
                override fun processTerminated(event: ProcessEvent) {
                    val stopped = synchronized(this@AntServerRunner) {
                        handler = null
                        cancelled
                    }
                    val failure = output.failure(event.exitCode)
                    onExit(when {
                        stopped -> "Ant was stopped."
                        failure != null && SapServerProcess.running(home) -> SapServerProcess.warning
                        else -> failure
                    })
                }
            })
            handler = process
            process.startNotify()
        }
    }

    @Synchronized fun cancel() {
        cancelled = true
        handler?.destroyProcess()
    }
}

/** Process events can contain partial lines, with stdout and stderr arriving independently. */
internal class AntBuildOutput {
    private val partial = mutableMapOf<Any, String>()
    private val tail = ArrayDeque<String>()
    private var failed = false

    @Synchronized fun append(text: String, stream: Any) {
        val lines = (partial.remove(stream).orEmpty() + text).split('\n')
        lines.dropLast(1).forEach(::line)
        partial[stream] = lines.last()
    }

    private fun line(raw: String) {
        val value = raw.trim()
        if (value.isEmpty()) return
        if (value == "BUILD FAILED") failed = true
        if (tail.size == 5) tail.removeFirst()
        tail.addLast(value.take(400))
    }

    @Synchronized fun failure(code: Int): String? {
        partial.values.forEach(::line)
        partial.clear()
        if (code == 0 && !failed) return null
        val reason = if (failed) "BUILD FAILED" else "exit code $code"
        val detail = tail.filterNot { it == "BUILD FAILED" || it.startsWith("Total time:") }
        return (listOf("Ant failed ($reason).") + detail.takeLast(3)).joinToString(" ")
    }
}

/** The wrapper records its Java PID inside the installation. A socket alone cannot identify this server. */
internal object SapServerProcess {
    const val warning = "Your SAP Commerce server is running. Stop it, then run ant server."

    fun running(home: Path): Boolean {
        val tomcat = home.resolve("bin/platform/tomcat")
        val pidFiles = mutableSetOf(home.resolve("data/hybristomcat.java.pid"))
        for (name in listOf("wrapper.conf", "wrapper-debug.conf")) {
            val conf = tomcat.resolve("conf").resolve(name)
            try {
                Files.newBufferedReader(conf).useLines { lines ->
                    val raw = lines.map(String::trim).lastOrNull { it.substringBefore('=').trim() == "wrapper.java.pidfile" }
                        ?.substringAfter('=')?.trim()?.trim('"')
                    if (!raw.isNullOrEmpty()) pidFiles.add(tomcat.resolve("bin").resolve(raw))
                }
            } catch (_: java.io.IOException) { /* The wrapper may not have been generated yet. */ }
              catch (_: java.nio.file.InvalidPathException) { /* An unresolved template is not a PID file. */ }
        }
        return pidFiles.any { file ->
            try {
                val pid = Files.newBufferedReader(file).use { it.readLine()?.trim()?.toLongOrNull() }
                val process = pid?.takeIf { it > 0 }?.let { ProcessHandle.of(it).orElse(null) }
                if (process == null || !process.isAlive) return@any false
                val info = process.info()
                val args = info.arguments().orElse(null)?.takeIf { it.isNotEmpty() }
                    ?: windowsArguments(process) ?: return@any false
                val bin = args.firstOrNull { it.startsWith("-DHYBRIS_BIN_DIR=") }?.substringAfter('=')
                bin != null && Files.isSameFile(Path.of(bin), home.resolve("bin")) &&
                    args.contains("org.apache.catalina.startup.Bootstrap") && process.isAlive
            } catch (_: java.io.IOException) { false }
              catch (_: SecurityException) { false }
              catch (_: java.nio.file.InvalidPathException) { false }
        }
    }

    /** The JDK may omit arguments on Windows. Neither a recent PID file nor a listening Java PID identifies Tomcat. */
    private fun windowsArguments(process: ProcessHandle): Array<String>? {
        if (!SystemInfo.isWindows) return null
        val name = process.info().command().orElse(null)?.let { Path.of(it).fileName.toString() }
        if (!name.equals("java.exe", true) && !name.equals("javaw.exe", true)) return null
        val system = System.getenv("SystemRoot") ?: return null
        val powershell = Path.of(system, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        val script = "[Console]::OutputEncoding = [Text.UTF8Encoding]::new(\$false); " +
            "[Console]::Write((Get-CimInstance Win32_Process -Filter 'ProcessId=${process.pid()}' -ErrorAction Stop).CommandLine)"
        val query = ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val output = CompletableFuture.supplyAsync {
                query.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            if (!query.waitFor(5, TimeUnit.SECONDS) || query.exitValue() != 0) return null
            val line = output.get()
            return if (line.isBlank()) null else Shell32Util.CommandLineToArgv(line)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: ExecutionException) {
            return null
        } finally {
            if (query.isAlive) query.destroyForcibly()
        }
    }
}
