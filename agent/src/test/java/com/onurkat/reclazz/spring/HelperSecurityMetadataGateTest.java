/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.access.SecurityConfig;
import org.springframework.security.access.annotation.SecuredAnnotationSecurityMetadataSource;
import org.springframework.security.access.method.DelegatingMethodSecurityMetadataSource;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Native metadata cache guards; runtime role decisions are proved by watcher tests. */
class HelperSecurityMetadataGateTest {
    public static class Helper { public String work() { return "held"; } }
    public static class ColdProduct { }
    public static class GuardedHelper {
        @org.springframework.security.access.annotation.Secured("ROLE_CURRENT")
        public String work() { return "guarded"; }
    }


    @Test void bodyOnlyHelperRetainsNativeMetadataAndColdProduct() throws Exception { exercise(false, false, false); }
    @Test void lastAnnotationRemovalRefreshesNativeMetadataWithoutRecreatingObjects() throws Exception { exercise(false, true, true); }
    @Test void structuralHelperWithoutCurrentSecurityKeepsAnnotationPresenceGuard() throws Exception { exercise(true, false, false); }

    private void exercise(boolean structural, boolean annotationsChanged, boolean refreshes) throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            AtomicInteger cold = new AtomicInteger();
            context.registerBean("methodSecurityMetadata", DelegatingMethodSecurityMetadataSource.class,
                    () -> new DelegatingMethodSecurityMetadataSource(List.of(new SecuredAnnotationSecurityMetadataSource())));
            context.registerBean("coldProduct", ColdProduct.class,
                    () -> { cold.incrementAndGet(); return new ColdProduct(); }, bd -> bd.setLazyInit(true));
            Helper held = new Helper();
            context.registerBean("held", Helper.class, () -> held);
            context.refresh();
            var source = context.getBean(DelegatingMethodSecurityMetadataSource.class);
            Method method = Helper.class.getDeclaredMethod("work");
            assertEquals(List.of(), source.getAttributes(method, Helper.class));
            Map<Object, Object> cache = cache(source);
            Object key = cache.keySet().iterator().next();
            var stale = List.of(new SecurityConfig("ROLE_OLD"));
            cache.put(key, stale);
            assertSame(stale, source.getAttributes(method, Helper.class));
            var reloader = new SpringReloadOrchestrator(new OneContext(context, new AtomicInteger()));
            reloader.onHelperReloaded(Helper.class.getName(), Helper.class, structural, annotationsChanged, false, Set.of(), null);
            assertSame(source, context.getBean(DelegatingMethodSecurityMetadataSource.class));
            assertSame(held, context.getBean(Helper.class));
            assertEquals("held", held.work());
            assertEquals(0, cold.get(), "metadata work must not create an ordinary lazy product");
            if (refreshes) {
                assertNotSame(stale, cache.get(key), "the removed rule must be recomputed from the current annotation absence");
                assertEquals(List.of(), source.getAttributes(method, Helper.class));
            } else {
                assertSame(stale, cache.get(key), "irrelevant helper saves must retain native cached answers");
            }
        }
    }

    @Test void bodyOnlySecuredHelperRetainsWarmedNativeAnswer() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("methodSecurityMetadata", DelegatingMethodSecurityMetadataSource.class,
                    () -> new DelegatingMethodSecurityMetadataSource(List.of(new SecuredAnnotationSecurityMetadataSource())));
            context.refresh();
            var source = context.getBean(DelegatingMethodSecurityMetadataSource.class);
            Method method = GuardedHelper.class.getDeclaredMethod("work");
            var before = source.getAttributes(method, GuardedHelper.class);
            assertEquals(List.of(new SecurityConfig("ROLE_CURRENT")), before);
            var reloader = new SpringReloadOrchestrator(new OneContext(context, new AtomicInteger()));
            reloader.onHelperReloaded(GuardedHelper.class.getName(), GuardedHelper.class, false, false, false, Set.of(), null);
            assertSame(before, source.getAttributes(method, GuardedHelper.class),
                    "a body-only secured helper must not clear its warmed native answer");
        }
    }

    @Test void compatibilityEntryWithoutSecurityAvoidsContextDiscovery() {
        AtomicInteger lookups = new AtomicInteger();
        var reloader = new SpringSecurityReloader(new OneContext(null, lookups));
        assertEquals(0, reloader.refreshMethodSecurity(Helper.class));
        assertEquals(0, lookups.get());
    }
    @Test void falseMetadataFlagWithoutSecurityAvoidsContextDiscovery() {
        AtomicInteger lookups = new AtomicInteger();
        var reloader = new SpringSecurityReloader(new OneContext(null, lookups));
        assertEquals(0, reloader.refreshMethodSecurity(Helper.class, false));
        assertEquals(0, lookups.get());
    }
    @Test void nullReloadAvoidsContextDiscoveryEvenWithMetadataFlag() {
        AtomicInteger lookups = new AtomicInteger();
        var reloader = new SpringSecurityReloader(new OneContext(null, lookups));
        assertEquals(0, reloader.refreshMethodSecurity(null, true));
        assertEquals(0, reloader.refreshMethodSecurity(null));
        assertEquals(0, lookups.get());
    }
    @Test void metadataChangedContextWithoutSecurityIsAQuietZero() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            var reloader = new SpringSecurityReloader(new OneContext(context, new AtomicInteger()));
            assertEquals(0, reloader.refreshMethodSecurity(Helper.class, true));
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<Object, Object> cache(DelegatingMethodSecurityMetadataSource source) throws Exception {
        var field = DelegatingMethodSecurityMetadataSource.class.getDeclaredField("attributeCache");
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(source);
    }
    private record OneContext(Object ctx, AtomicInteger lookups) implements PlatformContext {
        @Override public Platform getPlatformId() { return Platform.GENERIC; }
        @Override public void initialize() { }
        @Override public Map<String, List<Path>> getClassOutputDirs() { return Map.of(); }
        @Override public Map<String, List<Path>> getSourceDirs() { return Map.of(); }
        @Override public Map<String, List<Path>> getResourceDirs() { return Map.of(); }
        @Override public String resolveClasspath() { return ""; }
        @Override public String resolveClassName(Path classFile) { return null; }
        @Override public Path resolveOutputDir(Path classFile) { return null; }
        @Override public Object getApplicationContext() { lookups.incrementAndGet(); return ctx; }
    }
}
