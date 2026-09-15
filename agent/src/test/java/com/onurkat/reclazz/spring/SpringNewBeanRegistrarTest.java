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
    @Test
    void lazyComponentWaitsForFirstAccessLikeSpringStartup() throws Exception {
        NewLazyComponent.made = 0;
        try (var nativeContext = new AnnotationConfigApplicationContext(NewLazyComponent.class)) {
            assertEquals(0, NewLazyComponent.made);
        }
        try (var context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            assertEquals(SpringNewBeanRegistrar.Outcome.REGISTERED,
                    registrar(context).registerIfComponent(NewLazyComponent.class.getName(), bytecode(NewLazyComponent.class)));
            assertEquals(0, NewLazyComponent.made, "registration must not instantiate a lazy bean");
            Object first = context.getBean("newLazyComponent");
            assertSame(first, context.getBean("newLazyComponent"));
            assertEquals(1, NewLazyComponent.made);
        }
    }

    @Test
    void prototypeComponentIsCreatedPerLookupLikeSpringStartup() throws Exception {
        NewPrototypeComponent.made = 0;
        try (var nativeContext = new AnnotationConfigApplicationContext(NewPrototypeComponent.class)) {
            assertEquals(0, NewPrototypeComponent.made);
            assertNotSame(nativeContext.getBean(NewPrototypeComponent.class), nativeContext.getBean(NewPrototypeComponent.class));
            assertEquals(2, NewPrototypeComponent.made);
        }
        NewPrototypeComponent.made = 0;
        try (var context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            assertEquals(SpringNewBeanRegistrar.Outcome.REGISTERED,
                    registrar(context).registerIfComponent(NewPrototypeComponent.class.getName(), bytecode(NewPrototypeComponent.class)));
            assertEquals("prototype", context.getBeanFactory().getBeanDefinition("newPrototypeComponent").getScope());
            assertEquals(0, NewPrototypeComponent.made);
            assertNotSame(context.getBean(NewPrototypeComponent.class), context.getBean(NewPrototypeComponent.class));
            assertEquals(2, NewPrototypeComponent.made);
        }
    }

    @Test
    void customScopesAndScopedProxiesAreDeclinedBeforeRegistration() throws Exception {
        for (Class<?> type : List.of(NewRequestComponent.class, NewScopedProxyComponent.class)) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.refresh();
                var before = java.util.Set.of(context.getBeanDefinitionNames());
                assertEquals(SpringNewBeanRegistrar.Outcome.DECLINED,
                        registrar(context).registerIfComponent(type.getName(), bytecode(type)));
                assertEquals(before, java.util.Set.of(context.getBeanDefinitionNames()));
            }
        }
    }

    @Test
    void eagerCreationFailureRemovesOnlyTheNewDefinition() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            Object existing = new Object();
            context.getBeanFactory().registerSingleton("existing", existing);
            var before = java.util.Set.of(context.getBeanDefinitionNames());
            assertEquals(SpringNewBeanRegistrar.Outcome.DECLINED,
                    registrar(context).registerIfComponent(NewFailingComponent.class.getName(), bytecode(NewFailingComponent.class)));
            assertEquals(before, java.util.Set.of(context.getBeanDefinitionNames()));
            assertSame(existing, context.getBean("existing"));
        }
    }

    @Test
    void emptyScopeKeepsTheDefaultEagerSingleton() throws Exception {
        NewDefaultScopeComponent.made = 0;
        try (var context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            assertEquals(SpringNewBeanRegistrar.Outcome.REGISTERED,
                    registrar(context).registerIfComponent(NewDefaultScopeComponent.class.getName(), bytecode(NewDefaultScopeComponent.class)));
            assertEquals(1, NewDefaultScopeComponent.made);
            assertSame(context.getBean(NewDefaultScopeComponent.class), context.getBean(NewDefaultScopeComponent.class));
        }
    }

    @Test
    void lazyAbstractComponentsAreNotRegistered() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            var before = java.util.Set.of(context.getBeanDefinitionNames());
            assertEquals(SpringNewBeanRegistrar.Outcome.DECLINED,
                    registrar(context).registerIfComponent(NewAbstractComponent.class.getName(), bytecode(NewAbstractComponent.class)));
            assertEquals(before, java.util.Set.of(context.getBeanDefinitionNames()));
        }
    }

    @Test
    void registrationUsesTheFactoriesApplicationLoaderInsteadOfTheCallersLoader() throws Exception {
        byte[] bytes = bytecode(NewLazyComponent.class);
        var loader = new ClassLoader(NewLazyComponent.class.getClassLoader()) {
            Class<?> defineComponent() { return defineClass(NewLazyComponent.class.getName(), bytes, 0, bytes.length); }
        };
        Class<?> childType = loader.defineComponent();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            context.getBeanFactory().setBeanClassLoader(loader);
            assertNotSame(loader, context.getClassLoader(), "fixture must distinguish context and bean loaders");
            assertEquals(SpringNewBeanRegistrar.Outcome.REGISTERED,
                    registrar(context).registerIfComponent(childType.getName(), bytes));
            assertSame(childType, context.getBean("newLazyComponent").getClass());
        }
    }

}


@org.springframework.stereotype.Component
@org.springframework.context.annotation.Lazy
class NewLazyComponent {
    static int made;
    NewLazyComponent() { made++; }
}

@org.springframework.stereotype.Component
@org.springframework.context.annotation.Scope(scopeName = "prototype")
class NewPrototypeComponent {
    static int made;
    NewPrototypeComponent() { made++; }
}


@org.springframework.stereotype.Component
@org.springframework.context.annotation.Scope("request")
class NewRequestComponent { }

@org.springframework.stereotype.Component
@org.springframework.context.annotation.Scope(value = "prototype", proxyMode = org.springframework.context.annotation.ScopedProxyMode.TARGET_CLASS)
class NewScopedProxyComponent { }

@org.springframework.stereotype.Component
class NewFailingComponent {
    NewFailingComponent() { throw new IllegalStateException("fixture creation failure"); }
}


@org.springframework.stereotype.Component
@org.springframework.context.annotation.Scope
class NewDefaultScopeComponent {
    static int made;
    NewDefaultScopeComponent() { made++; }
}

@org.springframework.stereotype.Component
@org.springframework.context.annotation.Lazy
abstract class NewAbstractComponent { }
