/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.fasterxml.jackson.databind.*;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import java.nio.file.*;
import java.net.Socket;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DoctorStatusTest {
    @TempDir Path dir;
    @Test void realJvmReportsWatchAndMatchingVerificationIdentity() throws Exception {
        assertTrue(Files.isRegularFile(Path.of(System.getProperty("reclazz.agent.jar"))));
        Path port=dir.resolve("doctor.port");
        try(var app=WatchedApp.in(dir).agentArgs("startupDelaySec=1,debounceMs=100,portFile="+port)
                .with("App", """
                    package app;
                    public class App { public static void main(String[] args) throws Exception {
                        System.out.println("PID="+ProcessHandle.current().pid());
                        while(true) { System.out.println("ALIVE");Thread.sleep(100); }
                    }}
                    """).start()) {
            app.awaitOrFail("] Watching 1 director","watcher ready");
            try(var socket=new Socket("127.0.0.1",Integer.parseInt(Files.readString(port).trim()))) {
                socket.setSoTimeout(3000);
                var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                send(socket,"DOCTOR actual");JsonNode d=read(in,"DOCTOR_RESULT actual ");
                assertEquals("observed",d.get("status").asText());
                assertEquals(app.latest("PID=").substring(4),d.get("pid").asText());
                assertEquals("watching",d.get("watcherState").asText());
                assertTrue(d.get("watchedDirectoryCount").asInt()>0);
                boolean found=false;for(JsonNode path:d.get("watchedDirectories"))
                    if(Path.of(path.asText()).toRealPath().equals(dir.resolve("classes/app").toRealPath())) found=true;
                assertTrue(found,d.toString());
                assertTrue(d.get("buildOwnershipSupported").asBoolean());assertTrue(d.get("verifySupported").asBoolean());
                assertTrue(d.get("scanSupported").asBoolean());assertEquals("none",d.get("buildHold").asText());
                send(socket,"VERIFY proof app.App "+"a".repeat(64));JsonNode v=read(in,"VERIFY_RESULT proof ");
                assertEquals(v.get("sessionId"),d.get("sessionId"));
                assertFalse(d.get("reloadConfirmed").asBoolean());
                app.awaitOrFail("ALIVE","app remains running");
            }
        }
    }
    private void send(Socket s,String command) throws Exception {s.getOutputStream().write((command+"\n").getBytes(StandardCharsets.UTF_8));s.getOutputStream().flush();}
    private JsonNode read(BufferedReader in,String prefix) throws Exception {
        var json=new ObjectMapper();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);String line;
        while(System.nanoTime()<deadline && (line=in.readLine())!=null) {
            JsonNode event=json.readTree(line);String msg=event.path("message").asText();
            if(msg.startsWith(prefix))return json.readTree(msg.substring(prefix.length()));
        }
        throw new AssertionError("Missing "+prefix);
    }
}
