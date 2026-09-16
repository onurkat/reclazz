/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.Advised;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.*;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authorization.method.AuthorizationAdvisor;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager;
import org.springframework.beans.factory.config.BeanDefinition;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;
import static com.onurkat.reclazz.spring.SpringAddedMethodSecurityTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reuses only version-neutral fixture bytecode/body handles from the Spring 5 matrix. */
class SpringSixAddedMethodSecurityTest {
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity(proxyTargetClass=true)
    static class Config { @Bean Owner owner() { return new Owner(); } }
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity(proxyTargetClass=true)
    static class PlainConfig { }
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity(prePostEnabled=false,securedEnabled=true,proxyTargetClass=true)
    static class Disabled { @Bean Owner owner() { return new Owner(); } }
    public static class Both {
        @PreAuthorize("hasAuthority('WRITE')") @PostAuthorize("returnObject == authentication.name")
        public String work(String name) { return null; }
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); com.onurkat.reclazz.ui.RestartLedger.clear(); }
    // The shared fixture loader also reads metadata from this isolated source set.
    static void publishBoth(Scope scope) throws Exception {
        scope.publish(Both.class);
    }
    @Test void nativeModernInterceptorAcceptsHiddenMetadata() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            var advisor=(AuthorizationAdvisor)scope.context.getBean("preAuthorizeAuthorizationMethodInterceptor");
            assertEquals("org.springframework.security.config.annotation.method.configuration.DeferringMethodInterceptor",advisor.getClass().getName());
            Method method=hidden(Before.class).getDeclaredMethod("work",String.class);
            var invocation=new org.aopalliance.intercept.MethodInvocation() {
                public Method getMethod() { return method; }
                public Object[] getArguments() { return new Object[]{"alice"}; }
                public Object getThis() { return scope.target; }
                public AccessibleObject getStaticPart() { return method; }
                public Object proceed() { return work(scope.target,"alice"); }
            };
            assertThrows(AuthenticationCredentialsNotFoundException.class,()->advisor.invoke(invocation));
            login("READ"); assertThrows(AccessDeniedException.class,()->advisor.invoke(invocation)); assertEquals(0,scope.target.calls);
            login("WRITE"); assertEquals("alice",advisor.invoke(invocation)); assertEquals(1,scope.target.calls);
            System.out.println("NATIVE_MODERN_ADVISORS="+scope.context.getBeansOfType(org.springframework.aop.Advisor.class).keySet());
        }
    }
    @Test void nativePointcutsRequireTheSavedClassForNewClassDefaults() throws Exception {
        try(var scope=new Scope(Config.class)) {
            var advisor=(org.springframework.aop.PointcutAdvisor)scope.context.getBean("preAuthorizeAuthorizationAdvisor");
            assertEquals(AuthorizationManagerBeforeMethodInterceptor.preAuthorize().getPointcut(),advisor.getPointcut());
            Method metadata=hidden(ClassDefault.class).getDeclaredMethod("work",String.class);
            assertFalse(advisor.getPointcut().getMethodMatcher().matches(metadata,Owner.class));
            assertTrue(advisor.getPointcut().getMethodMatcher().matches(metadata,metadata.getDeclaringClass()));
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void addedPreAuthorizationProtectsPlainAndHeldProxy(boolean proxy) throws Throwable {
        try(var scope=new Scope(proxy?Config.class:PlainConfig.class)) {
            assertEquals(proxy,scope.bean instanceof Advised); scope.publish(Before.class);
            assertThrows(AuthenticationCredentialsNotFoundException.class,()->scope.invoke("alice"));
            login("READ"); assertThrows(AccessDeniedException.class,()->scope.invoke("alice"));
            login("WRITE"); assertThrows(AccessDeniedException.class,()->scope.invoke("bob")); assertEquals(0,scope.target.calls);
            assertEquals("alice",scope.invoke("alice"));
            assertEquals("alice",scope.site.dynamicInvoker().invokeWithArguments(scope.target,"alice")); assertEquals(2,scope.target.calls);
        }
    }
    @Test void bothPoliciesUseNativeBeforeAndAfterManagers() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            publishBoth(scope); login("READ"); assertThrows(AccessDeniedException.class,()->scope.invoke("alice")); assertEquals(0,scope.target.calls);
            login("WRITE"); assertEquals("alice",scope.invoke("alice"));
            assertThrows(AccessDeniedException.class,()->scope.invoke("bob")); assertEquals(2,scope.target.calls);
            assertEquals("alice",scope.bean.original("alice"));
        }
    }
    public static class PublicPolicy {
        @PreAuthorize("permitAll()") public String work(String name) { return null; }
    }
    @Test void modernPermitAllDoesNotEagerlyRequireAuthentication() throws Throwable {
        try(var scope=new Scope(PlainConfig.class)) {
            SecurityContextHolder.clearContext(); scope.publish(PublicPolicy.class);
            assertEquals("public",scope.invoke("public")); assertEquals(1,scope.target.calls);
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }
    @Test void postOnlyChecksTheReturnedValueAfterBody() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            scope.publish(After.class); login("READ"); assertEquals("alice",scope.invoke("alice"));
            assertThrows(AccessDeniedException.class,()->scope.invoke("bob")); assertEquals(2,scope.target.calls);
        }
    }
    @Test void classDefaultsAndMethodOverridesUseSavedMetadata() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            login("WRITE"); scope.publish(ClassDefault.class); assertThrows(AccessDeniedException.class,()->scope.invoke("alice"));
            scope.publish(ClassPolicy.class); assertEquals("alice",scope.invoke("alice"));
            scope.publish(ClassDefault.class); login("ADMIN"); assertEquals("alice",scope.invoke("alice")); assertEquals(2,scope.target.calls);
        }
    }
    @Test void policyAndAnnotationAndMethodRemovalFollowRetainedSite() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            scope.publish(Before.class); login("WRITE"); assertEquals("alice",scope.invoke("alice"));
            scope.publish(Changed.class); assertThrows(AccessDeniedException.class,()->scope.invoke("alice"));
            login("ADMIN"); assertEquals("alice",scope.invoke("alice"));
            scope.publish(Plain.class); SecurityContextHolder.clearContext(); assertEquals("public",scope.invoke("public"));
            scope.publish(Empty.class); assertTrue(assertThrows(IllegalStateException.class,()->scope.invoke("alice")).getMessage().contains("removed"));
            scope.publish(Before.class); login("READ"); assertThrows(AccessDeniedException.class,()->scope.invoke("alice"));
            login("WRITE"); assertEquals("alice",scope.invoke("alice")); assertEquals(4,scope.target.calls);
        }
    }
    @ParameterizedTest @ValueSource(strings={"pre","post"})
    void eachRequiredAuthorizationAdvisorMustBePresent(String missing) throws Throwable {
        try(var scope=new Scope(PlainConfig.class)) {
            // Infrastructure APC must no longer select this advisor; the other stays valid.
            scope.context.getBeanFactory().getBeanDefinition(missing+"AuthorizeAuthorizationAdvisor").setRole(BeanDefinition.ROLE_APPLICATION);
            scope.publish(missing.equals("pre")?After.class:Before.class); login("WRITE"); assertEquals("alice",scope.invoke("alice"));
            publishBoth(scope);
            assertTrue(assertThrows(IllegalStateException.class,()->scope.invoke("alice")).getMessage().contains("matching"));
            assertEquals(1,scope.target.calls);
        }
    }
    @Test void configuredNativeOrderProtectsCacheAndDatabaseWrites() throws Throwable {
        try(var scope=new Scope(Config.class,Operations.class)) {
            // Native modern pre order defaults200; put TX/cache after it.
            ((org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor)scope.context.getBean("org.springframework.transaction.config.internalTransactionAdvisor")).setOrder(300);
            ((org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor)scope.context.getBean("org.springframework.cache.config.internalCacheAdvisor")).setOrder(400);
            var jdbc=scope.context.getBean(org.springframework.jdbc.core.JdbcTemplate.class); jdbc.execute("create table entries(name varchar)");
            scope.publish(Combined.class); scope.site=site("write",scope.context);
            login("WRITE"); assertEquals("alice",scope.invoke("alice")); assertEquals("alice",scope.invoke("alice"));
            login("READ"); assertThrows(AccessDeniedException.class,()->scope.invoke("alice")); assertThrows(AccessDeniedException.class,()->scope.invoke("bob"));
            login("WRITE"); assertEquals("rollback",assertThrows(Exception.class,()->scope.invoke("fail")).getMessage());
            assertEquals(1,jdbc.queryForObject("select count(*) from entries",Integer.class)); assertEquals(2,scope.target.calls);
        }
    }
    @Test void disabledSecurityDoesNotMakeAnnotationsOptional() throws Throwable {
        try(var scope=new Scope(Disabled.class)) {
            scope.publish(Before.class); login("WRITE"); assertThrows(IllegalStateException.class,()->scope.invoke("alice")); assertEquals(0,scope.target.calls);
        }
    }
    @Test void customManagerAndHandlerAreRefused() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            Object advisor=scope.context.getBean("preAuthorizeAuthorizationMethodInterceptor");
            Object interceptor=delegate(advisor); Object manager=field(interceptor,"authorizationManager");
            ((PreAuthorizeAuthorizationManager)manager).setExpressionHandler(new org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler() { });
            scope.publish(Before.class); login("WRITE"); assertThrows(IllegalStateException.class,()->scope.invoke("alice")); assertEquals(0,scope.target.calls);
        }
        try(var scope=new Scope(Config.class)) {
            Object interceptor=delegate(scope.context.getBean("preAuthorizeAuthorizationMethodInterceptor"));
            var managerField=com.onurkat.reclazz.util.Reflect.findField(interceptor.getClass(),"authorizationManager");
            assertNotNull(managerField);
            org.springframework.security.authorization.AuthorizationManager<org.aopalliance.intercept.MethodInvocation> custom =
                    (authentication,invocation)->new org.springframework.security.authorization.AuthorizationDecision(true);
            managerField.set(interceptor,custom);
            scope.publish(Before.class); login("WRITE"); assertThrows(IllegalStateException.class,()->scope.invoke("alice")); assertEquals(0,scope.target.calls);
        }
    }
    @Test void unknownReceiverAndUnsupportedCombinationsRefuse() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            login("WRITE"); scope.publish(Before.class); Owner stranger=new Owner();
            assertThrows(IllegalStateException.class,()->scope.site.dynamicInvoker().invokeWithArguments(stranger,"alice")); assertEquals(0,stranger.calls);
            for(Class<?> metadata:List.of(Async.class,Callback.class,Filter.class,Composed.class)) {
                scope.publish(metadata); assertThrows(IllegalStateException.class,()->scope.invoke("alice")); assertEquals(0,scope.target.calls);
            }
        }
    }
    @Test void supersededCacheEntriesRetireButNativeOriginalRemains() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            login("WRITE"); scope.bean.original("alice"); scope.publish(Before.class); scope.invoke("alice");
            Object manager=field(delegate(scope.context.getBean("preAuthorizeAuthorizationMethodInterceptor")),"authorizationManager");
            Map<?,?> cache=(Map<?,?>)field(field(manager,"registry"),"cachedAttributes");
            int before=cache.size(); scope.publish(Changed.class);
            assertTrue(cache.size()<before); assertTrue(cache.keySet().stream().anyMatch(k->k.toString().contains("original")));
        }
    }
    @Configuration(proxyBeanMethods=false)
    static class Observed {
        @Bean java.util.concurrent.atomic.AtomicInteger observations() { return new java.util.concurrent.atomic.AtomicInteger(); }
        @Bean io.micrometer.observation.ObservationRegistry observationRegistry(java.util.concurrent.atomic.AtomicInteger observations) {
            var registry=io.micrometer.observation.ObservationRegistry.create();
            registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<io.micrometer.observation.Observation.Context>() {
                public boolean supportsContext(io.micrometer.observation.Observation.Context context) { return true; }
                public void onStop(io.micrometer.observation.Observation.Context context) { observations.incrementAndGet(); }
            });
            return registry;
        }
    }
    @Test void nativeObservationWrapperStillReportsAllowedAndDeniedCalls() throws Throwable {
        try(var scope=new Scope(Config.class,Observed.class)) {
            Object manager=field(delegate(scope.context.getBean("preAuthorizeAuthorizationMethodInterceptor")),"authorizationManager");
            assertEquals("org.springframework.security.authorization.ObservationAuthorizationManager",manager.getClass().getName());
            var count=scope.context.getBean(java.util.concurrent.atomic.AtomicInteger.class); int before=count.get();
            scope.publish(Before.class); login("WRITE"); assertEquals("alice",scope.invoke("alice"));
            login("READ"); assertThrows(AccessDeniedException.class,()->scope.invoke("alice"));
            assertEquals(before+2,count.get()); assertEquals(1,scope.target.calls);
            scope.publish(Changed.class); login("WRITE"); assertThrows(AccessDeniedException.class,()->scope.invoke("alice"));
            login("ADMIN"); assertEquals("alice",scope.invoke("alice")); assertEquals(before+4,count.get());
        }
    }
    public static class SecuredResult {
        @org.springframework.security.authorization.method.AuthorizeReturnObject public String work(String name) { return null; }
    }
    public static class DenialHandler {
        @PreAuthorize("denyAll()")
        @org.springframework.security.authorization.method.HandleAuthorizationDenied(handlerClass=org.springframework.security.authorization.method.ThrowingMethodAuthorizationDeniedHandler.class)
        public String work(String name) { return null; }
    }
    @Test void unsupportedReturnObjectAndDenialAnnotationsCannotBypassTheBridge() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            login("WRITE");
            for(Class<?> metadata:List.of(SecuredResult.class,DenialHandler.class)) {
                var plan=AddedOperationMetadata.create(Owner.class,bytes(metadata),java.lang.invoke.MethodHandles.privateLookupIn(Owner.class,java.lang.invoke.MethodHandles.lookup()),false);
                assertTrue(plan.operations()); assertTrue(plan.entries().get(0).security()); assertNotNull(plan.entries().get(0).reason());
                scope.publish(metadata); assertThrows(IllegalStateException.class,()->scope.invoke("alice")); assertEquals(0,scope.target.calls);
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void aMatchingUnsupportedReturnObjectAdvisorCannotRunOnTheBody(boolean ownerOnly) throws Throwable {
        try(var scope=new Scope(Config.class)) {
            var advisor=(org.springframework.security.authorization.method.AuthorizeReturnObjectMethodInterceptor)scope.context.getBean("authorizeReturnObjectMethodInterceptor");
            advisor.setPointcut(ownerOnly ? new org.springframework.aop.support.StaticMethodMatcherPointcut() {
                public boolean matches(Method method,Class<?> targetClass) { return targetClass==Owner.class; }
            } : org.springframework.aop.Pointcut.TRUE);
            scope.publish(Before.class); login("WRITE");
            assertTrue(assertThrows(IllegalStateException.class,()->scope.invoke("alice")).getMessage().contains("unsupported matching"));
            assertEquals(0,scope.target.calls);
        }
    }
    static Object delegate(Object advisor) throws Exception { return ((Supplier<?>)field(advisor,"delegate")).get(); }
    static Object field(Object bean,String name) throws Exception {
        Field f=com.onurkat.reclazz.util.Reflect.findField(bean.getClass(),name); assertNotNull(f,name); return f.get(bean);
    }
}
