/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A clean build, then an edit, in a running JVM.
 *
 * <p>Deleting the output tree and rebuilding it is a thing developers do
 * several times an hour, and it takes the watch with it: on Linux the watch is
 * registered against the inode, so the key is invalidated permanently. The
 * watcher used to drop it and never look again, which meant every later edit in
 * that tree went unnoticed, and when the tree was the only watched one the loop
 * stopped for the rest of the session after a single line printed during the
 * noisiest part of a build.
 *
 * <p>On a Mac this passes either way, and did before the fix: the JDK has no
 * native file watching there and polls instead, re-reading the directory every
 * cycle and never noticing it left. It is written for the machines where that
 * is not true, which is Linux, which is CI, containers, and the remote
 * development servers the README supports.
 */class ReloadSurvivesACleanBuildTest {

    @TempDir
    Path tmp;

    @Test
    void anEditAfterTheOutputTreeIsDeletedAndRebuiltStillReloads() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .with("Work", source("v1"))
                .with("App", """
                        package app;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                Work work = new Work();
                                System.out.println("APP_STARTED");
                                while (true) {
                                    Thread.sleep(400);
                                    System.out.println("SAW=" + work.tag());
                                }
                            }
                        }
                        """)
                .start()) {

            app.awaitOrFail("SAW=v1", "app did not start under the agent");

            // The control: reloading works before anything is deleted, so a
            // failure afterwards is about the deletion and not about the setup.
            app.rewrite("Work", source("v2"));
            app.awaitOrFail("SAW=v2", "reloading did not work even before the clean");

            // What `mvn clean` and `ant clean` do.
            try (var tree = Files.walk(app.classesDir())) {
                for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
            assertFalse(Files.exists(app.classesDir()), "the output tree is gone, as after a clean");
            Thread.sleep(1500);
            Files.createDirectories(app.classesDir());

            app.rewrite("Work", source("v3"));
            app.recompile("App");
            app.awaitOrFail("SAW=v3", "the watch did not come back with the directory, so every "
                    + "edit from here on is unnoticed");

            // And it keeps working, rather than the one recovered event being
            // all there is.
            app.rewrite("Work", source("v4"));
            app.awaitOrFail("SAW=v4", "the first edit after the clean landed and the next did not");
        }
    }

    private static String source(String value) {
        return """
                package app;
                public class Work {
                    public String tag() { return "%s"; }
                }
                """.formatted(value);
    }
}
