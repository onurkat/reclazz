/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class WatcherHostPauseTest {
    @TempDir Path tmp;

    @Test @EnabledOnOs({OS.MAC,OS.LINUX})
    void healthyPollerResumesAfterHostPauseAndAcceptsABuildScan() throws Exception {
        Path port = tmp.resolve("agent.port");
        try (WatchedApp app = WatchedApp.in(tmp).agentArgs("startupDelaySec=0,portFile=" + port)
                .jvmArgs("-Dpause.fixture=" + tmp).with("App", APP).start()) {
            app.awaitWatcherReady();
            Files.createFile(tmp.resolve("arm"));
            app.awaitOrFail("ARMED=", "fixture must hold the next poll until the health checker can run");
            long pid = Long.parseLong(app.latest("ARMED=").split("ARMED=")[1].trim());
            boolean held = false;
            try {
                signal(pid, "STOP"); held = true;
                Thread.sleep(10_200);
            } finally {
                if (held) signal(pid, "CONT");
                Files.writeString(tmp.resolve("resumed"), "resumed");
            }
            app.awaitOrFail("PAUSE_NANOS=", "child monotonic clock must be sampled across the pause");
            long elapsed = Long.parseLong(app.latest("PAUSE_NANOS=").split("PAUSE_NANOS=")[1].trim());
            System.out.println("Child monotonic interval including suspension: " + elapsed + " ns");
            try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(Files.readString(port).trim()))) {
                socket.setSoTimeout(15000);
                var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                var output = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                for (String state : new String[]{"started","ok"}) {
                    output.println("BUILD " + state + " request=p-" + state + " owner=fixture");
                    String expected = "BUILD_ACK p-" + state + " " + state + " owner=fixture";
                    String line;
                    while ((line = input.readLine()) != null && !line.contains(expected)) { /* other status events */ }
                    assertNotNull(line, expected);
                }
            }
            assertTrue(app.output().stream().noneMatch(line -> line.contains("file watcher has not completed a poll")
                    || line.contains("file watcher resumed")), app::tail);
        }
    }
    private static void signal(long pid, String signal) throws Exception {
        assertEquals(0, new ProcessBuilder("kill", "-" + signal, Long.toString(pid)).start().waitFor());
    }

    // Control only this child JVM. Give the health scheduler one normal tick after resume before releasing the poll loop.
    private static final String APP = """
            package app;
            import java.nio.file.*;
            public class App {
                public static void main(String[] args) throws Exception {
                    Path dir=Path.of(System.getProperty("pause.fixture"));
                    while (!Files.exists(dir.resolve("arm"))) Thread.sleep(10);
                    Class<?> agent=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent");
                    var serverField=agent.getDeclaredField("statusServer"); serverField.setAccessible(true);
                    Object server=serverField.get(null);
                    var watcherField=server.getClass().getDeclaredField("doctorWatcher"); watcherField.setAccessible(true);
                    Object watcher=watcherField.get(server);
                    var lock=watcher.getClass().getDeclaredField("scanBarrierLock"); lock.setAccessible(true);
                    synchronized (lock.get(watcher)) {
                        long start=System.nanoTime();
                        System.out.println("ARMED="+ProcessHandle.current().pid());
                        while (!Files.exists(dir.resolve("resumed"))) Thread.sleep(10);
                        System.out.println("PAUSE_NANOS="+(System.nanoTime()-start));
                        Thread.sleep(1250);
                    }
                    new java.util.concurrent.CountDownLatch(1).await();
                }
            }
            """;
}
