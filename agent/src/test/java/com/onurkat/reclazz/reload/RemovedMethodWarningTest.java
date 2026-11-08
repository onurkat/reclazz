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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a removed method's existing callers meet: what they would meet after
 * a restart.
 *
 * <p>The JVM will not let a method leave a loaded class, and for a while the
 * removed method's previous body went on answering every caller that still
 * named it. Measured on a live SAP Commerce server: an endpoint whose service
 * method was removed kept returning the old result, so the save looked as if
 * it had done nothing, and the same caller after a restart fails with
 * {@code NoSuchMethodError}. Before that it was three outcomes depending on
 * history the developer cannot see (a reloaded method kept its last body, a
 * refused redefinition kept everything, a never-reloaded one threw
 * {@code UnsupportedOperationException}).
 *
 * <p>Now the removed method's call site is retargeted in the same switch as
 * the new bodies, so all three histories end in the restart answer: a
 * {@code NoSuchMethodError} naming the method, or the inherited
 * implementation when the method was an override. Compiler-generated methods
 * keep their body, because a live lambda object still points at its own.
 */
class RemovedMethodWarningTest extends TransformTestBase {

    private static Instrumentation instrumentation;

    @BeforeAll
    static void setup() {
        instrumentation = ByteBuddyAgent.install();
        assertNotNull(instrumentation);
    }

    private record Rig(TransformContext context, ReclazzTransformer transformer,
                       StructuralReloader reloader, Class<?> cls, Object instance,
                       Method keep, Method gone, List<String> warnings,
                       StatusReporter.StatusListener listener) {
    }

