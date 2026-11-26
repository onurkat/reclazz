/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;

import static org.junit.jupiter.api.Assertions.*;

class SecurityChainOwnerTest {
    @Configuration(proxyBeanMethods = false)
    static class Policy {
        @Bean @Order(2) SecurityFilterChain secondary() { return chain(); }
        @Bean @Order(1) DefaultSecurityFilterChain primary() { return chain(); }
    }
    @Configuration(proxyBeanMethods = false)
    static class Other {
        @Bean String label() { return "fixture"; }
    }
    private static DefaultSecurityFilterChain chain() {
        return new DefaultSecurityFilterChain(request -> true, List.of());
    }
    private static SpringSecurityReloader reloader(AnnotationConfigApplicationContext... contexts) {
        var platform = (PlatformContext) Proxy.newProxyInstance(PlatformContext.class.getClassLoader(),
                new Class<?>[]{PlatformContext.class}, (p,m,args) -> {
                    if (m.getName().equals("getAllApplicationContexts")) return List.of(contexts);
                    throw new AssertionError(m.getName());
                });
        return new SpringSecurityReloader(platform);
    }
    @AfterEach void clearRestart() { RestartLedger.clear(); }

    @Test void packagePrivateAndSubtypeFactoriesPublishInSpringOrder() {
        try (var context = new AnnotationConfigApplicationContext(Policy.class);
             var unrelated = new AnnotationConfigApplicationContext(Other.class)) {
            var inline = chain();
            var oldPrimary = context.getBean("primary", SecurityFilterChain.class);
            var proxy = new FilterChainProxy(List.of(inline, oldPrimary, context.getBean("secondary", SecurityFilterChain.class)));
            context.getBeanFactory().registerSingleton("springSecurityFilterChain", proxy);
            var reloader = reloader(unrelated, context);
            assertTrue(SpringSecurityReloader.declaresChain(Policy.class));
            assertFalse(SpringSecurityReloader.declaresChain(Other.class));
            assertEquals(SpringSecurityReloader.Result.NOT_APPLICABLE, reloader.publish(Other.class, reloader.prepare(Other.class)));
            assertEquals(SpringSecurityReloader.Result.PUBLISHED, reloader.publish(Policy.class, reloader.prepare(Policy.class)));
            assertSame(inline, proxy.getFilterChains().get(0));
            assertSame(context.getBean("primary"), proxy.getFilterChains().get(1));
            assertSame(context.getBean("secondary"), proxy.getFilterChains().get(2));
            assertNotSame(oldPrimary, proxy.getFilterChains().get(1));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class InheritedPolicy extends Policy { }

    @Test void registeredInheritedFactoryMethodsIdentifyTheirOwningConfiguration() {
        try (var context = new AnnotationConfigApplicationContext(InheritedPolicy.class)) {
            var proxy = new FilterChainProxy(List.of(context.getBean("primary", SecurityFilterChain.class),
                    context.getBean("secondary", SecurityFilterChain.class)));
            context.getBeanFactory().registerSingleton("springSecurityFilterChain", proxy);
            var reloader = reloader(context);
            assertEquals(SpringSecurityReloader.Result.PUBLISHED,
                    reloader.publish(InheritedPolicy.class, reloader.prepare(InheritedPolicy.class)));
            assertSame(context.getBean("primary"), proxy.getFilterChains().get(0));
        }
    }

    @Test void unknownProxyProducesNamedRestartBeforeTouchingTheOldChain() {
        var messages = new ArrayList<String>();
        StatusReporter.StatusListener listener=(level,message) -> messages.add(message);
        StatusReporter.addListener(listener);
        try (var context = new AnnotationConfigApplicationContext(Policy.class)) {
            context.getBeanFactory().registerSingleton("springSecurityFilterChain", new Object());
            var original = context.getBean("primary");
            var reloader = reloader(context);
            assertEquals(SpringSecurityReloader.Result.RESTART, reloader.publish(Policy.class, reloader.prepare(Policy.class)));
            assertSame(original, context.getBean("primary"));
            assertTrue(messages.contains("Security rules changed in " + Policy.class.getName() + "; restart to enforce them"));
            assertFalse(messages.stream().anyMatch(s -> s.contains("rebuilt") || s.contains("re-created")));
        } finally { StatusReporter.removeListener(listener); }
    }
}
