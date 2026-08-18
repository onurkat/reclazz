/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adding a field to a JPA entity reloads the class and does not reach the
 * database, and until now nothing said so.
 *
 * <p>Measured on Spring Boot 3.3 with Hibernate, on JetBrains Runtime with
 * {@code -XX:+AllowEnhancedClassRedefinition}, which is the best case this
 * problem has: the class gained the field, Hibernate's metamodel did not, the
 * table had no column, and a value written and read back through a cleared
 * persistence context came back null. The reload log said "Reloaded".
 *
 * <p>The fix is not to make it work. Refreshing the mapping means rebuilding
 * the SessionFactory, which is a restart of the persistence layer under another
 * name, and it would still leave the missing column, so on a project with
 * {@code ddl-auto} at validate or none it would convert a working application
 * into one that fails its next query. The fix is to say what happened.
 *
 * <p>What these tests hold is the boundary: it fires for a mapped class that
 * gained something the mapping would carry, and it stays quiet for everything
 * else, because a warning that cries wolf on every reload of every class is one
 * nobody reads by the third day.
 */
class JpaEntityChangeTest {

    @Test
    void aNewPersistentFieldOnAnEntityIsReported() throws IOException {
        var change = JpaEntityChange.check(EntityBefore.class, bytecodeOf(EntityAfter.class));

        assertNotNull(change, "this is the case the whole class exists for");
        assertEquals(java.util.List.of("currency"), change.added());
        assertTrue(change.removed().isEmpty());
        assertTrue(change.describe().contains("gained currency"), change.describe());
    }

    @Test
    void aRemovedPersistentFieldIsReportedToo() throws IOException {
        var change = JpaEntityChange.check(EntityAfter.class, bytecodeOf(EntityBefore.class));

        assertNotNull(change);
        assertEquals(java.util.List.of("currency"), change.removed());
        assertTrue(change.describe().contains("lost currency"), change.describe());
    }

    /**
     * A class that is not mapped has no mapping to fall behind, and warning
     * about it would put a persistence note on every ordinary reload.
     */
    @Test
    void anOrdinaryClassIsNotReported() throws IOException {
        assertNull(JpaEntityChange.check(PlainBefore.class, bytecodeOf(PlainAfter.class)));
    }

    /**
     * Static, transient and {@code @Transient} fields are outside the mapping
     * by the specification. Reporting them would be a warning about a change
     * that changes nothing.
     */
    @Test
    void fieldsOutsideTheMappingDoNotCount() throws IOException {
        assertNull(JpaEntityChange.check(EntityBefore.class, bytecodeOf(EntityWithUnmapped.class)),
                "none of these would ever have become a column");
    }

    @Test
    void anUnchangedEntityIsSilent() throws IOException {
        assertNull(JpaEntityChange.check(EntityAfter.class, bytecodeOf(EntityAfter.class)));
    }

    /**
     * On a stock JVM the loaded class carries the members Reclazz injects.
     * Treating those as fields the developer removed would produce a
     * persistence warning naming {@code __reclazz$ext} on the first reload of
     * every entity.
     */
    @Test
    void theInjectedMembersAreNotMistakenForMapping() throws IOException {
        var change = JpaEntityChange.check(EntityInstrumented.class, bytecodeOf(EntityBefore.class));

        assertNull(change,
                "the injected members are ours; a mapping never saw them and "
                + "never will, so they cannot have been lost");
    }

    /**
     * The comparison has to be taken before the redefinition, not after.
     *
     * <p>On an enhanced-redefinition VM the redefine puts the field on the
     * loaded class, so a check made afterwards compares the new bytecode
     * against a class that already matches it and finds nothing. That is the
     * shape this simulates: the "loaded" side already has the field. Silence
     * here is correct for a check that runs too late, which is why the callers
     * must not run it there.
     */
    @Test
    void aCheckTakenAfterTheRedefinitionWouldFindNothing() throws IOException {
        assertNull(JpaEntityChange.check(EntityAfter.class, bytecodeOf(EntityAfter.class)),
                "this is what the late check sees, and why both call sites "
                + "compare before redefineClasses rather than after");
    }

