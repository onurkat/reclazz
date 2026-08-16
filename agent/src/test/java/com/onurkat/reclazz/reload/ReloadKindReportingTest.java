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
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A reload must report what CHANGED, not which engine ran.
 *
 * Every watched class goes through the companion engine, and the result
 * used to hardcode "structural" — so editing a single string literal was
 * announced as "Structural reload" and counted by the IDE widget's
 * structural-reload counter.
 */
class ReloadKindReportingTest extends TransformTestBase {

    private static Instrumentation instrumentation;

    @BeforeAll
    static void setup() {
        instrumentation = ByteBuddyAgent.install();
        assertNotNull(instrumentation);
    }

    private Class<?> loadV1(String name, String body) throws Exception {
        TransformContext ctx = new TransformContext();
        ctx.addWatched(name);
        AgentConfig config = AgentConfig.parse(null);
        byte[] raw = compile(new SourceFile(name, body)).get(name);
        byte[] transformed = new ReclazzTransformer(ctx, config)
                .transform(TransformTestBase.class.getClassLoader(), name, null, null, raw);
        Map<String, byte[]> loadMap = new LinkedHashMap<>();
        loadMap.put(name, transformed != null ? transformed : raw);
        lastContext = ctx;
        lastConfig = config;
        return defineAndLoad(loadMap, name);
    }

    private TransformContext lastContext;
    private AgentConfig lastConfig;

    @Test
    void bodyOnlyChangeIsNotReportedAsStructural() throws Exception {
        Class<?> cls = loadV1("KindBody",
                "public class KindBody { public String v() { return \"a\"; } }");
        assertNotNull(cls);

        byte[] v2 = compile(new SourceFile("KindBody",
                "public class KindBody { public String v() { return \"b\"; } }")).get("KindBody");

        ClassReloader.ReloadResult result =
                new StructuralReloader(instrumentation, lastContext, lastConfig, null)
                        .reload("KindBody", v2);

        assertTrue(result.isSuccess(), () -> "reload failed: " + result.getError());
        assertFalse(result.isStructuralReload(),
                "changing only a method body must not be reported as a structural reload");
    }

    @Test
    void addingAMethodIsReportedAsStructural() throws Exception {
        Class<?> cls = loadV1("KindStructural",
                "public class KindStructural { public String v() { return \"a\"; } }");
        assertNotNull(cls);

        byte[] v2 = compile(new SourceFile("KindStructural",
                "public class KindStructural {\n" +
                "    public String v() { return \"a\"; }\n" +
                "    public String extra() { return \"new\"; }\n" +
                "}")).get("KindStructural");

        ClassReloader.ReloadResult result =
                new StructuralReloader(instrumentation, lastContext, lastConfig, null)
                        .reload("KindStructural", v2);

        assertTrue(result.isSuccess(), () -> "reload failed: " + result.getError());
        assertTrue(result.isStructuralReload(),
                "adding a method must be reported as a structural reload");
    }
}
