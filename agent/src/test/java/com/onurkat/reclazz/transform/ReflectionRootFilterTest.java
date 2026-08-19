/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.bootstrap.LookupCapture;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Root-level hiding of {@code __reclazz$} members.
 *
 * <p>The call-site bridge ({@link ReflectionInterceptTransformer} rewriting to
 * ReflectionBridge) can never cover meta-reflection: invoking
 * {@code getDeclaredFields} through a {@code Method} object goes straight to
 * the JDK and no call site exists to rewrite. A {@code __reclazz$} field
 * leaking into a framework scan was observed once in the field through exactly
 * such a gap. These tests hold the filter that closes the whole class of
 * leaks, and hold its two safety properties: it must refuse cleanly on a JVM
 * that does not cooperate, and it must never cut Reclazz itself off from the
 * members it hides.
 */
class ReflectionRootFilterTest {

    private static Instrumentation instrumentation;

    @BeforeAll
    static void installAgent() {
        instrumentation = ByteBuddyAgent.install();
    }

    /**
     * The fixture carries the injected members literally, the way the
     * load-time transform writes them into a watched class.
     */
    static class Transformed {
        private Object[] __reclazz$ext = new Object[8];
        private static final MethodHandles.Lookup __reclazz$lookup = MethodHandles.lookup();
        public String greet() { return "hello"; }
        private String __reclazz$v0$greet$abc123() { return "v0"; }
    }

    /** Registered twice to prove the second call changes nothing. */
    static class RegisteredTwice {
        private Object[] __reclazz$ext = new Object[8];
        private static final MethodHandles.Lookup __reclazz$lookup = MethodHandles.lookup();
        public int answer() { return 42; }
        private int __reclazz$v0$answer$def456() { return -1; }
    }

    /** Has a __reclazz$-style field but no lookup, so Reclazz never made it. */
    static class NotOurs {
        private Object[] __reclazz$ext = new Object[8];
        public String name() { return "not-ours"; }
    }

    /**
     * A JVM that refuses any step of the probe must leave today's behaviour
     * exactly in place: no exception out of the probe, no exception out of a
     * registration attempt, and the members still visible the way the bridge
     * expects to find and strip them.
     */
    @Test
    void aFailedProbeFallsBackToTodaysBehaviourInsteadOfThrowing() {
        ReflectionRootFilter unavailable = new ReflectionRootFilter(null);

        assertFalse(unavailable.isAvailable(),
                "no Instrumentation means no module opening, so the probe must report unsupported");
        assertDoesNotThrow(() -> unavailable.registerFor(
                        NotOurs.class, Set.of("__reclazz$ext"), Set.of()),
                "registration on an unavailable filter is a no-op, not an error");
        assertTrue(fieldNames(NotOurs.class).contains("__reclazz$ext"),
                "nothing may be hidden when the filter is unavailable");
    }

    /**
     * The adversarial probes: the three routes that reached the members before
     * this existed. Direct scan and by-name lookup were covered by the bridge
     * only where a call site had been rewritten; meta-reflection was covered
     * nowhere, and is the route the observed leak took.
     */
    @Test
    void noRouteThroughReflectionReachesInjectedMembersAfterRegistration() throws Exception {
        ReflectionRootFilter filter = new ReflectionRootFilter(instrumentation);
        assertTrue(filter.isAvailable(), "this JDK is one the spike proved; the probe must pass here");

        filter.registerInjectedMembers(Transformed.class);

        // (a) direct scans
        assertEquals(List.of(), reclazzNames(fieldNames(Transformed.class)),
                "getDeclaredFields must not show injected fields");
        assertEquals(List.of(), reclazzNames(methodNames(Transformed.class)),
                "getDeclaredMethods must not show renamed method copies");

        // (b) meta-reflection, the route no call-site rewrite can cover
        Field[] metaFields = (Field[]) Class.class.getMethod("getDeclaredFields")
                .invoke(Transformed.class);
        assertEquals(List.of(), reclazzNames(Arrays.stream(metaFields).map(Field::getName).toList()),
                "invoking getDeclaredFields through a Method object must be filtered too");
        Method[] metaMethods = (Method[]) Class.class.getMethod("getDeclaredMethods")
                .invoke(Transformed.class);
        assertEquals(List.of(), reclazzNames(Arrays.stream(metaMethods).map(Method::getName).toList()),
                "and the same for getDeclaredMethods");

        // (c) by-name lookup
        assertThrows(NoSuchFieldException.class,
                () -> Transformed.class.getDeclaredField("__reclazz$ext"),
                "a filtered field must be unreachable by name as well");
        assertThrows(NoSuchFieldException.class,
                () -> Transformed.class.getDeclaredField("__reclazz$lookup"));
        assertThrows(NoSuchMethodException.class,
                () -> Transformed.class.getDeclaredMethod("__reclazz$v0$greet$abc123"));

        // Reflection on real members keeps working, invocation included.
        Method greet = Transformed.class.getDeclaredMethod("greet");
        assertEquals("hello", greet.invoke(new Transformed()),
                "hiding our members must not degrade reflection on the user's members");

        // And the engine's own access survives the hiding: the lookup was
        // captured before the field disappeared.
        assertNotNull(LookupCapture.get(Transformed.class),
                "the lookup must be captured before the field becomes unreachable, "
                        + "or the second reload of every class would fail");
    }

