/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.MvcSingletonBridge;
import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real native bean destruction and parent/child ownership, not stubbed lifecycle calls. */
public class SpringMvcSingletonsTest {
    static final AtomicInteger seeds = new AtomicInteger(), created = new AtomicInteger(), products = new AtomicInteger();
    static boolean failPrimary;
    @BeforeEach void prepare() {
        MvcSingletonBridge.clearForTests(); seeds.set(0); created.set(0); products.set(0); failPrimary = false;
        com.onurkat.reclazz.ui.RestartLedger.clear();
        MvcSingletonBridge.installed(HandlerInterceptor.class.getClassLoader(), 3);
    }
    @AfterEach void clear() { MvcSingletonBridge.clearForTests(); com.onurkat.reclazz.ui.RestartLedger.clear(); }
    public static class Dependency { final int seed = seeds.incrementAndGet(); }
    public static class Primary implements HandlerInterceptor {
        @Autowired Dependency dependency;
        public Primary() {
            if (failPrimary) throw new IllegalStateException("deliberate constructor failure");
            created.incrementAndGet();
        }
    }
    public static class Stable implements HandlerInterceptor { }
    @Configuration(proxyBeanMethods=false) @EnableWebMvc
    public static class Config implements WebMvcConfigurer {
        @Autowired Primary primary;
        @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(primary).order(10); }
    }
    private static final class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext(Dependency.class);
        final AnnotationConfigWebApplicationContext web = new AnnotationConfigWebApplicationContext();
        Fixture() {
            web.setParent(parent);
            // The native setter has javax/Jakarta descriptors on the two isolated graphs.
            try {
                var setter = java.util.Arrays.stream(web.getClass().getMethods())
                        .filter(m -> m.getName().equals("setServletContext") && m.getParameterCount() == 1).findFirst().orElseThrow();
                setter.invoke(web, new org.springframework.mock.web.MockServletContext());
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            web.register(Config.class, Primary.class, Stable.class); web.refresh();
        }
        Primary primary() { return web.getBean(Primary.class); }
        Object mapping() { return web.getBean("requestMappingHandlerMapping"); }
        @Override public void close() { web.close(); parent.close(); }
    }
    private static SpringBeanReloader reloader(Object... contexts) {
        var all = List.of(contexts);
        return new SpringBeanReloader(new PlatformContext() {
            @Override public Platform getPlatformId() { return Platform.GENERIC; }
            @Override public void initialize() { }
            @Override public Map<String, List<Path>> getClassOutputDirs() { return Map.of(); }
            @Override public Map<String, List<Path>> getSourceDirs() { return Map.of(); }
            @Override public Map<String, List<Path>> getResourceDirs() { return Map.of(); }
            @Override public String resolveClasspath() { return ""; }
            @Override public String resolveClassName(Path path) { return null; }
            @Override public Path resolveOutputDir(Path path) { return null; }
            @Override public Object getApplicationContext() { return all.get(0); }
            @Override public List<Object> getAllApplicationContexts() { return all; }
        });
    }
    @Test void nativeChildDependentsAndHeldMappingsFollowTheNewParentSeed() {
        try (var f = new Fixture()) {
            Primary old = f.primary(); int seed = old.dependency.seed; Object mapping = f.mapping(), stable = f.web.getBean(Stable.class);
            reloader(f.parent, f.web).refreshBean(Dependency.class.getName(), Dependency.class);
            Primary fresh = f.primary();
            assertNotSame(old, fresh); assertNotEquals(seed, fresh.dependency.seed);
            assertSame(f.parent.getBean(Dependency.class), fresh.dependency);
            assertSame(fresh, MvcSingletonBridge.current(old)); assertSame(f.mapping(), MvcSingletonBridge.current(mapping));
            assertSame(stable, f.web.getBean(Stable.class));
        }
    }
    @Test void equalBeanNamesInSeparateContextsNeverCrossTheirInstances() {
        try (var a = new Fixture(); var b = new Fixture()) {
            Primary oldA = a.primary(), oldB = b.primary();
            var reload = reloader(a.parent, a.web, b.parent, b.web);
            reload.refreshBean(Dependency.class.getName(), Dependency.class);
            assertSame(a.primary(), MvcSingletonBridge.current(oldA)); assertSame(b.primary(), MvcSingletonBridge.current(oldB));
            assertNotSame(a.primary(), b.primary());
            assertSame(a.parent.getBean(Dependency.class), a.primary().dependency);
            assertSame(b.parent.getBean(Dependency.class), b.primary().dependency);
        }
    }
    @Test void multipleClassRefreshesPublishOnlyTheFinalBatchGeneration() {
        try (var f = new Fixture()) {
            Primary original = f.primary(); Object mapping = f.mapping(); var reload = reloader(f.parent, f.web);
            reload.beginBatch(); reload.beginBatch();
            reload.refreshBean(Primary.class.getName(), Primary.class); Primary intermediate = f.primary();
            reload.refreshBean(Dependency.class.getName(), Dependency.class);
            // Native direct-field healing may keep the intermediate dependent alive.
            // An explicit later refresh creates a final generation that must win.
            reload.refreshBean(Primary.class.getName(), Primary.class);
            assertSame(original, MvcSingletonBridge.current(original));
            reload.endBatch(); assertSame(original, MvcSingletonBridge.current(original));
            reload.endBatch(); assertSame(f.primary(), MvcSingletonBridge.current(original));
            assertNotSame(intermediate, f.primary()); assertSame(f.mapping(), MvcSingletonBridge.current(mapping));
            assertNull(com.onurkat.reclazz.util.Reflect.readField(reload, "batchMvcBefore"));
        }
    }
    @Test void repeatedRefreshesKeepTheFirstHeldGenerationCurrent() {
        try (var f = new Fixture()) {
            Primary original = f.primary(); Object mapping = f.mapping(); var reload = reloader(f.parent, f.web);
            for (int i = 0; i < 4; i++) {
                reload.refreshBean(Dependency.class.getName(), Dependency.class);
                assertSame(f.primary(), MvcSingletonBridge.current(original)); assertSame(f.mapping(), MvcSingletonBridge.current(mapping));
            }
        }
    }
    @Test void missingTransformerReceiptDoesNotClaimRouting() {
        try (var f = new Fixture()) {
            Primary original = f.primary(); MvcSingletonBridge.clearForTests();
            reloader(f.parent, f.web).refreshBean(Dependency.class.getName(), Dependency.class);
            assertNotSame(original, f.primary()); assertSame(original, MvcSingletonBridge.current(original));
            assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream()
                    .anyMatch(line -> line.contains(Primary.class.getName()) && line.contains("MVC singleton routing unavailable")));
        }
    }
    @Test void aClosedContextIsNotRecreatedByItsSnapshot() {
        var f = new Fixture(); Primary original = f.primary(); var snapshot = SpringMvcSingletons.capture(List.of(f.web));
        f.close(); int before = created.get(); snapshot.publishCurrent();
        assertEquals(before, created.get()); assertSame(original, MvcSingletonBridge.current(original));
    }
    @Test void failedFirstRecreationReleasesTheBatchSnapshotWithoutPublishing() {
        try (var f = new Fixture()) {
            Primary original = f.primary(); var reload = reloader(f.parent, f.web);
            reload.beginBatch(); failPrimary = true;
            reload.refreshBean(Primary.class.getName(), Primary.class);
            assertNotNull(com.onurkat.reclazz.util.Reflect.readField(reload, "batchMvcBefore"));
            reload.endBatch();
            assertNull(com.onurkat.reclazz.util.Reflect.readField(reload, "batchMvcBefore"));
            assertSame(original, MvcSingletonBridge.current(original));
        } finally { failPrimary = false; }
    }
    @Test void aMissingServletHookCannotRedirectItsMapping() {
        try (var f = new Fixture()) {
            Primary original = f.primary(); Object mapping = f.mapping();
            MvcSingletonBridge.clearForTests(); MvcSingletonBridge.installed(HandlerInterceptor.class.getClassLoader(), 1);
            reloader(f.parent, f.web).refreshBean(Primary.class.getName(), Primary.class);
            assertSame(f.primary(), MvcSingletonBridge.current(original));
            assertNotSame(mapping, f.mapping()); assertSame(mapping, MvcSingletonBridge.current(mapping));
        }
    }
    @Test void proxyReceiversAreNotRedirected() {
        try (var context = new GenericApplicationContext()) {
            context.registerBean("proxy", HandlerInterceptor.class, () -> (HandlerInterceptor) java.lang.reflect.Proxy.newProxyInstance(
                    HandlerInterceptor.class.getClassLoader(), new Class<?>[]{HandlerInterceptor.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "synthetic interceptor proxy";
                        default -> null;
                    }));
            context.refresh(); Object original = context.getBean("proxy");
            var snapshot = SpringMvcSingletons.capture(List.of(context));
            context.getDefaultListableBeanFactory().destroySingleton("proxy"); Object fresh = context.getBean("proxy");
            snapshot.publishCurrent(); assertNotSame(original, fresh); assertSame(original, MvcSingletonBridge.current(original));
        }
    }
    public static class ProductFactory implements FactoryBean<Primary> {
        @Override public Primary getObject() { products.incrementAndGet(); return new Primary(); }
        @Override public Class<?> getObjectType() { return Primary.class; }
    }
    @Test void prototypesFactoryProductsAndUnownedRegistrationsAreNotInstantiated() {
        try (var context = new GenericApplicationContext()) {
            context.registerBean("prototype", Primary.class, bd -> bd.setScope("prototype"));
            context.registerBean("factory", ProductFactory.class);
            var manual = new Stable(); context.getBeanFactory().registerSingleton("manual", manual);
            context.refresh(); int before = created.get();
            var snapshot = SpringMvcSingletons.capture(List.of(context)); snapshot.publishCurrent();
            assertEquals(before, created.get()); assertEquals(0, products.get());
            assertSame(manual, MvcSingletonBridge.current(manual));
        }
    }
}
