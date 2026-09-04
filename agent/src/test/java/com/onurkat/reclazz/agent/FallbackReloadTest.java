/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.transform.TransformTestBase;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine that runs when the companion one does not.
 *
 * <p>Every class outside the watched set reloads through here: a plain
 * {@code redefineClasses}, with the JVM's own rules about what it will accept.
 * It is the path a developer meets when they edit something the agent is not
 * watching, and it had no test at all. Measured rather than assumed: 715
 * instructions, nought per cent covered, in the file that decides what to tell
 * somebody whose reload did not happen.
 *
 * <p>Writing these found one thing wrong. A stock JDK refuses an added method
 * with {@code UnsupportedOperationException: class redefinition failed:
 * attempted to add a method}, and that catch clause replaced the message with
 * "Class redefinition not supported by this JVM". The JVM supports redefinition
 * perfectly well; it refused one change, and said exactly which. The clause
 * below it, which reads the message to decide whether the refusal was
 * structural, could never run for the commonest refusal there is.
 */
class FallbackReloadTest extends TransformTestBase {

    private static Instrumentation instrumentation;

    @BeforeAll
    static void setup() {
        instrumentation = ByteBuddyAgent.install();
        assertNotNull(instrumentation, "these need a JVM this test process can instrument");
    }

    private ClassReloader reloader() {
        return new ClassReloader(instrumentation);
    }

    @Test
    void aBodyChangeOnAnUnwatchedClassTakesEffect() throws Exception {
        Class<?> cls = defineAndLoad(compile(new SourceFile("FallbackBody",
                "public class FallbackBody { public static String v() { return \"a\"; } }")),
                "FallbackBody");
        assertEquals("a", invokeStatic(cls, "v"));

        byte[] v2 = compile(new SourceFile("FallbackBody",
                "public class FallbackBody { public static String v() { return \"b\"; } }"))
                .get("FallbackBody");

        ClassReloader.ReloadResult result = reloader().reload("FallbackBody", v2);

        assertTrue(result.isSuccess(), () -> "reload failed: " + result.getError());
        assertFalse(result.isStructuralReload(),
                "the fallback engine never runs the companion path");
        assertEquals("b", invokeStatic(cls, "v"),
                "the redefinition landed but the running class still answers the old value");
    }

    /**
     * The defect this test set was written to find. The message has to be the
     * JVM's, because it is the only thing that says which change was refused.
     */
    @Test
    void aRefusedStructuralChangeSaysWhatTheJvmSaid() throws Exception {
        Class<?> cls = defineAndLoad(compile(new SourceFile("FallbackAdd",
                "public class FallbackAdd { public String v() { return \"a\"; } }")),
                "FallbackAdd");
        assertNotNull(cls);

        byte[] v2 = compile(new SourceFile("FallbackAdd",
                "public class FallbackAdd { public String v() { return \"a\"; }"
                        + " public String extra() { return \"b\"; } }")).get("FallbackAdd");

        ClassReloader.ReloadResult result = reloader().reload("FallbackAdd", v2);

        if (result.isSuccess()) {
            // An enhanced-redefinition VM takes this, and there is nothing to
            // report. Named rather than skipped so the reason is visible.
            assertTrue(true, "this JVM accepts an added method; nothing was refused");
            return;
        }

        assertFalse(result.getError().contains("not supported by this JVM"),
                () -> "the JVM refused one change and this reports the JVM as incapable: "
                        + result.getError());
        assertTrue(result.getError().contains("add"),
                () -> "the developer needs the JVM's own words to know what to undo: "
                        + result.getError());
        assertTrue(result.isStructuralChange(),
                "an added method is a structural change, whichever clause caught it");
        assertNotNull(result.getStructuralChangeAdvice(),
                "a refusal with nothing to do about it is where this path leaves people");
    }

    /**
     * The classifier on its own, because a live JVM refuses in one way at a
     * time and these are the messages the others send.
     */
    @Test
    void theRefusalsThatCountAsStructural() {
        assertTrue(ClassReloader.describesAStructuralRefusal(
                "class redefinition failed: attempted to add a method"));
        assertTrue(ClassReloader.describesAStructuralRefusal(
                "class redefinition failed: attempted to delete a method"));
        assertTrue(ClassReloader.describesAStructuralRefusal(
                "class redefinition failed: attempted to change the schema"));
        assertTrue(ClassReloader.describesAStructuralRefusal(
                "class redefinition failed: attempted to change class modifiers"));
        assertTrue(ClassReloader.describesAStructuralRefusal(
                "class redefinition failed: attempted to change superclass or interfaces"));

        assertFalse(ClassReloader.describesAStructuralRefusal(null),
                "no message is not a structural refusal, and must not read as one");
        assertFalse(ClassReloader.describesAStructuralRefusal("Class not found: demo.Missing"));
    }

    @Test
    void aClassTheJvmHasNeverLoadedIsNewRatherThanFailed() throws Exception {
        byte[] fresh = compile(new SourceFile("FallbackNeverLoaded",
                "public class FallbackNeverLoaded { public String v() { return \"a\"; } }"))
                .get("FallbackNeverLoaded");

        ClassReloader.ReloadResult result = reloader().reload("FallbackNeverLoaded", fresh);

        assertTrue(result.isSuccess(),
                "a class that has not been loaded needs no redefinition, and reporting a "
                        + "failure for it would be a red line about nothing");
        assertNull(result.getError());
    }

    /**
     * A class file the running JVM cannot read is refused before anything is
     * attempted, and named, because the file on disk is the thing to look at.
     */
    @Test
    void aClassFileFromTheFutureIsRefusedByName() {
        byte[] fromTheFuture = compile(new SourceFile("FallbackFuture",
                "public class FallbackFuture { public String v() { return \"a\"; } }"))
                .get("FallbackFuture");
        // Major version 99 is beyond anything that exists.
        byte[] tampered = fromTheFuture.clone();
        tampered[6] = 0;
        tampered[7] = 99;

        ClassReloader.ReloadResult result = reloader().reload("FallbackFuture", tampered);

        assertFalse(result.isSuccess());
        assertTrue(result.getError().startsWith("FallbackFuture"),
                () -> "the class is the thing the developer has to find: " + result.getError());
    }

}
