/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.plugin

import com.onurkat.reclazz.plugin.setup.AntBuildOutput
import com.onurkat.reclazz.plugin.setup.SapServerProcess
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.apache.catalina.startup.Bootstrap
import kotlin.test.*

class AntServerRunnerTest {
    @Test fun detectsSplitFailureEvenWithSuccessfulExitAndInterleavedStreams() {
        val output = AntBuildOutput()
        output.append("BUILD FA", "err")
        output.append("partial task output", "out")
        output.append("ILED\r\nplatform locked\r\nTotal time: 2 seconds", "err")
        val failure = assertNotNull(output.failure(0))
        assertTrue(failure.contains("BUILD FAILED"))
        assertTrue(failure.contains("platform locked"))
    }

    @Test fun nonzeroExitUsesTheLastDiagnosticAndSuccessfulRunHasNoError() {
        val output = AntBuildOutput()
        output.append("cannot generate wrapper", "err")
        assertTrue(assertNotNull(output.failure(3)).contains("exit code 3). cannot generate wrapper"))
        val success = AntBuildOutput()
        success.append("BUILD SUCCESSFUL\n", "out")
        assertNull(success.failure(0))
    }

    @Test fun unrelatedPortListenerAndUnrelatedLivePidDoNotIdentifyThisServer(@TempDir home: Path) {
        ServerSocket(0).use { listener ->
            val config = Files.createDirectories(home.resolve("config"))
            Files.writeString(config.resolve("local.properties"), "tomcat.http.port=${listener.localPort}")
            val data = Files.createDirectories(home.resolve("data"))
            Files.writeString(data.resolve("hybristomcat.java.pid"), ProcessHandle.current().pid().toString())
            assertFalse(SapServerProcess.running(home))
        }
    }

    @Test fun identifiesOnlyTheBootstrapForTheRecordedInstallation(@TempDir root: Path) {
        val home = Files.createDirectories(root.resolve("test installation"))
        val bin = Files.createDirectories(home.resolve("bin"))
        val sibling = Files.createDirectories(root.resolve("test installation sibling"))
        Files.createDirectories(sibling.resolve("bin"))
        val classes = Files.createDirectories(root.resolve("classes"))
        val target = classes.resolve("org/apache/catalina/startup/Bootstrap.class")
        Files.createDirectories(target.parent)
        requireNotNull(Bootstrap::class.java.getResourceAsStream("Bootstrap.class")).use {
            Files.copy(it, target)
        }
        val jdkBin = Path.of(System.getProperty("java.home"), "bin")
        val java = if (Files.exists(jdkBin.resolve("java.exe"))) jdkBin.resolve("java.exe") else jdkBin.resolve("java")
        val process = ProcessBuilder(java.toString(), "-DHYBRIS_BIN_DIR=$bin", "-cp", classes.toString(),
            "org.apache.catalina.startup.Bootstrap").start()
        try {
            for (installation in listOf(home, sibling)) {
                val data = Files.createDirectories(installation.resolve("data"))
                Files.writeString(data.resolve("hybristomcat.java.pid"), process.pid().toString())
            }
            assertTrue(SapServerProcess.running(home), "The recorded process belongs to this installation")
            assertFalse(SapServerProcess.running(sibling), "The same PID must not block a different installation")
        } finally {
            process.outputStream.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
}
