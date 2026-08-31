/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The door this feature depends on is being closed, on a published schedule.
 *
 * <p>JEP 471 deprecated the memory-access methods in {@code sun.misc.Unsafe}
 * for removal: a warning from JDK 24, an exception by default in JDK 26,
 * removal after that. Appending an enum constant needs those methods, because
 * writing a final field has no supported alternative; every candidate was
 * measured and a VarHandle from {@code unreflectVarHandle} answers
 * {@code UnsupportedOperationException} for a final field, with or without
 * {@code setAccessible}, instance and static alike.
 *
 * <p>What the schedule closes is that one class. {@code jdk.internal.misc.Unsafe}
 * is what the JDK uses for the same operations itself, is not deprecated, and
 * is not governed by {@code --sun-misc-unsafe-memory-access}; it is only
 * unexported, which an agent can change. Measured on JDK 21 from a class on
 * the bootstrap class path, which is where these live: before the open,
 * {@code InaccessibleObjectException}; after it, a static final field written.
 * So a refusal is a fallback rather than an ending, and these tests hold the
 * order of the two doors as much as the handling of the first one's refusal.
 *
 * <p>What was wrong was not the dependency but the failure. Run today with
 * {@code --sun-misc-unsafe-memory-access=deny}, which is JDK 26's behaviour
 * brought forward, the refusal escaped as:
 *
 * <pre>
 *   [ERR] Hot-swap failed for Status: Structural reload failed: staticFieldBase
 * </pre>
 *
 * <p>The whole class reload lost, including a method body changed in the same
 * save that had nothing to do with the enum, and a JDK internal name put in
 * front of a developer as though it meant something. Measured again after the
 * change, the unrelated method reloaded and the enum declined with a sentence
 * naming the policy and the flag.
 */
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class UnsafeDenialTest {

    /**
     * Not probed at startup on purpose: the probe is itself a call, and on JDK
     * 24 and 25 a call is what prints the warning that names Reclazz to the
     * developer. An application that never reloads an enum must never see it.
     */
    @Test
    void availabilityIsNotProbedEagerly() throws IOException {
        String source = source("UnsafeAccess");

        int findEnd = source.indexOf("static boolean isAvailable()");
        String beforeFirstUse = source.substring(0, findEnd);
        for (String memoryAccess : new String[]{
                "staticFieldBase", "staticFieldOffset", "objectFieldOffset", "putObject"}) {
            assertFalse(beforeFirstUse.contains("UNSAFE." + memoryAccess),
                    memoryAccess + " must not be called while resolving availability, "
                    + "or every startup prints the deprecation warning");
        }
    }

    /** A refusal of the first door has to be permanent, or every reload asks again. */
    @Test
    void aRefusalIsRememberedAndTurnsTheFirstDoorOff() throws IOException {
        String source = source("UnsafeAccess");

        assertTrue(source.contains("denied = true"), "the refusal has to be recorded");
        assertTrue(source.contains("UNSAFE != null && !denied"),
                "and the first door has to consult it");
    }

    /**
     * Every accessor has to hand a refusal to the fallback, and the fallback
     * has to convert what it cannot do, or the JDK's own wording reaches the
     * developer as the reason their reload failed.
     */
    @Test
    void everyAccessorFallsBackAndTheFallbackConverts() throws IOException {
        String access = source("UnsafeAccess");
        String internal = source("InternalUnsafe");

        int accessors = access.split("UNSAFE\\.", -1).length - 1;
        int fallbacks = access.split("fellBack\\(t\\)", -1).length - 1;
        assertTrue(fallbacks >= 6,
                "each accessor needs its own fallback; found " + fallbacks
                + " for " + accessors + " Unsafe calls");

        assertTrue(access.contains("if (!InternalUnsafe.isAvailable()) {"),
                "and a refusal only reaches the caller when the second door is shut too");

        int conversions = internal.split("throw UnsafeAccess.refused\\(", -1).length - 1;
        assertTrue(conversions >= 6,
                "the second door converts its own refusals; found " + conversions);
    }

    /**
     * The order is the whole design. Opening a JDK-internal package is not
     * free of consequence, and an application on a JDK that refuses nothing
     * must never have one opened on its behalf, so availability has to
     * short-circuit on the first door.
     */
    @Test
    void theSecondDoorIsOnlyAskedAfterTheFirstRefuses() throws IOException {
        String source = source("UnsafeAccess");

        assertTrue(source.contains("(UNSAFE != null && !denied) || InternalUnsafe.isAvailable()"),
                "the first door has to be the short-circuit, not the second");
        assertTrue(source.contains("public static void useForFallback("),
                "the Instrumentation has to be handed over rather than looked up: "
                + "these classes are on the bootstrap loader and cannot see the agent");
    }

    /** Handing the Instrumentation over must not open anything by itself. */
    @Test
    void theHandoverOpensNothing() throws IOException {
        String internal = source("InternalUnsafe");

        int handover = internal.indexOf("static void useForFallback(");
        int probe = internal.indexOf("static synchronized boolean isAvailable()");
        assertTrue(handover > 0 && probe > handover, "the handover comes first and does nothing");
        String body = internal.substring(handover, probe);
        assertFalse(body.contains("redefineModule"),
                "the module open belongs to the first real need, not to startup");
    }

    /**
     * The JDK renamed these in 12 and could rename them again; a rename must
     * be a decline, never an exception escaping into a reload.
     */
    @Test
    void bothSpellingsOfTheAccessorsAreTried() throws IOException {
        String internal = source("InternalUnsafe");

        assertTrue(internal.contains("either(type, \"getReference\", \"getObject\""),
                "getObject is what JDK 11 and earlier call it");
        assertTrue(internal.contains("either(type, \"putReference\", \"putObject\""));
        assertTrue(internal.contains("catch (Throwable notThisJvm)"),
                "a JDK that moved one of these declines rather than throwing");
    }

    /**
     * The claim this whole fallback rests on, run rather than reasoned about:
     * given an Instrumentation, {@code jdk.internal.misc} opens and the field
     * that JEP 471 was going to take away gets written.
     *
     * <p>The class under it is a {@code static final} with a
     * {@code ConstantValue}, which is the shape an enum's {@code $VALUES}
     * field has and the shape every supported API refuses: core reflection
     * answers {@code IllegalAccessException} and a VarHandle answers
     * {@code UnsupportedOperationException}, both asserted here so the
     * comparison is in the same run as the claim.
     *
     * <p>Ordered first because the second door resolves once per JVM and
     * caches the outcome: a test that asked before the handover would answer
     * for every test after it.
     */
    @Test
    @org.junit.jupiter.api.Order(1)
    void theSecondDoorWritesWhatEverySupportedApiRefuses() throws Exception {
        UnsafeAccess.useForFallback(net.bytebuddy.agent.ByteBuddyAgent.install());
        assertTrue(InternalUnsafe.isAvailable(),
                "jdk.internal.misc has to open to a bootstrap-loaded class");

        java.lang.reflect.Field field = Holder.class.getDeclaredField("VALUE");
        field.setAccessible(true);
        assertThrows(IllegalAccessException.class, () -> field.set(null, "reflection"),
                "core reflection is the first supported alternative, and it refuses");
        assertThrows(UnsupportedOperationException.class,
                () -> java.lang.invoke.MethodHandles
                        .privateLookupIn(Holder.class, java.lang.invoke.MethodHandles.lookup())
                        .unreflectVarHandle(field).set("varhandle"),
                "a VarHandle is the second, and it refuses too");

        InternalUnsafe.putStatic(field, "written");
        assertEquals("written", field.get(null),
                "the JDK's own Unsafe writes it, which is what keeps the enum work "
                + "alive past the sun.misc removal");
        assertEquals("written", InternalUnsafe.getStatic(field), "and reads it back");
    }

    /** A final static with a ConstantValue: the shape $VALUES has. */
    static final class Holder {
        static final String VALUE = "before";
    }

    private static String source(String simpleName) throws IOException {
        return java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/onurkat/reclazz/bootstrap/" + simpleName + ".java"));
    }

    /**
     * The append reads the constant array before it writes anything, and that
     * read is a memory access too. It sat outside the try, which is exactly
     * how the refusal escaped as a failed class reload.
     */
    @Test
    void theFirstReadIsInsideTheGuard() throws IOException {
        String source = source("EnumSurgery");

        int tryAt = source.indexOf("List<String> appended = new ArrayList<>();");
        int firstRead = source.indexOf("UnsafeAccess.getStatic(valuesField)");
        assertTrue(firstRead > tryAt,
                "reading $VALUES before the try is what let a refusal fail the whole reload");
        assertTrue(source.contains("catch (UnsafeAccess.MemoryAccessUnavailable e)"),
                "and the refusal needs its own branch, with its own sentence");
    }

    /** The sentence has to name the policy and the way to reproduce it. */
    @Test
    void theMessageExplainsTheJdkPolicy() throws IOException {
        List<String> text = stringsIn("com/onurkat/reclazz/bootstrap/EnumSurgery");

        assertTrue(text.stream().anyMatch(t -> t.contains("JDK 26 refuses it by default")),
                "a developer meeting this on a new JDK should not have to guess why");
        // The flag a developer needs here is the one that gives the feature
        // back, not the one that reproduces the refusal. Reproducing is our
        // problem and lives in the changelog; theirs is a server that stopped
        // picking up an enum value.
        assertTrue(text.stream().anyMatch(t -> t.contains("--sun-misc-unsafe-memory-access=allow")),
                "the way out has to be in the message, not only the diagnosis");
        assertTrue(text.stream().anyMatch(t -> t.contains("Nothing was changed")),
                "a half-applied enum is the one outcome worse than none");
    }

    private static List<String> stringsIn(String internalName) throws IOException {
        try (InputStream in = UnsafeDenialTest.class.getClassLoader()
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
