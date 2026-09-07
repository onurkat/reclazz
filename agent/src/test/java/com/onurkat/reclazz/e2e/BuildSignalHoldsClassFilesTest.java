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

    private static void send(Socket socket, String command) throws Exception {
        socket.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private static String unit(String name, int value) {
        return "package app;\npublic class " + name + " { public int value() { return " + value + "; } }\n";
    }
}
