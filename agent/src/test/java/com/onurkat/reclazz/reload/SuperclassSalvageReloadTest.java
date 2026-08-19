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
import com.onurkat.reclazz.transform.TransformedClassCache;
import com.onurkat.reclazz.ui.StatusReporter;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The crash scenario that killed the first per-method salvage, replayed
 * through the production reload chain.
 *
 * <p>A save changes the superclass AND one method body needs the new parent:
 * {@code Service} moves from {@code Base1} to {@code Base2}, methods a() and
 * b() are edited, and c() calls {@code yalnizB2()}, which only Base2
 * provides. The first attempt skipped c in the companion and stopped there;
 * the redefine payload still carried c's NEW body renamed over
 * {@code __reclazz$v0$...}, the trampoline's fallback dispatched into it,
 * and the app thread died with {@code UnsupportedOperationException:
 * Reclazz: method not found: Service.yalnizB2}. The whole class was then
 * refused again, which threw a() and b() away with it.
 *
 * <p>What these tests hold: a() and b() serve their new bodies, c() serves
 * the body it had, nothing throws, the warning names c and its reason, and
 * when the pin cannot be built the reloader refuses the whole class rather
 * than half-applying, which is the pre-salvage floor.
 */
class SuperclassSalvageReloadTest extends TransformTestBase {

    private static Instrumentation instrumentation;

    @BeforeAll
    static void setup() {
        instrumentation = ByteBuddyAgent.install();
        assertNotNull(instrumentation);
    }

    private static final String BASE1 =
            "public class %P%Base1 { public String who() { return \"base1\"; } }";
    private static final String BASE2 =
            "public class %P%Base2 {\n" +
            "    public String who() { return \"base2\"; }\n" +
            "    public String yalnizB2() { return \"only-b2\"; }\n" +
            "}";
    private static final String SERVICE_V1 =
            "public class %P%Service extends %P%Base1 {\n" +
            "    public String a() { return \"a1\"; }\n" +
            "    public String b() { return \"b1\"; }\n" +
            "    public String c() { return \"c1\"; }\n" +
            "}";
    private static final String SERVICE_V2 =
            "public class %P%Service extends %P%Base2 {\n" +
            "    public String a() { return \"a2\"; }\n" +
            "    public String b() { return \"b2\"; }\n" +
            "    public String c() { return yalnizB2(); }\n" +
            "}";

    /** The scenario's classes, compiled, transformed and loaded, ready to reload. */
    private record Fixture(TransformContext context, ReclazzTransformer transformer,
                           Class<?> serviceClass, Object instance,
                           Method a, Method b, Method c) {
    }

