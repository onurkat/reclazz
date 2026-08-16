/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.agent.AgentConfig;
import com.onurkat.reclazz.agent.ClassReloader;
import com.onurkat.reclazz.reload.StructuralReloader;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression: a companion whose method CONSTRUCTS another watched class
 * (`new Nested(...)`) must not rewrite the `INVOKESPECIAL <init>` call
 * into an invokedynamic. The JVMS forbids invokedynamic call sites named
 * `<init>`, so the companion failed verification with
 * "VerifyError: Illegal call to internal method ... @N: invokedynamic"
 * (live finding: EventTestService.triggerEvent doing `new TestEvent(...)`).
 *
 * The v2 change is STRUCTURAL (adds a method) on purpose: the body-only
 * redefinition fallback cannot mask a companion failure here, so this
 * test pins the companion path itself.
 */
class CompanionNestedCtorCallTest extends TransformTestBase {

    private static Instrumentation instrumentation;

    @BeforeAll
    static void setup() {
        instrumentation = ByteBuddyAgent.install();
        assertNotNull(instrumentation);
    }

    private static final String V1 =
            "public class OuterNews {\n" +
            "    public static class Evt {\n" +
            "        final String p;\n" +
            "        public Evt(String p) { this.p = p; }\n" +
            "    }\n" +
            "    public String fire() {\n" +
            "        Evt e = new Evt(\"x\");\n" +
            "        return \"v1:\" + e.p;\n" +
            "    }\n" +
            "}";

    private static final String V2 =
            "public class OuterNews {\n" +
            "    public static class Evt {\n" +
            "        final String p;\n" +
            "        public Evt(String p) { this.p = p; }\n" +
            "    }\n" +
            "    public String fire() {\n" +
            "        Evt e = new Evt(\"x\");\n" +
            "        return \"v2:\" + e.p;\n" +
            "    }\n" +
            "    public String extra() { return \"extra\"; }\n" +
            "}";

    @Test
    void companionMayConstructAnotherWatchedClass() throws Exception {
        TransformContext ctx = new TransformContext();
        AgentConfig config = AgentConfig.parse(null);
        ctx.addWatched("OuterNews");
        ctx.addWatched("OuterNews$Evt");

        Map<String, byte[]> v1Raw = compile(new SourceFile("OuterNews", V1));

        ReclazzTransformer transformer = new ReclazzTransformer(ctx, config);
        Map<String, byte[]> loadMap = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : v1Raw.entrySet()) {
            byte[] transformed = transformer.transform(
                    TransformTestBase.class.getClassLoader(),
                    e.getKey(), null, null, e.getValue());
            loadMap.put(e.getKey(), transformed != null ? transformed : e.getValue());
        }

        Class<?> cls = defineAndLoad(loadMap, "OuterNews");
        Object instance = cls.getDeclaredConstructor().newInstance();
        Method fire = cls.getDeclaredMethod("fire");
        assertEquals("v1:x", fire.invoke(instance));

        // Structural v2: companion generation is the ONLY way to apply it.
        byte[] v2Outer = compile(new SourceFile("OuterNews", V2)).get("OuterNews");
        StructuralReloader reloader = new StructuralReloader(
                instrumentation, ctx, config, null);
        ClassReloader.ReloadResult result = reloader.reload("OuterNews", v2Outer);

        assertTrue(result.isSuccess(),
                "companion reload with a nested-class constructor call must " +
                "verify and succeed, got: " + result.getError());
        assertEquals("v2:x", fire.invoke(instance),
                "reloaded body must run and still construct the nested class");
    }
}