    private static Rig rig(String name, String v1Source) throws Exception {
        TransformContext context = new TransformContext();
        context.addWatched(name);
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));

        byte[] raw = compile(new SourceFile(name, v1Source)).get(name);
        byte[] transformed = transformer.transform(
                TransformTestBase.class.getClassLoader(), name, null, null, raw);
        assertNotNull(transformed);

        Map<String, byte[]> loadMap = new LinkedHashMap<>();
        loadMap.put(name, transformed);
        Class<?> cls = defineAndLoad(loadMap, name);
        Object instance = cls.getDeclaredConstructor().newInstance();
        Method keep = cls.getDeclaredMethod("keep");
        Method gone = cls.getDeclaredMethod("gone");
        assertEquals("keep-v1", keep.invoke(instance));
        assertEquals("gone-v1", gone.invoke(instance));

        StructuralReloader reloader = new StructuralReloader(
                instrumentation, context, AgentConfig.parse(null), null);
        reloader.setTransformer(transformer);

        List<String> warnings = new ArrayList<>();
        StatusReporter.StatusListener listener = (level, message) -> {
            if ("WARN".equals(level)) warnings.add(message);
        };
        StatusReporter.addListener(listener);
        instrumentation.addTransformer(transformer, true);
        return new Rig(context, transformer, reloader, cls, instance, keep, gone,
                warnings, listener);
    }

    private static void teardown(Rig rig) {
        instrumentation.removeTransformer(rig.transformer());
        StatusReporter.removeListener(rig.listener());
    }

    private static void reload(Rig rig, String name, String source) {
        ClassReloader.ReloadResult result = rig.reloader().reload(name,
                compile(new SourceFile(name, source)).get(name));
        assertTrue(result.isSuccess(), String.valueOf(result.getError()));
    }

    // The receipt ledger stays package-private apart from its diagnostic entry
    // point. Exercise its real apply/outcome/query without widening those APIs.
    private static Object ledger(String name) throws Exception {
        Class<?> type = Class.forName("com.onurkat.reclazz.agent.ReloadVerification");
        var constructor = type.getDeclaredConstructor(Supplier.class);
        constructor.setAccessible(true);
        return constructor.newInstance((Supplier<Map<String, Integer>>) () -> Map.of(name, 1));
    }

    private static Object call(Object ledger, String method, Class<?>[] types, Object... args) {
        try {
            Method m = ledger.getClass().getDeclaredMethod(method, types); m.setAccessible(true);
            return m.invoke(ledger, args);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static String receipt(String name, byte[] bytes, Runnable work) throws Exception {
        Object ledger = ledger(name);
        call(ledger, "apply", new Class<?>[]{Map.class, Map.class, Runnable.class},
                Map.of(name, bytes), Map.of(), (Runnable) () -> {
                    work.run();
                    call(ledger, "outcome", new Class<?>[]{String.class, boolean.class, String.class},
                            name, true, "body applied");
                });
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        String json = (String) call(ledger, "query", new Class<?>[]{String.class, String.class, String.class}, "test", name, hash);
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path("status").asText();
    }

    private static void keptNotice(Rig rig) {
        // A type no longer resolvable by the live loader makes the real planner
        // retain the old target. Run its actual reporter; do not fake a WARN.
        RemovedMethodTargets.Plan kept = RemovedMethodTargets.plan(rig.cls(), java.lang.invoke.MethodHandles.publicLookup(),
                List.of(new TransformContext.MethodSig("unresolved", "()Lmissing/ReturnType;", java.lang.reflect.Modifier.PUBLIC)));
        assertEquals(List.of("unresolved"), kept.kept());
        try {
            Method report = StructuralReloader.class.getDeclaredMethod("reportRemovedMethods", String.class, RemovedMethodTargets.Plan.class);
            report.setAccessible(true); report.invoke(rig.reloader(), rig.cls().getName(), kept);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private void removalReceipt(String suffix, boolean kept, boolean first) throws Exception {
        String name = "RemovalReceipt" + suffix;
        Rig rig = rig(name, "public class " + name + " { public String keep() { return \"keep-v1\"; } public String gone() { return \"gone-v1\"; } }");
        try {
            byte[] bytes = compile(new SourceFile(name, "public class " + name + " { public String keep() { return \"keep-v2\"; } }")).get(name);
            String status = receipt(name, bytes, () -> {
                if (kept && first) keptNotice(rig);
                if (!suffix.equals("Kept")) {
                    assertTrue(rig.reloader().reload(name, bytes).isSuccess());
                }
                if (kept && !first) keptNotice(rig);
            });
            assertEquals(kept ? "unverified" : "applied", status);
            if (!suffix.equals("Kept")) { assertGone(rig, "gone"); }
            if (!kept) assertWarnsNoSuchMethod(rig);
            else assertTrue(rig.warnings().stream().anyMatch(w -> w.contains("keeps the previous implementation")));
        } finally { teardown(rig); }
    }

    @Test void throwingOnlyRemovalReceiptIsApplied() throws Exception { removalReceipt("Throwing", false, false); }
    @Test void keptOnlyRemovalReceiptIsUnverified() throws Exception { removalReceipt("Kept", true, false); }
    @Test void throwingThenKeptInOneBatchIsUnverified() throws Exception { removalReceipt("ThrowingKept", true, false); }
    @Test void keptThenThrowingInOneBatchIsUnverified() throws Exception { removalReceipt("KeptThrowing", true, true); }

    /** The removed method's call, which must fail the way a restarted JVM fails it. */
    private static void assertGone(Rig rig, String method) {
        InvocationTargetException call = assertThrows(InvocationTargetException.class,
                () -> rig.gone().invoke(rig.instance()),
                "a removed method must not keep answering with its previous body");
        Throwable cause = call.getCause();
        assertInstanceOf(NoSuchMethodError.class, cause,
                "what a caller compiled against the removed method gets after a restart");
        assertTrue(cause.getMessage().contains(rig.cls().getName() + "." + method + "()"),
                "the error names the method: " + cause.getMessage());
    }

    private static void assertWarnsNoSuchMethod(Rig rig) {
        String warning = String.join("\n", rig.warnings());
        assertTrue(warning.contains("[gone]") && warning.contains("NoSuchMethodError"),
                "the warning says what the callers meet, got: " + warning);
        assertFalse(warning.contains("keeps the previous implementation"),
                "a kept body that is not kept must not be claimed: " + warning);
    }

    /**
     * A method that was reloaded and then removed. Its call site already
     * pointed at a companion body, which is how the previous body stayed
     * reachable; the SAP Commerce remove-method scenario measures this case.
     */
    @Test
    void aPreviouslyReloadedMethodFailsLikeAfterARestart() throws Exception {
        Rig rig = rig("RemovedAfterReload",
                "public class RemovedAfterReload {\n" +
                "    public String keep() { return \"keep-v1\"; }\n" +
                "    public String gone() { return \"gone-v1\"; }\n" +
                "}");
        try {
            reload(rig, "RemovedAfterReload",
                    "public class RemovedAfterReload {\n" +
                    "    public String keep() { return \"keep-v1\"; }\n" +
                    "    public String gone() { return \"gone-v2\"; }\n" +
                    "}");
            assertEquals("gone-v2", rig.gone().invoke(rig.instance()));
            rig.warnings().clear();

            reload(rig, "RemovedAfterReload",
                    "public class RemovedAfterReload {\n" +
                    "    public String keep() { return \"keep-v3\"; }\n" +
                    "}");
            assertEquals("keep-v3", rig.keep().invoke(rig.instance()));
            assertGone(rig, "gone");
            assertWarnsNoSuchMethod(rig);
        } finally {
            teardown(rig);
        }
    }

    /** A method that was never reloaded before its removal. */
    @Test
    void aNeverReloadedMethodFailsLikeAfterARestart() throws Exception {
        Rig rig = rig("RemovedNoExtra",
                "public class RemovedNoExtra {\n" +
                "    public String keep() { return \"keep-v1\"; }\n" +
                "    public String gone() { return \"gone-v1\"; }\n" +
                "}");
        try {
            reload(rig, "RemovedNoExtra",
                    "public class RemovedNoExtra {\n" +
                    "    public String keep() { return \"keep-v2\"; }\n" +
                    "}");
            assertEquals("keep-v2", rig.keep().invoke(rig.instance()),
                    "the rest of the save still applies");
            assertGone(rig, "gone");
            assertWarnsNoSuchMethod(rig);
        } finally {
            teardown(rig);
        }
    }

    /**
     * Rewrites the class's record so {@code extra()} counts as an existing
     * method. The removal payload then carries it, the loaded class does
     * not, and the JVM refuses the redefinition: the refused branch the
     * next test is about.
     */
    private static void recordExtraAsExisting(TransformContext context, String name) {
        TransformContext.ClassMetadata m = context.getMetadata(name);
        assertNotNull(m);
        List<TransformContext.MethodSig> methods = new ArrayList<>(m.getMethods());
        if (methods.stream().noneMatch(s -> "extra".equals(s.name()))) {
            methods.add(new TransformContext.MethodSig(
                    "extra", "()Ljava/lang/String;", java.lang.reflect.Modifier.PUBLIC));
        }
        context.putMetadata(name, new TransformContext.ClassMetadata(
                methods, m.getFields(), 0, m.getSuperName(), m.getAnnotations(),
                m.isInterfacesKnown() ? m.getInterfaces() : null));
    }

    /**
     * When the redefinition is refused the loaded class keeps its previous
     * bytecode entirely, and the call site is still what decides the answer.
     */
    @Test
    void whenTheRedefinitionIsRefusedTheMethodStillFailsLikeAfterARestart() throws Exception {
        Rig rig = rig("RemovedAfterAdd",
                "public class RemovedAfterAdd {\n" +
                "    public String keep() { return \"keep-v1\"; }\n" +
                "    public String gone() { return \"gone-v1\"; }\n" +
                "}");
        try {
            reload(rig, "RemovedAfterAdd",
                    "public class RemovedAfterAdd {\n" +
                    "    public String keep() { return \"keep-v1\"; }\n" +
                    "    public String gone() { return \"gone-v1\"; }\n" +
                    "    public String extra() { return \"extra\"; }\n" +
                    "}");
            rig.warnings().clear();
            recordExtraAsExisting(rig.context(), "RemovedAfterAdd");

            reload(rig, "RemovedAfterAdd",
                    "public class RemovedAfterAdd {\n" +
                    "    public String keep() { return \"keep-v3\"; }\n" +
                    "    public String extra() { return \"extra\"; }\n" +
                    "}");
            assertEquals("keep-v3", rig.keep().invoke(rig.instance()));
            assertGone(rig, "gone");
            assertWarnsNoSuchMethod(rig);
        } finally {
            teardown(rig);
        }
    }

    /** Putting the method back makes it answer again, with the restored body. */
    @Test
    void aRestoredMethodAnswersWithItsNewBody() throws Exception {
        Rig rig = rig("RemovedThenRestored",
                "public class RemovedThenRestored {\n" +
                "    public String keep() { return \"keep-v1\"; }\n" +
                "    public String gone() { return \"gone-v1\"; }\n" +
                "}");
        try {
            reload(rig, "RemovedThenRestored",
                    "public class RemovedThenRestored {\n" +
                    "    public String keep() { return \"keep-v2\"; }\n" +
                    "}");
            assertGone(rig, "gone");

            reload(rig, "RemovedThenRestored",
                    "public class RemovedThenRestored {\n" +
                    "    public String keep() { return \"keep-v3\"; }\n" +
                    "    public String gone() { return \"gone-v3\"; }\n" +
                    "}");
            assertEquals("gone-v3", rig.gone().invoke(rig.instance()));
            assertEquals("keep-v3", rig.keep().invoke(rig.instance()));
        } finally {
            teardown(rig);
        }
    }

    /**
     * A removed override does not fail after a restart: the call resolves to
     * the inherited implementation. A throw here would break every
     * {@code toString}, {@code equals} or framework callback a developer
     * stops overriding.
     */
    @Test
    void aRemovedOverrideRunsTheInheritedImplementation() throws Exception {
        Rig rig = rig("RemovedOverride",
                "public class RemovedOverride {\n" +
                "    public String keep() { return \"keep-v1\"; }\n" +
                "    public String gone() { return \"gone-v1\"; }\n" +
                "    @Override public String toString() { return \"override-v1\"; }\n" +
                "}");
        try {
            assertEquals("override-v1", rig.instance().toString());
            reload(rig, "RemovedOverride",
                    "public class RemovedOverride {\n" +
                    "    public String keep() { return \"keep-v2\"; }\n" +
                    "    public String gone() { return \"gone-v1\"; }\n" +
                    "}");
            assertEquals("RemovedOverride@" + Integer.toHexString(rig.instance().hashCode()),
                    rig.instance().toString(), "Object.toString, as after a restart");
            assertEquals("keep-v2", rig.keep().invoke(rig.instance()));
            assertTrue(rig.warnings().stream().noneMatch(w -> w.contains("toString")),
                    "an override falling back to its parent is not a failure: " + rig.warnings());
        } finally {
            teardown(rig);
        }
    }

    /**
     * A lambda object created before the save still points at its
     * {@code lambda$} body, which the same save removed with the method that
     * made it. That body is compiler-generated and keeps answering.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aLiveLambdaKeepsItsBodyWhenTheMethodThatMadeItIsRemoved() throws Exception {
        Rig rig = rig("RemovedLambdaOwner",
                "public class RemovedLambdaOwner {\n" +
                "    public String keep() { return \"keep-v1\"; }\n" +
                "    public String gone() { return \"gone-v1\"; }\n" +
                "    public java.util.function.Supplier<String> supplier() { return () -> \"lambda-v1\"; }\n" +
                "}");
        try {
            Supplier<String> held = (Supplier<String>) rig.cls().getDeclaredMethod("supplier")
                    .invoke(rig.instance());
            reload(rig, "RemovedLambdaOwner",
                    "public class RemovedLambdaOwner {\n" +
                    "    public String keep() { return \"keep-v2\"; }\n" +
                    "}");
            assertEquals("lambda-v1", held.get());
            assertGone(rig, "gone");
        } finally {
            teardown(rig);
        }
    }
}
