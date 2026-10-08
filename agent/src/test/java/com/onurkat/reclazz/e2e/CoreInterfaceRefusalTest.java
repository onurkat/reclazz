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

class CoreInterfaceRefusalTest {
    @TempDir Path dir;

    @Test
    void stockVmKeepsInterfaceIdentityWhileEditedBodiesAndLaterSavesRun() throws Exception {
        try (var app = WatchedApp.in(dir)
                .with("Tag", "package app; public interface Tag { }")
                .with("Holder", holder(false, 1))
                .with("App", """
                        package app;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                Holder held = new Holder(); Class<?> original = held.getClass(); held.state = 29;
                                String id = ProcessHandle.current().pid() + ":" + java.util.UUID.randomUUID();
                                System.out.println("ID=" + id);
                                while (true) {
                                    System.out.println("LIVE=" + held.run() + ":tag=" + ((Object) held instanceof Tag)
                                        + ":interfaces=" + java.util.Arrays.toString(original.getInterfaces())
                                        + ":same=" + (original == held.getClass()) + ":state=" + held.state + " id=" + id);
                                    Thread.sleep(100);
                                }
                            }
                        }
                        """).start()) {
            app.awaitOrFail("LIVE=v1:tag=false", "original object must not implement Tag");
            app.awaitOrFail("] Watching 1 director", "watcher must be ready");
            String id = app.latest("ID=").substring(3);
            app.rewrite("Holder", holder(true, 2));
            String changed = "LIVE=v2:tag=false:interfaces=[]:same=true:state=29 id=" + id;
            app.awaitOrFail(changed, "body edit must reach the same object without pretending to add Tag");
            app.awaitOrFail("will not change the interfaces", "unsupported interface change must be explained");
            assertTrue(app.output().stream().anyMatch(s -> s.contains("added interface") && s.contains("Tag")), app.tail());
            app.rewrite("Holder", holder(false, 3));
            app.awaitOrFail("LIVE=v3:tag=false:interfaces=[]:same=true:state=29 id=" + id, "later ordinary save must still work");
            assertEquals(1, app.output().stream().filter(s -> s.startsWith("ID=")).count());
        }
    }

    private static String holder(boolean tagged, int version) {
        return "package app; public class Holder " + (tagged ? "implements Tag " : "")
                + "{ public int state = 7; public String run() { return \"v" + version + "\"; } }";
    }
}
