/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.AddedOperationBridge;
import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.*;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;
import java.lang.invoke.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ComposedCacheOperationTest {
    @AfterEach void clear() { RestartLedger.clear(); }
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE}) @Retention(RetentionPolicy.RUNTIME)
    @Cacheable(cacheNames = "values", key = "#p0", condition = "#p0 != 'skip'", unless = "#result == null")
    public @interface Cached {
        @AliasFor(annotation = Cacheable.class, attribute = "cacheNames") String[] regions() default {"values"};
        @AliasFor(annotation = Cacheable.class, attribute = "key") String key() default "#p0";
        @AliasFor(annotation = Cacheable.class, attribute = "condition") String condition() default "#p0 != 'skip'";
        @AliasFor(annotation = Cacheable.class, attribute = "unless") String unless() default "#result == null";
    }
    @Target({ElementType.TYPE, ElementType.METHOD}) @Retention(RetentionPolicy.RUNTIME) @Cached(regions = "changed")
    public @interface Catalog {
        @AliasFor(annotation = Cached.class, attribute = "regions") String[] value() default {"changed"};
        @AliasFor(annotation = Cached.class, attribute = "key") String key() default "'catalog/' + #p0";
    }
    @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @CachePut(cacheNames = "values", key = "#p0")
    public @interface Put { }
    @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
    @CacheEvict(cacheNames = "values", key = "#p0", beforeInvocation = true)
    public @interface Evict { }
    @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
    @Caching(put = @CachePut(cacheNames = "values", key = "#p0"),
            evict = @CacheEvict(cacheNames = "changed", allEntries = true, beforeInvocation = true))
    public @interface Refresh { }
    @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @Cacheable
    public @interface UseDefaults { }
    @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @Cached @org.springframework.scheduling.annotation.Async
    public @interface Mixed { }
    @Target(ElementType.ANNOTATION_TYPE) @Retention(RetentionPolicy.RUNTIME)
    public @interface Marker { }
    @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @Cached @Marker
    public @interface Marked { }
    @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE}) @Retention(RetentionPolicy.RUNTIME) @Cached @CycleB
    public @interface CycleA { }
    @Target(ElementType.ANNOTATION_TYPE) @Retention(RetentionPolicy.RUNTIME) @CycleA
    public @interface CycleB { }

    public static class Owner { int calls; }
    public static class Initial { @Cached public String value(String name) { return null; } }
    public static class Nested { @Catalog public String value(String name) { return null; } }
    @Cached(regions = "changed") public static class ClassDefault { public String value(String name) { return null; } }
    @Cached(regions = "changed") public static class MethodOverride {
        @Cached(key = "'method/' + #p0") public String value(String name) { return null; }
    }
    @CacheConfig(cacheNames = "values", keyGenerator = "keys") public static class Defaults {
        @UseDefaults public String value(String name) { return null; }
    }
    public static class Named { @Cached(key = "#name") public String value(String name) { return null; } }
    public static class PutValue { @Put public String value(String name) { return null; } }
    public static class EvictValue { @Evict public String value(String name) { return null; } }
    public static class RefreshValue { @Refresh public String value(String name) { return null; } }
    public static class Unsafe { @Mixed public String value(String name) { return null; } }
    public static class Unknown { @Marked public String value(String name) { return null; } }
    public static class Cyclic { @CycleA public String value(String name) { return null; } }
    public static class Async { @Cached @org.springframework.scheduling.annotation.Async public void value(String name) { } }
    public static class Future { @Cached public java.util.concurrent.CompletableFuture<String> value(String name) { return null; } }
    public static class Generic { @Cached public <T> T value(T name) { return null; } }
    public static class Callback { @Cached @org.springframework.context.event.EventListener public void event(String event) { } }
    @Cached public static class PrivateEvents {
        @org.springframework.context.event.EventListener private void event(String event) { }
        @org.springframework.transaction.event.TransactionalEventListener private void transactional(String event) { }
    }
    @Cached public static class PrivateSchedule { @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10) private void tick() { } }
    public static class Plain { public String value(String name) { return null; } }
    public static class Empty { }

    @Configuration(proxyBeanMethods = false) @EnableCaching(proxyTargetClass = true)
    public static class Config {
        @Bean public CacheManager cacheManager() { return new ConcurrentMapCacheManager("values", "changed"); }
        @Bean public KeyGenerator keys() {
            return (target, method, args) -> {
                assertEquals(Owner.class, target.getClass()); assertEquals("value", method.getName());
                return "custom/" + args[0];
            };
        }
    }
    @Configuration(proxyBeanMethods = false)
    @org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass = true)
    public static class NoCache { }

    @Test void nativeParserMergesCacheAliases() throws Exception {
        var operation = (CacheableOperation) new AnnotationCacheOperationSource().getCacheOperations(
                Nested.class.getMethod("value", String.class), Nested.class).iterator().next();
        assertEquals(Set.of("changed"), operation.getCacheNames());
        assertEquals("'catalog/' + #p0", operation.getKey());
        assertEquals("#result == null", operation.getUnless());
    }

    @ParameterizedTest @ValueSource(classes = {Initial.class, Nested.class, ClassDefault.class, MethodOverride.class,
            Defaults.class, PutValue.class, EvictValue.class, RefreshValue.class})
    void savedMetadataMatchesNativeCacheOperations(Class<?> fixture) throws Exception {
        var plan = AddedOperationMetadata.create(Owner.class, bytes(fixture), lookup(), false);
        assertTrue(plan.operations(), "composed cache must activate the adapter");
        var entry = plan.entries().stream().filter(e -> e.method().getName().equals("value")).findFirst().orElseThrow();
        assertTrue(entry.cache()); assertNull(entry.reason());
        var source = new AnnotationCacheOperationSource();
        var nativeOps = source.getCacheOperations(fixture.getMethod("value", String.class), fixture);
        var savedOps = source.getCacheOperations(entry.method(), Owner.class);
        assertNotNull(nativeOps); assertNotNull(savedOps);
        assertEquals(describe(nativeOps), describe(savedOps));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void heldPlainAndCglibCallsCacheAcrossMetadataChanges(boolean proxy) throws Throwable {
        try (Scope scope = new Scope(proxy, Config.class)) {
            var site = site(); scope.publish(Initial.class);
            assertEquals("a:1", invoke(site, scope.bean, "a")); assertEquals("a:1", invoke(site, scope.bean, "a"));
            scope.publish(Nested.class);
            assertEquals("a:2", invoke(site, scope.bean, "a")); assertEquals("a:2", invoke(site, scope.bean, "a"));
            assertEquals("a:2", scope.cache("changed").get("catalog/a").get());
            scope.publish(Plain.class);
            assertEquals("a:3", invoke(site, scope.bean, "a")); assertEquals("a:4", invoke(site, scope.bean, "a"));
            scope.publish(Initial.class);
            assertEquals("a:1", invoke(site, scope.bean, "a"), "metadata refresh must not erase native cache contents");
            scope.publish(Empty.class);
            assertThrows(IllegalStateException.class, () -> invoke(site, scope.bean, "a")); assertEquals(4, scope.target.calls);
            scope.publish(Initial.class); assertEquals("b:5", invoke(site, scope.bean, "b"));
        }
    }

    @Test void conditionUnlessAndSavedParameterNameFollowNativeRules() throws Throwable {
        try (Scope scope = new Scope(false, Config.class)) {
            var site = site(); scope.publish(Initial.class);
            assertEquals("skip:1", invoke(site, scope.bean, "skip")); assertEquals("skip:2", invoke(site, scope.bean, "skip"));
            assertNull(invoke(site, scope.bean, "null")); assertNull(invoke(site, scope.bean, "null")); assertEquals(4, scope.target.calls);
            scope.publish(Named.class);
            assertEquals("named:5", invoke(site, scope.bean, "named")); assertEquals("named:5", invoke(site, scope.bean, "named"));
        }
    }

    @Test void putEvictAndGroupedOperationsUseNativeSemantics() throws Throwable {
        try (Scope scope = new Scope(false, Config.class)) {
            var site = site(); scope.publish(PutValue.class);
            assertEquals("a:1", invoke(site, scope.bean, "a")); assertEquals("a:2", invoke(site, scope.bean, "a"));
            assertEquals("a:2", scope.cache("values").get("a").get());
            scope.cache("values").put("throw", "old"); scope.publish(EvictValue.class);
            assertThrows(IllegalArgumentException.class, () -> invoke(site, scope.bean, "throw"));
            assertNull(scope.cache("values").get("throw"), "beforeInvocation evicts even when the body throws");
            scope.cache("changed").put("other", "old"); scope.publish(RefreshValue.class);
            assertEquals("a:4", invoke(site, scope.bean, "a"));
            assertEquals("a:4", scope.cache("values").get("a").get()); assertNull(scope.cache("changed").get("other"));
        }
    }

    @Test void classDefaultsOverridesAndKeyGeneratorUseSavedMetadataAndActualTarget() throws Throwable {
        try (Scope scope = new Scope(true, Config.class)) {
            var site = site(); scope.publish(ClassDefault.class);
            assertEquals("a:1", invoke(site, scope.bean, "a")); assertEquals("a:1", invoke(site, scope.bean, "a"));
            scope.publish(MethodOverride.class); assertEquals("a:2", invoke(site, scope.bean, "a"));
            assertEquals("a:2", scope.cache("values").get("method/a").get());
            scope.publish(Defaults.class); assertEquals("a:3", invoke(site, scope.bean, "a"));
            assertEquals("a:3", invoke(site, scope.bean, "a")); assertEquals("a:3", scope.cache("values").get("custom/a").get());
        }
    }

    @ParameterizedTest @ValueSource(classes = {Unsafe.class, Unknown.class, Cyclic.class, Async.class, Future.class, Generic.class, Callback.class})
    void unsupportedGraphsAndCallbacksAreRefused(Class<?> fixture) throws Exception {
        var plan = AddedOperationMetadata.create(Owner.class, bytes(fixture), lookup(), false);
        assertTrue(plan.operations()); assertFalse(plan.entries().isEmpty());
        assertTrue(plan.entries().stream().allMatch(e -> e.reason() != null));
    }

    @Test void unsupportedRecognizedGraphsRefuseBeforeBody() throws Throwable {
        try (Scope scope = new Scope(false, Config.class)) {
            var site = site();
            for (Class<?> fixture : List.of(Unsafe.class, Unknown.class, Cyclic.class)) {
                scope.publish(fixture); assertThrows(IllegalStateException.class, () -> invoke(site, scope.bean, "a"));
                assertEquals(0, scope.target.calls);
            }
        }
    }

    @Test void privateClassAdvisedCallbacksCannotBypassTheOperationBoundary() throws Exception {
        byte[] events = bytes(PrivateEvents.class), scheduled = bytes(PrivateSchedule.class);
        var eventPlan = AddedEventListenerAdapter.inspect(events, Set.of("event:(Ljava/lang/String;)V", "transactional:(Ljava/lang/String;)V"), Owner.class.getClassLoader());
        assertTrue(eventPlan.methods().isEmpty()); assertEquals(2, eventPlan.refused().size());
        var schedulePlan = AddedScheduledAdapter.inspect(scheduled, Set.of("tick:()V"), Owner.class.getClassLoader());
        assertTrue(schedulePlan.methods().isEmpty()); assertEquals(1, schedulePlan.refused().size());
    }

    @Test void missingCacheAdvisorRefusesBeforeBody() throws Throwable {
        try (Scope scope = new Scope(false, NoCache.class)) {
            scope.publish(Initial.class);
            assertThrows(IllegalStateException.class, () -> invoke(site(), scope.bean, "a")); assertEquals(0, scope.target.calls);
        }
    }

    private static List<String> describe(Collection<CacheOperation> operations) {
        return operations.stream().map(o -> o.getClass().getName() + ":" + o.getCacheNames() + ":" + o.getKey()
                + ":" + o.getCondition() + ":" + o.getCacheManager() + ":" + o.getCacheResolver() + ":" + o.getKeyGenerator()
                + (o instanceof CacheableOperation c ? ":" + c.getUnless() + ":" + c.isSync() : "")
                + (o instanceof CachePutOperation p ? ":" + p.getUnless() : "")
                + (o instanceof CacheEvictOperation e ? ":" + e.isBeforeInvocation() + ":" + e.isCacheWide() : "")).sorted().toList();
    }
    static String body(Owner owner, String key) {
        owner.calls++;
        if (key.equals("throw")) throw new IllegalArgumentException("fixture body failure");
        return key.equals("null") ? null : key + ":" + owner.calls;
    }
    private static MethodHandles.Lookup lookup() throws IllegalAccessException { return MethodHandles.privateLookupIn(Owner.class, MethodHandles.lookup()); }
    private static CallSite site() throws ReflectiveOperationException {
        var type = MethodType.methodType(String.class, Owner.class, String.class);
        var direct = MethodHandles.lookup().findStatic(ComposedCacheOperationTest.class, "body", type);
        return AddedOperationBridge.externalCall(Owner.class, InjectedNames.siteKey("value", InjectedNames.descHash("(Ljava/lang/String;)Ljava/lang/String;")), new ConstantCallSite(direct));
    }
    private static Object invoke(CallSite site, Object bean, String key) throws Throwable { return site.dynamicInvoker().invokeWithArguments(bean, key); }
    private static byte[] bytes(Class<?> fixture) throws Exception {
        try (var stream = fixture.getResourceAsStream("/" + fixture.getName().replace('.', '/') + ".class")) {
            ClassNode node = new ClassNode(); new ClassReader(Objects.requireNonNull(stream).readAllBytes()).accept(node, 0);
            // Supply MethodParameters as javac -parameters would; ordinary unit
            // fixtures are not compiled with that option.
            node.methods.stream().filter(m -> m.name.equals("value")).forEach(m ->
                    m.parameters = List.of(new org.objectweb.asm.tree.ParameterNode("name", 0)));
            node.name = Type.getInternalName(Owner.class); ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
        }
    }
    private static class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context;
        final Owner target = new Owner();
        final Object bean;
        Scope(boolean proxy, Class<?> config) {
            context = new AnnotationConfigApplicationContext(config);
            if (proxy) {
                var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
                for (var advisor : context.getBeansOfType(Advisor.class).values()) factory.addAdvisor(advisor);
                bean = factory.getProxy();
            } else bean = target;
            context.getBeanFactory().registerSingleton("owner", bean);
        }
        void publish(Class<?> fixture) throws Exception { SpringAddedOperations.publish(Owner.class, bytes(fixture), lookup(), List.of(context)); }
        org.springframework.cache.Cache cache(String name) { return context.getBean(CacheManager.class).getCache(name); }
        @Override public void close() { context.close(); }
    }
}
