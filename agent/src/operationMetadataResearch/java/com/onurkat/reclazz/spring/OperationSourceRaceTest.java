/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cache.interceptor.AbstractFallbackCacheOperationSource;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CacheableOperation;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.transaction.interceptor.AbstractFallbackTransactionAttributeSource;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttribute;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Stage 1 RED regressions. Real Spring caches; held-Method stale parsing is modelled explicitly. */
class OperationSourceRaceTest {
    public static class Owner { public String read() { return "value"; } }
    public static class Control { public String read() { return "control"; } }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void withoutConcurrentRequestFreshMetadataIsRepopulated(boolean transaction) throws Exception {
        try (Scope scope = new Scope(transaction)) {
            assertTrue(scope.old(scope.oldMethod));
            assertEquals(1, scope.reloader.reloadOperationSources(Owner.class, true));
            assertFalse(scope.old(Owner.class.getMethod("read")));
            assertFalse(scope.old(scope.oldMethod), "equal old Method finds the fresh cached answer without a race");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void requestBetweenClearAndRepopulateMustNotLeaveStaleMetadata(boolean transaction) throws Exception {
        try (Scope scope = new Scope(transaction)) {
            assertTrue(scope.old(scope.oldMethod), "startup-captured Method has old metadata in this fixture");
            Method fresh = Owner.class.getMethod("read");
            assertEquals(scope.oldMethod, fresh);
            assertNotSame(scope.oldMethod, fresh);
            scope.map.pauseClear = true;
            Future<Integer> reload = scope.executor.submit(() -> scope.reloader.reloadOperationSources(Owner.class, true));
            try {
                await(scope.map.cleared);
                assertTrue(scope.old(scope.oldMethod), "request installs stale metadata after the clear");
            } finally {
                scope.map.continueClear.countDown();
            }
            assertEquals(1, reload.get(10, TimeUnit.SECONDS));
            assertFalse(scope.old(fresh), "repopulate returned the request's stale cache entry instead of fresh metadata");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void laterClassRefreshMustNotDiscardEarlierClassFreshMetadata(boolean transaction) throws Exception {
        try (Scope scope = new Scope(transaction)) {
            assertTrue(scope.old(scope.oldMethod));
            assertEquals(1, scope.reloader.reloadOperationSources(Owner.class, true));
            assertFalse(scope.old(scope.oldMethod), "first class refresh is initially correct");
            assertEquals(1, scope.reloader.reloadOperationSources(Control.class, true));
            assertFalse(scope.old(scope.oldMethod),
                    "the later class cleared the first class's fresh answer and exposed its held Method again");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void oldInFlightComputationMustNotOverwriteCompletedRefresh(boolean transaction) throws Exception {
        try (Scope scope = new Scope(transaction)) {
            scope.old(Control.class.getMethod("read")); // nonempty cache so the actual clear runs
            scope.parse.blockOld = true;
            Future<Boolean> request = scope.executor.submit(() -> scope.old(scope.oldMethod));
            try {
                await(scope.parse.oldComputing);
                assertEquals(1, scope.reloader.reloadOperationSources(Owner.class, true));
                assertFalse(scope.old(Owner.class.getMethod("read")), "fresh answer exists before the delayed request publishes");
            } finally {
                scope.parse.finishOld.countDown();
            }
            assertTrue(request.get(10, TimeUnit.SECONDS));
            assertFalse(scope.old(Owner.class.getMethod("read")),
                    "a delayed pre-refresh parse overwrote the completed fresh answer");
        }
    }

    /** Model the proposed compute-only epoch check; Spring's subsequent put stays real. */
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void computeOnlyDrainMustNotMissPublicationAfterComputeReturns(boolean transaction) throws Exception {
        try (Scope scope = new Scope(transaction)) {
            scope.parse.modelComputeHook = true;
            scope.old(Control.class.getMethod("read"));
            scope.map.pauseOldPut = true;
            Future<Boolean> request = scope.executor.submit(() -> scope.old(scope.oldMethod));
            try {
                await(scope.map.oldPublishing);
                assertEquals(0, scope.parse.inFlight.get(), "compute hook exited before Spring's put");
                scope.parse.generation.incrementAndGet();
                assertEquals(1, scope.reloader.reloadOperationSources(Owner.class, true));
                assertFalse(scope.old(Owner.class.getMethod("read")));
                assertEquals(0, scope.parse.inFlight.get(), "compute-only drain incorrectly reports no old writer");
            } finally {
                scope.map.continuePut.countDown();
            }
            assertTrue(request.get(10, TimeUnit.SECONDS), "pre-generation result is published after drain");
            assertFalse(scope.old(Owner.class.getMethod("read")),
                    "generation check and counter must cover publication, not stop when compute returns");
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "deterministic interleaving latch timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    static final class Parse {
        final Method old;
        volatile boolean blockOld, modelComputeHook;
        final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        final CountDownLatch oldComputing = new CountDownLatch(1), finishOld = new CountDownLatch(1);
        Parse(Method old) { this.old = old; }
        boolean stale(Method method) {
            long epoch = generation.get();
            if (modelComputeHook) inFlight.incrementAndGet();
            try {
                boolean stale = method == old && (!modelComputeHook || epoch == 0);
                if (stale && blockOld) { oldComputing.countDown(); await(finishOld); }
                if (modelComputeHook && generation.get() != epoch) stale = false; // normalized recomputation
                return stale;
            } finally {
                if (modelComputeHook) inFlight.decrementAndGet();
            }
        }
    }
    public static final class CacheSource extends AbstractFallbackCacheOperationSource {
        final Parse parse;
        CacheSource(Parse parse) { this.parse = parse; }
        @Override protected Collection<CacheOperation> findCacheOperations(Class<?> type) { return null; }
        @Override protected Collection<CacheOperation> findCacheOperations(Method method) {
            CacheableOperation.Builder builder = new CacheableOperation.Builder();
            builder.setCacheNames("owned"); builder.setCondition(Boolean.toString(parse.stale(method)));
            return List.of(builder.build());
        }
    }
    public static final class TxSource extends AbstractFallbackTransactionAttributeSource {
        final Parse parse;
        TxSource(Parse parse) { this.parse = parse; }
        @Override protected TransactionAttribute findTransactionAttribute(Class<?> type) { return null; }
        @Override protected TransactionAttribute findTransactionAttribute(Method method) {
            DefaultTransactionAttribute attribute = new DefaultTransactionAttribute();
            attribute.setReadOnly(parse.stale(method)); return attribute;
        }
    }
    static final class GateMap extends ConcurrentHashMap<Object, Object> {
        volatile boolean pauseClear, pauseOldPut;
        final CountDownLatch oldPublishing = new CountDownLatch(1), continuePut = new CountDownLatch(1);
        @Override public Object put(Object key, Object value) {
            boolean stale = value instanceof TransactionAttribute tx ? tx.isReadOnly()
                    : value instanceof Collection<?> values && !values.isEmpty()
                    && ((CacheOperation) values.iterator().next()).getCondition().equals("true");
            if (pauseOldPut && stale) { oldPublishing.countDown(); await(continuePut); }
            return super.put(key, value);
        }
        final CountDownLatch cleared = new CountDownLatch(1), continueClear = new CountDownLatch(1);
        @Override public void clear() {
            super.clear();
            if (pauseClear) { cleared.countDown(); await(continueClear); }
        }
    }
    static final class Scope implements AutoCloseable {
        final Method oldMethod = Owner.class.getMethod("read");
        final Parse parse = new Parse(oldMethod);
        final GateMap map = new GateMap();
        final Object source;
        final GenericApplicationContext context = new GenericApplicationContext();
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final SpringOperationSourceReloader reloader;
        Scope(boolean transaction) throws Exception {
            source = transaction ? new TxSource(parse) : new CacheSource(parse);
            Field field = null;
            for (Class<?> type = source.getClass(); type != null && field == null; type = type.getSuperclass()) {
                for (String name : List.of("attributeCache", "operationCache")) {
                    try { field = type.getDeclaredField(name); break; } catch (NoSuchFieldException ignored) { }
                }
            }
            assertNotNull(field); assertTrue(Map.class.isAssignableFrom(field.getType()));
            field.setAccessible(true); field.set(source, map);
            context.getBeanFactory().registerSingleton("operationSource", source); context.refresh();
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(PlatformContext.class.getClassLoader(),
                    new Class<?>[]{PlatformContext.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getAllApplicationContexts")) return List.of(context);
                        throw new UnsupportedOperationException(method.getName());
                    });
            reloader = new SpringOperationSourceReloader(platform);
        }
        boolean old(Method method) {
            if (source instanceof TxSource tx) return tx.getTransactionAttribute(method, method.getDeclaringClass()).isReadOnly();
            return ((CacheSource) source).getCacheOperations(method, method.getDeclaringClass()).iterator().next().getCondition().equals("true");
        }
        @Override public void close() throws Exception {
            map.continueClear.countDown(); map.continuePut.countDown(); parse.finishOld.countDown();
            executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); context.close();
        }
    }
}
