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
class UninstrumentedReportTest {

    /**
     * Asserted on the compiled reloader rather than by running a reload,
     * because the failure is a message that should not appear and no
     * behavioural assertion notices an absent warning.
     */
    @Test
    void theWarningIsGuardedByWhetherTheClassIsActuallyLoaded() throws IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/onurkat/reclazz/reload/StructuralReloader.java"));

        assertTrue(source.contains("reportUninstrumented(className, findLoadedClass(className))"),
                "the reporter has to be told whether the JVM has this class");
        assertTrue(source.contains("if (loaded == null) return;"),
                "a class the JVM never loaded is a new file, and there is nothing to say");
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
