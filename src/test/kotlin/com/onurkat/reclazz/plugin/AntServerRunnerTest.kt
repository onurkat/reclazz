/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.plugin

import com.onurkat.reclazz.plugin.setup.AntBuildOutput
import com.onurkat.reclazz.plugin.setup.SapServerProcess
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
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
}
