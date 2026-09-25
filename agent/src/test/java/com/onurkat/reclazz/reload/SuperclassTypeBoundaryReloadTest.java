/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.agent.ClassReloader;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.transform.ReclazzTransformer;
import com.onurkat.reclazz.transform.TransformContext;
import com.onurkat.reclazz.transform.TransformTestBase;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Native type boundaries through the real transformer and reload path. */
class SuperclassTypeBoundaryReloadTest extends TransformTestBase {
    private static Instrumentation instrumentation;

    @BeforeAll
    static void setup() {
        instrumentation = ByteBuddyAgent.install();
        assertNotNull(instrumentation);
    }

    private static final String OLD_BASE = """
            public class %P%OldBase {
                public int state = 7;
                public String who() { return "old-base"; }
            }
            """;
    private static final String NEW_BASE = """
            public class %P%NewBase {
                public %P%NewBase() {}
                public %P%NewBase(int value) {}
                public String who() { return "new-base"; }
                public int onlyNew() { return 99; }
            }
            """;
    private static final String ORIGINAL = """
            public class %P%Service extends %P%OldBase {
                public int marker = 3;
                public String body() { return "v1"; }
                public String boundary(Object value) { return "old-boundary"; }
                public Object self() { return this; }
            }
            """;
    private static final String REPLACEMENT = ORIGINAL
            .replace("extends %P%OldBase", "extends %P%NewBase")
            .replace("return \"v1\"", "return \"v2\"");
    // Deliberately not transformed: these operations are JVM native type checks.
    private static final String CALLER = """
            public class %P%Caller {
                public static boolean isNew(Object value) { return value instanceof %P%NewBase; }
                public static Object castNew(Object value) { return (%P%NewBase) value; }
            }
            """;

    private record Fixture(String prefix, TransformContext context,
                           ReclazzTransformer transformer, SharedLoader loader,
                           Class<?> service, Object instance) {
        Object call(String name) throws Exception {
            return service.getDeclaredMethod(name).invoke(instance);
        }
    }

    private static Map<String, byte[]> compileFixture(String prefix, String service) {
        return compile(
                new SourceFile(prefix + "OldBase", OLD_BASE.replace("%P%", prefix)),
                new SourceFile(prefix + "NewBase", NEW_BASE.replace("%P%", prefix)),
                new SourceFile(prefix + "Service", service.replace("%P%", prefix)),
                new SourceFile(prefix + "Caller", CALLER.replace("%P%", prefix)));
    }

    private static Fixture load(String prefix) throws Exception {
        TransformContext context = new TransformContext();
        for (String suffix : new String[]{"OldBase", "NewBase", "Service"}) {
            context.addWatched(prefix + suffix);
        }
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        for (var entry : compileFixture(prefix, ORIGINAL).entrySet()) {
            byte[] transformed = entry.getKey().equals(prefix + "Caller") ? null
                    : transformer.transform(TransformTestBase.class.getClassLoader(),
                            entry.getKey(), null, null, entry.getValue());
            bytes.put(entry.getKey(), transformed == null ? entry.getValue() : transformed);
        }
        // Existing harness loader isolates fixture names; no product loader is introduced.
        SharedLoader loader = sharedLoader(bytes);
        loader.load(prefix + "OldBase");
        loader.load(prefix + "NewBase");
        Class<?> service = loader.load(prefix + "Service");
        Fixture fixture = new Fixture(prefix, context, transformer, loader, service,
                service.getDeclaredConstructor().newInstance());
        assertEquals("v1", fixture.call("body"));
        assertSame(fixture.instance(), fixture.call("self"));
        assertEquals("old-boundary", boundary(fixture, fixture.instance()));
        return fixture;
    }

    private static Object boundary(Fixture f, Object value) throws Exception {
        return f.service().getDeclaredMethod("boundary", Object.class).invoke(f.instance(), value);
    }

    private static ClassReloader.ReloadResult reload(Fixture f, String source) {
        StructuralReloader reloader = new StructuralReloader(
                instrumentation, f.context(), AgentConfig.parse(null), null);
        reloader.setTransformer(f.transformer());
        instrumentation.addTransformer(f.transformer(), true);
        try {
            return reloader.reload(f.prefix() + "Service",
                    compileFixture(f.prefix(), source).get(f.prefix() + "Service"));
        } finally {
            instrumentation.removeTransformer(f.transformer());
        }
    }

