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
import static org.junit.jupiter.api.Assertions.*;

class StaticInitializerResetReloadTest {
    @TempDir Path tmp;

    @Test void changedStaticFinalMapIsSeenByWarmedReaders() throws Exception {
        run(true, false);
    }

    @Test void defaultOffPreservesStaticFinalValue() throws Exception {
        run(false, false);
    }

    @Test void constantTransitionDoesNotBlockMapAndMethodReload() throws Exception {
        run(true, true);
    }

    private void run(boolean enabled, boolean transition) throws Exception {
        String initial = holder("old", 0), changed = holder("new", 1);
        if (transition) {
            initial = initial.replace("public static int version()", "public static final int COUNT=Integer.parseInt(\"7\"); public static int version()");
            changed = changed.replace("public static int version()", "public static final int COUNT=9; public static int version()");
        }
        try (var app = WatchedApp.in(tmp).jvmArgs("-Xbatch", "-Dtest.dir=" + tmp)
                .agentArgs("verbose=false,resetStatics=" + enabled + ",excludeClasses=app.NativeReader,startupDelaySec=1,debounceMs=200")
                .with("App", APP).with("NativeReader", NATIVE).with("Holder", initial).start()) {
            app.awaitOrFail("READY=old:old:old", "readers did not warm");
            app.awaitOrFail("] Watching ", "watcher did not start");
            app.rewrite("Holder", changed);
            app.awaitOrFail("Reloaded app.Holder", "owner did not reload");
            Files.createFile(tmp.resolve("probe"));
            app.awaitOrFail("READERS=", "readers did not run");
            assertEquals(enabled ? "READERS=new:new:new:1" : "READERS=old:old:old:1", app.latest("READERS="), app.tail());
            if (transition) {
                app.awaitOrFail("COUNT=7", "transition field was changed");
                assertTrue(app.output().stream().anyMatch(s -> s.contains("not reset: became-constant")), app.tail());
            }
        }
    }

    private static String holder(String value, int version) {
        return """
                package app;
                import java.util.Map;
                public class Holder {
                    public static final Map<String,String> CODES = Map.of("key", "%s");
                    public static String read() { return CODES.get("key"); }
                    public static int version() { return %d; }
                }
                """.formatted(value, version);
    }
    private static final String APP = """
            package app;
            import java.lang.invoke.*;
            import java.nio.file.*;
            import java.util.Map;
            public class App {
                static volatile Object sink;
                public static void main(String[] args) throws Throwable {
                    var lookup = MethodHandles.lookup();
                    var getter = lookup.findStaticGetter(Holder.class, "CODES", Map.class);
                    var bootstrap = Class.forName("com.onurkat.reclazz.bootstrap.ReclazzBootstrap")
                        .getMethod("bootstrapStaticFieldGet", MethodHandles.Lookup.class,
                            String.class, MethodType.class, String.class);
                    var site = (CallSite) bootstrap.invoke(null, lookup, "CODES",
                        MethodType.methodType(Map.class), "app/Holder");
                    var dynamic = site.dynamicInvoker();
                    for (int i=0; i<200000; i++) {
                        sink = NativeReader.read(); sink = getter.invoke(); sink = dynamic.invoke();
                    }
                    System.out.println("READY=" + NativeReader.read() + ":" + ((Map) getter.invoke()).get("key")
                        + ":" + ((Map) dynamic.invoke()).get("key"));
                    Path probe = Path.of(System.getProperty("test.dir"), "probe");
                    while (!Files.exists(probe)) Thread.sleep(10);
                    System.out.println("READERS=" + NativeReader.read() + ":" + ((Map) getter.invoke()).get("key")
                        + ":" + ((Map) dynamic.invoke()).get("key") + ":" + Holder.version());
                    try { System.out.println("COUNT=" + Holder.class.getField("COUNT").get(null)); }
                    catch (NoSuchFieldException expected) { }
                }
            }
            """;
    private static final String NATIVE = """
            package app;
            public class NativeReader {
                public static String read() { return Holder.CODES.get("key"); }
            }
            """;
}
