/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class BatchCancellationTest {
    @TempDir Path dir;
    static JsonObject rpc(String id, String method) {
        return JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":"+id+",\"method\":\""+method+"\"}").getAsJsonObject();
    }
    static JsonObject cancel(String id) {
        return JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":"+id+"}}").getAsJsonObject();
    }

    @ParameterizedTest @ValueSource(strings={"2024-11-05","2025-06-18"})
    void cancellationClosesHandshakeAndReceiptWaitWhilePingStaysResponsive(String version) throws Exception {
        for (boolean handshake : List.of(true,false)) {
            try (var listener = new ServerSocket(0,1,InetAddress.getByName("127.0.0.1")); var child = new Child()) {
                child.initialize(version);
                CountDownLatch waiting=new CountDownLatch(1);
                CompletableFuture<Void> peer=CompletableFuture.runAsync(()->{
                    try(var socket=listener.accept()) {
                        socket.setSoTimeout(5000);
                        var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                        var out=new PrintWriter(socket.getOutputStream(),true,StandardCharsets.UTF_8);
                        if(!handshake) {
                            out.println("{\"level\":\"CONNECTED\",\"agent\":\"test\",\"version\":1}");
                            assertTrue(in.readLine().startsWith("VERIFY "));
                        }
                        waiting.countDown();
                        assertNull(in.readLine(),"cancel must close this socket without sending another command");
                    } catch(Exception e) { throw new CompletionException(e); }
                });
                JsonObject batch=BatchVerificationTest.request(listener.getLocalPort(),BatchVerificationTest.items("A","B"));
                batch.getAsJsonObject("params").getAsJsonObject("arguments").addProperty("timeoutMs","60000");
                child.send(batch); assertTrue(waiting.await(5,TimeUnit.SECONDS));
                // Unknown, wrong type, malformed reason and initialization cancellation are ignored.
                child.send(cancel("999")); child.send(cancel("\"1\"")); child.send(cancel("0"));
                JsonObject malformed=cancel("1");malformed.getAsJsonObject("params").addProperty("reason",5);child.send(malformed);
                malformed=cancel("1");malformed.addProperty("id",31);child.send(malformed);
                assertEquals(-32000,child.read().getAsJsonObject("error").get("code").getAsInt());
                child.send(rpc("2","ping"));assertEquals(2,child.read().get("id").getAsInt());assertFalse(peer.isDone());
                child.send(rpc("3","tools/list"));assertEquals(8,child.read().getAsJsonObject("result").getAsJsonArray("tools").size());
                JsonObject other=BatchVerificationTest.request(listener.getLocalPort(),BatchVerificationTest.items("C"));other.addProperty("id",4);
                child.send(other);assertEquals(-32000,child.read().getAsJsonObject("error").get("code").getAsInt());
                child.send(cancel("1.0")); // Numeric ID equivalence; string "1" above was distinct.
                peer.get(3,TimeUnit.SECONDS);
                child.send(rpc("5","ping"));assertEquals(5,child.read().get("id").getAsInt());
                assertNull(child.lines.poll(100,TimeUnit.MILLISECONDS),"cancelled batch has no response");
                // The bounded worker slot is released and another batch is accepted.
                JsonObject next=BatchVerificationTest.request(1,BatchVerificationTest.items("A"));
                for(int i=0;i<30;i++) {
                    next.addProperty("id",100+i);child.send(next);JsonObject answer=child.read();
                    assertEquals(100+i,answer.get("id").getAsInt());
                    if(answer.has("result")) {assertTrue(answer.getAsJsonObject("result").get("isError").getAsBoolean());break;}
                    assertTrue(i<29,"cancelled worker did not release its slot");Thread.sleep(10);
                }
                child.send(cancel("1"));child.send(rpc("6","ping"));assertEquals(6,child.read().get("id").getAsInt());
            }
        }
    }

    @Test void stdinEofClosesAnOutstandingSocketAndExits() throws Exception {
        try(var listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));var child=new Child()) {
            JsonObject r=BatchVerificationTest.request(listener.getLocalPort(),BatchVerificationTest.items("A"));
            r.getAsJsonObject("params").getAsJsonObject("arguments").addProperty("timeoutMs","60000");child.send(r);
            listener.setSoTimeout(5000);
            try(var socket=listener.accept()) {
                socket.setSoTimeout(3000);child.input.close();assertEquals(-1,socket.getInputStream().read());
                assertTrue(child.process.waitFor(5,TimeUnit.SECONDS));assertEquals(0,child.process.exitValue());
                child.reader.join(1000);assertTrue(child.lines.isEmpty());
            }
        }
    }

    @Test void completedRequestCancellationDoesNotPoisonTheNextRequest() throws Exception {
        try(var agent=new BuildSafetyTest.FakeAgent((in,out)->BatchVerificationTest.reply(out,in.readLine(),"applied"));var child=new Child()) {
            child.initialize("2025-06-18");child.send(BatchVerificationTest.request(agent.server.getLocalPort(),BatchVerificationTest.items("A")));
            JsonObject result=child.read();assertEquals(1,result.get("id").getAsInt());assertFalse(result.getAsJsonObject("result").get("isError").getAsBoolean());agent.verify();
            child.send(cancel("1"));child.send(cancel("0"));child.send(rpc("2","ping"));assertEquals(2,child.read().get("id").getAsInt());
            assertNull(child.lines.poll(100,TimeUnit.MILLISECONDS));
        }
    }

    @Test void cancelCompletionRaceProducesAtMostOneNonCancelledResponse() throws Exception {
        for(int i=0;i<12;i++) {
            CountDownLatch query = new CountDownLatch(1), reply = new CountDownLatch(1);
            try(var agent=new BuildSafetyTest.FakeAgent((in,out)->{
                String c=in.readLine();assertNotNull(c);query.countDown();
                assertTrue(reply.await(3,TimeUnit.SECONDS));BatchVerificationTest.reply(out,c,"applied");
                // A close racing the reply can be observed as EOF or a TCP reset.
                // SocketTimeoutException is not accepted: cancellation must close promptly.
                try { assertNull(in.readLine()); } catch (SocketException closedByCancellation) { }
            })) {
                LinkedBlockingQueue<JsonObject> answers=new LinkedBlockingQueue<>();
                try(var dispatcher=new BatchDispatcher(new McpServer(),answers::add)) {
                    dispatcher.dispatch(BatchVerificationTest.request(agent.server.getLocalPort(),BatchVerificationTest.items("A")));
                    assertTrue(query.await(3,TimeUnit.SECONDS));reply.countDown();
                    dispatcher.dispatch(cancel("1"));
                }
                assertTrue(answers.size() <= 1, "completion/cancel race cannot duplicate a response");
                if (!answers.isEmpty()) {
                    JsonObject completed = answers.remove();
                    assertEquals(1, completed.get("id").getAsInt());
                    assertFalse(completed.getAsJsonObject("result").get("isError").getAsBoolean());
                }
                agent.verify();
            }
        }
    }

    final class Child implements AutoCloseable {
        final Process process;
        final PrintWriter input;
        final LinkedBlockingQueue<String> lines=new LinkedBlockingQueue<>();
        final Thread reader;
        final Path stderr;
        Child() throws Exception {
            stderr=Files.createTempFile(dir,"mcp-", ".stderr");
            ProcessBuilder b=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",System.getProperty("reclazz.mcp.releaseJar"));
            b.redirectError(stderr.toFile());
            for(String key:List.of("CLASSPATH","JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS"))b.environment().remove(key);
            process=b.start();input=new PrintWriter(process.getOutputStream(),true,StandardCharsets.UTF_8);
            reader=new Thread(()->{try(var in=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=in.readLine())!=null)lines.add(line);}catch(IOException ignored){}});
            reader.setDaemon(true);reader.start();
        }
        void send(JsonObject r){input.println(r);assertFalse(input.checkError());}
        JsonObject read() throws Exception {String line=lines.poll(5,TimeUnit.SECONDS);assertNotNull(line,()->"No response; stderr="+stderr);return JsonParser.parseString(line).getAsJsonObject();}
        void initialize(String version) throws Exception {
            JsonObject r=rpc("0","initialize");JsonObject p=new JsonObject();p.addProperty("protocolVersion",version);r.add("params",p);send(r);
            assertEquals(version,read().getAsJsonObject("result").get("protocolVersion").getAsString());
        }
        @Override public void close() throws Exception {
            input.close();if(!process.waitFor(3,TimeUnit.SECONDS)){process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS));}
            reader.join(1000);
        }
    }
}
