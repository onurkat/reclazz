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
import java.nio.file.Path

/**
 * Runs `ant server` in bin/platform the way SAP Commerce documents it: the platform's setantenv script first, so its own
 * bundled ant and JDK settings are used, then `ant server`, which regenerates wrapper.conf and wrapper-debug.conf from the
 * properties the agent was written to.
 */
internal class AntServerRunner(private val home: Path) {
    @Volatile private var handler: OSProcessHandler? = null

    fun commandLine(): GeneralCommandLine {
        val platform = home.resolve("bin/platform")
        val command = if (SystemInfo.isWindows) GeneralCommandLine("cmd.exe", "/c", "setantenv.bat && ant server")
        else GeneralCommandLine("/bin/bash", "-c", ". ./setantenv.sh && ant server")
        return command.withWorkDirectory(platform.toFile())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
    }

    /** Starts the run; [onLine] gets every output line, [onExit] the exit code. */
    fun start(onLine: (String) -> Unit, onExit: (Int) -> Unit) {
        val process = OSProcessHandler(commandLine())
        process.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) { onLine(event.text.trimEnd('\n', '\r')) }
            override fun processTerminated(event: ProcessEvent) { handler = null; onExit(event.exitCode) }
        })
        handler = process
        process.startNotify()
    }

    fun cancel() { handler?.destroyProcess() }
}
