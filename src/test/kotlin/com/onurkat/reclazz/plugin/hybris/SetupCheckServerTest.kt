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
    @Test fun correlatesDoctorAndSendsNoMutationCommands() {
        ServerSocket(0).use { server ->
            val seen=CompletableFuture<String>()
            val worker=thread(isDaemon=true) { server.accept().use { socket ->
                socket.soTimeout=3000;val writer=socket.getOutputStream().bufferedWriter();val reader=socket.getInputStream().bufferedReader()
                writer.write("{\"level\":\"CONNECTED\",\"message\":\"ready\",\"timestamp\":\"\",\"agent\":\"9.8.7\",\"version\":1}\n");writer.flush()
                val command=reader.readLine();seen.complete(command);val id=command.substringAfter(' ')
                writer.write(event("DOCTOR_RESULT unrelated {}")+"\n")
                val payload="{\"requestId\":\"$id\",\"status\":\"observed\",\"agentVersion\":\"9.8.7\",\"watcherState\":\"watching\",\"watchedDirectoryCount\":3,\"unwatchableDirectoryCount\":0,\"buildHold\":\"idle\"}"
                writer.write(event("DOCTOR_RESULT $id $payload")+"\n");writer.flush()
                assertNull(reader.readLine(),"checker must disconnect without another command")
            } }
            val result=SetupCheckServer.probe(server.localPort,timeoutMillis=2000)
            assertNotNull(result);assertEquals("9.8.7",result.version);assertContains(result.summary,"watching");assertContains(result.summary,"3")
            assertTrue(seen.get(3,TimeUnit.SECONDS).matches(Regex("DOCTOR [A-Za-z0-9_-]{1,64}")));worker.join(3000);assertFalse(worker.isAlive)
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
