/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.CacheDependencyLedger;
import com.onurkat.reclazz.bootstrap.CacheLedgerFixture;
import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.*;
import org.springframework.cache.concurrent.*;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.support.GenericApplicationContext;
import java.lang.reflect.Proxy;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SpringCacheReloaderTest {
    GenericApplicationContext context;
    ConcurrentMapCacheManager manager;
    SpringCacheReloader reloader;
    static class Helper { }
    static class Service { @Cacheable("prices") public int price() { return 1; } }
    @BeforeEach void setup() {
        CacheLedgerFixture.reset(); CacheDependencyLedger.configure(true);
        manager = new ConcurrentMapCacheManager("prices", "descriptions");
        context = new GenericApplicationContext(); context.getBeanFactory().registerSingleton("cacheManager", manager); context.refresh();
        PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlatformContext.class},
                (p, m, args) -> m.getName().equals("getAllApplicationContexts") ? List.of(context) : null);
        reloader = new SpringCacheReloader(platform);
        manager.getCache("prices").put("key", "old"); manager.getCache("descriptions").put("key", "warm");
    }
    @AfterEach void cleanup() { context.close(); CacheLedgerFixture.reset(); }
    private void record(Object cache, Class<?> cls) {
        CacheDependencyLedger.open(); CacheDependencyLedger.cachesInUse(List.of(cache));
        CacheDependencyLedger.hit(cls); CacheDependencyLedger.close();
    }
    @Test void helperEvictsPricesAndLeavesDescriptionsPopulated() {
        record(manager.getCache("prices"), Helper.class);
        assertTrue(reloader.reloadCaches(Helper.class));
        assertNull(manager.getCache("prices").get("key")); assertNotNull(manager.getCache("descriptions").get("key"));
    }
    @Test void annotatedUnknownClassKeepsExistingFallback() {
        assertTrue(reloader.reloadCaches(Service.class));
        assertNull(manager.getCache("prices").get("key")); assertNull(manager.getCache("descriptions").get("key"));
    }
    @Test void unannotatedUnknownClassDoesNothing() {
        assertFalse(reloader.reloadCaches(Helper.class));
        assertNotNull(manager.getCache("prices").get("key")); assertNotNull(manager.getCache("descriptions").get("key"));
    }
    @Test void partialAttachHistoryDoesNotSuppressAnnotatedFallback() {
        CacheDependencyLedger.partialCoverage(); record(manager.getCache("prices"), Service.class);
        assertTrue(reloader.reloadCaches(Service.class));
        assertNull(manager.getCache("prices").get("key")); assertNull(manager.getCache("descriptions").get("key"));
    }
    @Test void sameNameInAnotherManagerStaysWarm() {
        var otherManager = new ConcurrentMapCacheManager("prices");
        context.getBeanFactory().registerSingleton("otherManager", otherManager);
        var other = otherManager.getCache("prices"); other.put("key", "warm");
        record(manager.getCache("prices"), Helper.class); reloader.reloadCaches(Helper.class);
        assertNull(manager.getCache("prices").get("key")); assertNotNull(other.get("key"));
    }
    @Test void resolverOnlyCacheIsEvictedAndFailedClearRetries() {
        var cache = new ConcurrentMapCache("resolver-only") {
            int tries;
            @Override public void clear() { if (++tries == 1) throw new IllegalStateException("temporary"); super.clear(); }
        };
        cache.put("key", "old"); record(cache, Helper.class);
        assertFalse(reloader.reloadCaches(Helper.class)); assertNotNull(cache.get("key"));
        assertTrue(reloader.reloadCaches(Helper.class)); assertNull(cache.get("key"));
        assertNotNull(manager.getCache("prices").get("key"));
    }
    @Test void overflowKeepsAnnotatedFallback() {
        List<Object> keep = new java.util.ArrayList<>();
        for (int i = 0; i < 513; i++) { var c = new ConcurrentMapCache("n" + i); keep.add(c); record(c, Service.class); }
        assertFalse(CacheDependencyLedger.completeCoverage()); assertTrue(reloader.reloadCaches(Service.class));
        assertNull(manager.getCache("prices").get("key")); assertNull(manager.getCache("descriptions").get("key"));
    }
}
