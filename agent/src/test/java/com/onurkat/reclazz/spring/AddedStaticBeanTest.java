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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AddedStaticBeanTest {
    public interface Transport { String name(); }
    public record Wire(String name) implements Transport { }
    public record Pair(Transport first, Transport second) { }
    public static class Product implements AutoCloseable {
        final Transport transport;
        int initialized;
        int closed;
        Product(Transport transport) { this.transport = transport; }
        public void initialize() { initialized++; }
        public void close() { closed++; }
    }
    @Configuration(proxyBeanMethods = false)
    public static class Config {
        static int calls;
        static boolean fail;
        static Object result;
        // Consumers precede providers, and both directions cross static/instance.
        private static Product client(Transport transport) { calls++; return new Product(transport); }
        public Product instanceClient(@Qualifier("fast") Transport transport) { return new Product(transport); }
        private static Pair qualified(@Qualifier("blue") Transport first, @Qualifier("red") Transport second) {
            return new Pair(first, second);
        }
        public static Pair named(Transport blue, Transport red) { return new Pair(blue, red); }
        private static Product plain() {
            calls++;
            if (fail) throw new IllegalStateException("static factory failure");
            return new Product(new Wire("static"));
        }
        public static Object erased() { return result; }
        public static Transport provider() { return new Wire("static provider"); }
        public Transport instanceProvider() { return new Wire("instance provider"); }
        public static Object primitive(int value) { return value; }
        public static Object array(Transport[] values) { return values; }
        public static Object generic(List<Transport> values) { return values; }
        public static Object rawList(List values) { return values; }
        public static Object value(@org.springframework.beans.factory.annotation.Value("${key}") String value) { return value; }
        public static native Object nativeFactory();
    }

    @BeforeEach void reset() { Config.calls = 0; Config.fail = false; Config.result = null; }
    @AfterEach void clear() { RestartLedger.clear(); Config.result = null; }

    @Test
    void privateStaticFactoryRegistersAndRunsLifecycle() throws Exception {
        Product product;
        try (Scope scope = new Scope()) {
            ClassNode source = beans("plain");
            annotation(source, "plain", AddedBeanAdapter.BEAN).values = List.of("initMethod", "initialize");
            scope.success(source);
            product = scope.context.getBean("plain", Product.class);
            assertSame(product, scope.context.getBean("plain"));
            assertEquals("static", product.transport.name());
            assertEquals(1, product.initialized);
            assertEquals(1, Config.calls);
            assertEquals(0, product.closed);
        }
        assertEquals(1, product.closed);
    }

    @Test
    void staticDelegateDoesNotResolveAnInstanceReceiver() throws Throwable {
        try (Scope scope = new Scope()) {
            var plan = AddedBeanAdapter.inspect(write(beans("plain")), signatures(beans("plain")));
            assertEquals(1, plan.factories().size());
            var adapter = AddedBeanAdapter.create(Config.class, () -> {
                fail("a static invocation must not request a configuration receiver");
                return null;
            }, plan.factories().get(0));
            assertTrue(adapter.getClass().isHidden());
            assertTrue(Arrays.stream(adapter.getClass().getDeclaredFields()).allMatch(f -> f.getName().startsWith("__reclazz$")));
            assertEquals("static", ((Product) adapter.get()).transport.name());
        }
    }

    @Test
    void requiredReferencesAndBothParameterQualifiersResolveThroughSpring() throws Exception {
        try (Scope scope = new Scope()) {
            scope.context.registerBean("blue", Transport.class, () -> new Wire("blue"));
            scope.context.registerBean("red", Transport.class, () -> new Wire("red"), d -> d.setPrimary(true));
            scope.success(beans("client", "qualified"));
            assertSame(scope.context.getBean("red"), scope.product("client").transport);
            Pair pair = scope.context.getBean("qualified", Pair.class);
            assertSame(scope.context.getBean("blue"), pair.first());
            assertSame(scope.context.getBean("red"), pair.second());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staticParameterNamesUseTheCorrectSlots(boolean debugOnly) throws Exception {
        try (Scope scope = new Scope()) {
            scope.context.registerBean("blue", Transport.class, () -> new Wire("blue"));
            scope.context.registerBean("red", Transport.class, () -> new Wire("red"));
            ClassNode source = beans("named");
            MethodNode method = method(source, "named");
            if (debugOnly) {
                method.parameters = null;
                assertTrue(method.localVariables.stream().anyMatch(v -> v.index == 0 && v.name.equals("blue")));
                assertTrue(method.localVariables.stream().anyMatch(v -> v.index == 1 && v.name.equals("red")));
            } else method.parameters = List.of(new ParameterNode("blue", 0), new ParameterNode("red", 0));
            scope.success(source);
            Pair pair = scope.context.getBean("named", Pair.class);
            assertSame(scope.context.getBean("blue"), pair.first());
            assertSame(scope.context.getBean("red"), pair.second());
        }
    }

    @Test
    void absentNamesRefuseAmbiguityAndMissingArgumentsDoNotCallFactory() throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = beans("client");
            method(source, "client").parameters = null;
            method(source, "client").localVariables = null;
            assertFalse(scope.reload(source));
            assertEquals(0, Config.calls);
            assertFalse(scope.context.containsBeanDefinition("client"));
            scope.context.registerBean("transport", Transport.class, () -> new Wire("one"));
            scope.context.registerBean("other", Transport.class, () -> new Wire("two"));
            assertFalse(scope.reload(source));
            assertEquals(0, Config.calls);
            assertFalse(scope.context.containsBeanDefinition("client"));
            scope.context.removeBeanDefinition("other");
            scope.success(source);
            assertSame(scope.context.getBean("transport"), scope.product("client").transport);
            assertEquals(1, Config.calls);
        }
    }

    @Test
    void sameSaveMixedFactoriesPreservePrimaryQualifierAndAliases() throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = beans("client", "instanceClient", "provider", "instanceProvider");
            method(source, "instanceProvider").visibleAnnotations.add(new AnnotationNode(Type.getDescriptor(Primary.class)));
            AnnotationNode qualifier = new AnnotationNode(Type.getDescriptor(Qualifier.class));
            qualifier.values = List.of("value", "fast");
            method(source, "provider").visibleAnnotations.add(qualifier);
            annotation(source, "provider", AddedBeanAdapter.BEAN).values = List.of("name", List.of("remote", "legacy"));
            scope.success(source);
            assertSame(scope.context.getBean("instanceProvider"), scope.product("client").transport);
            assertSame(scope.context.getBean("remote"), scope.product("instanceClient").transport);
            assertSame(scope.context.getBean("remote"), scope.context.getBean("legacy"));
            // Move primary onto the static provider, and select its alias from a static consumer.
            method(source, "instanceProvider").visibleAnnotations.removeIf(a -> a.desc.equals(Type.getDescriptor(Primary.class)));
            method(source, "provider").visibleAnnotations.add(new AnnotationNode(Type.getDescriptor(Primary.class)));
            method(source, "provider").visibleAnnotations.remove(qualifier);
            method(source, "instanceClient").visibleParameterAnnotations[0].get(0).values = List.of("value", "legacy");
            AnnotationNode alias = new AnnotationNode(Type.getDescriptor(Qualifier.class));
            alias.values = List.of("value", "legacy");
            @SuppressWarnings("unchecked") List<AnnotationNode>[] parameters = new List[]{List.of(alias)};
            method(source, "client").visibleParameterAnnotations = parameters;
            scope.success(source);
            assertSame(scope.context.getBean("remote"), scope.context.getBean(Transport.class));
            assertSame(scope.context.getBean("remote"), scope.product("client").transport);
        }
    }

    @Test
    void dependenciesRecreateWithCurrentObjectsAndKeepDestructionEdges() throws Exception {
        try (Scope scope = new Scope()) {
            scope.context.registerBean("wire", Transport.class, () -> new Wire("fresh"));
            ClassNode source = beans("client");
            scope.success(source);
            Product old = scope.product("client");
            var factory = scope.context.getDefaultListableBeanFactory();
            assertTrue(Arrays.asList(factory.getDependentBeans("wire")).contains("client"));
            factory.destroySingleton("wire");
            assertEquals(1, old.closed);
            Product next = scope.product("client");
            assertNotSame(old.transport, next.transport);
            assertSame(scope.context.getBean("wire"), next.transport);
            scope.success(source);
            assertEquals(1, next.closed);
        }
    }

    @Test
    void failureRecoveryAndRemovalCleanOnlyOwnedRegistrations() throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = beans("plain");
            annotation(source, "plain", AddedBeanAdapter.BEAN).values = List.of("name", List.of("product", "legacy"));
            Object original = new Object();
            scope.context.getBeanFactory().registerSingleton("original", original);
            scope.success(source);
            Product old = scope.product("product");
            Config.fail = true;
            assertFalse(scope.reload(source));
            assertEquals(1, old.closed);
            assertFalse(scope.context.containsBean("product"));
            assertFalse(scope.context.getDefaultListableBeanFactory().isAlias("legacy"));
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("static factory failure")));
            Config.fail = false;
            scope.success(source);
            Product next = scope.product("product");
            assertSame(next, scope.context.getBean("legacy"));
            scope.success(beans());
            assertEquals(1, next.closed);
            assertFalse(scope.context.containsBean("product"));
            assertFalse(scope.context.getDefaultListableBeanFactory().isAlias("legacy"));
            assertSame(original, scope.context.getBean("original"));
        }
    }

    @Test
    void nullAndInfrastructureHiddenBehindObjectAreStillRefused() throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = beans("erased");
            assertFalse(scope.reload(source));
            assertFalse(scope.context.containsBeanDefinition("erased"));
            Config.result = new org.springframework.beans.factory.config.BeanPostProcessor() { };
            assertFalse(scope.reload(source));
            assertFalse(scope.context.containsBeanDefinition("erased"));
            Config.result = new Product(new Wire("recovered"));
            scope.success(source);
            assertSame(Config.result, scope.context.getBean("erased"));
        }
    }

    @Test
    void separateContextsResolveTheirOwnArguments() throws Exception {
        try (Scope one = new Scope(); Scope two = new Scope()) {
            one.context.getBeanFactory().registerSingleton("wire", new Wire("one"));
            two.context.getBeanFactory().registerSingleton("wire", new Wire("two"));
            one.success(beans("client"));
            two.success(beans("client"));
            assertEquals("one", one.product("client").transport.name());
            assertEquals("two", two.product("client").transport.name());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"primitive", "array", "generic", "rawList", "value", "nativeFactory"})
    void unsupportedStaticFactoriesStillFailWithoutRegistration(String name) throws Exception {
        try (Scope scope = new Scope()) {
            assertFalse(scope.reload(beans(name)));
            assertFalse(scope.context.containsBeanDefinition(name));
            assertTrue(RestartLedger.size() > 0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "ambiguous", "prototype", "proxy"})
    void staticFactoriesStillRequireOneUnproxiedSingletonConfiguration(String kind) throws Exception {
        try (Scope scope = new Scope()) {
            var factory = scope.context.getDefaultListableBeanFactory();
            if (kind.equals("missing")) scope.context.removeBeanDefinition("config");
            if (kind.equals("ambiguous")) factory.registerSingleton("another", new Config());
            if (kind.equals("prototype")) {
                scope.context.removeBeanDefinition("config");
                var definition = new RootBeanDefinition(Config.class);
                definition.setScope("prototype");
                scope.context.registerBeanDefinition("config", definition);
            }
            if (kind.equals("proxy")) {
                factory.destroySingleton("config");
                var proxy = new org.springframework.aop.framework.ProxyFactory(new Config());
                proxy.setProxyTargetClass(true);
                factory.registerSingleton("config", proxy.getProxy());
            }
            assertFalse(scope.reload(beans("plain")));
            assertFalse(scope.context.containsBeanDefinition("plain"));
            assertEquals(0, Config.calls);
        }
    }

    private static final class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final SpringAddedBeanReloader reloader;
        Scope() throws Exception {
            LookupCapture.store(Config.class, MethodHandles.privateLookupIn(Config.class, MethodHandles.lookup()));
            context.registerBean("config", Config.class);
            context.refresh();
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PlatformContext.class}, (p, m, a) -> m.getName().equals("getAllApplicationContexts") ? List.of(context) : null);
            reloader = new SpringAddedBeanReloader(platform);
        }
        boolean reload(ClassNode source) { return reloader.reloadBeanMethods(Config.class, signatures(source), write(source)); }
        void success(ClassNode source) { assertTrue(reload(source), RestartLedger.digest().toString()); }
        Product product(String name) { return context.getBean(name, Product.class); }
        @Override public void close() { context.close(); }
    }
    private static ClassNode beans(String... names) throws Exception {
        try (var in = Config.class.getResourceAsStream("/" + Config.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(in);
            ClassNode source = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(source, 0);
            for (String name : names) method(source, name).visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(AddedBeanAdapter.BEAN)));
            return source;
        }
    }
    private static Set<String> signatures(ClassNode source) {
        Set<String> added = new HashSet<>();
        for (var m : source.methods) added.add(m.name + ":" + m.desc);
        return added;
    }
    private static MethodNode method(ClassNode source, String name) {
        return source.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static AnnotationNode annotation(ClassNode source, String name, String desc) {
        return method(source, name).visibleAnnotations.stream().filter(a -> a.desc.equals(desc)).findFirst().orElseThrow();
    }
    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
