/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.*;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import java.lang.annotation.*;
import java.util.List;
import static com.onurkat.reclazz.spring.SpringAddedMethodSecurityTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** The same policy contract runs on both isolated native security generations. */
class ComposedMethodSecurityTest {
    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD,ElementType.TYPE,ElementType.ANNOTATION_TYPE})
    @PreAuthorize("hasAuthority('WRITE') and #p0 == authentication.name") public @interface Writer { }
    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD,ElementType.TYPE,ElementType.ANNOTATION_TYPE})
    @Writer public @interface NestedWriter { }
    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD,ElementType.TYPE,ElementType.ANNOTATION_TYPE})
    @PreAuthorize("hasAuthority('ADMIN')") public @interface Admin { }
    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD,ElementType.TYPE,ElementType.ANNOTATION_TYPE})
    @PostAuthorize("returnObject == authentication.name") public @interface OwnResult { }
    @Retention(RetentionPolicy.RUNTIME) @PreAuthorize("hasAuthority('WRITE')") @OwnResult public @interface Checked { }
    @Retention(RetentionPolicy.RUNTIME) @Writer @org.springframework.scheduling.annotation.Async public @interface Mixed { }
    @Retention(RetentionPolicy.RUNTIME) @Writer public @interface Parameterized {
        @org.springframework.core.annotation.AliasFor(annotation=PreAuthorize.class, attribute="value")
        String value() default "unused";
    }
    @Retention(RetentionPolicy.RUNTIME) public @interface Marker { }
    @Retention(RetentionPolicy.RUNTIME) @Writer @Marker public @interface UnknownMixed { }
    @Retention(RetentionPolicy.RUNTIME) @Writer @Admin public @interface Ambiguous { }
    @Retention(RetentionPolicy.RUNTIME) @Writer @CycleB public @interface CycleA { }
    @Retention(RetentionPolicy.RUNTIME) @CycleA public @interface CycleB { }
    public static class Pre { @NestedWriter public String work(String name) { return null; } }
    public static class Post { @OwnResult public String work(String name) { return null; } }
    public static class Both { @Checked public String work(String name) { return null; } }
    public static class ChangedPolicy { @Admin public String work(String name) { return null; } }
    @Admin public static class DefaultPolicy { public String work(String name) { return null; } }
    @Admin public static class OverridePolicy { @Writer public String work(String name) { return null; } }
    public static class MixedPolicy { @Mixed public String work(String name) { return null; } }
    public static class ParameterizedPolicy { @Parameterized public String work(String name) { return null; } }
    public static class UnknownPolicy { @UnknownMixed public String work(String name) { return null; } }
    public static class DuplicatePolicy { @Writer @Admin public String work(String name) { return null; } }
    @Writer @Admin public static class DuplicateClassPolicy { public String work(String name) { return null; } }
    public static class AmbiguousPolicy { @Ambiguous public String work(String name) { return null; } }
    public static class CyclicPolicy { @CycleA public String work(String name) { return null; } }
    public static class AsyncPolicy { @Writer @org.springframework.scheduling.annotation.Async public String work(String name) { return null; } }
    public static class CallbackPolicy { @Writer @org.springframework.context.event.EventListener public String work(String name) { return null; } }
    public static class CombinedPolicy {
        @NestedWriter @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
        @org.springframework.cache.annotation.Cacheable(cacheNames="secured",key="#p0")
        public String work(String name) { return null; }
    }
    public static class Native {
        int calls;
        @NestedWriter public String pre(String name) { calls++; return name; }
        @OwnResult public String post(String name) { calls++; return name; }
        @Checked public String both(String name) { calls++; return name; }
        public int calls() { return calls; }
    }
    Class<?> configuration() { return Config.class; }
    Class<?> plainConfiguration() { return SecurityOnly.class; }
    Class<?> disabledConfiguration() { return SpringAddedMethodSecurityTest.Disabled.class; }
    void configureOrder(Scope scope) { }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); com.onurkat.reclazz.ui.RestartLedger.clear(); }

    @Test void nativeNestedMarkerControlEnforcesPreAndPostPolicies() {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(configuration()); context.registerBean("native",Native.class); context.refresh();
            Native bean=context.getBean(Native.class);
            assertThrows(AuthenticationCredentialsNotFoundException.class,()->bean.pre("alice"));
            login("READ"); assertThrows(AccessDeniedException.class,()->bean.pre("alice")); assertEquals(0,bean.calls());
            login("WRITE"); assertEquals("alice",bean.pre("alice"));
            assertThrows(AccessDeniedException.class,()->bean.pre("bob")); assertEquals(1,bean.calls());
            login("READ"); assertThrows(AccessDeniedException.class,()->bean.post("bob")); assertEquals(2,bean.calls());
            assertThrows(AccessDeniedException.class,()->bean.both("alice")); assertEquals(2,bean.calls());
            login("WRITE"); assertEquals("alice",bean.both("alice")); assertEquals(3,bean.calls());
            assertThrows(AccessDeniedException.class,()->bean.both("bob")); assertEquals(4,bean.calls());
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void addedNestedPolicyProtectsPlainSingletonHeldProxyAndTarget(boolean proxied) throws Throwable {
        try(var scope=new Scope(proxied?configuration():plainConfiguration())) {
            scope.publish(Pre.class);
            assertThrows(AuthenticationCredentialsNotFoundException.class,()->scope.invoke("alice"));
            login("READ"); assertThrows(AccessDeniedException.class,()->scope.invoke("alice"));
            login("WRITE"); assertThrows(AccessDeniedException.class,()->scope.invoke("bob")); assertEquals(0,scope.target.calls);
            assertEquals("alice",scope.invoke("alice"));
            assertEquals("alice",scope.site.dynamicInvoker().invokeWithArguments(scope.target,"alice")); assertEquals(2,scope.target.calls);
            if(proxied) assertEquals("alice",scope.bean.original("alice"));
        }
    }
    @Test void postPolicyExecutesAfterBodyAndCombinedPolicyRequiresBoth() throws Throwable {
        try(var scope=new Scope(configuration())) {
            scope.publish(Post.class); login("READ");
            assertThrows(AccessDeniedException.class,()->scope.invoke("bob")); assertEquals(1,scope.target.calls);
            assertEquals("alice",scope.invoke("alice"));
            scope.publish(Both.class); assertThrows(AccessDeniedException.class,()->scope.invoke("alice")); assertEquals(2,scope.target.calls);
            login("WRITE"); assertEquals("alice",scope.invoke("alice")); assertEquals(3,scope.target.calls);
            assertThrows(AccessDeniedException.class,()->scope.invoke("bob")); assertEquals(4,scope.target.calls);
        }
    }
    @Test void classDefaultsAndMethodOverrideRemainNative() throws Throwable {
        try(var scope=new Scope(configuration())) {
            scope.publish(DefaultPolicy.class); login("WRITE"); assertThrows(AccessDeniedException.class,()->scope.invoke("alice"));
            scope.publish(OverridePolicy.class); assertEquals("alice",scope.invoke("alice"));
            scope.publish(DefaultPolicy.class); login("ADMIN"); assertEquals("alice",scope.invoke("alice")); assertEquals(2,scope.target.calls);
        }
    }
    @Test void policyChangesRemovalAndRestoreFollowHeldSite() throws Throwable {
        try(var scope=new Scope(configuration())) {
            scope.publish(Pre.class); login("WRITE"); assertEquals("alice",scope.invoke("alice"));
            scope.publish(ChangedPolicy.class); assertThrows(AccessDeniedException.class,()->scope.invoke("alice"));
            login("ADMIN"); assertEquals("alice",scope.invoke("alice"));
            scope.publish(Plain.class); login("READ"); assertEquals("public",scope.invoke("public"));
            scope.publish(Empty.class); assertThrows(IllegalStateException.class,()->scope.invoke("alice"));
            scope.publish(Pre.class); assertThrows(AccessDeniedException.class,()->scope.invoke("alice"));
            login("WRITE"); assertEquals("alice",scope.invoke("alice")); assertEquals(4,scope.target.calls);
        }
    }
    @Test void mixedParameterizedAmbiguousCyclicAndCallbackPoliciesRefuseBeforeBody() throws Throwable {
        try(var scope=new Scope(configuration())) {
            login("WRITE");
            for(Class<?> metadata:List.of(MixedPolicy.class,ParameterizedPolicy.class,UnknownPolicy.class,
                    DuplicatePolicy.class,DuplicateClassPolicy.class,AmbiguousPolicy.class,CyclicPolicy.class,AsyncPolicy.class,CallbackPolicy.class)) {
                scope.publish(metadata); assertThrows(IllegalStateException.class,()->scope.invoke("alice"),metadata.getName());
                assertEquals(0,scope.target.calls);
            }
        }
    }
    @Test void missingAndDisabledSecurityCannotRunComposedBody() throws Throwable {
        for(Class<?> config:List.of(disabledConfiguration(),Operations.class)) try(var scope=new Scope(config)) {
            scope.publish(Pre.class); login("WRITE");
            assertThrows(IllegalStateException.class,()->scope.invoke("alice")); assertEquals(0,scope.target.calls);
        }
    }
    @Test void composedAuthorizationStillProtectsCachedResultsAndDatabaseWrites() throws Throwable {
        try(var scope=new Scope(configuration(),Operations.class)) {
            configureOrder(scope);
            var jdbc=scope.context.getBean(org.springframework.jdbc.core.JdbcTemplate.class); jdbc.execute("create table entries(name varchar)");
            scope.publish(CombinedPolicy.class); scope.site=site("write",scope.context);
            login("WRITE"); assertEquals("alice",scope.invoke("alice")); assertEquals("alice",scope.invoke("alice"));
            login("READ"); assertThrows(AccessDeniedException.class,()->scope.invoke("alice"));
            login("WRITE");
            // Writer deliberately checks the argument too: invalid input cannot start a transaction/body.
            assertThrows(AccessDeniedException.class,()->scope.invoke("fail"));
            assertEquals(1,jdbc.queryForObject("select count(*) from entries",Integer.class)); assertEquals(1,scope.target.calls);
        }
    }
}
