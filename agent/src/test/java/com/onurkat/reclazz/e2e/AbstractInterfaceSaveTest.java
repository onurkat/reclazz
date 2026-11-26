/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AbstractInterfaceSaveTest {
    @TempDir Path dir;

    private static Map<String, String> sources(boolean added, String value) {
        return Map.of(
                "Api", "package app; public interface Api { String old();" + (added ? "String added();" : "") + "}",
                "Impl", "package app; public class Impl implements Api { public String old(){return \"" + value + "\";}"
                        + (added ? "public String added(){return \"new\";}" : "") + "}",
                "Caller", "package app; public class Caller { public String read(Api a){return a." + (added ? "added" : "old") + "();}}",
                "Other", "package app; public class Other { public String read(){return \"" + value + "\";}}");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void heldBatchPreservesWorkingCallerAndCanBeSavedAgain(boolean compile) throws Exception {
        var builder = WatchedApp.in(dir).mavenLayout().agentArgs("startupDelaySec=1,debounceMs=350,autoCompile=" + compile);
        sources(false, "old").forEach(builder::with);
        try (var app = builder.jvmArgs("-Dprobe.dir=" + dir).with("App", """
                package app;
                import java.nio.file.*;
                public class App {
                    public static void main(String[] args) throws Exception {
                        Api impl = new Impl(); var caller = new Caller(); var other = new Other();
                        System.out.println("BASE=" + caller.read(impl) + ":" + other.read());
                        for (int n=0; n<2; n++) {
                            while (!Files.exists(Path.of(System.getProperty("probe.dir"), "step"+n))) Thread.sleep(20);
                            String result;
                            do {
                                try { result=caller.read(impl)+":"+impl.old()+":"+other.read(); }
                                catch (LinkageError broken) { result=broken.getClass().getSimpleName(); }
                                System.out.println("STEP"+n+"="+result);
                                if(n==0 || result.equals("updated:updated:updated"))break;
                                Thread.sleep(50);
                            } while(true);
                        }
                    }
                }
                """).start()) {
            app.awaitOrFail("BASE=old:old", "existing caller failed before the save");
            app.awaitWatcherReady();
            save(app, compile, sources(true, "updated"));
            app.awaitOrFail("Held this save: app.Api, app.Caller, app.Impl, app.Other", "missing complete batch receipt");
            Files.createFile(dir.resolve("step0"));
            app.awaitOrFail("STEP0=old:old:old", "the held save changed a caller or a sibling");
            save(app, compile, sources(false, "updated"));
            Files.createFile(dir.resolve("step1"));
            app.awaitOrFail("STEP1=updated:updated:updated", "a previously withheld edit was lost");
            assertEquals(1L, app.output().stream().filter(line -> line.contains("Held this save:")).count(), app.tail());
        }
    }

    private void save(WatchedApp app, boolean compile, Map<String, String> sources) throws Exception {
        if (!compile) {
            app.rewriteAll(sources);
            return;
        }
        for (var entry : sources.entrySet())
            Files.writeString(dir.resolve("src/main/java/app").resolve(entry.getKey() + ".java"), entry.getValue());
    }
}
