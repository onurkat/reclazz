/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.plugin.hybris

import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class SetupCheckServerTest {
    private fun event(message:String)="{\"level\":\"INFO\",\"message\":\"${message.replace("\\","\\\\").replace("\"","\\\"")}\",\"timestamp\":\"\"}"
    @Test fun correlatesDoctorAndSendsNoMutationCommands() = checkDoctor(1)

    @Test fun authenticatesBeforeRequestingDoctor() = checkDoctor(2)

    @org.junit.jupiter.api.io.TempDir lateinit var directory: java.nio.file.Path

    private fun checkDoctor(version: Int) {
        ServerSocket(0).use { server ->
            val seen=CompletableFuture<String>()
            val fixture = "ab".repeat(32)
            val file = directory.resolve("agent.port")
            java.nio.file.Files.writeString(file, server.localPort.toString() + if (version == 2) "\ntoken=$fixture\n" else "")
            val endpoint = com.onurkat.reclazz.protocol.StatusEndpoint.read(file)
            val worker=thread(isDaemon=true) { server.accept().use { socket ->
                socket.soTimeout=3000;val writer=socket.getOutputStream().bufferedWriter();val reader=socket.getInputStream().bufferedReader()
                if (version == 2) check(reader.readLine() == "AUTH $fixture")
                writer.write("{\"level\":\"CONNECTED\",\"message\":\"ready\",\"timestamp\":\"\",\"agent\":\"9.8.7\",\"version\":$version}\n");writer.flush()
                val command=reader.readLine();seen.complete(command);val id=command.substringAfter(' ')
                writer.write(event("DOCTOR_RESULT unrelated {}")+"\n")
                val payload="{\"requestId\":\"$id\",\"status\":\"observed\",\"agentVersion\":\"9.8.7\",\"watcherState\":\"watching\",\"watchedDirectoryCount\":3,\"unwatchableDirectoryCount\":0,\"buildHold\":\"idle\"}"
                writer.write(event("DOCTOR_RESULT $id $payload")+"\n");writer.flush()
                assertNull(reader.readLine(),"checker must disconnect without another command")
            } }
            val result=SetupCheckServer.probeEndpoint(endpoint,timeoutMillis=2000)
            assertNotNull(result);assertEquals("9.8.7",result.version);assertContains(result.summary,"watching");assertContains(result.summary,"3")
            assertTrue(seen.get(3,TimeUnit.SECONDS).matches(Regex("DOCTOR [A-Za-z0-9_-]{1,64}")));worker.join(3000);assertFalse(worker.isAlive)
        }
    }
    @Test fun reconnectReadsTheNewCredentialFromTheSameFile() {
        ServerSocket(0).use { server ->
            val file = directory.resolve("rotating.port")
            val first = "ab".repeat(32)
            val second = "cd".repeat(32)
            fun publish(value: String) = java.nio.file.Files.writeString(file, "${server.localPort}\ntoken=$value\n")
            publish(first)
            val connected = java.util.concurrent.CountDownLatch(2)
            val done = CompletableFuture<Void>()
            val client = com.onurkat.reclazz.plugin.reload.AgentStatusClient(
                { com.onurkat.reclazz.protocol.StatusEndpoint.read(file) },
                { event -> if (event.level == "CONNECTED") connected.countDown() }, {}
            )
            val worker = thread(isDaemon = true) {
                try {
                    for (expected in listOf(first, second)) server.accept().use { peer ->
                        peer.soTimeout = 4000
                        check(peer.getInputStream().bufferedReader().readLine() == "AUTH $expected")
                        peer.getOutputStream().write("{\"level\":\"CONNECTED\",\"message\":\"ready\",\"version\":2}\n".toByteArray())
                        if (expected == first) publish(second)
                        else check(connected.await(4, TimeUnit.SECONDS))
                    }
                    done.complete(null)
                } catch (error: Throwable) { done.completeExceptionally(error) }
            }
            try {
                client.connect()
                done.get(9, TimeUnit.SECONDS)
            } finally { client.disconnectVoluntarily(); worker.join(200) }
        }
    }

    @Test fun noListenerReturnsNoServer() { val port=ServerSocket(0).use { it.localPort };assertNull(SetupCheckServer.probe(port,timeoutMillis=250)) }
    @Test fun reachableButSilentEndpointIsNotReportedAsHealthy() {
        ServerSocket(0).use { server ->
            val worker=thread(isDaemon=true) { server.accept().use { it.getInputStream().read() } }
            assertFailsWith<IOException> { SetupCheckServer.probe(server.localPort,timeoutMillis=250) };worker.join(2000);assertFalse(worker.isAlive)
        }
    }
    @Test fun cancellationPropagatesAndDisconnects() {
        ServerSocket(0).use { server ->
            val worker=thread(isDaemon=true) { server.accept().use { it.getInputStream().read() } }
            var checks=0
            assertFailsWith<java.util.concurrent.CancellationException> { SetupCheckServer.probe(server.localPort,timeoutMillis=2000) { if(++checks>2)throw java.util.concurrent.CancellationException() } }
            worker.join(2000);assertFalse(worker.isAlive)
        }
    }
}
