/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class AutoCompileHoldsABrokenPackageTest {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void fixingOrDeletingOnlyTheBlockerRetriesTheWholeAttempt(boolean delete) throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp).mavenLayout()
                .agentArgs("startupDelaySec=1,debounceMs=500,autoCompile=true")
                .with("Service", unit("Service", 1)).with("Dto", unit("Dto", 1))
                .with("App", """
                        package app;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                Service service = new Service(); Dto dto = new Dto();
                                while (true) {
                                    System.out.println("VALUES=" + service.value() + ":" + dto.value());
                                    Thread.sleep(25);
                                }
                            }
                        }
                        """).start()) {
            app.awaitOrFail("VALUES=1:1", "original application");
            app.awaitOrFail("] Watching ", "source watcher ready");
            Path sources = tmp.resolve("src/main/java/app");
            Path output = app.classesDir().resolve("app/Service.class");
            byte[] before = Files.readAllBytes(output);
            Files.writeString(sources.resolve("Service.java"), unit("Service", 2));
            Files.writeString(sources.resolve("Dto.java"), "package app;\npublic class Dto { broken syntax }\n");
            app.awaitOrFail("Compilation failed:", "the attempt must reject the syntax error");
            boolean unchanged = Arrays.equals(before, Files.readAllBytes(output));
            System.out.println("[build-package] failedAttemptOutputUnchanged=" + unchanged);
            assertTrue(unchanged, "a failed source package must leave real output byte-identical");
            app.awaitOrFail("Holding 2 source files", "both sources retained for retry");
            assertFalse(app.output().stream().anyMatch(line -> line.contains("VALUES=2:")), app.tail());
            if (delete) Files.delete(sources.resolve("Dto.java"));
            else Files.writeString(sources.resolve("Dto.java"), unit("Dto", 2));
            app.awaitOrFail(delete ? "VALUES=2:1" : "VALUES=2:2", "the untouched Service must also retry");
            System.out.println("[build-package] retryServiceValue=2 blockerDeleted=" + delete);
        }
    }

    private static String unit(String name, int value) {
        return "package app;\npublic class " + name + " { public int value() { return " + value + "; } }\n";
    }
}