    private static Fixture load(String prefix) throws Exception {
        TransformContext context = new TransformContext();
        for (String n : new String[]{prefix + "Base1", prefix + "Base2", prefix + "Service"}) {
            context.addWatched(n);
        }
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));

        Map<String, byte[]> compiled = compile(
                new SourceFile(prefix + "Base1", BASE1.replace("%P%", prefix)),
                new SourceFile(prefix + "Base2", BASE2.replace("%P%", prefix)),
                new SourceFile(prefix + "Service", SERVICE_V1.replace("%P%", prefix)));

        Map<String, byte[]> loadMap = new LinkedHashMap<>();
        for (var entry : compiled.entrySet()) {
            byte[] transformed = transformer.transform(
                    TransformTestBase.class.getClassLoader(),
                    entry.getKey(), null, null, entry.getValue());
            loadMap.put(entry.getKey(), transformed != null ? transformed : entry.getValue());
        }

        SharedLoader loader = sharedLoader(loadMap);
        loader.load(prefix + "Base1");
        loader.load(prefix + "Base2");
        Class<?> serviceClass = loader.load(prefix + "Service");
        Object instance = serviceClass.getDeclaredConstructor().newInstance();

        Method a = serviceClass.getDeclaredMethod("a");
        Method b = serviceClass.getDeclaredMethod("b");
        Method c = serviceClass.getDeclaredMethod("c");
        // Warm every call site, so the pin has to hold against sites that
        // already dispatch, not only against lazy first-touch bootstraps.
        assertEquals("a1", a.invoke(instance));
        assertEquals("b1", b.invoke(instance));
        assertEquals("c1", c.invoke(instance));

        return new Fixture(context, transformer, serviceClass, instance, a, b, c);
    }

    private static byte[] compileV2(String prefix) {
        return compile(
                new SourceFile(prefix + "Base1", BASE1.replace("%P%", prefix)),
                new SourceFile(prefix + "Base2", BASE2.replace("%P%", prefix)),
                new SourceFile(prefix + "Service", SERVICE_V2.replace("%P%", prefix)))
                .get(prefix + "Service");
    }

    @Test
    void theEntangledMethodIsPinnedAndEverythingElseReloads() throws Exception {
        String prefix = "Salvage";
        Fixture f = load(prefix);

        StructuralReloader reloader = new StructuralReloader(
                instrumentation, f.context(), AgentConfig.parse(null), null);
        reloader.setTransformer(f.transformer());

        List<String> warnings = new ArrayList<>();
        StatusReporter.StatusListener listener = (level, message) -> {
            if ("WARN".equals(level)) warnings.add(message);
        };
        StatusReporter.addListener(listener);
        // Registered like the agent's own, so the redefinition of the spliced
        // payload passes through the (now idempotent) transformer and the
        // cache picks the spliced bytes up as the new last known good.
        instrumentation.addTransformer(f.transformer(), true);
        try {
            ClassReloader.ReloadResult result = reloader.reload(prefix + "Service",
                    compileV2(prefix));
            assertTrue(result.isSuccess(), "the salvage must reload, not refuse: "
                    + result.getError());

            assertEquals("a2", f.a().invoke(f.instance()), "a() carries its new body");
            assertEquals("b2", f.b().invoke(f.instance()), "b() carries its new body");
            assertEquals("c1", f.c().invoke(f.instance()),
                    "c() keeps the implementation it had; anything else is either the "
                    + "crash (new body dispatched) or a silent wrong answer");

            String warning = String.join("\n", warnings);
            assertTrue(warning.contains("except c (calls yalnizB2, which only "
                            + prefix + "Base2 provides)"),
                    "the warning must name the pinned method and its reason, got: " + warning);
            assertTrue(warning.contains("keeps the implementation it had"), warning);

            // The cache now holds the spliced payload, which is what a later
            // save will splice from: its fallback body for c is still c1.
            byte[] cached = TransformedClassCache.get(prefix + "Service");
            assertNotNull(cached, "the redefinition must refresh the cache");
            List<String> constants = stringConstants(cached);
            assertTrue(constants.contains("a2") && constants.contains("c1")
                            && !constants.contains("c-should-not-exist"),
                    "the cached last known good carries the new a() and the pinned old c()");

            // A subsequent ordinary reload of the same class still works: the
            // engine is not wedged on the spliced state.
            byte[] v3 = compile(
                    new SourceFile(prefix + "Base1", BASE1.replace("%P%", prefix)),
                    new SourceFile(prefix + "Base2", BASE2.replace("%P%", prefix)),
                    new SourceFile(prefix + "Service", SERVICE_V1.replace("%P%", prefix)
                            .replace("a1", "a3").replace("c1", "c3")))
                    .get(prefix + "Service");
            ClassReloader.ReloadResult after = reloader.reload(prefix + "Service", v3);
            assertTrue(after.isSuccess(), "ordinary reload after the salvage: "
                    + after.getError());
            assertEquals("a3", f.a().invoke(f.instance()));
            assertEquals("c3", f.c().invoke(f.instance()),
                    "back on the old superclass, c() is not entangled and reloads normally");
        } finally {
            instrumentation.removeTransformer(f.transformer());
            StatusReporter.removeListener(listener);
        }
    }

    /**
     * Without a last-known-good entry there is nothing to pin to, and
     * half-applying (a and b new, c undefined) is the crash again. The floor
     * is the pre-salvage behaviour: refuse the whole class, name the reason,
     * change nothing.
     */
    @Test
    void aMissingCacheEntryRefusesTheWholeClassAndAppliesNothing() throws Exception {
        String prefix = "PinFallback";
        Fixture f = load(prefix);
        forgetCacheEntry(prefix + "Service");

        StructuralReloader reloader = new StructuralReloader(
                instrumentation, f.context(), AgentConfig.parse(null), null);
        reloader.setTransformer(f.transformer());

        ClassReloader.ReloadResult result = reloader.reload(prefix + "Service",
                compileV2(prefix));

        assertFalse(result.isSuccess(), "no cache, no pin, no half-applied class");
        assertTrue(result.getError().contains("no last-known-good bytecode"),
                "the refusal has to say why the pin could not be built: " + result.getError());
        assertTrue(result.getError().contains("c calls yalnizB2"),
                "and which method needed it: " + result.getError());

        assertEquals("a1", f.a().invoke(f.instance()),
                "a refused reload must leave every body exactly as it was");
        assertEquals("c1", f.c().invoke(f.instance()));
    }

    /** Reaches into the cache the way no production code can, to simulate a cold one. */
    private static void forgetCacheEntry(String internalName) throws Exception {
        var field = TransformedClassCache.class.getDeclaredField("ENTRIES");
        field.setAccessible(true);
        ((Map<?, ?>) field.get(null)).remove(internalName);
    }

    private static List<String> stringConstants(byte[] bytecode) {
        org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(bytecode);
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
