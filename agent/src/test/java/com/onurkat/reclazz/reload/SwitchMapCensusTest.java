/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.transform.ReclazzTransformer;
import com.onurkat.reclazz.transform.TransformContext;
import com.onurkat.reclazz.transform.TransformTestBase;
import com.onurkat.reclazz.util.ClassLookup;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Census counts, not timings: ordinary saves must not scan every loaded class. */
class SwitchMapCensusTest extends TransformTestBase implements Opcodes {
    static class Holder { static int existing = 7; }

    @BeforeEach @AfterEach void clearAddedStatics() throws Exception {
        // Keep the global weak publication store independent of other test classes/order.
        var field = AddedStatics.class.getDeclaredField("published"); field.setAccessible(true);
        ((Map<?, ?>) field.get(null)).clear();
    }
    private static Instrumentation census(AtomicInteger calls, Class<?>... classes) {
        return (Instrumentation) Proxy.newProxyInstance(SwitchMapCensusTest.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getAllLoadedClasses")) { calls.incrementAndGet(); return classes.clone(); }
                    throw new AssertionError("Unexpected instrumentation call: " + method.getName());
                });
    }
    private static byte[] shape(boolean added) {
        ClassWriter w = new ClassWriter(0);
        w.visit(V17, ACC_PUBLIC, Holder.class.getName().replace('.', '/'), null, "java/lang/Object", null);
        w.visitField(ACC_STATIC, "existing", "I", null, null).visitEnd();
        if (added) w.visitField(ACC_STATIC, "added", "I", null, null).visitEnd();
        w.visitEnd(); return w.toByteArray();
    }
    @Test void censusAndFreshAreLazyAndEachSnapshotIsReadOnlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        var loaded = ClassLookup.loadedClasses(census(calls, Holder.class));
        var fresh = loaded.fresh();
        assertEquals(0, calls.get(), "creating snapshots must not enumerate classes");
        assertSame(Holder.class, loaded.find(Holder.class.getName(), Holder.class.getClassLoader()));
        assertEquals(1, calls.get());
        Class<?>[] copy = loaded.all(); copy[0] = null;
        assertSame(Holder.class, loaded.all()[0]); assertEquals(1, calls.get());
        assertSame(Holder.class, fresh.find(Holder.class.getName())); assertEquals(2, calls.get());
        assertEquals(1, fresh.all().length); assertEquals(2, calls.get());
    }
    @Test void completionWithoutSwitchPlansDoesNotTakeAnotherCensus() {
        AtomicInteger calls = new AtomicInteger();
        SwitchMapRefresh.begin(Map.of(Holder.class.getName(), shape(false)), Map.of(),
                ClassLookup.loadedClasses(census(calls, Holder.class)));
        try {
            assertEquals(1, calls.get(), "one preparation census");
            calls.set(0); SwitchMapRefresh.result(Holder.class, true); SwitchMapRefresh.complete(null);
            assertNull(SwitchMapRefresh.failure(Holder.class));
            assertEquals(0, calls.get(), "no switch table needs a post-admission census");
        } finally { SwitchMapRefresh.end(); }
        assertTrue(SwitchMapRefresh.alreadyApplied(Holder.class, shape(false)));
    }
    @Test void emptyStagedAndPublishedStaticSetsDoNotResolveOwners() {
        AddedStatics.begin(Map.of(Holder.class, shape(false)));
        try { assertNoStaticLookup(); }
        finally { AddedStatics.finish(Set.of(Holder.class)); }
        assertNoStaticLookup();
    }
    private static void assertNoStaticLookup() {
        AtomicInteger calls = new AtomicInteger();
        boolean contains = AddedStatics.generating(Holder.class, ClassLookup.loadedClasses(census(calls, Holder.class)),
                () -> AddedStatics.contains(Holder.class.getName().replace('.', '/'), "existing", "I"));
        assertFalse(contains); assertEquals(0, calls.get(), "no added static exists to resolve");
    }
    @Test void actualAddedStaticsStillResolveFromOneCensusBeforeAndAfterPublication() {
        AddedStatics.begin(Map.of(Holder.class, shape(true)));
        try { assertAddedStaticLookup(); }
        finally { AddedStatics.finish(Set.of(Holder.class)); }
        assertAddedStaticLookup();
        AddedStatics.begin(Map.of(Holder.class, shape(false))); AddedStatics.finish(Set.of(Holder.class));
        assertNoStaticLookup();
    }
    private static void assertAddedStaticLookup() {
        AtomicInteger calls = new AtomicInteger();
        var loaded = ClassLookup.loadedClasses(census(calls, Holder.class));
        assertTrue(AddedStatics.generating(Holder.class, loaded,
                () -> AddedStatics.contains(Holder.class.getName().replace('.', '/'), "added", "I")));
        assertEquals(1, calls.get());
    }
    @Test void structuralReloadWithoutAddedStaticsDoesNotEnumerateLoadedClasses() throws Exception {
        String name = "CensusStructural";
        String source = "public class CensusStructural { public static int stable=7; public int read(){return %s;} %s }";
        byte[] first = compile(new SourceFile(name, source.formatted("stable", ""))).get(name);
        byte[] next = compile(new SourceFile(name, source.formatted("extra()-1", "public int extra(){return stable+2;}"))).get(name);
        TransformContext context = new TransformContext(); context.addWatched(name);
        AgentConfig config = AgentConfig.parse(null);
        ReclazzTransformer transformer = new ReclazzTransformer(context, config);
        Class<?> owner = defineAndLoad(Map.of(name, transformer.transform(getClass().getClassLoader(), name, null, null, first)), name);
        Object held = owner.getConstructor().newInstance(); assertEquals(7, owner.getMethod("read").invoke(held));
        Instrumentation real = ByteBuddyAgent.install(); AtomicInteger calls = new AtomicInteger();
        Instrumentation counted = (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getAllLoadedClasses")) calls.incrementAndGet();
                    try { return method.invoke(real, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        StructuralReloader structural = new StructuralReloader(counted, context, config, null); structural.setTransformer(transformer);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(owner.getClassLoader()); real.addTransformer(transformer, true);
        AddedStatics.begin(Map.of(owner, next));
        try {
            var result = structural.reload(name, next); assertTrue(result.isSuccess(), result.getError());
            assertEquals(8, owner.getMethod("read").invoke(held));
            assertEquals(0, calls.get(), "no added static: companion generation must not request a full census");
        } finally {
            AddedStatics.finish(Set.of(owner)); real.removeTransformer(transformer);
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
