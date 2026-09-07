/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.*;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.*;

class CacheDependencyLedgerTest {
    @BeforeEach void reset() { CacheLedgerFixture.reset(); CacheDependencyLedger.configure(true); }
    @AfterEach void cleanup() { CacheLedgerFixture.reset(); }
    static class Helper { }
    static class Other { }
    static void record(Object cache, Class<?> type) {
        CacheDependencyLedger.open(); CacheDependencyLedger.cachesInUse(List.of(cache));
        CacheDependencyLedger.hit(type); CacheDependencyLedger.close();
    }
    @Test void noFrameRecordsNothing() {
        CacheDependencyLedger.hit(Helper.class);
        assertTrue(CacheDependencyLedger.cachesDependingOn(Helper.class).isEmpty());
    }
    @Test void outerCacheInheritsDependenciesWhenInnerCallIsAHit() {
        var prices = new ConcurrentMapCache("prices"); var quotes = new ConcurrentMapCache("quotes");
        record(prices, Helper.class);
        CacheDependencyLedger.open(); CacheDependencyLedger.cachesInUse(List.of(quotes));
        CacheDependencyLedger.open(); CacheDependencyLedger.cachesInUse(List.of(prices));
        CacheDependencyLedger.close(); CacheDependencyLedger.close();
        assertEquals(Set.of(prices, quotes), new HashSet<>(CacheDependencyLedger.cachesDependingOn(Helper.class)));
        // Query/eviction does not discard edges or class records.
        CacheDependencyLedger.cachesDependingOn(Helper.class).forEach(CacheDependencyLedger::clearObserved);
        assertEquals(2, CacheDependencyLedger.cachesDependingOn(Helper.class).size());
    }
    @Test void nestedMissAttributesBothCachesAndOtherThreadsDoNotLeak() throws Exception {
        var outer = new ConcurrentMapCache("outer"); var inner = new ConcurrentMapCache("inner");
        CacheDependencyLedger.open(); CacheDependencyLedger.cachesInUse(List.of(outer));
        Thread other = new Thread(() -> CacheDependencyLedger.hit(Other.class)); other.start(); other.join(2000);
        assertFalse(other.isAlive());
        record(inner, Helper.class); CacheDependencyLedger.close();
        assertEquals(Set.of(inner, outer), new HashSet<>(CacheDependencyLedger.cachesDependingOn(Helper.class)));
        assertTrue(CacheDependencyLedger.cachesDependingOn(Other.class).isEmpty());
    }
    @Test void sameNamedCachesAreIndependent() {
        var a = new ConcurrentMapCache("prices"); var b = new ConcurrentMapCache("prices");
        record(a, Helper.class); record(b, Other.class);
        assertEquals(List.of(a), CacheDependencyLedger.cachesDependingOn(Helper.class));
    }
    @Test void frameOpenedAndClosedInsideBatchCannotLeaveAStaleEntry() {
        var cache = new ConcurrentMapCache("prices");
        CacheDependencyLedger.beginMutation();
        CacheDependencyLedger.open(); CacheDependencyLedger.cachesInUse(List.of(cache)); CacheDependencyLedger.hit(Helper.class);
        assertTrue(CacheDependencyLedger.cachesDependingOn(Helper.class).isEmpty());
        cache.put("price", 10); CacheDependencyLedger.close();
        CacheDependencyLedger.endMutation();
        assertNull(cache.get("price"));
        assertEquals(List.of(cache), CacheDependencyLedger.cachesDependingOn(Helper.class));
    }
    @Test void computationSpanningSingleMutationAndFailureIsInvalidated() {
        var cache = new ConcurrentMapCache("prices");
        CacheDependencyLedger.open(); CacheDependencyLedger.cachesInUse(List.of(cache));
        CacheDependencyLedger.beginMutation();
        try { throw new IllegalStateException("reload failed"); }
        catch (IllegalStateException expected) { }
        finally { CacheDependencyLedger.endMutation(); }
        cache.put("price", 10); CacheDependencyLedger.close();
        assertNull(cache.get("price"));
        // The finally also prevents subsequent stable computations being discarded.
        CacheDependencyLedger.open(); CacheDependencyLedger.cachesInUse(List.of(cache));
        cache.put("price", 20); CacheDependencyLedger.close(); assertNotNull(cache.get("price"));
    }
    @Test void failedClearKeepsRecordsAndCanRetry() {
        var cache = new ConcurrentMapCache("prices") {
            int tries;
            @Override public void clear() { if (++tries == 1) throw new IllegalStateException("temporary"); super.clear(); }
        };
        record(cache, Helper.class); cache.put("price", 10);
        assertFalse(CacheDependencyLedger.clearObserved(cache)); assertNotNull(cache.get("price"));
        assertEquals(List.of(cache), CacheDependencyLedger.cachesDependingOn(Helper.class));
        assertTrue(CacheDependencyLedger.clearObserved(cache)); assertNull(cache.get("price"));
    }
    @Test void closeAndReporterFailureCannotEscapeOrLeakAFrame() {
        var cache = new ConcurrentMapCache("prices") { @Override public void clear() { throw new AssertionError("clear"); } };
        CacheDependencyLedger.reporter(s -> { throw new AssertionError("reporter"); });
        CacheDependencyLedger.open(); CacheDependencyLedger.cachesInUse(List.of(cache));
        CacheDependencyLedger.beginMutation(); CacheDependencyLedger.endMutation();
        assertDoesNotThrow(CacheDependencyLedger::close);
        CacheDependencyLedger.hit(Other.class);
        assertTrue(CacheDependencyLedger.cachesDependingOn(Other.class).isEmpty());
    }
    @Test void synchronousCacheComputationDoesNotDeadlockAgainstCrossingClose() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        CountDownLatch ownsCache = new CountDownLatch(1), clearing = new CountDownLatch(1), mayHit = new CountDownLatch(1);
        var cache = new ConcurrentMapCache("prices") {
            @Override public void clear() {
                clearing.countDown(); lock.lock(); try { super.clear(); } finally { lock.unlock(); }
            }
        };
        ExecutorService threads = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
        try {
            Future<?> loader = threads.submit(() -> {
                lock.lock();
                try {
                    ownsCache.countDown(); assertTrue(mayHit.await(3, TimeUnit.SECONDS));
                    CacheDependencyLedger.open(); CacheDependencyLedger.hit(Helper.class); CacheDependencyLedger.close();
                } catch (InterruptedException e) { throw new AssertionError(e); }
                finally { lock.unlock(); }
            });
            assertTrue(ownsCache.await(3, TimeUnit.SECONDS));
            Future<?> closer = threads.submit(() -> {
                CacheDependencyLedger.open(); CacheDependencyLedger.cachesInUse(List.of(cache));
                CacheDependencyLedger.beginMutation(); CacheDependencyLedger.endMutation(); CacheDependencyLedger.close();
            });
            assertTrue(clearing.await(3, TimeUnit.SECONDS)); mayHit.countDown();
            loader.get(3, TimeUnit.SECONDS); closer.get(3, TimeUnit.SECONDS);
        } finally { mayHit.countDown(); threads.shutdownNow(); }
    }
    @Test void nestedCloseDefersClearingUntilOuterCacheLoaderHasReturned() {
        var cache = new ConcurrentMapCache("prices");
        CacheDependencyLedger.open(); CacheDependencyLedger.cachesInUse(List.of(cache));
        CacheDependencyLedger.open(); CacheDependencyLedger.cachesInUse(List.of(cache));
        CacheDependencyLedger.beginMutation(); CacheDependencyLedger.endMutation();
        cache.put("price", 10); CacheDependencyLedger.close(); assertNotNull(cache.get("price"));
        CacheDependencyLedger.close(); assertNull(cache.get("price"));
    }
    @Test void overflowMarksCoveragePartialAndKeepsKnownRecords() {
        List<Object> keep = new ArrayList<>();
        for (int i = 0; i < 513; i++) {
            var cache = new ConcurrentMapCache("cache" + i); keep.add(cache); record(cache, Helper.class);
        }
        assertFalse(CacheDependencyLedger.completeCoverage());
        assertEquals(512, CacheDependencyLedger.cachesDependingOn(Helper.class).size());
    }
    @Test void sameClassNamesInTwoLoadersAreIndependent() throws Exception {
        Class<?> a = helper(new Loader()), b = helper(new Loader());
        var ca = new ConcurrentMapCache("a"); var cb = new ConcurrentMapCache("b");
        record(ca, a); record(cb, b);
        assertEquals(a.getName(), b.getName()); assertNotSame(a, b);
        assertEquals(List.of(ca), CacheDependencyLedger.cachesDependingOn(a));
    }
    @Test void recordedLoaderClassAndCacheCanBeCollected() throws Exception {
        WeakReference<?>[] refs = disposable();
        for (int i = 0; i < 100 && Arrays.stream(refs).anyMatch(r -> r.get() != null); i++) {
            System.gc(); Thread.sleep(20);
            CacheDependencyLedger.cachesDependingOn(Helper.class);
        }
        for (WeakReference<?> ref : refs) assertNull(ref.get(), "ledger retained application objects");
    }
    private static WeakReference<?>[] disposable() throws Exception {
        Loader loader = new Loader(); Class<?> cls = helper(loader);
        Object cache = Proxy.newProxyInstance(loader, new Class<?>[]{org.springframework.cache.Cache.class},
                (p, m, args) -> m.getName().equals("getName") ? "proxy" : null);
        record(cache, cls); assertTrue(CacheDependencyLedger.clearObserved(cache));
        return new WeakReference<?>[]{new WeakReference<>(loader), new WeakReference<>(cls), new WeakReference<>(cache)};
    }
    private static Class<?> helper(Loader loader) throws Exception {
        String name = Helper.class.getName();
        try (var in = Helper.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            return loader.define(name, in.readAllBytes());
        }
    }
    private static class Loader extends ClassLoader {
        Loader() { super(CacheDependencyLedgerTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
}
