/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The first change to a file in a session waits for the JDK's file watcher
 * to notice it, which on macOS is a poll on a two-second cycle. The IDE
 * knows the moment a build has finished and says so over the status socket;
 * the watcher then looks for itself. Measured here both ways, on fresh
 * classes each time so every change is a first change.
 */
class ScanOnBuildFinishedLatencyTest {

    private static final int ROUNDS = 3;

    @TempDir
    Path tmp;

    @Test
    void aScanAfterTheBuildBeatsWaitingForThePoll() throws Exception {
        Path portFile = tmp.resolve("agent.port");
        WatchedApp.Builder builder = WatchedApp.in(tmp)
                .agentArgs("startupDelaySec=1,debounceMs=200,portFile=" + portFile)
                .with("App", driver());
        for (int i = 0; i < ROUNDS * 2; i++) builder.with("Unit" + i, unit(i, "v1"));

        try (WatchedApp app = builder.start()) {
            app.awaitOrFail("APP_STARTED", "the app did not start under the agent");
            app.awaitOrFail("Content-hash baseline", "the watcher never took its baseline");
            int port = Integer.parseInt(Files.readString(portFile).trim());

            List<Long> polled = new ArrayList<>();
            List<Long> scanned = new ArrayList<>();
            for (int i = 0; i < ROUNDS * 2; i++) {
                boolean nudge = i % 2 == 1;
                String name = "Unit" + i;
                long written = System.currentTimeMillis();
                app.rewrite(name, unit(i, "v2"));
                if (nudge) {
                    try (Socket s = new Socket("127.0.0.1", port)) {
                        OutputStream out = s.getOutputStream();
                        out.write("SCAN\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        Thread.sleep(50);            // let the line land before the socket goes
                    }
                }
                String noticedLine = "Class file changed: " + name + ".class";
                assertTrue(app.awaits(noticedLine, 30), () -> "the agent never noticed " + name + ":\n" + app.tail());
                long noticed = app.firstSeenMillis(noticedLine) - written;
                (nudge ? scanned : polled).add(noticed);
                app.awaitOrFail("Reloaded app." + name, name + " never reloaded");
            }
            System.out.println("[diag] writeToNoticedMs polled=" + polled + " scanned=" + scanned);

            for (long ms : scanned) {
                assertTrue(ms < 1500, "a scan on request should notice the change well inside the JDK's poll cycle, took "
                        + ms + "ms (all: " + scanned + ")");
            }
        }
    }

    private static String unit(int i, String version) {
        return """
                package app;
                public class Unit%d {
                    public String v() { return "%s"; }
                }
                """.formatted(i, version);
    }

    private static String driver() {
        return """
                package app;
                public class App {
                    public static void main(String[] args) throws Exception {
                        System.out.println("APP_STARTED");
                        while (true) { Thread.sleep(1000); }
                    }
                }
                """;
    }
}
