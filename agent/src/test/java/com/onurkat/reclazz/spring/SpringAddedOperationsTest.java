/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.AddedOperationBridge;
import com.onurkat.reclazz.bootstrap.InjectedNames;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.cache.annotation.*;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.lang.invoke.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpringAddedOperationsTest {
    public static class Owner {
        final JdbcTemplate jdbc;
        int calls;
        Owner(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    }
    public static class Metadata {
        @Transactional(rollbackFor=Exception.class) public String write(int id, boolean fail) { return null; }
        @Cacheable(cacheNames="values", key="#p0", condition="#p0 != 'skip'", unless="#result == null")
        public String value(String name) { return null; }
        @CachePut(cacheNames="values", key="#p0") public String put(String name) { return null; }
        @CacheEvict(cacheNames="values", key="#p0") public void evict(String name) { }
    }
    public static class Renamed {
        @Cacheable(cacheNames="changed", key="#p0") public String value(String name) { return null; }
    }
    public static class Plain { public String value(String name) { return null; } }
    public static class Empty { }
    public static class Unsafe {
        @org.springframework.scheduling.annotation.Async
        @Cacheable(cacheNames="values") public String value(String name) { return null; }
    }
    @CacheConfig(cacheNames="values")
    public static class Defaults {
        @Cacheable(key="#name") public String value(String name) { return null; }
    }
    public static class Combined {
        @Transactional(rollbackFor=Exception.class)
        @Cacheable(cacheNames="values", key="#p0")
        public String write(int id, boolean fail) { return null; }
    }
    public static class MissingManager {
        @Transactional(transactionManager="missingManager")
        public String write(int id, boolean fail) { return null; }
    }
    @Transactional(rollbackFor=Exception.class)
    public static class ClassTransaction { public String write(int id, boolean fail) { return null; } }
    @CacheConfig(cacheNames="values", keyGenerator="keys")
    public static class CustomKey {
        @Cacheable public String value(String name) { return null; }
    }
    public static class OverrideMetadata {
        @Override @Cacheable(cacheNames="values") public String toString() { return "override"; }
    }
    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement(proxyTargetClass=true)
    @EnableCaching(proxyTargetClass=true)
    public static class Config {
        @Bean public JdbcDataSource dataSource() {
            var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:operations_" + UUID.randomUUID()); return ds;
        }
        @Bean public JdbcTemplate jdbc(JdbcDataSource ds) { return new JdbcTemplate(ds); }
        @Bean public org.springframework.transaction.PlatformTransactionManager transactionManager(JdbcDataSource ds) {
            return new DataSourceTransactionManager(ds);
        }
        @Bean public org.springframework.cache.interceptor.KeyGenerator keys() {
            return (target, method, args) -> {
                assertEquals(Owner.class, target.getClass());
                assertEquals("value", method.getName());
                return "custom/" + args[0];
            };
        }
        @Bean public org.springframework.cache.CacheManager cacheManager() { return new ConcurrentMapCacheManager("values", "changed"); }
    }
    @AfterEach void clearLedger() { com.onurkat.reclazz.ui.RestartLedger.clear(); }

    @Test void plainSingletonCommitsAndRollsBackTheRealDatabase() throws Throwable {
        try (var scope = new Scope(false)) {
            scope.publish(Metadata.class);
            var write = site("write", MethodType.methodType(String.class, Owner.class, int.class, boolean.class));
            assertEquals("1:true", write.dynamicInvoker().invokeWithArguments(scope.bean, 1, false));
            assertEquals("checked failure", assertThrows(Exception.class,
                    () -> write.dynamicInvoker().invokeWithArguments(scope.bean, 2, true)).getMessage());
            assertEquals(1, scope.jdbc.queryForObject("select count(*) from entries", Integer.class));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        }
    }

    @Test void retainedCglibSiteSeesCacheRenameRemovalAndRestorationOnTheSameTarget() throws Throwable {
        try (var scope = new Scope(true)) {
            var value = site("value", MethodType.methodType(String.class, Owner.class, String.class));
            scope.publish(Metadata.class);
            assertEquals("a:1", invoke(value, scope.bean, "a"));
            assertEquals("a:1", invoke(value, scope.bean, "a"));
            scope.publish(Renamed.class);
            assertEquals("a:2", invoke(value, scope.bean, "a"));
            assertEquals("a:2", invoke(value, scope.bean, "a"));
            scope.publish(Plain.class);
            assertEquals("a:3", invoke(value, scope.bean, "a"));
            assertEquals("a:4", invoke(value, scope.bean, "a"));
            scope.publish(Metadata.class);
            // Existing values in the original region retain normal cache semantics.
            assertEquals("a:1", invoke(value, scope.bean, "a"));
            assertEquals(4, scope.target.calls);
        }
    }

    @Test void conditionsUnlessPutAndEvictUseSpringRules() throws Throwable {
        try (var scope = new Scope(false)) {
            scope.publish(Metadata.class);
            var value = site("value", MethodType.methodType(String.class, Owner.class, String.class));
            var put = site("put", MethodType.methodType(String.class, Owner.class, String.class));
            var evict = site("evict", MethodType.methodType(void.class, Owner.class, String.class));
            assertEquals("skip:1", invoke(value, scope.bean, "skip"));
            assertEquals("skip:2", invoke(value, scope.bean, "skip"));
            assertNull(invoke(value, scope.bean, "null")); assertNull(invoke(value, scope.bean, "null"));
            assertEquals(4, scope.target.calls);
            assertEquals("put", invoke(put, scope.bean, "a"));
            assertEquals("put", invoke(value, scope.bean, "a"));
            invoke(evict, scope.bean, "a");
            assertEquals("a:5", invoke(value, scope.bean, "a"));
        }
    }

    @Test void cacheConfigAndSavedParameterNamesAreAvailableToSpring() throws Throwable {
        try (var scope = new Scope(false)) {
            scope.publish(Defaults.class);
            var value = site("value", MethodType.methodType(String.class, Owner.class, String.class));
            assertEquals("named:1", invoke(value, scope.bean, "named"));
            assertEquals("named:1", invoke(value, scope.bean, "named"));
        }
    }

    @Test void missingInfrastructureRefusesBeforeTheWrite() throws Throwable {
        try (var context = new org.springframework.context.support.GenericApplicationContext()) {
            context.refresh();
            var owner = new Owner(null);
            context.getBeanFactory().registerSingleton("owner", owner);
            publish(Metadata.class, context);
            var value = site("value", MethodType.methodType(String.class, Owner.class, String.class));
            var failure = assertThrows(IllegalStateException.class, () -> invoke(value, owner, "a"));
            assertTrue(failure.getMessage().contains("auto-proxy creator"));
            assertEquals(0, owner.calls);
        }
    }

    @Test void extraAdviceIsRefusedRatherThanBypassed() throws Throwable {
        try (var scope = new Scope(true)) {
            ((org.springframework.aop.framework.Advised) scope.bean).addAdvice(
                    (org.aopalliance.intercept.MethodInterceptor) invocation -> { throw new SecurityException("denied"); });
            scope.publish(Metadata.class);
            var value = site("value", MethodType.methodType(String.class, Owner.class, String.class));
            var failure = assertThrows(IllegalStateException.class, () -> invoke(value, scope.bean, "a"));
            assertTrue(failure.getMessage().contains("additional proxy advisor"));
            assertEquals(0, scope.target.calls);
        }
    }

    @Test void extraMethodAnnotationIsRefusedBeforeItsBody() throws Throwable {
        try (var scope = new Scope(false)) {
            scope.publish(Unsafe.class);
            var value = site("value", MethodType.methodType(String.class, Owner.class, String.class));
            var failure = assertThrows(IllegalStateException.class, () -> invoke(value, scope.bean, "a"));
            assertTrue(failure.getMessage().contains("unsupported method annotation"));
            assertEquals(0, scope.target.calls);
        }
    }

    @Test void removedOperationFailsInsteadOfCallingItsOldBody() throws Throwable {
        try (var scope = new Scope(false)) {
            scope.publish(Metadata.class);
            var value = site("value", MethodType.methodType(String.class, Owner.class, String.class));
            scope.publish(Empty.class);
            assertTrue(assertThrows(IllegalStateException.class, () -> invoke(value, scope.bean, "a"))
                    .getMessage().contains("removed"));
            assertEquals(0, scope.target.calls);
        }
    }

    @Test void unknownReceiverIsNotMistakenForTheRegisteredSingleton() throws Throwable {
        try (var scope = new Scope(false)) {
            scope.publish(Metadata.class);
            var stranger = new Owner(scope.jdbc);
            var value = site("value", MethodType.methodType(String.class, Owner.class, String.class));
            assertTrue(assertThrows(IllegalStateException.class, () -> invoke(value, stranger, "a"))
                    .getMessage().contains("not a captured singleton"));
            assertEquals(0, stranger.calls);
        }
    }

    @Test void closedContextDoesNotContinueServingCachedOperations() throws Throwable {
        try (var scope = new Scope(false)) {
            scope.publish(Metadata.class);
            var value = site("value", MethodType.methodType(String.class, Owner.class, String.class));
            assertEquals("a:1", invoke(value, scope.bean, "a"));
            scope.context.close();
            assertTrue(assertThrows(IllegalStateException.class, () -> invoke(value, scope.bean, "a"))
                    .getMessage().contains("no longer live"));
        }
    }

    @Test void aSecondSingletonOfTheSameClassUsesItsOwnTargetState() throws Throwable {
        try (var scope = new Scope(false)) {
            var other = new Owner(scope.jdbc);
            scope.context.getBeanFactory().registerSingleton("other", other);
            scope.publish(Plain.class);
            var value = site("value", MethodType.methodType(String.class, Owner.class, String.class));
            assertEquals("a:1", invoke(value, scope.bean, "a"));
            assertEquals("a:1", invoke(value, other, "a"));
            assertEquals(1, scope.target.calls); assertEquals(1, other.calls);
        }
    }

    @Test void transactionAndCacheTogetherDoNotCacheFailuresOrRepeatWrites() throws Throwable {
        try (var scope = new Scope(true)) {
            scope.publish(Combined.class);
            var write = site("write", MethodType.methodType(String.class, Owner.class, int.class, boolean.class));
            assertEquals("1:true", write.dynamicInvoker().invokeWithArguments(scope.bean, 1, false));
            assertEquals("1:true", write.dynamicInvoker().invokeWithArguments(scope.bean, 1, false));
            assertThrows(Exception.class, () -> write.dynamicInvoker().invokeWithArguments(scope.bean, 2, true));
            assertEquals(1, scope.jdbc.queryForObject("select count(*) from entries", Integer.class));
            assertEquals("2:true", write.dynamicInvoker().invokeWithArguments(scope.bean, 2, false));
            assertEquals(2, scope.jdbc.queryForObject("select count(*) from entries", Integer.class));
        }
    }

    @Test void transactionManagerQualifierIsResolvedBySpringBeforeTheBody() throws Throwable {
        try (var scope = new Scope(false)) {
            scope.publish(MissingManager.class);
            var write = site("write", MethodType.methodType(String.class, Owner.class, int.class, boolean.class));
            var failure = assertThrows(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,
                    () -> write.dynamicInvoker().invokeWithArguments(scope.bean, 1, false));
            assertTrue(failure.getMessage().contains("missingManager"));
            assertEquals(0, scope.jdbc.queryForObject("select count(*) from entries", Integer.class));
        }
    }

    @Test void changingTheTargetSourceAfterPublicationCannotUseTheCapturedOldTarget() throws Throwable {
        try (var scope = new Scope(true)) {
            scope.publish(Metadata.class);
            ((org.springframework.aop.framework.Advised) scope.bean).setTargetSource(new org.springframework.aop.TargetSource() {
                public Class<?> getTargetClass() { return Owner.class; }
                public boolean isStatic() { return false; }
                public Object getTarget() { throw new AssertionError("must not fetch dynamic target"); }
                public void releaseTarget(Object target) { throw new AssertionError("must not release dynamic target"); }
            });
            var value = site("value", MethodType.methodType(String.class, Owner.class, String.class));
            assertTrue(assertThrows(IllegalStateException.class, () -> invoke(value, scope.bean, "a"))
                    .getMessage().contains("dynamic or custom"));
            assertEquals(0, scope.target.calls);
        }
    }

    @Test void classLevelTransactionMetadataAppliesToTheAddedMethod() throws Throwable {
        try (var scope = new Scope(false)) {
            scope.publish(ClassTransaction.class);
            var write = site("write", MethodType.methodType(String.class, Owner.class, int.class, boolean.class));
            assertEquals("1:true", write.dynamicInvoker().invokeWithArguments(scope.bean, 1, false));
            assertThrows(Exception.class, () -> write.dynamicInvoker().invokeWithArguments(scope.bean, 2, true));
            assertEquals(1, scope.jdbc.queryForObject("select count(*) from entries", Integer.class));
        }
    }

    @Test void configuredKeyGeneratorReceivesTheActualTarget() throws Throwable {
        try (var scope = new Scope(true)) {
            scope.publish(CustomKey.class);
            var value = site("value", MethodType.methodType(String.class, Owner.class, String.class));
            assertEquals("a:1", invoke(value, scope.bean, "a"));
            assertEquals("a:1", invoke(value, scope.bean, "a"));
            assertEquals("a:1", scope.context.getBean(org.springframework.cache.CacheManager.class)
                    .getCache("values").get("custom/a").get());
        }
    }

    @Test void aNewOverrideOfAnInheritedMethodIsNotAdvertisedAsAnAddedOperation() throws Exception {
        byte[] bytes;
        try (var stream = OverrideMetadata.class.getResourceAsStream("SpringAddedOperationsTest$OverrideMetadata.class")) {
            bytes = stream.readAllBytes();
        }
        var plan = AddedOperationMetadata.create(Owner.class, bytes,
                MethodHandles.privateLookupIn(Owner.class, MethodHandles.lookup()), false);
        assertTrue(plan.entries().isEmpty());
    }

    static String write(Owner owner, int id, boolean fail) throws Exception {
        owner.jdbc.update("insert into entries values (?)", id);
        if (fail) throw new Exception("checked failure");
        return id + ":" + TransactionSynchronizationManager.isActualTransactionActive();
    }
    static String value(Owner owner, String key) { owner.calls++; return key.equals("null") ? null : key + ":" + owner.calls; }
    static String put(Owner owner, String key) { return "put"; }
    static void evict(Owner owner, String key) { }
    private static CallSite site(String name, MethodType type) throws Exception {
        MethodHandle direct = MethodHandles.lookup().findStatic(SpringAddedOperationsTest.class, name, type);
        String desc = type.dropParameterTypes(0, 1).toMethodDescriptorString();
        return AddedOperationBridge.externalCall(Owner.class, InjectedNames.siteKey(name, InjectedNames.descHash(desc)), new ConstantCallSite(direct));
    }
    private static Object invoke(CallSite site, Object owner, Object arg) throws Throwable { return site.dynamicInvoker().invokeWithArguments(owner, arg); }
    private static void publish(Class<?> metadata, Object context) throws Exception {
        byte[] bytes;
        try (var stream = metadata.getResourceAsStream("SpringAddedOperationsTest$" + metadata.getSimpleName() + ".class")) { bytes = stream.readAllBytes(); }
        var node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        node.name = Type.getInternalName(Owner.class);
        // The production publisher retains javac's MethodParameters; supply one in this fixture.
        node.methods.stream().filter(m -> m.name.equals("value")).forEach(m ->
                m.parameters = List.of(new org.objectweb.asm.tree.ParameterNode("name", 0)));
        var writer = new ClassWriter(0); node.accept(writer);
        SpringAddedOperations.publish(Owner.class, writer.toByteArray(),
                MethodHandles.privateLookupIn(Owner.class, MethodHandles.lookup()), List.of(context));
    }
    private static final class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class);
        final JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        final java.sql.Connection anchor;
        final Owner target;
        final Object bean;
        Scope(boolean proxy) throws Exception {
            anchor = context.getBean(JdbcDataSource.class).getConnection();
            jdbc.execute("create table entries (id int)");
            target = new Owner(jdbc);
            if (proxy) {
                var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
                for (var advisor : context.getBeansOfType(Advisor.class).values()) factory.addAdvisor(advisor);
                bean = factory.getProxy();
            } else bean = target;
            context.getBeanFactory().registerSingleton("owner", bean);
        }
        void publish(Class<?> metadata) throws Exception { SpringAddedOperationsTest.publish(metadata, context); }
        @Override public void close() throws Exception { context.close(); anchor.close(); }
    }
}
