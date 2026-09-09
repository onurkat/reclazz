/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.CommonAnnotationBeanPostProcessor;
import org.springframework.stereotype.Component;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SpringLifecycleReloaderTest {
    private static final Set<String> ADDED = Set.of("open:()V", "close:()V", "otherClose:()V",
            "failInit:()V", "failDestroy:()V");

    @Component public static class Owner implements InitializingBean, DisposableBean {
        @Autowired String token;
        final List<String> calls = new ArrayList<>();
        private void open() { calls.add("open:" + token); }
        private void close() { calls.add("close"); }
        private void otherClose() { calls.add("other-close"); }
        private void failInit() { throw new IllegalArgumentException("init-boom"); }
        private void failDestroy() { calls.add("destroy-boom"); throw new IllegalArgumentException("destroy-boom"); }
        @Override public void afterPropertiesSet() { calls.add("after"); }
        @Override public void destroy() { calls.add("disposable"); }
        // Deliberately equal: callback ownership must use object identity.
        @Override public boolean equals(Object other) { return other instanceof Owner; }
        @Override public int hashCode() { return 1; }
    }

    @AfterEach void clearDiagnostics() { RestartLedger.clear(); }

    static final class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final SpringLifecycleReloader reloader;
        Scope() throws Exception { this(c -> { }); }
        Scope(Consumer<AnnotationConfigApplicationContext> customize) throws Exception {
            LookupCapture.store(Owner.class, MethodHandles.privateLookupIn(Owner.class, MethodHandles.lookup()));
            context.registerBean("token", String.class, () -> "injected");
            context.registerBean("owner", Owner.class);
            customize.accept(context);
            context.refresh();
            reloader = new SpringLifecycleReloader(platform(context));
        }
        Owner owner() { return context.getBean(Owner.class); }
        Owner recreate() throws Exception {
            return (Owner) SpringBeanReloader.destroyAndRefreshBean(context, "owner")[1];
        }
        void install(byte[] bytes) {
            var prepared = reloader.prepare(Owner.class, ADDED, bytes);
            assertNotNull(prepared, RestartLedger.digest().toString());
            assertTrue(prepared.install());
        }
        @Override public void close() { context.close(); }
    }

    @Test void callbacksRunAfterInjectionBeforeInitializingBeanAndOnlyOnNewInstances() throws Exception {
        try (Scope s = new Scope()) {
            Owner old = s.owner();
            s.install(metadata("open", "close"));
            assertEquals(List.of("after"), old.calls);
            Owner fresh = s.recreate();
            assertEquals(List.of("after", "disposable"), old.calls);
            assertEquals(List.of("open:injected", "after"), fresh.calls);
            s.context.close();
            assertEquals(List.of("open:injected", "after", "close", "disposable"), fresh.calls);
        }
    }

    @Test void removedAndChangedMetadataKeepsCleanupOwedToOldInstances() throws Exception {
        try (Scope s = new Scope()) {
            s.install(metadata("open", "close"));
            Owner first = s.recreate();
            s.install(metadata("open", "otherClose"));
            Owner second = s.recreate();
            assertEquals(List.of("open:injected", "after", "close", "disposable"), first.calls);
            s.install(metadata(null, null));
            Owner third = s.recreate();
            assertEquals(List.of("open:injected", "after", "other-close", "disposable"), second.calls);
            s.context.close();
            assertEquals(List.of("after", "disposable"), third.calls);
        }
    }

    @Test void preDestroyOnlyRegistersOnNewInstanceAndPostConstructOnlyNeedsNoDestroyCallback() throws Exception {
        try (Scope s = new Scope()) {
            s.install(metadata(null, "close"));
            Owner first = s.recreate();
            s.install(metadata("open", null));
            Owner second = s.recreate();
            assertEquals(List.of("after", "close", "disposable"), first.calls);
            s.context.close();
            assertEquals(List.of("open:injected", "after", "disposable"), second.calls);
        }
    }

    @Test void initializationFailurePropagatesAndLeavesNoOwnedFailedInstance() throws Exception {
        try (Scope s = new Scope()) {
            s.install(metadata("failInit", "close"));
            s.context.getDefaultListableBeanFactory().destroySingleton("owner");
            Exception failure = assertThrows(org.springframework.beans.factory.BeanCreationException.class, s::owner);
            Throwable root = failure;
            while (root.getCause() != null) root = root.getCause();
            assertEquals("init-boom", root.getMessage());
            var processor = s.context.getDefaultListableBeanFactory().getBeanPostProcessors().stream()
                    .filter(p -> Proxy.isProxyClass(p.getClass()))
                    .map(Proxy::getInvocationHandler).filter(SpringLifecycleReloader.Processor.class::isInstance)
                    .map(SpringLifecycleReloader.Processor.class::cast).findFirst().orElseThrow();
            assertTrue(processor.instances.isEmpty());
        }
    }

    @Test void destroyExceptionDoesNotSkipDisposableBeanOrOtherBeans() throws Exception {
        try (Scope s = new Scope(c -> c.registerBean("other", Owner.class))) {
            s.install(metadata("open", "failDestroy"));
            var f = s.context.getDefaultListableBeanFactory();
            f.destroySingleton("owner"); f.destroySingleton("other");
            Owner first = (Owner) s.context.getBean("owner");
            Owner second = (Owner) s.context.getBean("other");
            s.context.close();
            assertEquals(List.of("open:injected", "after", "destroy-boom", "disposable"), first.calls);
            assertEquals(first.calls, second.calls);
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("destroy-boom")));
        }
    }

    @Test void processorRegistrationAndInvocationAreIdempotent() throws Exception {
        try (Scope s = new Scope()) {
            int original = s.context.getDefaultListableBeanFactory().getBeanPostProcessorCount();
            s.install(metadata("open", "close"));
            s.install(metadata("open", "close"));
            Owner fresh = s.recreate();
            assertEquals(original + 1, s.context.getDefaultListableBeanFactory().getBeanPostProcessorCount());
            DestructionAwareBeanPostProcessor processor = (DestructionAwareBeanPostProcessor) s.context
                    .getDefaultListableBeanFactory().getBeanPostProcessors().get(original);
            processor.postProcessBeforeInitialization(fresh, "owner");
            processor.postProcessBeforeDestruction(fresh, "owner");
            processor.postProcessBeforeDestruction(fresh, "owner");
            assertFalse(processor.requiresDestruction(fresh));
            s.context.close();
            assertEquals(List.of("open:injected", "after", "close", "disposable"), fresh.calls);
        }
    }

    @Test void factoriesAndEqualInstancesHaveIndependentOwnership() throws Exception {
        try (Scope a = new Scope(); Scope b = new Scope()) {
            a.install(metadata("open", "close"));
            b.install(metadata("open", "otherClose"));
            Owner first = a.recreate(), second = b.recreate();
            a.context.close();
            assertEquals(List.of("open:injected", "after", "close", "disposable"), first.calls);
            assertEquals(List.of("open:injected", "after"), second.calls);
            b.context.close();
            assertEquals(List.of("open:injected", "after", "other-close", "disposable"), second.calls);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"static", "arguments", "nonvoid", "extra-method", "extra-class", "inherited", "duplicate", "both"})
    void invalidMetadataDoesNotChangeProcessorListOrDestroyBean(String shape) throws Exception {
        try (Scope s = new Scope()) {
            Owner original = s.owner();
            int count = s.context.getDefaultListableBeanFactory().getBeanPostProcessorCount();
            ClassNode node = read(metadata("open", "close"));
            MethodNode init = method(node, "open");
            Set<String> added = ADDED;
            switch (shape) {
                case "static" -> init.access |= Opcodes.ACC_STATIC;
                case "arguments" -> { init.desc = "(I)V"; added = Set.of("open:(I)V", "close:()V"); }
                case "nonvoid" -> { init.desc = "()I"; added = Set.of("open:()I", "close:()V"); }
                case "extra-method" -> init.visibleAnnotations.add(new AnnotationNode("Lorg/springframework/transaction/annotation/Transactional;"));
                case "extra-class" -> node.visibleAnnotations.add(new AnnotationNode("Lorg/springframework/context/annotation/Scope;"));
                case "inherited" -> node.superName = "app/Base";
                case "duplicate" -> method(node, "otherClose").visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Ljavax/annotation/PreDestroy;")));
                case "both" -> init.visibleAnnotations.add(new AnnotationNode("Ljavax/annotation/PreDestroy;"));
            }
            assertNull(s.reloader.prepare(Owner.class, added, write(node)));
            assertSame(original, s.owner());
            assertEquals(List.of("after"), original.calls);
            assertEquals(count, s.context.getDefaultListableBeanFactory().getBeanPostProcessorCount());
            assertTrue(RestartLedger.size() > 0);
        }
    }

    @Test void allContextsArePreflightedBeforePublishingAnyCallbacks() throws Exception {
        try (Scope a = new Scope(); Scope b = new Scope(c ->
                c.getBeanDefinition("owner").setScope("prototype"))) {
            var reloader = new SpringLifecycleReloader(platform(a.context, b.context));
            int count = a.context.getDefaultListableBeanFactory().getBeanPostProcessorCount();
            Owner old = a.owner();
            assertNull(reloader.prepare(Owner.class, ADDED, metadata("open", "close")));
            assertEquals(count, a.context.getDefaultListableBeanFactory().getBeanPostProcessorCount());
            assertEquals(List.of("after"), a.recreate().calls);
            assertEquals(List.of("after", "disposable"), old.calls);
        }
    }

    @Test void customLifecycleAnnotationConfigurationIsRefused() throws Exception {
        try (Scope s = new Scope()) {
            s.context.getBean(CommonAnnotationBeanPostProcessor.class).setInitAnnotationType(Deprecated.class);
            assertNull(s.reloader.prepare(Owner.class, ADDED, metadata("open", "close")));
            assertEquals(List.of("after"), s.owner().calls);
        }
    }

    @Test void refusedSavePreservesPreviouslyInstalledCallbacks() throws Exception {
        try (Scope s = new Scope()) {
            s.install(metadata("open", "close"));
            Owner first = s.recreate();
            ClassNode invalid = read(metadata("open", "otherClose"));
            method(invalid, "open").access |= Opcodes.ACC_STATIC;
            assertNull(s.reloader.prepare(Owner.class, ADDED, write(invalid)));
            Owner second = s.recreate();
            assertEquals(List.of("open:injected", "after", "close", "disposable"), first.calls);
            s.context.close();
            assertEquals(first.calls, second.calls);
        }
    }

    @Test void restrictionsDoNotApplyToOriginalLifecycleMethods() throws Exception {
        try (Scope s = new Scope()) {
            s.context.getBean(CommonAnnotationBeanPostProcessor.class).setInitAnnotationType(Deprecated.class);
            var prepared = s.reloader.prepare(Owner.class, Set.of(), metadata("open", "close"));
            assertNotNull(prepared);
            assertTrue(prepared.install());
            assertEquals(List.of("after"), s.recreate().calls);
            assertEquals(0, RestartLedger.size());
        }
    }

    @Test void proxiedBeanIsRefusedBeforeDestroyingIt() throws Exception {
        try (Scope s = new Scope(c -> c.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                return name.equals("owner") ? new org.springframework.aop.framework.ProxyFactory(bean).getProxy() : bean;
            }
        }))) {
            Object original = s.context.getBean("owner");
            assertNull(s.reloader.prepare(Owner.class, ADDED, metadata("open", "close")));
            assertSame(original, s.context.getBean("owner"));
        }
    }

    @Test void configuredCallbackIsRefusedWithoutDoubleInitialization() throws Exception {
        try (Scope s = new Scope(c -> ((org.springframework.beans.factory.support.AbstractBeanDefinition)
                c.getBeanDefinition("owner")).setInitMethodName("open"))) {
            assertEquals(List.of("after", "open:injected"), s.owner().calls);
            assertNull(s.reloader.prepare(Owner.class, ADDED, metadata("open", "close")));
            assertEquals(List.of("after", "open:injected"), s.owner().calls);
        }
    }

    private static PlatformContext platform(Object... contexts) {
        return (PlatformContext) Proxy.newProxyInstance(PlatformContext.class.getClassLoader(),
                new Class<?>[]{PlatformContext.class}, (p,m,a) -> m.getName().equals("getAllApplicationContexts") ? List.of(contexts) : null);
    }

    private static byte[] metadata(String init, String destroy) throws Exception {
        try (var stream = Owner.class.getResourceAsStream("/" + Owner.class.getName().replace('.', '/') + ".class")) {
            ClassNode node = read(stream.readAllBytes());
            if (init != null) method(node, init).visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Ljavax/annotation/PostConstruct;")));
            if (destroy != null) method(node, destroy).visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Ljavax/annotation/PreDestroy;")));
            return write(node);
        }
    }
    private static ClassNode read(byte[] bytes) { var node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    private static byte[] write(ClassNode node) { var writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
    private static MethodNode method(ClassNode node, String name) { return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(); }
}
