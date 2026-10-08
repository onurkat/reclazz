/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CoreLambdaReloadTest {
    @TempDir Path dir;

    @Test
    void introducedLambdasUseCurrentCapturesAndExposeSerializationLimit() throws Exception {
        try (var app = WatchedApp.in(dir)
                .with("Holder", "package app; public class Holder { public String state = \"initial\"; public String run() { return \"before\"; } }")
                .with("App", """
                        package app;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                Holder held = new Holder(); held.state = "user";
                                String identity = ProcessHandle.current().pid() + ":" + java.util.UUID.randomUUID();
                                System.out.println("ID=" + identity);
                                while (true) {
                                    System.out.println("LIVE=" + held.run() + " id=" + identity);
                                    Thread.sleep(100);
                                }
                            }
                        }
                        """).start()) {
            app.awaitOrFail("LIVE=before", "original held object must run first");
            app.awaitOrFail("] Watching 1 director", "watcher must be ready before the edit");
            String identity = app.latest("ID=").substring(3);
            for (int version : new int[]{1, 2, 3}) {
                app.rewrite("Holder", holder(version));
                String expected = "LIVE=v" + version + ":user:local" + version + ":a-b:serial=NotSerializableException id=" + identity;
                app.awaitOrFail(expected, "new lambdas must execute on the held object in the same process");
                assertEquals(expected, app.latest("LIVE="), app.tail());
            }
            assertEquals(1, app.output().stream().filter(s -> s.startsWith("ID=")).count());
        }
    }

    private static String holder(int version) {
        return """
                package app;
                import java.io.*;
                import java.util.function.Supplier;
                public class Holder {
                    public String state = "initial";
                    public String run() {
                        String local = "local%d";
                        Supplier<String> capture = () -> helper(state) + ":" + local;
                        String stream = java.util.List.of("A", "B").stream()
                            .map(s -> s.toLowerCase()).reduce((a, b) -> a + "-" + b).orElseThrow();
                        Supplier<String> serializable = (Supplier<String> & Serializable) () -> "serial";
                        String serialization;
                        try (var out = new ObjectOutputStream(new ByteArrayOutputStream())) {
                            out.writeObject(serializable); serialization = "ok";
                        } catch (IOException e) { serialization = e.getClass().getSimpleName(); }
                        return "v%d:" + capture.get() + ":" + stream + ":serial=" + serialization;
                    }
                    private String helper(String value) { return value; }
                }
                """.formatted(version, version);
    }
}
