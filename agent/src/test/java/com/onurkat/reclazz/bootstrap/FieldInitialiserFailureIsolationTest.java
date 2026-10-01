/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FieldInitialiserFailureIsolationTest {
    private static final String DESC = "Ljava/lang/Object;";
    public static class Receiver {
        public Object[] __reclazz$ext;
        public boolean fail;
        public final AtomicInteger attempts = new AtomicInteger();
    }
    public static Object conditional(Object value) {
        Receiver receiver = (Receiver) value;
        receiver.attempts.incrementAndGet();
        if (receiver.fail) throw new IllegalStateException("fixture failure");
        return "ready";
    }
    private static MethodHandle conditional() throws Exception {
        return MethodHandles.lookup().findStatic(FieldInitialiserFailureIsolationTest.class,
                "conditional", MethodType.methodType(Object.class, Object.class));
    }
    private static MethodHandle constant(Object value) {
        return MethodHandles.dropArguments(MethodHandles.constant(Object.class, value), 0, Object.class);
    }
    private static void install(MethodHandle handle) {
        FieldStore.setInstanceInitialisers(Receiver.class, Map.of("added:" + DESC, handle));
    }
    private static Object read(Receiver receiver) {
        return FieldStore.getExtField(receiver, Receiver.class.getName(), "added", DESC);
    }
    private static void write(Receiver receiver, Object value) {
        FieldStore.putExtField(receiver, value, Receiver.class.getName(), "added", DESC);
    }
    @AfterEach void clear() { FieldStore.setInstanceInitialisers(Receiver.class, Map.of()); }

    @Test void oneObjectFailureDoesNotDisableAnotherObject() throws Exception {
        install(conditional());
        Receiver bad = new Receiver(); bad.fail = true;
        Receiver good = new Receiver();
        assertNull(read(bad));
        for (int i = 0; i < 20; i++) assertNull(read(bad));
        assertEquals(1, bad.attempts.get());
        assertTrue(FieldStore.hasInstanceInitialiser(Receiver.class, "added", DESC));
        assertEquals("ready", read(good));
        assertEquals(1, good.attempts.get());
    }

    @Test void newerRegistrationRecoversOnlyFailedOrUnsetValues() throws Exception {
        install(conditional());
        Receiver failed = new Receiver(); failed.fail = true;
        Receiver successful = new Receiver();
        Receiver written = new Receiver(); write(written, "application");
        Receiver writtenNull = new Receiver(); write(writtenNull, null);
        assertNull(read(failed));
        assertEquals("ready", read(successful));
        FieldStore.setInstanceInitialisers(Receiver.class, Map.of());
        assertNull(read(failed));
        install(constant("new"));
        assertEquals("new", read(failed));
        assertEquals("ready", read(successful));
        assertEquals("application", read(written));
        assertNull(read(writtenNull));
        Receiver initialisedNull = new Receiver(); install(constant(null));
        assertNull(read(initialisedNull));
        install(constant("later"));
        assertNull(read(initialisedNull));
    }

    public static class Blocked {
        final CountDownLatch entered;
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger attempts = new AtomicInteger();
        final boolean fail;
        Blocked(boolean fail, int readers) { this.fail = fail; entered = new CountDownLatch(readers); }
        public Object compute(Object ignored) throws Exception {
            attempts.incrementAndGet(); entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timed out");
            if (fail) throw new IllegalStateException("old failure");
            return "old";
        }
        MethodHandle handle() throws Exception {
            return MethodHandles.lookup().findVirtual(Blocked.class, "compute",
                    MethodType.methodType(Object.class, Object.class)).bindTo(this);
        }
        void awaitEntry() throws Exception { assertTrue(entered.await(10, TimeUnit.SECONDS)); }
    }

    @Test void oldFailureCannotRetireNewInitializerAndOldSuccessCannotPublish() throws Exception {
        for (boolean fail : new boolean[] {true, false}) {
            Blocked old = new Blocked(fail, 1); install(old.handle());
            Receiver receiver = new Receiver();
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<Object> pending = worker.submit(() -> read(receiver)); old.awaitEntry();
                install(constant("new"));
                old.release.countDown();
                assertNull(pending.get(10, TimeUnit.SECONDS), "stale outcomes are discarded");
                assertTrue(FieldStore.hasInstanceInitialiser(Receiver.class, "added", DESC));
                assertEquals("new", read(receiver));
                assertEquals("new", read(new Receiver()));
            } finally { old.release.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    @Test void inFlightOutcomeNeverOverwritesApplicationOrNewGenerationValue() throws Exception {
        for (boolean fail : new boolean[] {true, false}) {
            for (int mode = 0; mode < 4; mode++) {
                Blocked old = new Blocked(fail, 1); install(old.handle());
                Receiver receiver = new Receiver();
                ExecutorService worker = Executors.newSingleThreadExecutor();
                try {
                    Future<Object> pending = worker.submit(() -> read(receiver)); old.awaitEntry();
                    Object expected = mode == 0 || mode == 3 ? null : mode == 1 ? "application" : "new";
                    if (mode == 2) { install(constant("new")); assertEquals(expected, read(receiver)); }
                    else if (mode == 3) { receiver.fail = true; install(conditional()); assertNull(read(receiver)); }
                    else write(receiver, expected);
                    old.release.countDown();
                    assertEquals(expected, pending.get(10, TimeUnit.SECONDS));
                    assertEquals(expected, read(receiver));
                    if (mode == 3) assertEquals(1, receiver.attempts.get(), "old completion cannot reopen new failure");
                } finally { old.release.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS)); }
            }
        }
    }

    @Test void overlappingFailuresDoNotCauseRepeatedReadRetries() throws Exception {
        Blocked blocked = new Blocked(true, 2); install(blocked.handle());
        Receiver receiver = new Receiver();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Object> a = workers.submit(() -> read(receiver));
            Future<Object> b = workers.submit(() -> read(receiver));
            blocked.awaitEntry(); blocked.release.countDown();
            assertNull(a.get(10, TimeUnit.SECONDS)); assertNull(b.get(10, TimeUnit.SECONDS));
            for (int i = 0; i < 20; i++) assertNull(read(receiver));
            assertEquals(2, blocked.attempts.get(), "only already-running computations may overlap");
            assertTrue(FieldStore.hasInstanceInitialiser(Receiver.class, "added", DESC));
        } finally { blocked.release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void primitiveFailureReturnsDefaultAndOtherFieldsRemainIndependent() throws Exception {
        Receiver receiver = new Receiver(); receiver.fail = true;
        FieldStore.setInstanceInitialisers(Receiver.class, Map.of("number:I", conditional(), "added:" + DESC, constant("other")));
        assertEquals(0, FieldStore.getExtField(receiver, Receiver.class.getName(), "number", "I"));
        assertEquals(0, FieldStore.getExtField(receiver, Receiver.class.getName(), "number", "I"));
        assertEquals(1, receiver.attempts.get());
        assertEquals("other", read(receiver));
    }

    public static class MixedOutcomes {
        final AtomicInteger attempts = new AtomicInteger();
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch bothEntered = new CountDownLatch(2);
        final CountDownLatch firstRelease = new CountDownLatch(1);
        final CountDownLatch secondRelease = new CountDownLatch(1);
        final boolean firstFails;
        MixedOutcomes(boolean firstFails) { this.firstFails = firstFails; }
        public Object compute(Object ignored) throws Exception {
            int attempt = attempts.incrementAndGet();
            firstEntered.countDown(); bothEntered.countDown();
            assertTrue((attempt == 1 ? firstRelease : secondRelease).await(10, TimeUnit.SECONDS));
            if ((attempt == 1) == firstFails) throw new IllegalStateException("mixed failure");
            return "success";
        }
    }

    @Test void firstPublishedOutcomeWinsWhenSuccessAndFailureOverlap() throws Exception {
        for (boolean firstFails : new boolean[] {true, false}) {
            MixedOutcomes race = new MixedOutcomes(firstFails);
            install(MethodHandles.lookup().findVirtual(MixedOutcomes.class, "compute",
                    MethodType.methodType(Object.class, Object.class)).bindTo(race));
            Receiver receiver = new Receiver();
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<Object> first = workers.submit(() -> read(receiver));
                assertTrue(race.firstEntered.await(10, TimeUnit.SECONDS));
                Future<Object> second = workers.submit(() -> read(receiver));
                assertTrue(race.bothEntered.await(10, TimeUnit.SECONDS));
                Object expected = firstFails ? null : "success";
                race.firstRelease.countDown(); assertEquals(expected, first.get(10, TimeUnit.SECONDS));
                race.secondRelease.countDown(); assertEquals(expected, second.get(10, TimeUnit.SECONDS));
                for (int i = 0; i < 20; i++) assertEquals(expected, read(receiver));
                assertEquals(2, race.attempts.get());
            } finally {
                race.firstRelease.countDown(); race.secondRelease.countDown();
                workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test void failureMarkerDoesNotRetainOldHandlePayloadOrReceiver() throws Exception {
        Receiver receiver = new Receiver();
        WeakReference<?> payload = failedPayload(receiver);
        install(constant("new"));
        collected(payload);
        assertNotNull(receiver.__reclazz$ext, "keep the failed slot alive while collecting the old handle");
        WeakReference<?> weakReceiver = failedReceiver();
        collected(weakReceiver);
    }
    private static WeakReference<?> failedPayload(Receiver receiver) throws Exception {
        Blocked payload = new Blocked(true, 0); payload.release.countDown();
        install(payload.handle()); assertNull(read(receiver));
        return new WeakReference<>(payload);
    }
    private static WeakReference<?> failedReceiver() throws Exception {
        install(conditional()); Receiver receiver = new Receiver(); receiver.fail = true;
        assertNull(read(receiver)); return new WeakReference<>(receiver);
    }
    private static void collected(WeakReference<?> reference) throws Exception {
        for (int i = 0; i < 40 && reference.get() != null; i++) { System.gc(); Thread.sleep(20); }
        assertNull(reference.get(), "failure bookkeeping must not retain application objects");
    }

    public static class Disposable {
        public Object[] __reclazz$ext;
        public static Object fail(Object ignored) { throw new IllegalStateException("discardable"); }
    }

    @Test void failedInstanceAndRegisteredHandleDoNotPinOwningLoader() throws Exception {
        collected(disposableLoader());
    }

    private static WeakReference<?> disposableLoader() throws Exception {
        String name = Disposable.class.getName();
        byte[] bytes;
        try (var input = Disposable.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            assertNotNull(input); bytes = input.readAllBytes();
        }
        class Loader extends ClassLoader {
            Loader() { super(FieldInitialiserFailureIsolationTest.class.getClassLoader()); }
            Class<?> define() { return defineClass(name, bytes, 0, bytes.length); }
        }
        Loader loader = new Loader(); Class<?> owner = loader.define();
        assertNotSame(Disposable.class, owner);
        Object receiver = owner.getConstructor().newInstance();
        MethodHandle handle = MethodHandles.publicLookup().findStatic(owner, "fail",
                MethodType.methodType(Object.class, Object.class));
        FieldStore.setInstanceInitialisers(owner, Map.of("added:" + DESC, handle));
        assertNull(FieldStore.getExtField(receiver, name, "added", DESC));
        assertNotNull(owner.getField("__reclazz$ext").get(receiver), "failure must leave real bookkeeping");
        assertTrue(FieldStore.hasInstanceInitialiser(owner, "added", DESC));
        return new WeakReference<>(loader);
    }
}
