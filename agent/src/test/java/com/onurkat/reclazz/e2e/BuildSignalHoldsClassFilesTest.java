/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BuildSignalHoldsClassFilesTest {
    @TempDir Path tmp;

    @Test
    void aFailedBuildStaysLiveOnlyAfterTheNextSuccess() throws Exception {
        Path portFile = tmp.resolve("agent.port");
        try (WatchedApp app = WatchedApp.in(tmp)
                .agentArgs("startupDelaySec=1,debounceMs=100,portFile=" + portFile)
                .with("A", unit("A", 1)).with("B", unit("B", 1))
                .with("App", """
                        package app;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                A a = new A(); B b = new B();
                                while (true) {
                                    System.out.println("VALUES=" + a.value() + ":" + b.value());
                                    Thread.sleep(25);
                                }
                            }
                        }
                        """).start()) {
            app.awaitOrFail("VALUES=1:1", "initial values");
            app.awaitOrFail("] Watching 1 director", "watcher ready");
            try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(Files.readString(portFile).trim()))) {
                send(socket, "BUILD started");
                app.rewrite("A", unit("A", 2));
                send(socket, "SCAN");
                assertFalse(app.awaits("VALUES=2:", 3), () -> "unfinished build escaped: " + app.tail());
                send(socket, "BUILD failed");
                assertFalse(app.awaits("VALUES=2:", 1), "a failed build stays held");
                send(socket, "BUILD started");
                app.rewrite("B", unit("B", 2));
                send(socket, "BUILD ok");
                app.awaitOrFail("VALUES=2:2", "both classes must apply after success");
                app.awaitOrFail("2 class files changed together", "one accepted batch");
                System.out.println("[build-package] heldBeforeSuccess=2 appliedAfterSuccess=2");
            }
        }
    }

    @Test
    void disconnectedOwnerKeepsFailedOutputHeldUntilItsOwnRecovery() throws Exception {
        Path portFile = tmp.resolve("owned.port");
        try (WatchedApp app = WatchedApp.in(tmp)
                .agentArgs("startupDelaySec=1,debounceMs=100,portFile=" + portFile)
                .with("A", unit("A", 1))
                .with("App", """
                        package app;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                A a = new A();
                                while (true) { System.out.println("VALUE=" + a.value()); Thread.sleep(25); }
                            }
                        }
                        """).start()) {
            app.awaitOrFail("VALUE=1", "initial value");
            app.awaitOrFail("] Watching 1 director", "watcher ready");
            int port = Integer.parseInt(Files.readString(portFile).trim());
            try (Socket first = new Socket("127.0.0.1", port)) {
                first.setSoTimeout(5000);
                var in = new java.io.BufferedReader(new java.io.InputStreamReader(first.getInputStream(), StandardCharsets.UTF_8));
                send(first, "BUILD started request=a1 owner=alice");
                receipt(in, "BUILD_ACK a1 started owner=alice");
                app.rewrite("A", unit("A", 2));
                send(first, "SCAN");
                send(first, "BUILD failed request=a2 owner=alice");
                receipt(in, "BUILD_ACK a2 failed owner=alice");
            }
            try (Socket second = new Socket("127.0.0.1", port)) {
                second.setSoTimeout(5000);
                var in = new java.io.BufferedReader(new java.io.InputStreamReader(second.getInputStream(), StandardCharsets.UTF_8));
                for (String state : java.util.List.of("started", "ok", "failed")) {
                    send(second, "BUILD " + state + " request=b-" + state + " owner=bob");
                    receipt(in, "BUILD_REJECTED b-" + state + " " + state + " owner=bob");
                }
                send(second, "BUILD ok request=legacy");
                receipt(in, "BUILD_REJECTED legacy ok");
                assertFalse(app.awaits("VALUE=2", 2), () -> "failed output escaped: " + app.tail());
                send(second, "BUILD started request=a3 owner=alice");
                receipt(in, "BUILD_ACK a3 started owner=alice");
                app.rewrite("A", unit("A", 3));
                send(second, "BUILD ok request=a4 owner=alice");
                receipt(in, "BUILD_ACK a4 ok owner=alice");
                app.awaitOrFail("VALUE=3", "same owner recovers across connections");
            }
        }
    }

    private static void receipt(java.io.BufferedReader in, String expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        String line;
        while (System.nanoTime() < deadline && (line = in.readLine()) != null) {
            if (line.contains("\"message\":\"" + expected + "\"")) return;
        }
        fail("Missing receipt: " + expected);
    }

    private static void send(Socket socket, String command) throws Exception {
        socket.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private static String unit(String name, int value) {
        return "package app;\npublic class " + name + " { public int value() { return " + value + "; } }\n";
    }
}