    @Test
    void salvageKeepsNativeIdentityAndOriginalState() throws Exception {
        Fixture f = load("NativeBoundary");
        Class<?> originalClass = f.instance().getClass();
        ClassLoader originalLoader = originalClass.getClassLoader();
        Class<?> oldBase = originalClass.getSuperclass();
        Class<?> newBase = f.loader().load(f.prefix() + "NewBase");
        Class<?> caller = f.loader().load(f.prefix() + "Caller");
        var isNew = caller.getDeclaredMethod("isNew", Object.class);
        var castNew = caller.getDeclaredMethod("castNew", Object.class);
        Object newBaseInstance = newBase.getDeclaredConstructor().newInstance();
        assertEquals(true, isNew.invoke(null, newBaseInstance), "positive instanceof control");
        assertSame(newBaseInstance, castNew.invoke(null, newBaseInstance), "positive cast control");
        assertEquals(false, isNew.invoke(null, f.instance()));
        oldBase.getField("state").setInt(f.instance(), 19);

        var result = reload(f, REPLACEMENT);
        assertTrue(result.isSuccess(), result.getError());
        assertEquals("v2", f.call("body"), "independent body really changed on the held receiver");
        Object returned = f.call("self");
        assertSame(f.instance(), returned, "dispatch must return the original receiver");
        assertSame(originalClass, returned.getClass());
        assertSame(originalLoader, returned.getClass().getClassLoader());
        assertSame(oldBase, returned.getClass().getSuperclass());
        assertTrue(oldBase.isInstance(returned));
        assertFalse(newBase.isInstance(returned));
        assertEquals(false, isNew.invoke(null, returned));
        var thrown = assertThrows(InvocationTargetException.class,
                () -> castNew.invoke(null, returned));
        assertInstanceOf(ClassCastException.class, thrown.getCause());
        assertEquals("old-base", oldBase.getMethod("who").invoke(returned));
        assertEquals(19, oldBase.getField("state").getInt(returned));
    }

    @Test
    void aNewBaseCastPinsOnlyItsMethod() throws Exception {
        // Keep CHECKCAST separate from a new-base method call, which would
        // independently trigger pinning and mask a broken type-instruction guard.
        assertPinned("CastBoundary", "return ((%P%NewBase) value) == null ? \"null\" : \"cast\";");
    }

    @Test
    void aNewBaseInstanceofPinsOnlyItsMethod() throws Exception {
        assertPinned("InstanceofBoundary", "return value instanceof %P%NewBase ? \"yes\" : \"no\";");
    }

    private static void assertPinned(String prefix, String newBody) throws Exception {
        Fixture f = load(prefix);
        var result = reload(f, REPLACEMENT.replace("return \"old-boundary\";", newBody));
        assertTrue(result.isSuccess(), result.getError());
        assertEquals("v2", f.call("body"));
        assertEquals("old-boundary", boundary(f, f.instance()));
        Object newBase = f.loader().load(prefix + "NewBase").getDeclaredConstructor().newInstance();
        assertEquals("old-boundary", boundary(f, newBase), "pin also holds for a valid new-base argument");
    }

    @Test
    void aNewBaseReturnTypeRefusesTheWholeClass() throws Exception {
        assertRefused("ReturnBoundary", "public %P%NewBase typed() { return null; }",
                "typed takes or returns ReturnBoundaryNewBase");
    }

    @Test
    void aNewBaseParameterTypeRefusesTheWholeClass() throws Exception {
        assertRefused("ParameterBoundary", "public int typed(%P%NewBase value) { return 1; }",
                "typed takes or returns ParameterBoundaryNewBase");
    }

    @Test
    void aConstructorDependingOnTheNewBaseRefusesTheWholeClass() throws Exception {
        assertRefused("CtorBodyBoundary", "public %P%Service() { marker = onlyNew(); }",
                "<init> calls onlyNew");
    }

    @Test
    void aMissingOldBaseConstructorRefusesTheWholeClass() throws Exception {
        assertRefused("CtorSignatureBoundary", "public %P%Service() { super(42); }",
                "has no constructor CtorSignatureBoundaryOldBase(I)");
    }

    private static void assertRefused(String prefix, String member, String reason) throws Exception {
        Fixture f = load(prefix);
        f.service().getField("marker").setInt(f.instance(), 17);
        String replacement = REPLACEMENT.replace("public int marker = 3;", "public int marker = 3; " + member);
        var result = reload(f, replacement);
        assertFalse(result.isSuccess(), "unsafe class must be refused");
        assertTrue(result.getError().contains(reason), result.getError());
        assertEquals("v1", f.call("body"), "refusal cannot partially retarget a warmed method");
        assertEquals("old-boundary", boundary(f, f.instance()));
        assertSame(f.instance(), f.call("self"));
        assertEquals(17, f.service().getField("marker").getInt(f.instance()));
        assertSame(f.loader().load(prefix + "OldBase"), f.service().getSuperclass());
        Object fresh = f.service().getDeclaredConstructor().newInstance();
        assertEquals(3, f.service().getField("marker").getInt(fresh));
        assertEquals("v1", f.service().getDeclaredMethod("body").invoke(fresh));
        assertTrue(java.util.Arrays.stream(f.service().getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("typed")));
    }
}
