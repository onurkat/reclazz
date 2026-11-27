/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.StatusTestClient;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WatcherDeathIsReportedTest {
    @TempDir Path dir;
    @Test void closedWatcherIsReportedByHealthDoctorAndOnePush() throws Exception { exercise(false); }
    void exercise(boolean overwrite) throws Exception {
        Path port=dir.resolve("agent.port"),jar=dir.resolve("agent-copy.jar");
        Files.copy(Path.of(WatchedApp.agentJarOrSkip()),jar);
        try(var app=WatchedApp.in(dir).withoutAgent()
                .jvmArgs("-Dprobe.dir="+dir,"-javaagent:"+jar+"=watchDirs="+dir.resolve("classes")+",startupDelaySec=1,portFile="+port)
                .with("App",APP).start()) {
            app.awaitOrFail("] Watching 1 director","watcher ready");
            try(var socket=StatusTestClient.connect(port)) {
                socket.setSoTimeout(400);var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                var json=new ObjectMapper();List<String> errors=new ArrayList<>();boolean health=false,doctor=false,ledger=false,restartHealth=false;
                if(overwrite) {
                    Files.write(jar,new byte[4096]);
                    Files.writeString(dir.resolve("classes/app/Fresh.class"),"broken class triggers a new watcher path");
                } else Files.createFile(dir.resolve("kill-watcher"));
                long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10), nextRequest=0;
                while(System.nanoTime()<end) {
                    if(System.nanoTime()>=nextRequest) {
                        socket.getOutputStream().write("HEALTH\nDOCTOR probe\nPENDING\n".getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
                        nextRequest=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(300);
                    }
                    try {
                        String line=in.readLine();if(line==null)break;
                        var event=json.readTree(line);String message=event.path("message").asText();
                        if(event.path("level").asText().equals("ERROR") && message.startsWith("file watcher stopped ("))errors.add(message);
                        if(event.path("level").asText().equals("INFO") && message.startsWith("file watcher stopped ("))health=true;
                        if(message.startsWith("DOCTOR_RESULT probe "))doctor=json.readTree(message.substring("DOCTOR_RESULT probe ".length())).path("watcherState").asText().equals("stopped");
                        if(message.contains("The file watcher: it stopped during this session ("))ledger=true;
                        if(message.equals("1 thing waiting on a restart; ask PENDING for what."))restartHealth=true;
                    } catch(SocketTimeoutException idle) { }
                }
                System.out.println("WATCHER_DIAG overwrite="+overwrite+" health="+health+" doctor="+doctor+" pushes="+errors.size()+" ledger="+ledger+" restartHealth="+restartHealth);
                assertTrue(health,"HEALTH must report stopped: "+app.tail());assertTrue(doctor,app.tail());assertEquals(1,errors.size(),errors.toString());
                assertTrue(ledger,"PENDING must retain the stopped watcher: "+app.tail());
                assertTrue(restartHealth,"HEALTH must count the watcher restart concern: "+app.tail());
            }
        }
    }
    static final String APP="""
        package app;
        public class App {
            public static void main(String[] args) throws Exception {
                var trigger=java.nio.file.Path.of(System.getProperty("probe.dir"),"kill-watcher");
                while(!java.nio.file.Files.exists(trigger))Thread.sleep(20);
                var server=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent").getDeclaredField("statusServer");server.setAccessible(true);
                Object status=server.get(null);var field=status.getClass().getDeclaredField("doctorWatcher");field.setAccessible(true);Object watcher=field.get(status);
                var service=watcher.getClass().getDeclaredField("watchService");service.setAccessible(true);((java.nio.file.WatchService)service.get(watcher)).close();
                System.out.println("WATCHER_CLOSED");while(true)Thread.sleep(1000);
            }
        }
        """;
}
