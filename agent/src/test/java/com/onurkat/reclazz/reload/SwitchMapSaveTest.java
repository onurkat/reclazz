/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.agent.*;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.transform.*;
import com.onurkat.reclazz.platform.NoopPlatformContext;
import com.onurkat.reclazz.watcher.ChangeEvent;
import com.onurkat.reclazz.watcher.FileWatcher;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production save preparer before real companion/native reloads. */
class SwitchMapSaveTest extends TransformTestBase {
    @TempDir Path tmp;
    private static final AtomicInteger names = new AtomicInteger();
    private static String source(String pkg, int shape) {
        String cases = shape == 0 ? "case BANK -> \"bank-v1\"; case CARD -> \"card-v1\"; default -> \"other-v1\";"
                : shape == 2 || shape == 4 ? "case WALLET -> \"wallet-v2\"; case BANK -> \"bank-v2\"; case CARD -> \"card-v2\";"
                : "case BANK -> \"bank-v2\"; case CARD -> \"card-v2\"; case WALLET -> \"wallet-v2\";";
        if (shape == 5) cases += " case EXTRA -> \"extra-v2\";";
        String mode = shape == 3 || shape == 4 ? " + \"|\" + mode(Mode.FAST)" : "";
        return "package " + pkg + "; public class Svc { public String label(Channel c) { return switch(c) { " + cases
                + " }; } public String all() { return label(Channel.BANK) + \"|\" + label(Channel.CARD) + \"|\" + label(Channel.WALLET)" + mode + (shape == 5 ? " + \"|\" + label(Channel.EXTRA)" : "") + "; } "
                + (shape == 3 ? "public String mode(Mode m) { return switch(m) { case FAST -> \"fast\"; case SLOW -> \"slow\"; }; }"
                : shape == 4 ? "public String mode(Mode m) { return switch(m) { case SLOW -> \"slow\"; case FAST -> \"fast\"; }; }" : "") + " }";
    }
    private final class Fixture implements AutoCloseable {
        final String pkg = "switchsave" + names.incrementAndGet();
        final Instrumentation real = ByteBuddyAgent.install();
        final AtomicInteger nativeCalls = new AtomicInteger();
        final TransformContext context = new TransformContext();
        final ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        final Map<String, byte[]> baseline = compileShape(0);
        final java.net.URLClassLoader loader;
        Path captureRoot = tmp;
        byte[] pendingEnumOverride;
        final java.net.URLClassLoader foreignEnumLoader;
        final Class<?> outer, holder;
        final Object bean;
        final StructuralReloader structural;
        final Field agentInstrumentation;
        final Object previousInstrumentation;
        Fixture() throws Exception { this(false); }
        Fixture(boolean foreignEnum) throws Exception {
            ClassLoader parent = getClass().getClassLoader();
            foreignEnumLoader = foreignEnum ? new java.net.URLClassLoader(new java.net.URL[]{tmp.toUri().toURL()}, parent) : null;
            if (foreignEnum) parent = new ClassLoader(parent) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    return name.equals(pkg + ".Channel") ? foreignEnumLoader.loadClass(name) : super.loadClass(name, resolve);
                }
            };
            loader = new java.net.URLClassLoader(new java.net.URL[]{tmp.toUri().toURL()}, parent);
            Map<String, byte[]> transformed = new LinkedHashMap<>();
            baseline.keySet().forEach(context::addWatched);
            for (var entry : baseline.entrySet()) {
                byte[] result = transformer.transform(loader, entry.getKey(), null, null, entry.getValue());
                transformed.put(entry.getKey(), result == null ? entry.getValue() : result);
            }
            for (var entry : transformed.entrySet()) {
                Path file = tmp.resolve(entry.getKey() + ".class"); Files.createDirectories(file.getParent()); Files.write(file, entry.getValue());
            }
            outer = Class.forName(pkg + ".Svc", true, loader); bean = outer.getConstructor().newInstance();
            assertEquals("bank-v1|card-v1|other-v1", all()); holder = Class.forName(pkg + ".Svc$1", true, loader);
            Instrumentation counted = (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Instrumentation.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("redefineClasses")) nativeCalls.incrementAndGet();
                        try { return method.invoke(real, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
            structural = new StructuralReloader(counted, context, AgentConfig.parse(null), null); structural.setTransformer(transformer);
            agentInstrumentation = ReclazzAgent.class.getDeclaredField("instrumentation"); agentInstrumentation.setAccessible(true);
            previousInstrumentation = agentInstrumentation.get(null); agentInstrumentation.set(null, real);
            real.addTransformer(transformer, true);
        }
        Map<String, byte[]> compileShape(int shape) {
            return compile(new SourceFile(pkg + ".Channel", "package " + pkg + "; public enum Channel { BANK, CARD, WALLET" + (shape == 5 ? ", EXTRA" : "") + " }"),
                    new SourceFile(pkg + ".Mode", "package " + pkg + "; public enum Mode { FAST, SLOW }"),
                    new SourceFile(pkg + ".Svc", source(pkg, shape)));
        }
        String all() throws Exception { return (String) outer.getMethod("all").invoke(bean); }
        ReloadQueue.SavePlan prepare(int shape, boolean capturedHolder, boolean holderFirst, boolean unsupported) throws Exception {
            Map<String, byte[]> next = compileShape(shape);
            Path dir = Files.createDirectories(captureRoot.resolve(pkg));
            assertTrue(next.containsKey(pkg + "/Svc$1"), "javac must capture the holder alongside its outer");
            byte[] holderBytes = next.get(pkg + "/Svc$1");
            if (unsupported) {
                ClassNode node = new ClassNode(); new ClassReader(holderBytes).accept(node, 0);
                node.methods.get(0).instructions.insert(new InsnNode(Opcodes.NOP));
                ClassWriter w = new ClassWriter(0); node.accept(w); holderBytes = w.toByteArray();
            }
            Files.write(dir.resolve("Svc$1.class"), holderBytes);
            ChangeEvent outerEvent = new ChangeEvent(dir.resolve("Svc.class"), ChangeEvent.Type.MODIFIED, "test", "classes")
                    .withBytes(next.get(pkg + "/Svc"));
            ChangeEvent holderEvent = new ChangeEvent(dir.resolve("Svc$1.class"), ChangeEvent.Type.MODIFIED, "test", "classes").withBytes(holderBytes);
            List<ChangeEvent> captured = capturedHolder ? (holderFirst ? List.of(holderEvent, outerEvent) : List.of(outerEvent, holderEvent)) : List.of(outerEvent);
            if (shape == 5) Files.write(dir.resolve("Channel.class"), pendingEnumOverride == null
                    ? next.get(pkg + "/Channel") : pendingEnumOverride);
            Method prepare = ReclazzAgent.class.getDeclaredMethod("prepareSwitchMaps", List.class, FileWatcher.class);
            prepare.setAccessible(true);
            var watcher = new FileWatcher(new NoopPlatformContext(), AgentConfig.parse(null));
            try {
                return (ReloadQueue.SavePlan) prepare.invoke(null, captured, watcher);
            } finally {
                watcher.stopWatching();
            }
        }
        void apply(ReloadQueue.SavePlan plan, boolean bracketed, boolean holderFirst) throws Exception {
            List<ChangeEvent> ordered = new ArrayList<>(plan.apply());
            ordered.sort(Comparator.comparingInt(e -> e.getPath().getFileName().toString().contains("$") == holderFirst ? 0 : 1));
            if (bracketed) structural.beginBatch();
            try {
                for (ChangeEvent event : ordered) {
                    String name = new ClassReader(event.getBytes()).getClassName().replace('/', '.');
                    var result = structural.reload(name, event.getBytes());
                    assertTrue(result.isSuccess(), result.getError());
                    SwitchMapRefresh.result(Class.forName(name, false, outer.getClassLoader()), result.isSuccess());
                }
            } finally { if (bracketed) structural.endBatch(); }
            SwitchMapRefresh.complete(structural);
            assertNull(SwitchMapRefresh.failure(outer)); assertNull(SwitchMapRefresh.failure(holder));
        }
        public void close() throws Exception {
            try { SwitchMapRefresh.end(); }
            finally {
                real.removeTransformer(transformer); agentInstrumentation.set(null, previousInstrumentation);
                loader.close(); if (foreignEnumLoader != null) foreignEnumLoader.close();
            }
        }
    }
    @ParameterizedTest @ValueSource(ints = {1, 2, 3})
    void allCasesInBracketedAndUnbracketedReloadsAndBothOrders(int shape) throws Exception {
        for (boolean bracketed : List.of(false, true)) for (boolean holderFirst : List.of(false, true)) {
            try (var f = new Fixture()) {
                var plan = f.prepare(shape, true, holderFirst, false); assertTrue(plan.refused().isEmpty());
                f.apply(plan, bracketed, holderFirst);
                assertEquals("bank-v2|card-v2|wallet-v2" + (shape == 3 ? "|fast" : ""), f.all());
            }
        }
    }
    @ParameterizedTest @ValueSource(ints = {1, 2, 3})
    void outerOnlyArrivalPullsChangedHolderBeforeAnyReload(int shape) throws Exception {
        try (var f = new Fixture()) {
            var plan = f.prepare(shape, false, false, false); assertEquals(2, plan.apply().size());
            f.apply(plan, true, false);
            assertEquals("bank-v2|card-v2|wallet-v2" + (shape == 3 ? "|fast" : ""), f.all());
        }
    }
    @Test void addedMapRenumbersAgainOnNextSave() throws Exception {
        try (var f = new Fixture()) {
            f.apply(f.prepare(3, true, false, false), true, false); assertEquals("bank-v2|card-v2|wallet-v2|fast", f.all());
            SwitchMapRefresh.end();
            f.apply(f.prepare(4, false, false, false), true, true); assertEquals("bank-v2|card-v2|wallet-v2|fast", f.all());
        }
    }
    @Test void unsupportedHolderRefusesWholePairBeforeCompanionOrNativePublication() throws Exception {
        for (boolean holderFirst : List.of(false, true)) for (boolean captured : List.of(false, true)) {
            try (var f = new Fixture()) {
                int calls = f.nativeCalls.get(); var plan = f.prepare(2, captured, holderFirst, true);
                assertTrue(plan.apply().isEmpty()); assertEquals(2, plan.refused().size());
                assertEquals("bank-v1|card-v1|other-v1", f.all()); assertEquals(calls, f.nativeCalls.get());
            }
        }
    }
    @Test void appendedConstantAndNewCaseInOneSaveStillPullTheHolder() throws Exception {
        try (var f = new Fixture()) {
            f.apply(f.prepare(1, true, false, false), true, false); assertEquals("bank-v2|card-v2|wallet-v2", f.all());
            SwitchMapRefresh.end();
            var plan = f.prepare(5, false, false, false);
            assertEquals(3, plan.apply().size(), "new enum constant must not hide the changed holder behind a currently equal table");
            assertEquals("Channel.class", plan.apply().get(0).getPath().getFileName().toString());
            // Applying an enum append installs a JDK collection transformer. Its runtime
            // assertion belongs to the forked agent test, not the unbootstrapped JUnit host.
        }
    }
    @Test void pendingEnumFromAnotherOutputRootRefusesHolderAndOuter() throws Exception {
        try (var f = new Fixture()) {
            f.captureRoot = Files.createDirectory(tmp.resolve("foreign-output"));
            var plan = f.prepare(5, false, false, false);
            assertTrue(plan.apply().isEmpty()); assertEquals(2, plan.refused().size());
            assertEquals(0, f.nativeCalls.get()); assertEquals("bank-v1|card-v1|other-v1", f.all());
        }
    }
    @Test void pendingEnumFromAnotherDefiningLoaderRefusesHolderAndOuter() throws Exception {
        try (var f = new Fixture(true)) {
            var plan = f.prepare(5, false, false, false);
            assertTrue(plan.apply().isEmpty()); assertEquals(2, plan.refused().size());
            assertEquals(0, f.nativeCalls.get()); assertEquals("bank-v1|card-v1|other-v1", f.all());
        }
    }
    @Test void reorderedPendingEnumRefusesHolderAndOuter() throws Exception {
        try (var f = new Fixture()) {
            f.pendingEnumOverride = compile(new SourceFile(f.pkg + ".Channel", "package " + f.pkg
                    + "; public enum Channel { CARD, BANK, WALLET, EXTRA } ")).get(f.pkg + "/Channel");
            var plan = f.prepare(5, false, false, false);
            assertTrue(plan.apply().isEmpty()); assertEquals(2, plan.refused().size());
            assertEquals(0, f.nativeCalls.get()); assertEquals("bank-v1|card-v1|other-v1", f.all());
        }
    }
    @Test void identicalLateEventDoesNotReloadOrRewriteTables() throws Exception {
        try (var f = new Fixture()) {
            f.apply(f.prepare(1, true, false, false), true, false); SwitchMapRefresh.end();
            Field field = Arrays.stream(f.holder.getDeclaredFields()).filter(x -> x.getName().startsWith("$SwitchMap$")).findFirst().orElseThrow();
            int[] before = com.onurkat.reclazz.bootstrap.EnumSurgery.readSwitchTable(field);
            int calls = f.nativeCalls.get();
            var duplicate = f.prepare(1, true, false, false);
            assertTrue(duplicate.apply().isEmpty()); assertTrue(duplicate.refused().isEmpty());
            assertSame(before, com.onurkat.reclazz.bootstrap.EnumSurgery.readSwitchTable(field));
            assertEquals(calls, f.nativeCalls.get());
        }
    }
    @Test void compilerSavePullsOmittedPendingEnumBeforeItsUsers() throws Exception {
        try (var f = new Fixture()) {
            f.prepare(5, true, false, false); SwitchMapRefresh.end();
            Map<String, byte[]> compiled = new LinkedHashMap<>();
            f.compileShape(5).forEach((name, value) -> { if (!name.endsWith("/Channel")) compiled.put(name.replace('/', '.'), value); });
            Method method = ReclazzAgent.class.getDeclaredMethod("prepareCompiledSwitchMaps", Map.class, Map.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked") var accepted = (Map<String, byte[]>) method.invoke(null, compiled, Map.of());
            var names = new ArrayList<>(accepted.keySet());
            assertTrue(names.indexOf(f.pkg + ".Channel") < names.indexOf(f.holder.getName()));
            assertTrue(names.indexOf(f.pkg + ".Channel") < names.indexOf(f.outer.getName()));
            assertTrue(accepted.containsKey(f.holder.getName())); assertTrue(accepted.containsKey(f.outer.getName()));
            assertEquals(0, f.nativeCalls.get());
        }
    }
    @Test void failedPendingEnumPreventsDependentCompanionGeneration() throws Exception {
        try (var f = new Fixture()) {
            var plan = f.prepare(5, true, false, false);
            Class<?> channel = Class.forName(f.pkg + ".Channel", false, f.loader);
            SwitchMapRefresh.result(channel, false);
            Method method = ReclazzAgent.class.getDeclaredMethod("reloadWithCacheBoundary", String.class, String.class, byte[].class, ClassReloader.class, Class.class);
            method.setAccessible(true);
            for (Class<?> target : List.of(f.holder, f.outer)) {
                byte[] bytes = plan.apply().stream().filter(event -> new ClassReader(event.getBytes()).getClassName().equals(target.getName().replace('.', '/')))
                        .findFirst().orElseThrow().getBytes();
                var result = (ClassReloader.ReloadResult) method.invoke(null, target.getName(), target.getName().replace('.', '/'), bytes, null, target);
                assertFalse(result.isSuccess()); assertTrue(result.getError().contains("dependency failed"));
            }
            assertEquals(0, f.nativeCalls.get()); assertEquals("bank-v1|card-v1|other-v1", f.all());
        }
    }
    @Test void incompleteCompilerSaveIsRefusedBeforePublication() throws Exception {
        try (var f = new Fixture()) {
            Map<String, byte[]> compiled = new LinkedHashMap<>();
            f.compileShape(2).forEach((name, value) -> { if (!name.endsWith("$1")) compiled.put(name.replace('/', '.'), value); });
            Method method = ReclazzAgent.class.getDeclaredMethod("prepareCompiledSwitchMaps", Map.class, Map.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked") var accepted = (Map<String, byte[]>) method.invoke(null, compiled, Map.of());
            assertFalse(accepted.containsKey(f.outer.getName()));
            assertEquals("bank-v1|card-v1|other-v1", f.all()); assertEquals(0, f.nativeCalls.get());
        }
    }
    @Test void byteIdenticalHolderIsNotPulledOrWritten() throws Exception {
        try (var f = new Fixture()) {
            var table = f.holder.getDeclaredFields(); Field field = Arrays.stream(table).filter(x -> x.getName().startsWith("$SwitchMap$")).findFirst().orElseThrow();
            int[] before = com.onurkat.reclazz.bootstrap.EnumSurgery.readSwitchTable(field);
            var plan = f.prepare(0, false, false, false); assertEquals(1, plan.apply().size());
            f.apply(plan, false, false); assertSame(before, com.onurkat.reclazz.bootstrap.EnumSurgery.readSwitchTable(field));
        }
    }
}