    @Test
    void bothReloadEnginesCompareBeforeRedefining() throws IOException {
        String classReloader = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/onurkat/reclazz/agent/ClassReloader.java"));

        int checkAt = classReloader.indexOf("JpaEntityChange.check(existingClass, newBytecode)");
        int redefineAt = classReloader.indexOf("instrumentation.redefineClasses(definition)");
        assertTrue(checkAt > 0 && redefineAt > 0, "both calls have to be there");
        assertTrue(checkAt < redefineAt,
                "checking after the redefine makes the warning disappear on exactly "
                + "the VM where the field does land");

        int batchCheck = classReloader.indexOf("JpaEntityChange.check(existingClass, bytecode)");
        int batchRedefine = classReloader.indexOf("definitions.toArray");
        assertTrue(batchCheck > 0 && batchCheck < batchRedefine,
                "the batch path reloads whole directories at startup and needs the same order");
    }

    /** The message has to name the field and both things the developer must do. */
    @Test
    void theMessageSaysWhatToDo() throws IOException {
        java.util.List<String> text = stringsIn(
                "com/onurkat/reclazz/reload/JpaEntityChange");

        assertTrue(text.stream().anyMatch(t -> t.contains("persistence mapping still has the old shape")),
                "the reason has to be the mapping, not the class");
        // What to do about it moved to JpaSchemaAdvice when it stopped being one
        // answer: the instruction differs by ddl-auto, and at validate a restart
        // is actively the wrong order.
        java.util.List<String> advice = stringsIn(
                "com/onurkat/reclazz/reload/JpaSchemaAdvice");
        assertTrue(advice.stream().anyMatch(t -> t.contains("add the column")),
                "a restart alone does not always fix it, and saying only 'restart' "
                + "would send the developer round the loop a second time");
        assertTrue(advice.stream().anyMatch(t -> t.contains("refuse to start")),
                "the validate case has to warn that restarting breaks the application");
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    // The scanner matches the annotation by descriptor and never loads it, so
    // these fixtures use the real jakarta annotations to prove the descriptor
    // it matches is the one a real application actually emits.
    @SuppressWarnings("unused")
    @jakarta.persistence.Entity
    static class EntityBefore {
        Long id;
        String code;
    }

    @SuppressWarnings("unused")
    @jakarta.persistence.Entity
    static class EntityAfter {
        Long id;
        String code;
        String currency;
    }

    @SuppressWarnings("unused")
    @jakarta.persistence.Entity
    static class EntityWithUnmapped {
        Long id;
        String code;
        static String shared;
        transient String scratch;
        @jakarta.persistence.Transient String derived;
    }

    /** An entity as it looks once the load-time transform has been through it. */
    @SuppressWarnings("unused")
    @jakarta.persistence.Entity
    static class EntityInstrumented {
        Long id;
        String code;
        Object[] __reclazz$ext;
    }

    @SuppressWarnings("unused")
    static class PlainBefore {
        String code;
    }

    @SuppressWarnings("unused")
    static class PlainAfter {
        String code;
        String currency;
    }

    private static byte[] bytecodeOf(Class<?> c) throws IOException {
        try (InputStream in = c.getClassLoader()
                .getResourceAsStream(c.getName().replace('.', '/') + ".class")) {
            assertNotNull(in, "cannot read " + c.getName());
            return in.readAllBytes();
        }
    }

    private static java.util.List<String> stringsIn(String internalName) throws IOException {
        try (InputStream in = JpaEntityChangeTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, "cannot read " + internalName);
            org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(in.readAllBytes());
            java.util.List<String> out = new java.util.ArrayList<>();
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