    /**
     * The JDK accepts exactly one registration per class (a second one throws
     * {@code IllegalArgumentException: Filter already registered}, measured on
     * SapMachine 21 and JBR 25). Re-registration therefore must be a recorded
     * no-op; if it reached the JDK again, every reload after the first would
     * blow up on the class it had just reloaded.
     */
    @Test
    void reRegisteringAClassKeepsEveryNameAndDoesNotReachTheJdkTwice() {
        ReflectionRootFilter filter = new ReflectionRootFilter(instrumentation);
        assertTrue(filter.isAvailable());

        filter.registerInjectedMembers(RegisteredTwice.class);
        assertDoesNotThrow(() -> filter.registerInjectedMembers(RegisteredTwice.class),
                "the second registration is what every reload after the first performs");
        // A narrower explicit request must not shrink the filter either.
        assertDoesNotThrow(() -> filter.registerFor(
                RegisteredTwice.class, Set.of("__reclazz$ext"), Set.of()));

        assertEquals(List.of(), reclazzNames(fieldNames(RegisteredTwice.class)),
                "both fields stay hidden after re-registration");
        assertEquals(List.of(), reclazzNames(methodNames(RegisteredTwice.class)),
                "and the renamed method stays hidden too");

        // A second instance meeting the same class must also stay calm: the
        // registration record is JVM-wide, like the JDK state it mirrors.
        ReflectionRootFilter second = new ReflectionRootFilter(instrumentation);
        assertDoesNotThrow(() -> second.registerInjectedMembers(RegisteredTwice.class));
    }

    /**
     * A class Reclazz did not transform is not ours to filter, whatever its
     * members are called. Registering on it would be exactly the kind of
     * global side effect on somebody else's class this agent promises not to
     * have.
     */
    @Test
    void aClassWithoutTheInjectedLookupIsLeftAlone() {
        ReflectionRootFilter filter = new ReflectionRootFilter(instrumentation);
        assertTrue(filter.isAvailable());

        filter.registerInjectedMembers(NotOurs.class);

        assertTrue(fieldNames(NotOurs.class).contains("__reclazz$ext"),
                "no lookup field means Reclazz never transformed this class, so nothing is hidden");
    }

    /**
     * The filter's benefit came with a leak, and this holds the fix.
     *
     * <p>The JDK's own {@code fieldFilterMap}/{@code methodFilterMap} keep a
     * strong {@code Class} key with no removal API. A class registered from a
     * discardable loader (a Tomcat webapp that gets redeployed) could then
     * never be collected, and neither could its whole classloader, which is the
     * exact retention 1.0.22 removed elsewhere. Measured on a throwaway loader
     * before the fix: not collectable. So a class on a loader outside the
     * system classloader's permanent chain is registered nowhere in the JDK
     * map and keeps the bridge's call-site cover instead. This test defines a
     * transformed class in a throwaway loader, registers it, drops the loader,
     * and asserts it collects.
     */
    @Test
    void aClassOnADiscardableLoaderDoesNotPinItsLoaderInTheJdkFilterMap() throws Exception {
        ReflectionRootFilter filter = new ReflectionRootFilter(instrumentation);
        assumeTrue(filter.isAvailable(), "root filter unsupported on this JVM; nothing to leak");

        java.lang.ref.WeakReference<ClassLoader> watch = registerInThrowawayLoader(filter);

        for (int attempt = 0; attempt < 25 && watch.get() != null; attempt++) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(watch.get(),
                "a class registered from a discardable loader must not be pinned in the "
                + "JDK filter map, or the whole loader leaks on every redeploy");
    }

    /**
     * Isolated so the only strong reference to the loader is the one this
     * method drops on return: a transformed class defined by a throwaway
     * loader, registered with the filter, then let go.
     */
    private static java.lang.ref.WeakReference<ClassLoader> registerInThrowawayLoader(
            ReflectionRootFilter filter) throws Exception {
        String name = Transformed.class.getName();
        byte[] bytes;
        try (java.io.InputStream in = ReflectionRootFilterTest.class.getClassLoader()
                .getResourceAsStream(name.replace('.', '/') + ".class")) {
            bytes = in.readAllBytes();
        }
        ClassLoader loader = new ClassLoader(ReflectionRootFilterTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> victim = (Class<?>) loader.getClass().getDeclaredMethod("define").invoke(loader);
        filter.registerInjectedMembers(victim);
        return new java.lang.ref.WeakReference<>(loader);
    }

    private static List<String> fieldNames(Class<?> c) {
        return Arrays.stream(c.getDeclaredFields()).map(Field::getName).toList();
    }

    private static List<String> methodNames(Class<?> c) {
        return Arrays.stream(c.getDeclaredMethods()).map(Method::getName).toList();
    }

    private static List<String> reclazzNames(List<String> names) {
        return names.stream().filter(n -> n.startsWith("__reclazz$")).toList();
    }
}
