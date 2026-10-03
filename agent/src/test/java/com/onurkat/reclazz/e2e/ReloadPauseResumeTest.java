/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ReloadPauseResumeTest {
    @TempDir Path tmp;

    @Test void pauseRetainsSavesAndResumeRespectsIndependentBuildOwnership() throws Exception {
        Path portFile = tmp.resolve("agent.port");
        try (WatchedApp app = WatchedApp.in(tmp)
                .agentArgs("startupDelaySec=1,debounceMs=100,portFile=" + portFile)
                .with("A", unit(1)).with("App", """
                    package app;
                    public class App {
                        public static void main(String[] args) throws Exception {
                            A a = new A(); long pid = ProcessHandle.current().pid();
                            while (true) {
                                System.out.println("VALUE=" + a.value() + " PID=" + pid);
                                Thread.sleep(25);
                            }
                        }
                    }
                    """).start()) {
            app.awaitOrFail("VALUE=1 PID=", "initial value");
            app.awaitOrFail("] Watching 1 director", "watcher ready");
            String pid = app.latest("VALUE=1 PID=").split("PID=")[1];
            try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(Files.readString(portFile).trim()))) {
                socket.setSoTimeout(5000);
                var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                control(socket, in, "pause", "p1", true);
                control(socket, in, "pause", "p2", true);
                app.rewrite("A", unit(2)); send(socket, "SCAN");
                assertFalse(app.awaits("VALUE=2 PID=", 2), app::tail);
                String state = control(socket, in, "status", "s1", true);
                assertTrue(state.contains("pendingClasses=1"), state);
                send(socket, "PENDING");
                receipt(in, "Automatic reload: paused=true pendingClasses=1");
                app.rewrite("A", unit(3));
                control(socket, in, "resume", "r1", false);
                app.awaitOrFail("VALUE=3 PID=" + pid, "latest output applies to same retained object/JVM");
                assertFalse(app.output().stream().anyMatch(line -> line.contains("VALUE=2 PID=")));
                control(socket, in, "resume", "r2", false);

                // Neither active nor failed owned output may escape through manual resume.
                send(socket, "BUILD started request=b1 owner=alice"); receipt(in, "BUILD_ACK b1 started owner=alice");
                control(socket, in, "pause", "p3", true);
                app.rewrite("A", unit(4)); send(socket, "SCAN");
                assertTrue(control(socket, in, "resume", "r3", false).contains("buildHold=named"));
                assertFalse(app.awaits("VALUE=4 PID=", 2), app::tail);
                send(socket, "BUILD failed request=b2 owner=alice"); receipt(in, "BUILD_ACK b2 failed owner=alice");
                control(socket, in, "pause", "p4", true);
                assertTrue(control(socket, in, "resume", "r4", false).contains("buildHold=named"));
                assertFalse(app.awaits("VALUE=4 PID=", 2), app::tail);
                send(socket, "BUILD ok request=b3 owner=bob"); receipt(in, "BUILD_REJECTED b3 ok owner=bob");

                // The owner's success may clear BUILD, but cannot clear manual pause.
                control(socket, in, "pause", "p5", true);
                app.rewrite("A", unit(5));
                send(socket, "BUILD ok request=b4 owner=alice"); receipt(in, "BUILD_ACK b4 ok owner=alice");
                assertFalse(app.awaits("VALUE=5 PID=", 2), app::tail);
                assertTrue(control(socket, in, "status", "s2", true).contains("buildHold=none"));
                control(socket, in, "resume", "r5", false);
                app.awaitOrFail("VALUE=5 PID=" + pid, "successful owner output released after manual resume");
                assertFalse(app.output().stream().anyMatch(line -> line.contains("VALUE=4 PID=")));
                System.out.println("[pause-resume] held=2,4,5 applied=3,5 samePID=" + pid);
            }
        }
    }

    @Test void pauseAlsoRetainsAutomaticSourceCompilation() throws Exception {
        Path portFile = tmp.resolve("source.port");
        try (var app = WatchedApp.in(tmp).mavenLayout()
                .agentArgs("startupDelaySec=1,debounceMs=100,autoCompile=true,portFile=" + portFile)
                .with("A", unit(1)).with("App", """
                    package app;
                    public class App {
                        public static void main(String[] args) throws Exception {
                            A a = new A();
                            while (true) { System.out.println("VALUE=" + a.value()); Thread.sleep(25); }
                        }
                    }
                    """).start()) {
            app.awaitOrFail("VALUE=1", "initial value"); app.awaitOrFail("] Watching ", "source watcher");
            try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(Files.readString(portFile).trim()))) {
                socket.setSoTimeout(5000);
                var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                control(socket, in, "pause", "p", true);
                byte[] before = Files.readAllBytes(app.classesDir().resolve("app/A.class"));
                Files.writeString(tmp.resolve("src/main/java/app/A.java"), unit(2));
                send(socket, "SCAN");
                assertFalse(app.awaits("VALUE=2", 2), app::tail);
                assertArrayEquals(before, Files.readAllBytes(app.classesDir().resolve("app/A.class")));
                // Source watching has no initial file-state baseline: the first SCAN
                // discovers both A.java and App.java, and both must remain queued.
                String pending = control(socket, in, "status", "s", true);
                assertTrue(pending.contains("pendingActions=2"), pending);
                control(socket, in, "resume", "r", false);
                app.awaitOrFail("VALUE=2", "source compiled and applied after resume");
            }
        }
    }

    @Test void pausedPropertySavesAreAppliedOnlyAfterResume() throws Exception {
        Path properties = Files.createDirectories(tmp.resolve("classes")).resolve("application.properties");
        Files.writeString(properties, "demo.value=old\n");
        Path portFile = tmp.resolve("property.port");
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100,portFile=" + portFile)
                .with("App", """
                    package app;
                    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
                    @org.springframework.context.annotation.PropertySource("classpath:application.properties")
                    public class App {
                        @org.springframework.beans.factory.annotation.Value("${demo.value}") String value;
                        public static void main(String[] args) throws Exception {
                            var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext(App.class);
                            App bean = ctx.getBean(App.class);
                            while (true) { System.out.println("PROPERTY=" + bean.value); Thread.sleep(25); }
                        }
                    }
                    """).start()) {
            app.awaitOrFail("PROPERTY=old", "property baseline"); app.awaitOrFail("] Watching ", "property watcher");
            try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(Files.readString(portFile).trim()))) {
                socket.setSoTimeout(5000);
                var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                control(socket, in, "pause", "p", true);
                Files.writeString(properties, "demo.value=new\n"); send(socket, "SCAN");
                assertFalse(app.awaits("PROPERTY=new", 2), app::tail);
                assertTrue(control(socket, in, "status", "s", true).contains("pendingActions=1"));
                control(socket, in, "resume", "r", false);
                app.awaitOrFail("PROPERTY=new", "pending property applied after resume");
            }
        }
    }

    private static String control(Socket socket, BufferedReader in, String action, String id, boolean paused) throws Exception {
        send(socket, "RELOAD " + action + " request=" + id);
        return receipt(in, "RELOAD_STATE " + id + " " + action + " paused=" + paused);
    }
    private static String receipt(BufferedReader in, String expected) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String line;
        while (System.nanoTime() < until && (line = in.readLine()) != null)
            if (line.contains("\"message\":\"" + expected)) return line;
        throw new AssertionError("Missing receipt: " + expected);
    }
    private static void send(Socket socket, String command) throws Exception {
        socket.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }
    private static String unit(int value) {
        return "package app; public class A { public int value() { return " + value + "; } }";
    }
}
