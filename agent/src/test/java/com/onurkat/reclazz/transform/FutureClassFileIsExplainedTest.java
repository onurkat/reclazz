/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.agent.AgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A class file from next year's compiler, through the real transformer.
 *
 * <p>Measured before this existed, with the version bytes bumped by hand on an
 * otherwise ordinary class:
 *
 * <pre>
 *   major 69   transformed
 *   major 70   [ERR] Transform failed for Future: Unsupported class file major version 70
 *   major 71   [ERR] Transform failed for Future: Unsupported class file major version 71
 * </pre>
 *
 * <p>One of those per watched class, which on a real application is a page of
 * identical red at startup, naming a header number rather than a Java release
 * and saying nothing about what to do. It also reads as far worse than it is:
 * the application runs, and a method body change still reloads, because the JVM
 * reads those classes and that path never touches the bytecode library.
 */
class FutureClassFileIsExplainedTest extends TransformTestBase {

    @BeforeEach
    void freshSession() {
        ClassFileVersionGuard.resetForTests();
    }

    /** Runs the transformer over a class at the given version, capturing what it says. */
    private static Said transform(String className, int major) throws Exception {
        byte[] raw = compile(new SourceFile(className,
                "public class " + className + " { public String tag() { return \"v\"; } }"))
                .get(className);
        byte[] atVersion = raw.clone();
        atVersion[6] = (byte) (major >> 8);
        atVersion[7] = (byte) major;

        TransformContext context = new TransformContext();
        context.addWatched(className);
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));

        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        byte[] result;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            result = transformer.transform(
                    TransformTestBase.class.getClassLoader(), className, null, null, atVersion);
        } finally {
            System.setOut(original);
        }
        return new Said(result, captured.toString(StandardCharsets.UTF_8));
    }

    private record Said(byte[] transformed, String output) {
    }

    @Test
    void aVersionThisBuildKnowsIsInstrumentedAsUsual() throws Exception {
        Said said = transform("Current", ClassFileVersionGuard.highestSupported());

        assertNotNull(said.transformed(), "the newest version this build knows has to work: "
                + said.output());
    }

    @Test
    void aVersionFromTheFutureIsExplainedInJavaReleases() throws Exception {
        int tooNew = ClassFileVersionGuard.highestSupported() + 1;
        Said said = transform("Future", tooNew);

        assertNull(said.transformed(), "a class that cannot be read cannot be instrumented");
        assertFalse(said.output().contains("Transform failed"),
                () -> "the library's own failure reached the developer: " + said.output());
        assertTrue(said.output().contains("Java " + ClassFileVersionGuard.javaRelease(tooNew)),
                () -> "the message should name the Java release, not a header number: "
                        + said.output());
        assertTrue(said.output().contains("--release"),
                () -> "and one of the two ways out: " + said.output());
    }

    /**
     * The reason this is said once. Every class in the application is compiled
     * by the same compiler, so on the JDK where this happens it happens to all
     * of them, and a page of the same sentence is not more information than one
     * line of it.
     */
    @Test
    void itIsSaidOncePerSessionRatherThanOncePerClass() throws Exception {
        int tooNew = ClassFileVersionGuard.highestSupported() + 1;

        Said first = transform("FutureOne", tooNew);
        Said second = transform("FutureTwo", tooNew);

        assertTrue(first.output().contains("--release"), "the first one says it");
        assertEquals("", second.output().trim(),
                () -> "and the second says it again: " + second.output());
    }

    /** Both are remembered, so a later refusal on either gives the real reason. */
    @Test
    void everySkippedClassIsRemembered() throws Exception {
        int tooNew = ClassFileVersionGuard.highestSupported() + 1;

        transform("FutureOne", tooNew);
        transform("FutureTwo", tooNew);

        assertTrue(ClassFileVersionGuard.wasSkipped("FutureOne"));
        assertTrue(ClassFileVersionGuard.wasSkipped("FutureTwo"));
    }
}
