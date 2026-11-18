package com.onurkat.reclazz.hybris.codegen;

import com.onurkat.reclazz.platform.HybrisContext;
import com.onurkat.reclazz.platform.ExtensionInfo;

import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.List;
import com.onurkat.reclazz.hybris.*;
import com.onurkat.reclazz.watcher.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the real reloader with a bounded shell fixture, not the SAP generator. */
@DisabledOnOs(OS.WINDOWS)
class CodegenSaveDuringRunTest {
    @TempDir Path tempDir;
    @Test void secondSaveInsideTouchWindowRunsAgain() throws Exception { exercise(true, false); }
    @Test void ownTouchDoesNotLoop() throws Exception { exercise(false, false); }
    /** A polling watcher under ant's CPU load reported the touch 4-6s late; that echo looped ant forever. */
    @Test void ownTouchReportedLateDoesNotLoop() throws Exception { exercise(false, true); }
    @Test void realEditReportedLateStillRunsAgain() throws Exception { exercise(true, true); }
    private void exercise(boolean edit, boolean lateEcho) throws Exception {
        Path root = tempDir;
        Path platform = root.resolve("bin/platform"), ext = root.resolve("bin/custom/demo");
        Files.createDirectories(platform.resolve("apache-ant/bin"));
        Files.createDirectories(ext.resolve("resources"));
        Path xml = ext.resolve("resources/demo-items.xml");
        Files.writeString(xml, "<items>v1</items>\n");
        Files.writeString(platform.resolve("setantenv.sh"), "export PATH=\"$PWD/apache-ant/bin:$PATH\"\n");
        Path ant = platform.resolve("apache-ant/bin/ant");
        Files.writeString(ant, """
                #!/bin/sh
                echo run >> runs
                cp ../custom/demo/resources/demo-items.xml observed.xml
                touch read.ready
                while [ ! -f release ]; do sleep 0.02; done
                echo BUILD SUCCESSFUL
                """);
        if (!ant.toFile().setExecutable(true)) throw new IllegalStateException("cannot make fixture executable");
        HybrisContext context = new HybrisContext(root);
        context.getExtensions().put("demo", new ExtensionInfo("demo", ext, List.of(), true, false, true));
        var reloader = new CodegenReloader(context);
        try {
            reloader.handle(new ChangeEvent(xml, ChangeEvent.Type.MODIFIED, "demo", "resources"));
            await(() -> Files.exists(platform.resolve("read.ready")));
            long touched = (Long) field(reloader, "lastTouchedAtMs");
            if (edit) Files.writeString(xml, "<items>v2</items>\n");
            if (lateEcho) setLong(reloader, "lastTouchedAtMs", touched - 10_000L);
            long delay = System.currentTimeMillis() - (Long) field(reloader, "lastTouchedAtMs");
            if (!lateEcho && delay >= 2800) throw new AssertionError("fixture missed echo window");
            reloader.handle(new ChangeEvent(xml, ChangeEvent.Type.MODIFIED, "demo", "resources"));
            boolean queued = (Boolean) field(reloader, "rerunPending");
            Files.createFile(platform.resolve("release"));
            await(() -> !(Boolean) field(reloader, "running"));
            String observed = Files.readString(platform.resolve("observed.xml")).trim();
            int runs = Files.readAllLines(platform.resolve("runs")).size();
            System.out.println("CODEGEN second-save-after-ms=" + delay + " queued=" + queued
                    + " ant-runs=" + runs + " source=" + Files.readString(xml).trim() + " generated-from=" + observed);
            assertEquals(edit, queued);
            assertEquals(edit ? 2 : 1, runs);
            assertTrue(observed.contains(edit ? "v2" : "v1"));
        } finally {
            Files.writeString(platform.resolve("release"), "");
            ((java.util.concurrent.ExecutorService) field(reloader, "executor")).shutdownNow();
        }
    }
    /** With the echo matched by content, a failed run must still let the same bytes be saved to retry. */
    @Test void sameContentSavedAgainAfterFailedRunRetries() throws Exception {
        Path platform = tempDir.resolve("bin/platform"), ext = tempDir.resolve("bin/custom/demo");
        Files.createDirectories(platform.resolve("apache-ant/bin"));
        Files.createDirectories(ext.resolve("resources"));
        Path xml = ext.resolve("resources/demo-items.xml");
        Files.writeString(xml, "<items>v1</items>\n");
        Files.writeString(platform.resolve("setantenv.sh"), "export PATH=\"$PWD/apache-ant/bin:$PATH\"\n");
        Path ant = platform.resolve("apache-ant/bin/ant");
        Files.writeString(ant, """
                #!/bin/sh
                echo run >> runs
                if [ ! -f fixed ]; then echo BUILD FAILED; exit 1; fi
                echo BUILD SUCCESSFUL
                """);
        if (!ant.toFile().setExecutable(true)) throw new IllegalStateException("cannot make fixture executable");
        HybrisContext context = new HybrisContext(tempDir);
        context.getExtensions().put("demo", new ExtensionInfo("demo", ext, List.of(), true, false, true));
        var reloader = new CodegenReloader(context);
        try {
            reloader.handle(new ChangeEvent(xml, ChangeEvent.Type.MODIFIED, "demo", "resources"));
            await(() -> Files.exists(platform.resolve("runs")) && !(Boolean) field(reloader, "running"));
            Files.createFile(platform.resolve("fixed"));
            reloader.handle(new ChangeEvent(xml, ChangeEvent.Type.MODIFIED, "demo", "resources"));
            await(() -> Files.readAllLines(platform.resolve("runs")).size() == 2 && !(Boolean) field(reloader, "running"));
            assertEquals(2, Files.readAllLines(platform.resolve("runs")).size());
        } finally {
            ((java.util.concurrent.ExecutorService) field(reloader, "executor")).shutdownNow();
        }
    }
    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static void setLong(Object target, String name, long value) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.setLong(target, value);
    }
    interface Check { boolean done() throws Exception; }
    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!check.done()) { if (System.nanoTime() > deadline) throw new AssertionError("fixture timeout"); Thread.sleep(10); }
    }
}
