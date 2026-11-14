/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.AddedOperationBridge;
import com.onurkat.reclazz.bootstrap.InjectedNames;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.*;
import org.springframework.security.access.intercept.aopalliance.MethodSecurityInterceptor;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.cache.annotation.*;
import org.springframework.transaction.annotation.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import com.onurkat.reclazz.spring.SpringAddedMethodSecurityTest.Scope;
import static com.onurkat.reclazz.spring.SpringAddedMethodSecurityTest.*;

/** Legacy-only test bodies kept out of the shared modern-security fixture class. */
final class LegacyAddedMethodSecuritySupport {
    private LegacyAddedMethodSecuritySupport() { }
    static void nativeInterceptorAcceptsHiddenMethodWithActualTargetAndArguments() throws Throwable {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            Owner bean=context.getBean(Owner.class);
            Owner target=(Owner)((Advised)bean).getTargetSource().getTarget();
            MethodSecurityInterceptor interceptor=context.getBean(MethodSecurityInterceptor.class);
            // A native probe independent of Reclazz operation activation/routing.
            Method method=hidden(Before.class).getDeclaredMethod("work",String.class);
            var invocation=new org.aopalliance.intercept.MethodInvocation() {
                public Method getMethod() { return method; }
                public Object[] getArguments() { return new Object[]{"alice"}; }
                public Object getThis() { return target; }
                public AccessibleObject getStaticPart() { return method; }
                public Object proceed() { return work(target,"alice"); }
            };
            assertThrows(AuthenticationCredentialsNotFoundException.class,()->interceptor.invoke(invocation));
            login("READ"); assertThrows(AccessDeniedException.class,()->interceptor.invoke(invocation));
            assertEquals(0,target.calls);
            login("WRITE"); assertEquals("alice",interceptor.invoke(invocation)); assertEquals(1,target.calls);
            System.out.println("NATIVE_SECURITY="+interceptor.getClass().getName()+",source="+interceptor.getSecurityMetadataSource().getClass().getName());
        }
    }
    static void customExpressionHandlerRefusesBeforeBody() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            Object voter=scope.context.getBean(MethodSecurityInterceptor.class).getAccessDecisionManager();
            var voters=((org.springframework.security.access.vote.AffirmativeBased)voter).getDecisionVoters();
            var field=voters.get(0).getClass().getDeclaredField("preAdvice"); field.setAccessible(true);
            var advice=(org.springframework.security.access.expression.method.ExpressionBasedPreInvocationAdvice)field.get(voters.get(0));
            advice.setExpressionHandler(new org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler() { });
            scope.publish(Before.class); login("WRITE");
            assertThrows(IllegalStateException.class,()->scope.invoke("alice")); assertEquals(0,scope.target.calls);
        }
    }
    static void customMetadataSourceCannotTurnAnnotatedOperationPublic() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            scope.context.getBean(MethodSecurityInterceptor.class).setSecurityMetadataSource(new org.springframework.security.access.method.AbstractMethodSecurityMetadataSource() {
                public Collection<org.springframework.security.access.ConfigAttribute> getAttributes(Method m,Class<?> t) { return List.of(); }
                public Collection<org.springframework.security.access.ConfigAttribute> getAllConfigAttributes() { return List.of(); }
            });
            scope.publish(Before.class); login("WRITE");
            assertThrows(IllegalStateException.class,()->scope.invoke("alice")); assertEquals(0,scope.target.calls);
        }
    }
    static void customDecisionManagerIsNotSilentlyTreatedAsStandardSecurity() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            scope.context.getBean(MethodSecurityInterceptor.class).setAccessDecisionManager(new org.springframework.security.access.AccessDecisionManager() {
                public void decide(org.springframework.security.core.Authentication a,Object o,Collection<org.springframework.security.access.ConfigAttribute> c) { }
                public boolean supports(org.springframework.security.access.ConfigAttribute c) { return true; }
                public boolean supports(Class<?> c) { return true; }
            });
            scope.publish(Before.class); login("WRITE");
            assertThrows(IllegalStateException.class,()->scope.invoke("alice")); assertEquals(0,scope.target.calls);
        }
    }
    static void retiredMetadataCacheEntriesAreRemovedWithoutClearingOriginalMethod() throws Throwable {
        try(var scope=new Scope(Config.class)) {
            login("WRITE"); scope.bean.original("alice"); scope.publish(Before.class); scope.invoke("alice");
            Object source=scope.context.getBean(MethodSecurityInterceptor.class).getSecurityMetadataSource();
            Field f=source.getClass().getDeclaredField("attributeCache"); f.setAccessible(true); Map<?,?> cache=(Map<?,?>)f.get(source);
            var before=new HashSet<>(cache.keySet());
            scope.publish(Changed.class);
            assertTrue(cache.size()<before.size(),"superseded hidden Method retained");
            assertTrue(cache.keySet().stream().anyMatch(k->k.toString().contains("original")),"unrelated native metadata removed");
        }
    }
}
