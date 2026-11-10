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

    @Test void packagedAwaitKeepsPingResponsiveAndClosesItsPersistentStreamOnCancelOrEof() throws Exception {
        for (boolean eof : List.of(false, true)) {
            CountDownLatch waiting = new CountDownLatch(1);
            try (var agent = new BuildSafetyTest.FakeAgent((in, out) -> {
                String c;
                while ((c = in.readLine()) != null) {
                    if (c.startsWith("DOCTOR ")) {
                        JsonObject d = DoctorTest.evidence(c); d.addProperty("sessionId", "session-test"); DoctorTest.send(out, c, d);
                    } else {
                        assertTrue(c.startsWith("RELOAD status request="), c);
                        waiting.countDown(); assertNull(in.readLine(), "cancel/EOF must close the subscription, not issue a mutation");
                        return;
                    }
                }
            }); var child = new Child()) {
                child.initialize("2025-06-18");
                child.send(AwaitReloadTest.request(agent.server.getLocalPort(), "checkpoint"));
                JsonObject cp = ReloadVerificationTest.text(child.read().getAsJsonObject("result"));
                assertEquals("ready", cp.get("status").getAsString());
                JsonObject wait = AwaitReloadTest.waitRequest(agent.server.getLocalPort(), cp); wait.addProperty("id", 42);
                wait.getAsJsonObject("params").getAsJsonObject("arguments").addProperty("timeoutMs", "60000");
                child.send(wait); assertTrue(waiting.await(3, TimeUnit.SECONDS));
                child.send(cancel("41")); child.send(rpc("43", "ping"));
                assertEquals(43, child.read().get("id").getAsInt());
                child.send(rpc("44", "tools/list")); assertEquals(11, child.read().getAsJsonObject("result").getAsJsonArray("tools").size());
                JsonObject other = BatchVerificationTest.request(agent.server.getLocalPort(), BatchVerificationTest.items("A")); other.addProperty("id", 45);
                child.send(other); assertEquals(-32000, child.read().getAsJsonObject("error").get("code").getAsInt());
                if (eof) child.input.close(); else child.send(cancel("42"));
                agent.verify();
                if (eof) { assertTrue(child.process.waitFor(3, TimeUnit.SECONDS)); assertEquals(0, child.process.exitValue()); }
                else { child.send(rpc("46", "ping")); assertEquals(46, child.read().get("id").getAsInt()); }
                assertNull(child.lines.poll(100, TimeUnit.MILLISECONDS), "cancelled await cannot emit a late response");
            }
        }
    }

    @Test void packagedEventsDoctorWaitIsCancellableWithoutBlockingPingOrQueuingOtherWork() throws Exception {
        for (boolean eof : List.of(false, true)) {
            CountDownLatch blocked = new CountDownLatch(1);
            try (var agent = new BuildSafetyTest.FakeAgent((in, out) -> {
                for (int i = 0; i < 2; i++) {
                    String c = in.readLine(); JsonObject d = DoctorTest.evidence(c);
                    d.addProperty("sessionId", "session-test"); DoctorTest.send(out, c, d);
                }
                assertTrue(in.readLine().startsWith("DOCTOR ")); blocked.countDown();
                assertNull(in.readLine(), "cancel/EOF closes the shared stream without mutating");
            }); var child = new Child()) {
                child.initialize("2025-06-18");
                child.send(AwaitReloadTest.request(agent.server.getLocalPort(), "checkpoint"));
                JsonObject cp = ReloadVerificationTest.text(child.read().getAsJsonObject("result"));
                JsonObject page = RecentEventsTest.request(agent.server.getLocalPort(), cp, "timeoutMs", "60000"); page.addProperty("id", 42);
                child.send(page); assertTrue(blocked.await(3, TimeUnit.SECONDS));
                child.send(rpc("43", "ping")); assertEquals(43, child.read().get("id").getAsInt());
                child.send(rpc("44", "tools/list")); assertEquals(11, child.read().getAsJsonObject("result").getAsJsonArray("tools").size());
                for (JsonObject other : List.of(RecentEventsTest.request(agent.server.getLocalPort(), cp), AwaitReloadTest.waitRequest(agent.server.getLocalPort(), cp),
                        BatchVerificationTest.request(agent.server.getLocalPort(), BatchVerificationTest.items("A")))) {
                    other.addProperty("id", 45); child.send(other);
                    assertEquals(-32000, child.read().getAsJsonObject("error").get("code").getAsInt());
                }
                if (eof) child.input.close(); else child.send(cancel("42"));
                agent.verify();
                if (eof) { assertTrue(child.process.waitFor(3, TimeUnit.SECONDS)); assertEquals(0, child.process.exitValue()); }
                else { child.send(rpc("46", "ping")); assertEquals(46, child.read().get("id").getAsInt()); }
                assertNull(child.lines.poll(100, TimeUnit.MILLISECONDS), "cancelled page cannot emit a late response");
            }
        }
    }

    @Test void packagedResourceEventsRemainCancellableAndMetadataNeverQueuesWork() throws Exception {
        for (boolean eof : List.of(false, true)) {
            CountDownLatch blocked = new CountDownLatch(1);
            try (var agent = new BuildSafetyTest.FakeAgent((in, out) -> {
                for (int i = 0; i < 2; i++) {
                    String c = in.readLine(); JsonObject d = DoctorTest.evidence(c);
                    d.addProperty("sessionId", "session-test"); DoctorTest.send(out, c, d);
                }
                assertTrue(in.readLine().startsWith("DOCTOR ")); blocked.countDown();
                assertNull(in.readLine(), "resource cancellation/EOF must close the shared stream");
            })) {
                Path port = Files.writeString(dir.resolve("resource.port"), Integer.toString(agent.server.getLocalPort()));
                try (var child = new Child("--port-file", port.toString())) {
                    child.initialize("2025-06-18");
                    child.send(AwaitReloadTest.request(agent.server.getLocalPort(), "checkpoint"));
                    JsonObject cp = ReloadVerificationTest.text(child.read().getAsJsonObject("result"));
                    JsonObject page = McpResourcesTest.rpc("resources/read", McpResourcesTest.params("uri", McpResourcesTest.uri(cp, "0")));
                    page.addProperty("id", 42); child.send(page); assertTrue(blocked.await(3, TimeUnit.SECONDS));
                    child.send(cancel("\"42\"")); // Wrong ID type must not cancel numeric42.
                    for (String method : List.of("ping", "tools/list", "resources/list", "resources/templates/list", "prompts/list", "prompts/get")) {
                        JsonObject pure = rpc("43", method);
                        if (method.equals("prompts/get")) pure.add("params", McpResourcesTest.params("name", "verify_my_last_change"));
                        child.send(pure); JsonObject answer = child.read();
                        assertEquals(43, answer.get("id").getAsInt()); assertTrue(answer.has("result"), answer.toString());
                    }
                    child.send(page); assertEquals(-32600, child.read().getAsJsonObject("error").get("code").getAsInt());
                    for (JsonObject other : List.of(page.deepCopy(), McpResourcesTest.rpc("resources/read", McpResourcesTest.params("uri", "reclazz://status")),
                            BatchVerificationTest.request(agent.server.getLocalPort(), BatchVerificationTest.items("A")))) {
                        other.addProperty("id", 45); child.send(other);
                        assertEquals(-32000, child.read().getAsJsonObject("error").get("code").getAsInt());
                    }
                    if (eof) child.input.close(); else child.send(cancel("42"));
                    agent.verify();
                    if (eof) { assertTrue(child.process.waitFor(3, TimeUnit.SECONDS)); assertEquals(0, child.process.exitValue()); }
                    else {
                        for (int i = 0; i < 30; i++) {
                            page.addProperty("id", 100 + i); child.send(page); JsonObject answer = child.read();
                            assertEquals(100 + i, answer.get("id").getAsInt());
                            if (answer.has("result")) {
                                JsonObject data = JsonParser.parseString(answer.getAsJsonObject("result").getAsJsonArray("contents").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
                                assertEquals("unavailable", data.get("status").getAsString()); break;
                            }
                            assertTrue(i < 29, "resource worker slot never released"); Thread.sleep(10);
                        }
                    }
                    assertNull(child.lines.poll(100, TimeUnit.MILLISECONDS), "cancelled resource must not emit a late answer");
                }
            }
        }
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
                child.send(rpc("3","tools/list"));assertEquals(11,child.read().getAsJsonObject("result").getAsJsonArray("tools").size());
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
        Child(String... startup) throws Exception {
            stderr=Files.createTempFile(dir,"mcp-", ".stderr");
            ProcessBuilder b=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",System.getProperty("reclazz.mcp.releaseJar"));
            b.command().addAll(List.of(startup));
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
