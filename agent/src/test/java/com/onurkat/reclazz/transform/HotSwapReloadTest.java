/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.agent.AgentConfig;
import com.onurkat.reclazz.agent.ClassReloader;
import com.onurkat.reclazz.bootstrap.DispatchTable;
import com.onurkat.reclazz.reload.StructuralReloader;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end hot-swap test using the real JVM Instrumentation API via
 * ByteBuddyAgent.install(). Compiles a v1 of a class, transforms it, loads
 * it, invokes a method, then compiles a v2 with a different return value,
 * transforms v2, and uses Instrumentation.redefineClasses to swap the new
 * bytes in. Verifies the loaded class's method now returns the v2 value.
 */
class HotSwapReloadTest extends TransformTestBase {

    private static Instrumentation instrumentation;

    @BeforeAll
    static void setup() {
        instrumentation = ByteBuddyAgent.install();
        assertNotNull(instrumentation, "ByteBuddyAgent.install must succeed");
    }

    @Test
    void methodBodyHotSwap() throws Exception {
        // v1: method returns "v1"
        Map<String, byte[]> v1Classes = compileAndTransform(
                new SourceFile("HotClass",
                        "public class HotClass {\n" +
                        "    public String greet() { return \"v1\"; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(v1Classes, "HotClass");
        Object instance = cls.getDeclaredConstructor().newInstance();
        Method greet = cls.getDeclaredMethod("greet");
        assertEquals("v1", greet.invoke(instance));

        // v2: method returns "v2"
        Map<String, byte[]> v2Classes = compileAndTransform(
                new SourceFile("HotClass",
                        "public class HotClass {\n" +
                        "    public String greet() { return \"v2\"; }\n" +
                        "}")
        );

        // redefine the loaded class with v2 bytes
        ClassDefinition def = new ClassDefinition(cls, v2Classes.get("HotClass"));
        instrumentation.redefineClasses(def);

        // Same instance, same Method object — should now return v2
        assertEquals("v2", greet.invoke(instance));
    }

    @Test
    void reloadBeforeFirstInvocationStillTakesEffect() throws Exception {
        // Reload a class BEFORE invoking any of its methods. Without a fix,
        // the first call site bootstrap installs a handle to the v0 renamed
        // method (because the dispatch table has no entry yet — call sites
        // are lazy), so the v2 reload is silently lost until the SECOND
        // invocation. This reproduces the user-reported "first reload doesn't
        // work but second and third do" bug.
        Map<String, byte[]> v1Classes = compileAndTransform(
                new SourceFile("LazyClass",
                        "public class LazyClass {\n" +
                        "    public String greet() { return \"v1\"; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(v1Classes, "LazyClass");
        Object instance = cls.getDeclaredConstructor().newInstance();
        Method greet = cls.getDeclaredMethod("greet");

        // Reload to v2 BEFORE any invocation
        Map<String, byte[]> v2Classes = compileAndTransform(
                new SourceFile("LazyClass",
                        "public class LazyClass {\n" +
                        "    public String greet() { return \"v2\"; }\n" +
                        "}")
        );
        instrumentation.redefineClasses(new ClassDefinition(cls, v2Classes.get("LazyClass")));

        // First invocation must see v2 (not v1)
        assertEquals("v2", greet.invoke(instance));
    }

    /**
     * Reproduces the user-reported "first reload is silently lost" bug.
     * Production reload path (StructuralReloader) generates a companion
     * class and calls DispatchTable.retargetAll. retargetAll iterates
     * existing MutableCallSite entries and updates them. If no call site
     * has been bootstrapped yet (lazy invokedynamic, first server startup
     * with no traffic), the dispatch table is empty and retargetAll
     * silently does nothing. The first user request after the reload
     * bootstraps a CallSite pointing to the v0 renamed method — the
     * reload is silently lost. This test calls retargetAll BEFORE any
     * trampoline has fired and verifies the next invocation picks up
     * the new target.
     */
    @Test
    void retargetBeforeFirstBootstrapStillTakesEffect() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("EarlyReload",
                        "public class EarlyReload {\n" +
                        "    public String greet() { return \"v0\"; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "EarlyReload");
        Object instance = cls.getDeclaredConstructor().newInstance();

        // Find the descHash for greet()Ljava/lang/String; from the renamed
        // method that MethodTrampolineAdapter created.
        String renamedPrefix = "__reclazz$v0$greet$";
        String renamedName = null;
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().startsWith(renamedPrefix)) {
                renamedName = m.getName();
                break;
            }
        }
        assertNotNull(renamedName, "renamed greet should exist");
        String descHash = renamedName.substring(renamedPrefix.length());
        String siteKey = "greet:" + descHash;

        // Build a MethodHandle that returns "v2-from-retarget". The bootstrap
        // for an instance method expects type (Receiver)ReturnType, so we
        // build a constant handle that ignores the receiver.
        MethodHandle constantV2 = MethodHandles.constant(String.class, "v2-from-retarget");
        MethodHandle handleForReceiver = MethodHandles.dropArguments(constantV2, 0, cls);

        // Ahead-of-bootstrap retarget: this is what StructuralReloader does
        // on a reload. Before our fix, it silently failed when no CallSite
        // existed yet.
        Map<String, MethodHandle> targets = new LinkedHashMap<>();
        targets.put(siteKey, handleForReceiver);
        DispatchTable.retargetAll(cls, MethodHandles.lookup(), targets);

        // Now call greet() for the first time. The trampoline triggers the
        // bootstrap → CallSite is created → our fix consults latestMethodTargets
        // and uses handleForReceiver instead of the v0 renamed method.
        Method greet = cls.getDeclaredMethod("greet");
        Object result = greet.invoke(instance);
        assertEquals("v2-from-retarget", result,
                "First-touch bootstrap must honor the latest retarget target");
    }

    /**
     * End-to-end through the real production reload chain:
     * {@code StructuralReloader → CompanionGenerator → companionLookup.findStatic →
     * DispatchTable.retargetAll}. Unlike
     * {@link #retargetBeforeFirstBootstrapStillTakesEffect()} which constructs
     * a sentinel {@code MethodHandles.constant} handle directly, this test
     * drives the real pipeline end-to-end: it compiles v1 through
     * {@link ReclazzTransformer} so the class gets a real
     * {@code __reclazz$lookup} field and dispatch trampolines, loads it via a
     * normal class loader (so {@code <clinit>} populates the lookup), then
     * invokes {@link StructuralReloader#reload(String, byte[])} with raw v2
     * bytes BEFORE the class's method has ever been called. This is the
     * "first server startup with no traffic yet, file saved in IDE" scenario
     * — the bug surface covered by release 1.0.14.
     */
    @Test
    void structuralReloaderEndToEndBeforeFirstInvocation() throws Exception {
        TransformContext ctx = new TransformContext();
        AgentConfig config = AgentConfig.parse(null);
        ctx.addWatched("EndToEndFirstTouch");

        // Compile + transform v1 through our own context so the metadata
        // produced by MethodTrampolineAdapter survives into the reloader call.
        byte[] v1Raw = compile(new SourceFile("EndToEndFirstTouch",
                "public class EndToEndFirstTouch {\n" +
                "    public String greet() { return \"v1\"; }\n" +
                "}")).get("EndToEndFirstTouch");

        ReclazzTransformer transformer = new ReclazzTransformer(ctx, config);
        byte[] v1Transformed = transformer.transform(
                TransformTestBase.class.getClassLoader(),
                "EndToEndFirstTouch", null, null, v1Raw);
        assertNotNull(v1Transformed, "v1 transform must produce output");

        // Load transformed v1 — clinit populates __reclazz$lookup
        Map<String, byte[]> loadMap = new LinkedHashMap<>();
        loadMap.put("EndToEndFirstTouch", v1Transformed);
        Class<?> cls = defineAndLoad(loadMap, "EndToEndFirstTouch");
        Object instance = cls.getDeclaredConstructor().newInstance();

        // Raw v2 — StructuralReloader.reload takes untransformed new bytes
        byte[] v2Raw = compile(new SourceFile("EndToEndFirstTouch",
                "public class EndToEndFirstTouch {\n" +
                "    public String greet() { return \"v2\"; }\n" +
                "}")).get("EndToEndFirstTouch");

        // Drive the full production reload path (no platform context → not
        // Hybris, skips Hybris-only side effects but exercises the core chain).
        StructuralReloader reloader = new StructuralReloader(
                instrumentation, ctx, config, null);
        ClassReloader.ReloadResult result = reloader.reload("EndToEndFirstTouch", v2Raw);
        assertTrue(result.isSuccess(),
                "Structural reload must succeed, got: " + result.getError());

        // First-ever invocation of greet() on this instance. The trampoline
        // triggers the invokedynamic bootstrap now — which must consult the
        // just-installed companion target and call the v2 body.
        Method greet = cls.getDeclaredMethod("greet");
        assertEquals("v2", greet.invoke(instance),
                "First-touch after StructuralReloader.reload must return v2 " +
                "(real CompanionGenerator + findStatic chain)");
    }

    /**
     * Same end-to-end chain, but with v1 invoked at least once before the
     * reload. This exercises the "warm" call-site path: the
     * {@link DispatchTable} already holds a MutableCallSite from v1, and
     * {@code retargetAll} must swap the target atomically.
     */
    @Test
    void structuralReloaderEndToEndAfterFirstInvocation() throws Exception {
        TransformContext ctx = new TransformContext();
        AgentConfig config = AgentConfig.parse(null);
        ctx.addWatched("EndToEndWarm");

        byte[] v1Raw = compile(new SourceFile("EndToEndWarm",
                "public class EndToEndWarm {\n" +
                "    public String greet() { return \"warm-v1\"; }\n" +
                "}")).get("EndToEndWarm");

        ReclazzTransformer transformer = new ReclazzTransformer(ctx, config);
        byte[] v1Transformed = transformer.transform(
                TransformTestBase.class.getClassLoader(),
                "EndToEndWarm", null, null, v1Raw);
        assertNotNull(v1Transformed, "v1 transform must produce output");

        Map<String, byte[]> loadMap = new LinkedHashMap<>();
        loadMap.put("EndToEndWarm", v1Transformed);
        Class<?> cls = defineAndLoad(loadMap, "EndToEndWarm");
        Object instance = cls.getDeclaredConstructor().newInstance();

        // Warm up the call site with a v1 invocation
        Method greet = cls.getDeclaredMethod("greet");
        assertEquals("warm-v1", greet.invoke(instance));

        byte[] v2Raw = compile(new SourceFile("EndToEndWarm",
                "public class EndToEndWarm {\n" +
                "    public String greet() { return \"warm-v2\"; }\n" +
                "}")).get("EndToEndWarm");

        StructuralReloader reloader = new StructuralReloader(
                instrumentation, ctx, config, null);
        ClassReloader.ReloadResult result = reloader.reload("EndToEndWarm", v2Raw);
        assertTrue(result.isSuccess(),
                "Structural reload must succeed, got: " + result.getError());

        assertEquals("warm-v2", greet.invoke(instance),
                "Warm call site must atomically retarget to v2");
    }

    /**
     * Regression test for the 1.0.30 Hybris live-test finding: after
     * structural reload, a method body that calls a {@code protected}
     * method inherited from a superclass in a DIFFERENT package used to
     * throw {@code IllegalAccessError} at runtime. The original class
     * passed Java's access check because it was a legitimate subclass;
     * the companion class that holds the reloaded body is NOT a subclass
     * (it extends {@code Object} and lives in its own hidden-class
     * runtime package), so the {@code INVOKEVIRTUAL} instruction copied
     * verbatim into the companion failed with:
     *
     * <pre>
     * IllegalAccessError: class ChildClass$$Reclazz$v1 tried to access
     * protected method 'getSecret' (...)
     * </pre>
     *
     * The fix in {@link CompanionGenerator} rewrites every cross-package
     * invocation in the companion to an {@code invokedynamic} that
     * resolves via the target class's {@code __reclazz$lookup}, which
     * has subclass-level access to inherited protected members.
     */
    @Test
    void structuralReloadPreservesAccessToInheritedProtectedMethod() throws Exception {
        TransformContext ctx = new TransformContext();
        AgentConfig config = AgentConfig.parse(null);
        ctx.addWatched("com/reclazz/probe/child/ProtectedAccessChild");

        // Parent in its own package — NOT watched / not transformed.
        // Child extends parent from a different package and calls the
        // parent's protected method.
        SourceFile parentSource = new SourceFile(
                "com.reclazz.probe.parent.ProtectedAccessParent",
                "package com.reclazz.probe.parent;\n" +
                "public class ProtectedAccessParent {\n" +
                "    protected String getSecret() { return \"parent-secret\"; }\n" +
                "}");
        SourceFile childV1Source = new SourceFile(
                "com.reclazz.probe.child.ProtectedAccessChild",
                "package com.reclazz.probe.child;\n" +
                "import com.reclazz.probe.parent.ProtectedAccessParent;\n" +
                "public class ProtectedAccessChild extends ProtectedAccessParent {\n" +
                // v1 greet() already calls the protected parent method.
                // Not strictly necessary for the test (we're verifying v2
                // after reload), but it keeps the source-to-bytecode
                // mapping symmetric.
                "    public String greet() { return \"v1:\" + getSecret(); }\n" +
                "}");

        Map<String, byte[]> compiled = compile(parentSource, childV1Source);

        // Transform only the child — parent stays as-is.
        ReclazzTransformer transformer = new ReclazzTransformer(ctx, config);
        byte[] childRaw = compiled.get("com/reclazz/probe/child/ProtectedAccessChild");
        byte[] childTransformed = transformer.transform(
                TransformTestBase.class.getClassLoader(),
                "com/reclazz/probe/child/ProtectedAccessChild",
                null, null, childRaw);
        assertNotNull(childTransformed, "v1 transform must produce output");

        Map<String, byte[]> loadMap = new LinkedHashMap<>();
        loadMap.put("com/reclazz/probe/parent/ProtectedAccessParent",
                compiled.get("com/reclazz/probe/parent/ProtectedAccessParent"));
        loadMap.put("com/reclazz/probe/child/ProtectedAccessChild", childTransformed);

        SharedLoader loader = sharedLoader(loadMap);
        Class<?> childCls = loader.load("com.reclazz.probe.child.ProtectedAccessChild");
        Object instance = childCls.getDeclaredConstructor().newInstance();

        // v1 sanity check — baseline call works.
        assertEquals("v1:parent-secret",
                childCls.getDeclaredMethod("greet").invoke(instance));

        // v2 — method body keeps the cross-package protected call.
        // After StructuralReloader.reload() copies this body into the
        // companion class, the INVOKEVIRTUAL on getSecret() would fail
        // with IllegalAccessError without the bytecode rewrite.
        byte[] childV2Raw = compile(
                parentSource,
                new SourceFile(
                        "com.reclazz.probe.child.ProtectedAccessChild",
                        "package com.reclazz.probe.child;\n" +
                        "import com.reclazz.probe.parent.ProtectedAccessParent;\n" +
                        "public class ProtectedAccessChild extends ProtectedAccessParent {\n" +
                        "    public String greet() { return \"v2[\" + getSecret() + \"]\"; }\n" +
                        "}"))
                .get("com/reclazz/probe/child/ProtectedAccessChild");

        StructuralReloader reloader = new StructuralReloader(
                instrumentation, ctx, config, null);
        ClassReloader.ReloadResult result = reloader.reload(
                "com.reclazz.probe.child.ProtectedAccessChild", childV2Raw);
        assertTrue(result.isSuccess(),
                "Structural reload must succeed, got: " + result.getError());

        // The real test: invoke the reloaded greet(). Without the
        // invokedynamic rewrite in CompanionGenerator, this throws
        // IllegalAccessError from the companion class's attempt to call
        // the cross-package protected getSecret().
        Object reloaded = childCls.getDeclaredMethod("greet").invoke(instance);
        assertEquals("v2[parent-secret]", reloaded,
                "Reloaded body must access parent's protected method via " +
                "ProtectedCallResolver bootstrap");
    }

    @Test
    void redefineDoesNotBreakOtherMethods() throws Exception {
        Map<String, byte[]> v1Classes = compileAndTransform(
                new SourceFile("HotClass2",
                        "public class HotClass2 {\n" +
                        "    public int a() { return 1; }\n" +
                        "    public int b() { return 2; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(v1Classes, "HotClass2");
        Object instance = cls.getDeclaredConstructor().newInstance();
        assertEquals(1, cls.getDeclaredMethod("a").invoke(instance));
        assertEquals(2, cls.getDeclaredMethod("b").invoke(instance));

        // Redefine: change a() to return 11, leave b() unchanged
        Map<String, byte[]> v2Classes = compileAndTransform(
                new SourceFile("HotClass2",
                        "public class HotClass2 {\n" +
                        "    public int a() { return 11; }\n" +
                        "    public int b() { return 2; }\n" +
                        "}")
        );
        instrumentation.redefineClasses(new ClassDefinition(cls, v2Classes.get("HotClass2")));

        assertEquals(11, cls.getDeclaredMethod("a").invoke(instance));
        assertEquals(2, cls.getDeclaredMethod("b").invoke(instance));
    }
}
