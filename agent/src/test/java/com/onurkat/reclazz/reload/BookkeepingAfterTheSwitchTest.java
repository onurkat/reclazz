/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.agent.ClassReloader;
import com.onurkat.reclazz.transform.ReclazzTransformer;
import com.onurkat.reclazz.transform.TransformContext;
import com.onurkat.reclazz.transform.TransformTestBase;
import com.onurkat.reclazz.ui.StatusReporter;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The dispatch switch is the moment the JVM starts serving the new bodies,
 * and nothing after it can take that back. A step of bookkeeping that fails
 * afterwards therefore has to cost that step alone: not the reload's
 * reported outcome, which used to say "failed" of a class already running
 * the new code, and not the steps after it or the save after that.
 */
class BookkeepingAfterTheSwitchTest extends TransformTestBase {

    private static Instrumentation instrumentation;
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final StatusReporter.StatusListener listener = (level, message) -> lines.add(level + " " + message);

    @BeforeAll
    static void setup() {
        instrumentation = ByteBuddyAgent.install();
        assertNotNull(instrumentation);
    }

    @AfterEach
    void reset() {
        StructuralReloader.afterSwitchProbe = null;
        StatusReporter.removeListener(listener);
    }

    @Test
    void aFailureAfterTheSwitchCostsThatStepAndNotTheRecord() throws Exception {
        String name = "Committed";
        TransformContext context = new TransformContext();
        context.addWatched(name);
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        byte[] transformed = transformer.transform(TransformTestBase.class.getClassLoader(), name, null, null,
                compile(new SourceFile(name, source("v1", ""))).get(name));
        Map<String, byte[]> loadMap = new LinkedHashMap<>();
        loadMap.put(name, transformed);
        Class<?> cls = defineAndLoad(loadMap, name);
        Object instance = cls.getDeclaredConstructor().newInstance();

        StructuralReloader reloader = new StructuralReloader(instrumentation, context, AgentConfig.parse(null), null);
        reloader.setTransformer(transformer);
        instrumentation.addTransformer(transformer, true);
        StatusReporter.addListener(listener);
        try {
            // A structural save whose bookkeeping blows up right after the switch.
            StructuralReloader.afterSwitchProbe = () -> {
                throw new IllegalStateException("simulated bookkeeping failure");
            };
            ClassReloader.ReloadResult second = reloader.reload(name, compile(new SourceFile(name,
                    source("v2", "    public String added() { return \"added\"; }\n"))).get(name));

            assertTrue(second.isSuccess(), "the bodies are live, so the reload is a success: " + second.getError());
            assertEquals("v2", cls.getMethod("hello").invoke(instance), "the switch happened");
            assertTrue(lines.stream().anyMatch(l -> l.startsWith("WARN")
                            && l.contains("registering the added fields did not complete")
                            && l.contains("simulated bookkeeping failure")),
                    "the failed step is named, with its cause: " + lines);
            assertFalse(lines.stream().anyMatch(l -> l.contains("Structural reload failed")),
                    "a step's failure is not the reload's: " + lines);

            // The next save diffs against the right baseline: one more method,
            // not two, and it lands.
            StructuralReloader.afterSwitchProbe = null;
            lines.clear();
            ClassReloader.ReloadResult third = reloader.reload(name, compile(new SourceFile(name,
                    source("v3", "    public String added() { return \"added\"; }\n"
                            + "    public String more() { return \"more\"; }\n"))).get(name));
            assertTrue(third.isSuccess(), String.valueOf(third.getError()));
            assertEquals("v3", cls.getMethod("hello").invoke(instance));
            assertTrue(lines.stream().noneMatch(l -> l.contains("did not complete")), lines.toString());
            // The record keeps describing the class the JVM holds, which is
            // what the removal tests pin as well; added methods live in the
            // companion and are diffed afresh on every save.
            TransformContext.ClassMetadata record = context.getMetadata(name);
            assertTrue(record.getMethods().stream().anyMatch(m -> "hello".equals(m.name())), names(record).toString());
        } finally {
            instrumentation.removeTransformer(transformer);
        }
    }

    private static String source(String hello, String extra) {
        return "public class Committed {\n"
                + "    public String hello() { return \"" + hello + "\"; }\n"
                + extra
                + "}";
    }

    private static List<String> names(TransformContext.ClassMetadata record) {
        return record.getMethods().stream().map(TransformContext.MethodSig::name).toList();
    }
}
