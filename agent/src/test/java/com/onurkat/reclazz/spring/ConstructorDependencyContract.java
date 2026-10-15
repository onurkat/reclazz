/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.support.LookupOverride;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** The same real Spring constructor contract runs on isolated Spring 5 and 6 graphs. */
public abstract class ConstructorDependencyContract {
    public static class Helper { static int next = 10; final int seed = next; }
    public static class Owner {
        final Helper helper; final int captured;
        public Owner(Helper helper) { this.helper = helper; captured = helper.seed; }
    }
    public static class Unrelated { }

    static AnnotationConfigApplicationContext context() {
        Helper.next = 10;
        return new AnnotationConfigApplicationContext(Helper.class, Owner.class, Unrelated.class);
    }
    static SpringBeanReloader reloader(Object... contexts) {
        PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(
                PlatformContext.class.getClassLoader(), new Class<?>[]{PlatformContext.class},
                (p,m,a) -> m.getName().equals("getAllApplicationContexts") ? List.of(contexts) : null);
        return new SpringBeanReloader(platform);
    }
    static String name(AnnotationConfigApplicationContext c, Class<?> type) {
        return c.getBeanNamesForType(type)[0];
    }
    static void edge(AnnotationConfigApplicationContext c) {
        var f = c.getDefaultListableBeanFactory();
        assertTrue(Arrays.asList(f.getDependentBeans(name(c, Helper.class))).contains(name(c, Owner.class)),
                "native dependency edge must survive owner reconstruction");
        assertTrue(Arrays.asList(f.getDependenciesForBean(name(c, Owner.class))).contains(name(c, Helper.class)));
    }
    @Test void nativeCachedRecreationLosesEdge() {
        try (var c = context()) {
            edge(c);
            c.getDefaultListableBeanFactory().destroySingleton(name(c, Owner.class));
            assertEquals(10, c.getBean(Owner.class).captured);
            assertArrayEquals(new String[0], c.getDefaultListableBeanFactory().getDependentBeans(name(c, Helper.class)),
                    "native cache replay control; no agent repair is invoked");
        }
    }
    @Test void repeatedOwnerRecreationKeepsNativeDependencyEdges() {
        try (var c = context()) {
            Object unrelated = c.getBean(Unrelated.class);
            Object unrelatedDefinition = c.getBeanFactory().getMergedBeanDefinition(name(c, Unrelated.class));
            Owner previous = c.getBean(Owner.class);
            for (int save = 0; save < 3; save++) {
                reloader(c).refreshBean(Owner.class.getName(), Owner.class);
                Owner fresh = c.getBean(Owner.class);
                assertNotSame(previous, fresh);
                assertEquals(10, fresh.captured);
                assertSame(c.getBean(Helper.class), fresh.helper);
                edge(c);
                assertSame(unrelated, c.getBean(Unrelated.class));
                assertSame(unrelatedDefinition, c.getBeanFactory().getMergedBeanDefinition(name(c, Unrelated.class)),
                        "unrelated merged metadata must not be flushed");
                previous = fresh;
            }
        }
    }
    @Test void repeatedDependencyRecreationUsesCurrentConstructorValue() {
        try (var c = context()) {
            Object unrelated = c.getBean(Unrelated.class);
            for (int seed : new int[]{20, 30, 40}) {
                // The direct owner reload warms its constructor just like an added callback save.
                reloader(c).refreshBean(Owner.class.getName(), Owner.class);
                Helper.next = seed;
                c.getDefaultListableBeanFactory().destroySingleton(name(c, Helper.class));
                assertNull(c.getDefaultListableBeanFactory().getSingleton(name(c, Owner.class)),
                        "native helper destruction must retire its registered dependent owner");
                Owner fresh = c.getBean(Owner.class);
                assertEquals(seed, fresh.captured);
                assertSame(c.getBean(Helper.class), fresh.helper);
                assertSame(unrelated, c.getBean(Unrelated.class));
            }
        }
    }
    @Test void supportedPreflightDoesNotChangeAnyLiveState() throws Exception {
        try (var c = context()) {
            var f = c.getDefaultListableBeanFactory();
            String n = name(c, Owner.class);
            Object old = f.getSingleton(n), definition = f.getMergedBeanDefinition(n);
            var prepared = SpringConstructorDependencies.prepare(List.of(c), c, n);
            assertNotNull(prepared);
            assertSame(old, f.getSingleton(n));
            assertSame(definition, f.getMergedBeanDefinition(n));
            edge(c);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"supplier", "factoryMethod", "factoryBean", "arguments",
            "methodOverride", "prototype", "parentDefinition", "definitionQualifier"})
    void unsupportedDefinitionPreflightLeavesLiveOwnerUntouched(String policy) {
        try (var c = context()) {
            var f = c.getDefaultListableBeanFactory(); String n = name(c, Owner.class);
            Object old = f.getSingleton(n), merged = f.getMergedBeanDefinition(n);
            var d = (AbstractBeanDefinition) c.getBeanDefinition(n);
            switch (policy) {
                case "supplier" -> d.setInstanceSupplier(() -> old);
                case "factoryMethod" -> d.setFactoryMethodName("create");
                case "factoryBean" -> d.setFactoryBeanName("creator");
                case "arguments" -> d.getConstructorArgumentValues().addIndexedArgumentValue(0, new Helper());
                case "methodOverride" -> d.getMethodOverrides().addOverride(new LookupOverride("lookup", "helper"));
                case "prototype" -> d.setScope("prototype");
                case "parentDefinition" -> d.setParentName("base");
                case "definitionQualifier" -> d.addQualifier(new AutowireCandidateQualifier("custom"));
                default -> fail(policy);
            }
            assertNull(SpringConstructorDependencies.prepare(List.of(c), c, n));
            assertSame(old, f.getSingleton(n));
            assertSame(merged, f.getMergedBeanDefinition(n));
            edge(c);
        }
    }
    @Test void separateSameNameContextsDoNotReceiveCacheRepair() {
        try (var a = context(); var b = context()) {
            Object left = a.getBean(Owner.class), right = b.getBean(Owner.class);
            assertEquals(name(a, Owner.class), name(b, Owner.class));
            for (var c : List.of(a,b)) {
                assertNull(SpringConstructorDependencies.prepare(List.of(a,b), c, name(c, Owner.class)));
                edge(c);
            }
            assertSame(left, a.getBean(Owner.class)); assertSame(right, b.getBean(Owner.class));
        }
    }
    @Test void parentContextDoesNotReceiveCacheRepair() {
        try (var parent = context(); var child = new AnnotationConfigApplicationContext()) {
            child.setParent(parent); child.register(Helper.class, Owner.class); child.refresh();
            Object original = child.getBean(Owner.class);
            assertNull(SpringConstructorDependencies.prepare(List.of(child), child, name(child, Owner.class)));
            assertSame(original, child.getBean(Owner.class)); edge(child); edge(parent);
        }
    }
    @Test void proxyDoesNotReceiveCacheRepair() {
        try (var c = new AnnotationConfigApplicationContext()) {
            c.getBeanFactory().addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String n) {
                    return bean instanceof Owner ? new ProxyFactory(bean).getProxy() : bean;
                }
            });
            c.register(Helper.class, Owner.class); c.refresh();
            String n = name(c, Owner.class); Object original = c.getBean(n);
            assertNotEquals(Owner.class, original.getClass());
            assertNull(SpringConstructorDependencies.prepare(List.of(c), c, n));
            assertSame(original, c.getBean(n)); edge(c);
        }
    }
    public static class CustomDefinition extends RootBeanDefinition {
        public CustomDefinition() { super(Owner.class); }
    }
    @Test void customDefinitionDoesNotReceiveCacheRepair() {
        try (var c = new AnnotationConfigApplicationContext()) {
            c.register(Helper.class); c.registerBeanDefinition("owner", new CustomDefinition()); c.refresh();
            Object old = c.getBean("owner");
            assertNull(SpringConstructorDependencies.prepare(List.of(c), c, "owner"));
            assertSame(old, c.getBean("owner"));
        }
    }
    @Test void manualSingletonDoesNotReceiveCacheRepair() {
        try (var c = context()) {
            Owner manual = new Owner(c.getBean(Helper.class));
            c.getBeanFactory().registerSingleton("manual", manual);
            assertNull(SpringConstructorDependencies.prepare(List.of(c), c, "manual"));
            assertSame(manual, c.getBean("manual"));
        }
    }
    @Test void changedOwnerAfterPreparationIsRejectedBeforeInvalidation() throws Exception {
        try (var c = context()) {
            String n = name(c, Owner.class); var f = c.getDefaultListableBeanFactory();
            var prepared = SpringConstructorDependencies.prepare(List.of(c), c, n); assertNotNull(prepared);
            f.destroySingleton(n); Object fresh = c.getBean(n), merged = f.getMergedBeanDefinition(n);
            assertThrows(IllegalStateException.class, prepared::apply);
            assertSame(fresh, f.getSingleton(n)); assertSame(merged, f.getMergedBeanDefinition(n));
        }
    }
    @Test void changedDefinitionAfterPreparationIsRejectedBeforeInvalidation() throws Exception {
        try (var c = context()) {
            String n = name(c, Owner.class); var f = c.getDefaultListableBeanFactory();
            Object original = c.getBean(n), merged = f.getMergedBeanDefinition(n);
            var prepared = SpringConstructorDependencies.prepare(List.of(c), c, n); assertNotNull(prepared);
            ((AbstractBeanDefinition) c.getBeanDefinition(n)).setInstanceSupplier(() -> original);
            assertThrows(IllegalStateException.class, prepared::apply);
            assertSame(original, f.getSingleton(n)); assertSame(merged, f.getMergedBeanDefinition(n)); edge(c);
        }
    }
    public static class MultipleConstructors {
        public MultipleConstructors() { }
        @org.springframework.beans.factory.annotation.Autowired
        public MultipleConstructors(Helper helper) { }
    }
    @Test void multipleConstructorsDoNotReceiveCacheRepair() {
        try (var c = new AnnotationConfigApplicationContext(Helper.class, MultipleConstructors.class)) {
            String n = name(c, MultipleConstructors.class); Object original = c.getBean(n);
            assertNull(SpringConstructorDependencies.prepare(List.of(c), c, n));
            assertSame(original, c.getBean(n));
            assertArrayEquals(new String[]{n}, c.getBeanFactory().getDependentBeans(name(c, Helper.class)));
        }
    }
    public static class Creator implements org.springframework.beans.factory.FactoryBean<Owner> {
        private final Owner product;
        public Creator(Helper helper) { product = new Owner(helper); }
        @Override public Owner getObject() { return product; }
        @Override public Class<?> getObjectType() { return Owner.class; }
    }
    @Test void factoryBeanDoesNotReceiveCacheRepair() {
        try (var c = new AnnotationConfigApplicationContext()) {
            c.register(Helper.class); c.registerBeanDefinition("creator", new RootBeanDefinition(Creator.class)); c.refresh();
            Object product = c.getBean("creator"), creator = c.getBean("&creator");
            assertNull(SpringConstructorDependencies.prepare(List.of(c), c, "creator"));
            assertSame(product, c.getBean("creator")); assertSame(creator, c.getBean("&creator"));
        }
    }
    public static class QualifiedOwner {
        final Helper helper;
        public QualifiedOwner(@Qualifier("blue") Helper h) { helper = h; }
    }
    @Test void stableQualifierUsesNativeSelection() {
        try (var c = new AnnotationConfigApplicationContext()) {
            c.registerBeanDefinition("blue", new RootBeanDefinition(Helper.class));
            c.registerBeanDefinition("red", new RootBeanDefinition(Helper.class));
            c.register(QualifiedOwner.class); c.refresh(); String n = name(c, QualifiedOwner.class);
            for (int save = 0; save < 3; save++) {
                reloader(c).refreshBean(QualifiedOwner.class.getName(), QualifiedOwner.class);
                assertSame(c.getBean("blue"), c.getBean(QualifiedOwner.class).helper);
                assertArrayEquals(new String[]{n}, c.getBeanFactory().getDependentBeans("blue"));
                assertArrayEquals(new String[0], c.getBeanFactory().getDependentBeans("red"));
            }
        }
    }
    @Test void nativeCandidateRemovalDoesNotRestoreStaleEdges() {
        try (var c = new AnnotationConfigApplicationContext()) {
            var blue = new RootBeanDefinition(Helper.class); blue.setPrimary(true);
            c.registerBeanDefinition("blue", blue);
            c.registerBeanDefinition("red", new RootBeanDefinition(Helper.class));
            c.register(Owner.class); c.refresh();
            reloader(c).refreshBean(Owner.class.getName(), Owner.class);
            String n = name(c, Owner.class);
            c.removeBeanDefinition("blue");
            assertNull(c.getBeanFactory().getSingleton(n));
            c.getBean(n);
            reloader(c).refreshBean(Owner.class.getName(), Owner.class);
            assertSame(c.getBean("red"), c.getBean(Owner.class).helper);
            assertArrayEquals(new String[0], c.getBeanFactory().getDependentBeans("blue"));
            assertArrayEquals(new String[]{n}, c.getBeanFactory().getDependentBeans("red"));
            assertArrayEquals(new String[]{"red"}, c.getBeanFactory().getDependenciesForBean(n));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"constructor", "qualifier"})
    @SuppressWarnings("unchecked")
    void actualConstructorAnnotationEditRefusesCacheRepair(String edit) throws Exception {
        var instrumentation = net.bytebuddy.agent.ByteBuddyAgent.install();
        try (var c = context()) {
            String n = name(c, Owner.class); Object old = c.getBean(n);
            assertNotNull(SpringConstructorDependencies.prepare(List.of(c), c, n));
            // Follow the existing SpringAopReloaderTest pattern: transform after JaCoCo,
            // keeping its injected members through both the edit and restoration.
            var transformer = new java.lang.instrument.ClassFileTransformer() {
                @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                        java.security.ProtectionDomain domain, byte[] bytes) {
                    if (type != Owner.class) return null;
                    var node = new org.objectweb.asm.tree.ClassNode();
                    new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
                    var constructor = node.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
                    if (edit.equals("constructor")) {
                        constructor.visibleAnnotations = new java.util.ArrayList<>(List.of(
                                new org.objectweb.asm.tree.AnnotationNode("Lorg/springframework/beans/factory/annotation/Autowired;")));
                    } else {
                        var qualifier = new org.objectweb.asm.tree.AnnotationNode("Lorg/springframework/beans/factory/annotation/Qualifier;");
                        qualifier.values = new java.util.ArrayList<>(List.of("value", "missing"));
                        constructor.visibleParameterAnnotations = new List[]{new java.util.ArrayList<>(List.of(qualifier))};
                    }
                    var writer = new org.objectweb.asm.ClassWriter(0); node.accept(writer);
                    return writer.toByteArray();
                }
            };
            instrumentation.addTransformer(transformer, true);
            try {
                instrumentation.retransformClasses(Owner.class);
                var live = Owner.class.getDeclaredConstructors()[0];
                if (edit.equals("constructor")) assertNotNull(live.getAnnotation(org.springframework.beans.factory.annotation.Autowired.class));
                else assertEquals("missing", live.getParameters()[0].getAnnotation(Qualifier.class).value());
                assertNull(SpringConstructorDependencies.prepare(List.of(c), c, n));
                assertSame(old, c.getBeanFactory().getSingleton(n)); edge(c);
            } finally {
                instrumentation.removeTransformer(transformer);
                instrumentation.retransformClasses(Owner.class);
            }
        }
    }

}
