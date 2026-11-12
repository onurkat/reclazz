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

    static void sharedLoaderScanOwnerEnrollment() throws Exception {
        com.onurkat.reclazz.platform.ApplicationContextHolder.clear();
        var file=java.nio.file.Files.createTempFile("reclazz-scan-owner-",".xml");
        java.nio.file.Files.writeString(file,SpringComponentScanOwnersTest.xml("<c:component-scan base-package='com.onurkat.reclazz.spring'/>"));
        try(var root=new org.springframework.context.support.GenericApplicationContext();var web=new org.springframework.context.support.GenericApplicationContext();var unrelated=new org.springframework.context.support.GenericApplicationContext()) {
            root.refresh();web.setParent(root);unrelated.setParent(root);
            web.registerBean("mapping",org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
            web.registerBean("stableControl",ScanStableController.class);
            unrelated.registerBean("mapping",org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
            web.refresh();unrelated.refresh();SpringComponentScanOwnersTest.capture(web,file);
            PlatformContext platform=(PlatformContext)Proxy.newProxyInstance(PlatformContext.class.getClassLoader(),new Class[]{PlatformContext.class},(p,m,a)->m.getName().equals("getAllApplicationContexts")?List.of(root,web,unrelated):null);
            var registrar=new SpringNewBeanRegistrar(platform,new SpringMvcReloader(platform));
            assertEquals(SpringNewBeanRegistrar.Outcome.REGISTERED,registrar.registerIfComponent(ScanOwnedController.class.getName(),bytecode(ScanOwnedController.class)));
            assertFalse(root.getBeanFactory().containsBeanDefinition("scanOwnedController"));
            assertFalse(unrelated.getBeanFactory().containsBeanDefinition("scanOwnedController"));
            var mapping=web.getBean("mapping",org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
            assertTrue(mapping.getHandlerMethods().values().stream().anyMatch(h->h.getBeanType()==ScanOwnedController.class));
            assertTrue(mapping.getHandlerMethods().values().stream().anyMatch(h->h.getBeanType()==ScanStableController.class));
            assertTrue(unrelated.getBean("mapping",org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class).getHandlerMethods().isEmpty());
        } finally {com.onurkat.reclazz.platform.ApplicationContextHolder.clear();java.nio.file.Files.deleteIfExists(file);}
    }
    @Test void aSharedDefiningLoaderDoesNotStealTheWebScanOwner() throws Exception {sharedLoaderScanOwnerEnrollment();}

    @Test void scanOwnerCollisionAndFailedCreationLeaveNoGlobalDuplicate() throws Exception {
        com.onurkat.reclazz.platform.ApplicationContextHolder.clear();
        var file=java.nio.file.Files.createTempFile("reclazz-scan-refusal-",".xml");
        java.nio.file.Files.writeString(file,SpringComponentScanOwnersTest.xml("<c:component-scan base-package='com.onurkat.reclazz.spring'/>"));
        try(var root=new org.springframework.context.support.GenericApplicationContext();var web=new org.springframework.context.support.GenericApplicationContext()) {
            root.refresh();web.setParent(root);web.refresh();SpringComponentScanOwnersTest.capture(web,file);
            PlatformContext platform=(PlatformContext)Proxy.newProxyInstance(PlatformContext.class.getClassLoader(),new Class[]{PlatformContext.class},(p,m,a)->m.getName().equals("getAllApplicationContexts")?List.of(root,web):null);
            var registrar=new SpringNewBeanRegistrar(platform,new SpringMvcReloader(platform));
            Object sentinel=new Object();web.getBeanFactory().registerSingleton("scanOwnedController",sentinel);
            assertEquals(SpringNewBeanRegistrar.Outcome.OWNER_FAILED,registrar.registerIfComponent(ScanOwnedController.class.getName(),bytecode(ScanOwnedController.class)));
            assertSame(sentinel,web.getBean("scanOwnedController"));assertFalse(root.containsBean("scanOwnedController"));
            assertEquals(SpringNewBeanRegistrar.Outcome.OWNER_FAILED,registrar.registerIfComponent(ScanThrowingController.class.getName(),bytecode(ScanThrowingController.class)));
            assertFalse(web.containsBean("scanThrowingController"));assertFalse(root.containsBean("scanThrowingController"));
            assertEquals(SpringNewBeanRegistrar.Outcome.FILTERED,registrar.registerIfComponent(ScanProfileController.class.getName(),bytecode(ScanProfileController.class)));
            assertFalse(web.containsBean("scanProfileController"));
        } finally {com.onurkat.reclazz.platform.ApplicationContextHolder.clear();java.nio.file.Files.deleteIfExists(file);}
    }
    @Test void missingMvcRollsBackOwnedRegistrationAsHandledFailure() throws Exception {
        com.onurkat.reclazz.platform.ApplicationContextHolder.clear();
        var file=java.nio.file.Files.createTempFile("reclazz-scan-no-mvc-",".xml");java.nio.file.Files.writeString(file,SpringComponentScanOwnersTest.xml("<c:component-scan base-package='com.onurkat.reclazz.spring'/>"));
        try(var web=new org.springframework.context.support.GenericApplicationContext()) {
            web.refresh();SpringComponentScanOwnersTest.capture(web,file);
            PlatformContext platform=(PlatformContext)Proxy.newProxyInstance(PlatformContext.class.getClassLoader(),new Class[]{PlatformContext.class},(p,m,a)->m.getName().equals("getAllApplicationContexts")?List.of(web):null);
            var outcome=new SpringNewBeanRegistrar(platform,new SpringMvcReloader(platform)).registerIfComponent(ScanOwnedController.class.getName(),bytecode(ScanOwnedController.class));
            assertEquals(SpringNewBeanRegistrar.Outcome.OWNER_FAILED,outcome);assertTrue(outcome.isHandled());assertFalse(web.containsBean("scanOwnedController"));
        } finally {com.onurkat.reclazz.platform.ApplicationContextHolder.clear();java.nio.file.Files.deleteIfExists(file);}
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

@org.springframework.stereotype.Controller("scanOwnedController")
class ScanOwnedController { @org.springframework.web.bind.annotation.GetMapping("/scan-owned") @org.springframework.web.bind.annotation.ResponseBody public String value() {return "owned";} }
@org.springframework.stereotype.Controller("stableControl")
class ScanStableController { @org.springframework.web.bind.annotation.GetMapping("/scan-stable") @org.springframework.web.bind.annotation.ResponseBody public String value() {return "stable";} }
@org.springframework.stereotype.Controller("scanThrowingController")
class ScanThrowingController { ScanThrowingController() {throw new IllegalStateException("owned constructor failure");} }
@org.springframework.stereotype.Controller("scanProfileController")
@org.springframework.context.annotation.Profile("not-active")
class ScanProfileController { }
