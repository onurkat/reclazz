/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ActiveLog4jPropertiesTest {
    @Test void onlySuccessfulReloadsOfTheActiveFileReportApplied(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path active = classes.resolve("log4j2.properties");
        Path shadow = Files.createDirectories(classes.resolve("shadow")).resolve("log4j2.properties");
        String info = "name=Test\nrootLogger.level=info\nlogger.test.name=com.example.probe\nlogger.test.level=info\n";
        String debug = info.replace("test.level=info", "test.level=debug");
        Files.writeString(active, info);
        Files.writeString(shadow, info);
        String logging = System.getProperty("reclazz.logging.log4j.classpath");
        assertNotNull(logging);
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath() + File.pathSeparator + logging)
                .agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dlog4j.configurationFile=" + active.toUri(), "-Dprobe.dir=" + tmp)
                .with("App", APP).start()) {
            app.awaitOrFail("INITIAL=false", "configured logger did not start at INFO");
            app.awaitWatcherReady();

            Files.writeString(shadow, debug);
            app.awaitOrFail("Config file changed: log4j2.properties", "shadow file did not reach ordinary property processing");
            probe(tmp, app, 1, false);

            Files.writeString(active, debug + "appender.incomplete.name=MissingType\n");
            app.awaitOrFail("log4j2.properties was not applied", "invalid save was not reported as rejected");
            probe(tmp, app, 2, false);
            String applied = "log4j2.properties applied to the running Log4j2 context";
            assertFalse(app.output().stream().anyMatch(line -> line.contains(applied)), app.tail());

            Files.writeString(active, debug);
            app.awaitOrFail(applied, "valid active file did not reload");
            probe(tmp, app, 3, true);
        }
    }

    private static void probe(Path dir, WatchedApp app, int n, boolean expected) throws Exception {
        Files.createFile(dir.resolve("probe" + n));
        app.awaitOrFail("PROBE" + n + "=" + expected, "same JVM logger level differs");
    }

    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.apache.logging.log4j.LogManager;
            public class App {
                public static void main(String[] args) throws Exception {
                    var log = LogManager.getLogger("com.example.probe");
                    System.out.println("INITIAL=" + log.isDebugEnabled());
                    for (int n = 1; n <= 3; n++) {
                        while (!Files.exists(Path.of(System.getProperty("probe.dir"), "probe" + n))) Thread.sleep(20);
                        System.out.println("PROBE" + n + "=" + log.isDebugEnabled());
                    }
                }
            }
            """;
}
