/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.agent.AgentConfig;
import com.onurkat.reclazz.agent.ClassReloader;
import com.onurkat.reclazz.transform.ReclazzTransformer;
import com.onurkat.reclazz.transform.TransformContext;
import com.onurkat.reclazz.transform.TransformTestBase;
import com.onurkat.reclazz.ui.StatusReporter;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a removal leaves behind for the next save.
 *
 * <p>A removed method cannot leave the loaded class, so it stays in it, and
 * the record of that class has to say so. Recording it as gone made restoring
 * it in a later save read as ADDING a method, and a class carrying an added
 * member cannot be redefined at all: the constructor-body refresh stopped
 * landing from that point on, and a field added afterwards read its default
 * even on objects built after the reload.
 *
 * <p>Two of the twenty-three live scenarios failed on exactly that, and no
 * unit test did, which is what these two are for. The first holds the record
 * against what the JVM actually holds; the second walks the sequence the live
 * suite walks, remove then restore then add a field, and asks the object what
 * it got.
 */
class RemovalThenAddFieldTest extends TransformTestBase {

    private static Instrumentation instrumentation;

    @BeforeAll
    static void setup() {
        instrumentation = ByteBuddyAgent.install();
        assertNotNull(instrumentation);
    }

    @Test
    void aSaveAfterARemovalDiffsAgainstWhatTheClassActuallyHas() throws Exception {
        String name = "AfterRemoval";
        TransformContext context = new TransformContext();
        context.addWatched(name);
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));

        byte[] raw = compile(new SourceFile(name,
                "public class AfterRemoval {\n"
                + "    public String keep() { return \"keep-v1\"; }\n"
                + "    public String gone() { return \"gone-v1\"; }\n"
                + "}")).get(name);
        byte[] transformed = transformer.transform(
                TransformTestBase.class.getClassLoader(), name, null, null, raw);
        Map<String, byte[]> loadMap = new LinkedHashMap<>();
        loadMap.put(name, transformed);
        Class<?> cls = defineAndLoad(loadMap, name);

        StructuralReloader reloader = new StructuralReloader(
                instrumentation, context, AgentConfig.parse(null), null);
        reloader.setTransformer(transformer);
        instrumentation.addTransformer(transformer, true);
        try {
            // 1. remove a method
            ClassReloader.ReloadResult removed = reloader.reload(name,
                    compile(new SourceFile(name,
                            "public class AfterRemoval {\n"
                            + "    public String keep() { return \"keep-v2\"; }\n"
                            + "}")).get(name));
            assertTrue(removed.isSuccess(), String.valueOf(removed.getError()));

            // 2. the very next save adds nothing at all. The recorded members
            // have to match what the class was left holding, or every later
            // diff carries a method that is not new.
            // The record has to describe the class the JVM actually holds,
            // and the JVM would not let the method go. Recording it as gone is
            // what made restoring it later read as adding a method, which is
            // what stopped the class being redefinable at all.
            TransformContext.ClassMetadata after = context.getMetadata(name);
            assertNotNull(after);
            assertTrue(after.getMethods().stream().anyMatch(m -> "gone".equals(m.name())),
                    "the loaded class still has the method, so the record must too: "
                    + after.getMethods().stream().map(TransformContext.MethodSig::name).toList());

            ClassReloader.ReloadResult again = reloader.reload(name,
                    compile(new SourceFile(name,
                            "public class AfterRemoval {\n"
                            + "    public String keep() { return \"keep-v3\"; }\n"
                            + "}")).get(name));
            assertTrue(again.isSuccess(), String.valueOf(again.getError()));
            assertEquals("keep-v3", cls.getDeclaredMethod("keep")
                    .invoke(cls.getDeclaredConstructor().newInstance()));
        } finally {
            instrumentation.removeTransformer(transformer);
        }
    }

    /**
     * The live sequence, in order: remove a method, put it back the way the
     * suite restores its baseline, then add a field the constructor
     * initialises.
     *
     * <p>This is the sequence that failed on the server, and the object is
     * what settles it: the constructor that ran has to be the one compiled
     * with the field in it.
     */
    @Test
    void aFieldAddedAfterARemovalIsInitialisedByTheNewConstructor() throws Exception {
        String name = "AddFieldAfterRemoval";
        String v1 = "public class AddFieldAfterRemoval {\n"
                + "    public String hello() { return \"hello\"; }\n"
                + "    public String removable() { return \"removable\"; }\n"
                + "}";

        TransformContext context = new TransformContext();
        context.addWatched(name);
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));

        byte[] transformed = transformer.transform(TransformTestBase.class.getClassLoader(),
                name, null, null, compile(new SourceFile(name, v1)).get(name));
        Map<String, byte[]> loadMap = new LinkedHashMap<>();
        loadMap.put(name, transformed);
        Class<?> cls = defineAndLoad(loadMap, name);

        StructuralReloader reloader = new StructuralReloader(
                instrumentation, context, AgentConfig.parse(null), null);
        reloader.setTransformer(transformer);
        java.util.List<String> warnings = new java.util.ArrayList<>();
        StatusReporter.StatusListener listener = (level, message) -> {
            if ("WARN".equals(level)) warnings.add(message);
        };
        StatusReporter.addListener(listener);
        instrumentation.addTransformer(transformer, true);
        try {
            reload(reloader, name, "public class AddFieldAfterRemoval {\n"
                    + "    public String hello() { return \"hello\"; }\n"
                    + "}");
            reload(reloader, name, v1);
            reload(reloader, name, "public class AddFieldAfterRemoval {\n"
                    + "    private int answer = 42;\n"
                    + "    public String hello() { return \"hello:\" + answer; }\n"
                    + "    public String removable() { return \"removable\"; }\n"
                    + "}");

            Object fresh = cls.getDeclaredConstructor().newInstance();
            assertEquals("hello:42", cls.getDeclaredMethod("hello").invoke(fresh),
                    "an object built after the reload runs the new constructor, so the "
                    + "field it initialises cannot read 0");

            String said = String.join("\n", warnings);
            assertFalse(said.contains("read their default even on objects created after"),
                    "and nothing may claim otherwise: " + said);
        } finally {
            StatusReporter.removeListener(listener);
            instrumentation.removeTransformer(transformer);
        }
    }

    private static void reload(StructuralReloader reloader, String name, String source)
            throws Exception {
        ClassReloader.ReloadResult result = reloader.reload(
                name, compile(new SourceFile(name, source)).get(name));
        assertTrue(result.isSuccess(), String.valueOf(result.getError()));
    }
}
