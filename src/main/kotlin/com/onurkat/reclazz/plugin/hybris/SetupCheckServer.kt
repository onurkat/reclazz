/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.plugin.hybris

import com.onurkat.reclazz.plugin.reload.AgentStatusClient
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A temporary, read-only connection; it does not replace the project's reload connection. */
internal object SetupCheckServer {
    fun probe(port: Int?, timeoutMillis: Long = 3000, checkCancelled: () -> Unit = {}): SetupCheck.Server? {
        if(port==null || port !in 1..65535)return null
        val id=UUID.randomUUID().toString().replace("-", "")
        val prefix="DOCTOR_RESULT $id ";val answer=CompletableFuture<String>()
        lateinit var client:AgentStatusClient
        client=AgentStatusClient(port,onEvent={event ->
            when {
                event.level=="CONNECTED" -> if(!client.send("DOCTOR $id"))answer.completeExceptionally(IOException("DOCTOR could not be sent"))
                event.message.startsWith(prefix) -> answer.complete(event.message.removePrefix(prefix))
            }
        },onDisconnect={answer.completeExceptionally(IOException("DOCTOR connection closed"))})
        client.connect()
        try {
            val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while(System.nanoTime()<deadline) {
                checkCancelled()
                try {
                    val body=answer.get(50,TimeUnit.MILLISECONDS)
                    val fields=client.parseJsonObject(body)
                    if(fields["requestId"]!=id || fields["status"]!="observed")throw IOException("DOCTOR evidence unavailable")
                    return SetupCheck.Server(fields["agentVersion"].orEmpty(),buildString {
                        append("DOCTOR: ").append(fields["watcherState"] ?: "unknown")
                        append(", ").append(fields["watchedDirectoryCount"] ?: "unknown").append(" watched directories")
                        append(", ").append(fields["unwatchableDirectoryCount"] ?: "unknown").append(" unwatchable")
                        append(", build ").append(fields["buildHold"] ?: "unknown").append('.' )
                    })
                } catch (_:TimeoutException) { /* Short waits keep cancellation responsive. */ }
                  catch (failure:ExecutionException) { throw IOException("DOCTOR failed",failure.cause) }
            }
            checkCancelled()
            if(client.isConnected)throw IOException("DOCTOR timed out")
            return null
        } finally { client.disconnectVoluntarily() }
    }
}
