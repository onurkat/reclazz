/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Saying something about the developer's server that is not true.
 *
 * <p>A class with no recorded metadata was reported as having been "loaded
 * before Reclazz could instrument it". That is one of two possible reasons for
 * the metadata to be missing, and the other one is ordinary: the JVM has never
 * loaded the class at all, because it is a new file compiled beside a changed
 * one. Observed live, on a class that had just been written for the first time:
 *
 * <pre>
 *   [WARN] Base2 was loaded before Reclazz could instrument it, so only method
 *          bodies can be reloaded; adding or removing members needs a restart.
 * </pre>
 *
 * <p>Base2 had never been loaded by anything. The sentence sent the reader
 * looking for a startup-ordering problem they did not have, and it appeared
 * every time somebody added a class.
 *
 * <p>The distinguishing question is whether the JVM actually has the class, so
 * that is the question now asked before the sentence is said.
 */
class UninstrumentedReportTest extends com.onurkat.reclazz.transform.TransformTestBase {

    /** Observe both branches instead of matching a particular local-variable spelling. */
    @Test
    void theWarningIsGuardedByWhetherTheClassIsActuallyLoaded() throws Exception {
        String freshName = "ReportNotYetLoaded";
        String loadedName = "ReportActuallyLoaded";
        String body = "public class %s { public String value(){return \"%s\";} }";
        byte[] fresh = compile(new SourceFile(freshName, body.formatted(freshName, "new"))).get(freshName);
        var raw = compile(new SourceFile(loadedName, body.formatted(loadedName, "before")));
        Class<?> loaded = defineAndLoad(raw, loadedName);
        Object held = loaded.getConstructor().newInstance();
        var instrumentation = net.bytebuddy.agent.ByteBuddyAgent.install();
        var context = new com.onurkat.reclazz.transform.TransformContext();
        var reloader = new StructuralReloader(instrumentation, context,
                com.onurkat.reclazz.config.AgentConfig.parse(null), null);
        List<String> warnings = new ArrayList<>();
        com.onurkat.reclazz.ui.StatusReporter.StatusListener listener = (level, message) -> {
            if (level.equals("WARN")) warnings.add(message);
        };
        com.onurkat.reclazz.ui.StatusReporter.addListener(listener);
        try {
            var newFile = reloader.reload(freshName, fresh);
            assertTrue(newFile.isSuccess(), newFile.getError());
            assertTrue(warnings.isEmpty(), "an unloaded new class must not produce a pre-attach warning: " + warnings);
            var edit = compile(new SourceFile(loadedName, body.formatted(loadedName, "after"))).get(loadedName);
            var result = reloader.reload(loadedName, edit);
            assertTrue(result.isSuccess(), result.getError());
            assertEquals("after", loaded.getMethod("value").invoke(held));
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains(loadedName)
                    && warnings.get(0).contains("loaded before Reclazz could instrument it"), warnings.toString());
        } finally {
            com.onurkat.reclazz.ui.StatusReporter.removeListener(listener);
        }
    }

    /** The sentence itself is still the right one for the case it belongs to. */
    @Test
    void theRemainingMessageStillDescribesTheRealCase() throws IOException {
        List<String> text = stringsIn("com/onurkat/reclazz/reload/StructuralReloader");

        assertTrue(text.stream().anyMatch(t -> t.contains("loaded before Reclazz could instrument it")),
                "a class that genuinely missed the transform still needs telling");
        assertTrue(text.stream().anyMatch(t -> t.contains("EntityManagerFactory loads them during startup")),
                "and the example that sends people here is worth keeping");
    }

    private static List<String> stringsIn(String internalName) throws IOException {
        try (InputStream in = UninstrumentedReportTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, "cannot read " + internalName);
            ClassReader reader = new ClassReader(in.readAllBytes());
            List<String> out = new ArrayList<>();
            char[] buffer = new char[reader.getMaxStringLength()];
            for (int i = 1; i < reader.getItemCount(); i++) {
                int offset = reader.getItem(i);
                if (offset == 0) continue;
                try {
                    if (reader.readByte(offset - 1) == 8) {
                        Object value = reader.readConst(i, buffer);
                        if (value instanceof String s) out.add(s);
                    }
                } catch (RuntimeException ignored) {
                    // not every pool slot reads back as a constant
                }
            }
            return out;
        }
    }
}
