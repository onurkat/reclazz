/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.*;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.context.annotation.*;
import java.lang.invoke.*;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AddedFullConfigurationTest {
    @Configuration public static class Config {
        public Object product() { return new Object(); }
        public Object withArgument(Object value) { return value; }
        private Object hidden() { return new Object(); }
        public final Object fixed() { return new Object(); }
        public static Object stat() { return new Object(); }
    }
    @AfterEach void reset() {
        AddedBeanBridge.publish(Config.class, Map.of()); RestartLedger.clear();
    }
    @Test void acceptsDefaultAndExplicitFullButRefusesUnsupportedInstanceShapes() throws Exception {
        var plan = plan("product", "stat");
        assertTrue(plan.full()); assertEquals(2, plan.factories().size()); assertTrue(plan.refused().isEmpty());
        for (String name : List.of("withArgument", "hidden", "fixed"))
            assertEquals(1, plan(name).refused().size(), name);
        ClassNode node = read(); node.visibleAnnotations.get(0).values = new ArrayList<>(List.of("proxyBeanMethods", true));
        assertTrue(AddedBeanAdapter.inspect(write(node), Set.of()).full());
    }
    @Test void recognizesOnlyTheDirectNativeEnhancerAndItsOwnFactory() throws Exception {
        try (var one = context(); var two = context()) {
            Object config = one.getBean(Config.class);
            assertTrue(SpringConfigurationCalls.matches(config, Config.class, one.getBeanFactory(), true));
            assertFalse(SpringConfigurationCalls.matches(config, Config.class, two.getBeanFactory(), true));
            assertThrows(IllegalStateException.class, () -> SpringConfigurationCalls.matches(new Config(), Config.class, one.getBeanFactory(), true));
            var proxy = new org.springframework.aop.framework.ProxyFactory(config); proxy.setProxyTargetClass(true);
            assertThrows(IllegalStateException.class, () -> SpringConfigurationCalls.matches(proxy.getProxy(), Config.class, one.getBeanFactory(), true));
        }
    }
    @Test void nativeFactoriesResolvePerContextAliasAndRecreatedSingleton() throws Throwable {
        try (var one = context(); var two = context()) {
            var reloader = reloader(one, two);
            byte[] bytes = annotated("product", "stat");
            assertTrue(reloader.reloadBeanMethods(Config.class, keys(bytes), bytes));
            Object first = one.getBean("product"), other = two.getBean("product");
            assertNotSame(first, other);
            assertSame(first, one.getBean("alias"));
            var raw = new ConstantCallSite(MethodHandles.lookup().findVirtual(Config.class, "product", MethodType.methodType(Object.class)));
            String key = InjectedNames.siteKey("product", InjectedNames.descHash("()Ljava/lang/Object;"));
            var reference = AddedBeanBridge.call(Config.class, key, raw).dynamicInvoker();
            Config held = one.getBean(Config.class);
            assertSame(first, reference.invokeWithArguments(held));
            assertSame(other, reference.invokeWithArguments(two.getBean(Config.class)));
            one.getDefaultListableBeanFactory().destroySingleton("product");
            Object replacement = reference.invokeWithArguments(held);
            assertNotSame(first, replacement); assertSame(replacement, one.getBean("alias"));
            assertNotSame(replacement, reference.invokeWithArguments(new Config()));
            assertNotNull(one.getBean("stat"));
            assertTrue(reloader.reloadBeanMethods(Config.class, Set.of(), original()));
            assertFalse(one.containsBean("product")); assertFalse(one.containsBean("alias"));
            assertNotSame(replacement, reference.invokeWithArguments(held));
        }
    }
    @Test void factoryBodyBypassesTheReferenceRoute() throws Throwable {
        try (var context = context()) {
            var factory = plan("product").factories().get(0);
            AddedBeanBridge.publish(Config.class, Map.of(InjectedNames.siteKey("product", InjectedNames.descHash(factory.method().desc)),
                    (receiver, args, raw) -> { throw new AssertionError("factory body entered reference route"); }));
            var supplier = AddedBeanAdapter.create(Config.class, () -> context.getBean(Config.class), factory);
            assertNotNull(supplier.get());
        }
    }
    private static AnnotationConfigApplicationContext context() {
        LookupCapture.store(Config.class, MethodHandles.lookup());
        return new AnnotationConfigApplicationContext(Config.class);
    }
    private static SpringAddedBeanReloader reloader(AnnotationConfigApplicationContext... contexts) {
        PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(PlatformContext.class.getClassLoader(), new Class[]{PlatformContext.class},
                (p, m, a) -> m.getName().equals("getAllApplicationContexts") ? List.of(contexts) : null);
        return new SpringAddedBeanReloader(platform);
    }
    private static AddedBeanAdapter.Plan plan(String... names) throws Exception {
        byte[] bytes = annotated(names); return AddedBeanAdapter.inspect(bytes, keys(bytes));
    }
    private static Set<String> keys(byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        Set<String> keys = new HashSet<>();
        for (MethodNode m : node.methods) if (m.visibleAnnotations != null) keys.add(m.name + ":" + m.desc);
        return keys;
    }
    private static byte[] original() throws Exception {
        try (var in = Config.class.getResourceAsStream("/" + Config.class.getName().replace('.', '/') + ".class")) {
            return Objects.requireNonNull(in).readAllBytes();
        }
    }
    private static ClassNode read() throws Exception { var node = new ClassNode(); new ClassReader(original()).accept(node, 0); return node; }
    private static byte[] write(ClassNode node) { var writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
    private static byte[] annotated(String... names) throws Exception {
        var node = read();
        for (MethodNode m : node.methods) if (List.of(names).contains(m.name)) {
            var bean = new AnnotationNode(AddedBeanAdapter.BEAN);
            if (m.name.equals("product")) bean.values = new ArrayList<>(List.of("name", List.of("product", "alias")));
            m.visibleAnnotations = new ArrayList<>(List.of(bean));
        }
        return write(node);
    }
}
