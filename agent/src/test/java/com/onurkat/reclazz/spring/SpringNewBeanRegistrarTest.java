/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.spring.newbean.LedgerComponent;
import com.onurkat.reclazz.spring.newbean.NotAComponent;
import com.onurkat.reclazz.spring.newbean.PlainDomainComponent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A brand-new class carrying a custom meta-annotated stereotype registers as a bean. */
class SpringNewBeanRegistrarTest {

    private static SpringNewBeanRegistrar registrar(AnnotationConfigApplicationContext context) {
        PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(
                PlatformContext.class.getClassLoader(), new Class[]{PlatformContext.class},
                (p, m, a) -> m.getName().equals("getAllApplicationContexts") ? List.of(context) : null);
        return new SpringNewBeanRegistrar(platform, new SpringMvcReloader(platform));
    }

    private static byte[] bytecode(Class<?> type) throws Exception {
        try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            return java.util.Objects.requireNonNull(in).readAllBytes();
        }
    }

    @Test
    void customStereotypeWithAliasForNameRegistersAsABean() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            var outcome = registrar(context).registerIfComponent(LedgerComponent.class.getName(), bytecode(LedgerComponent.class));
            assertEquals(SpringNewBeanRegistrar.Outcome.REGISTERED, outcome);
            // @AliasFor carries the custom annotation's value to the stereotype name.
            assertTrue(context.containsBean("ledger"), "the aliased bean name is used");
            assertInstanceOf(LedgerComponent.class, context.getBean("ledger"));
        }
    }

    @Test
    void customStereotypeWithoutAnExplicitNameUsesTheDefaultName() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            var outcome = registrar(context).registerIfComponent(PlainDomainComponent.class.getName(), bytecode(PlainDomainComponent.class));
            assertEquals(SpringNewBeanRegistrar.Outcome.REGISTERED, outcome);
            assertTrue(context.containsBean("plainDomainComponent"), "the generated default bean name is used");
            assertInstanceOf(PlainDomainComponent.class, context.getBean("plainDomainComponent"));
        }
    }

    @Test
    void aClassWithoutAnyStereotypeIsNotAComponent() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            assertEquals(SpringNewBeanRegistrar.Outcome.NOT_A_COMPONENT,
                    registrar(context).registerIfComponent(NotAComponent.class.getName(), bytecode(NotAComponent.class)));
            assertFalse(context.containsBean("notAComponent"));
        }
    }
}
