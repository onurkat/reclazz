/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;

import java.lang.annotation.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AddedBeanBootConditionsTest {
    static int made, closed;
    @BeforeEach void reset() { made = closed = 0; RestartLedger.clear(); }
    @AfterEach void clear() { RestartLedger.clear(); }
    public static class Product implements AutoCloseable {
        public Product() { made++; }
        @Override public void close() { closed++; }
    }
    @Configuration(proxyBeanMethods = false)
    public static class Config {
        public Product product() { return new Product(); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class PropertyConfig {
        @Bean({"product", "alias"})
        @ConditionalOnProperty(prefix = "feature", name = {"enabled", "licensed"}, havingValue = "true")
        public Product product() { return new Product(); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class DefaultPropertyConfig {
        @Bean({"product", "alias"}) @ConditionalOnProperty("feature.enabled")
        public Product product() { return new Product(); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class MissingPropertyConfig {
        @Bean({"product", "alias"}) @ConditionalOnProperty(name = "feature.enabled", matchIfMissing = true)
        public Product product() { return new Product(); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class PresentConfig {
        @Bean({"product", "alias"}) @ConditionalOnBean
        public Product product() { return new Product(); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class MissingConfig {
        @Bean({"product", "alias"}) @ConditionalOnMissingBean
        public Product product() { return new Product(); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class NamedConfig {
        @Bean({"product", "alias"}) @ConditionalOnBean(name = "marker")
        public Product product() { return new Product(); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class TypedConfig {
        @Bean({"product", "alias"}) @ConditionalOnBean(String.class)
        public Product product() { return new Product(); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class LocalConfig {
        @Bean({"product", "alias"}) @ConditionalOnBean(value = String.class, search = SearchStrategy.CURRENT)
        public Product product() { return new Product(); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class IgnoredConfig {
        @Bean({"product", "alias"}) @ConditionalOnMissingBean(value = Product.class, ignored = IgnoredProduct.class)
        public Product product() { return new Product(); }
    }
    public static class IgnoredProduct extends Product { }
    @Configuration(proxyBeanMethods = false)
    public static class CombinedConfig {
        @Bean({"product", "alias"}) @Profile("enabled")
        @ConditionalOnBean(String.class) @ConditionalOnMissingBean(Product.class)
        @ConditionalOnProperty("feature.enabled")
        public Product product() { return new Product(); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class LazyConfig {
        @Bean({"product", "alias"}) @Lazy @ConditionalOnMissingBean
        public Product product() { return new Product(); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class InvalidConfig {
        @Bean({"product", "alias"}) @ConditionalOnProperty
        public Product product() { return new Product(); }
    }
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    @ConditionalOnProperty("feature.enabled")
    public @interface CustomCondition { }
    @Configuration(proxyBeanMethods = false)
    public static class CustomConfig {
        @Bean({"product", "alias"}) @CustomCondition
        public Product product() { return new Product(); }
    }

    @Test void nativePropertySemanticsMatchAddedFactories() throws Exception {
        for (String value : List.of("<missing>", "false", "true", "TRUE", "other")) {
            Consumer<AnnotationConfigApplicationContext> setup = c -> properties(c,
                    value.equals("<missing>") ? Map.of() : Map.of("feature.enabled", value, "feature.licensed", "true"));
            compare(PropertyConfig.class, setup, value.equalsIgnoreCase("true"));
            compare(DefaultPropertyConfig.class, setup, !Set.of("<missing>", "false").contains(value));
            compare(MissingPropertyConfig.class, setup, !value.equals("false"));
        }
        compare(PropertyConfig.class, c -> properties(c, Map.of("feature.enabled", "true")), false);
    }

    @Test void beanPresenceAndInferredReturnTypeMatchNativeStartup() throws Exception {
        for (boolean present : List.of(false, true)) {
            Consumer<AnnotationConfigApplicationContext> setup = c -> {
                if (present) c.registerBean("existing", Product.class);
            };
            compare(PresentConfig.class, setup, present);
            compare(MissingConfig.class, setup, !present);
            Consumer<AnnotationConfigApplicationContext> named = c -> {
                if (present) c.registerBean("marker", String.class, () -> "external");
            };
            compare(NamedConfig.class, named, present);
            compare(TypedConfig.class, named, present);
        }
    }

    @Test void parentSearchAndIgnoredTypes() throws Exception {
        try (var parent = new AnnotationConfigApplicationContext()) {
            parent.registerBean("marker", String.class, () -> "parent"); parent.refresh();
            compare(TypedConfig.class, c -> c.setParent(parent), true);
            compare(LocalConfig.class, c -> c.setParent(parent), false);
            assertEquals("parent", parent.getBean("marker"));
        }
        compare(IgnoredConfig.class, c -> c.registerBean("ignored", IgnoredProduct.class), true);
    }

    @Test void repeatedSavesRetireOnlyOwnedProducts() throws Exception {
        try (Scope scope = new Scope(c -> { })) {
            byte[] bytes = bytes(MissingConfig.class);
            assertTrue(scope.reload(bytes), RestartLedger.digest().toString());
            Object first = scope.context.getBean("product");
            assertSame(first, scope.context.getBean("alias"));
            assertTrue(scope.reload(bytes), "the previous owned definition must not veto its replacement");
            assertNotSame(first, scope.context.getBean("product"));
            assertEquals(2, made); assertEquals(1, closed);
            scope.context.registerBean("external", Product.class);
            Object external = scope.context.getBean("external");
            assertTrue(scope.reload(bytes));
            assertFalse(scope.context.containsBean("product")); assertFalse(scope.context.isAlias("alias"));
            assertEquals(2, closed); assertSame(external, scope.context.getBean("external"));
            scope.context.removeBeanDefinition("external");
            assertTrue(scope.reload(bytes));
            assertTrue(scope.context.containsBean("product"));
            assertTrue(scope.reload(bytes(Config.class)), "removing Bean annotation retires owned definition");
            assertFalse(scope.context.containsBean("product")); assertFalse(scope.context.isAlias("alias"));
            assertEquals(4, made); assertEquals(4, closed);
        }
    }

    @Test void combinedConditionsAndProfile() throws Exception {
        for (int missing = 0; missing < 4; missing++) {
            int gate = missing;
            compare(CombinedConfig.class, c -> {
                if (gate != 1) c.getEnvironment().setActiveProfiles("enabled");
                if (gate != 2) c.registerBean("marker", String.class, () -> "marker");
                if (gate != 3) properties(c, Map.of("feature.enabled", "true"));
            }, gate == 0);
        }
    }

    @Test void propertyChangeAppliesOnNextConfigurationSaveAndErrorsRetireOldProduct() throws Exception {
        Map<String, Object> values = new HashMap<>(Map.of("feature.enabled", "true"));
        try (Scope scope = new Scope(c -> properties(c, values))) {
            assertTrue(scope.reload(bytes(DefaultPropertyConfig.class)));
            Object first = scope.context.getBean("product");
            values.put("feature.enabled", "false");
            assertSame(first, scope.context.getBean("product"), "property-only changes do not replay factories");
            assertTrue(scope.reload(bytes(DefaultPropertyConfig.class)));
            assertFalse(scope.context.containsBean("product")); assertEquals(1, closed);
            values.put("feature.enabled", "true");
            assertTrue(scope.reload(bytes(DefaultPropertyConfig.class)));
            assertFalse(scope.reload(bytes(InvalidConfig.class)), "native invalid annotation must fail");
            assertFalse(scope.context.containsBean("product")); assertFalse(scope.context.isAlias("alias"));
            assertEquals(2, made); assertEquals(2, closed);
            assertTrue(RestartLedger.digest().toString().contains("name"));
        }
    }

    @Test void missingBootAnnotationFailsClosed() throws Exception {
        try (Scope scope = new Scope(c -> properties(c, Map.of("feature.enabled", "true")))) {
            assertTrue(scope.reload(bytes(DefaultPropertyConfig.class)));
            scope.context.getBeanFactory().setBeanClassLoader(new ClassLoader(getClass().getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.equals(ConditionalOnProperty.class.getName())) throw new ClassNotFoundException(name);
                    return super.loadClass(name, resolve);
                }
            });
            assertFalse(scope.reload(bytes(DefaultPropertyConfig.class)));
            assertFalse(scope.context.containsBean("product")); assertFalse(scope.context.isAlias("alias"));
            assertEquals(1, made); assertEquals(1, closed);
        }
    }

    @Test void unsupportedAnnotationsStillRefuse() throws Exception {
        try (Scope scope = new Scope(c -> properties(c, Map.of("feature.enabled", "true")))) {
            assertFalse(scope.reload(bytes(CustomConfig.class)));
            assertFalse(scope.context.containsBean("product")); assertEquals(0, made);
        }
    }

    @Test void lazyDefinitionsParticipateWithoutPrematureCreation() throws Exception {
        try (Scope scope = new Scope(c -> {
            var definition = new org.springframework.beans.factory.support.RootBeanDefinition(Product.class);
            definition.setLazyInit(true); c.registerBeanDefinition("external", definition);
        })) {
            assertEquals(0, made);
            assertTrue(scope.reload(bytes(LazyConfig.class)));
            assertFalse(scope.context.containsBean("product")); assertEquals(0, made);
            scope.context.removeBeanDefinition("external");
            assertTrue(scope.reload(bytes(LazyConfig.class)));
            assertTrue(scope.context.containsBean("product")); assertEquals(0, made);
            assertSame(scope.context.getBean("product"), scope.context.getBean("alias"));
            assertEquals(1, made);
        }
        assertEquals(1, closed);
    }

    // A pure native probe stays green even before the production change.
    @Test void nativeBootConditionsAreActive() {
        try (var c = new AnnotationConfigApplicationContext()) {
            properties(c, Map.of("feature.enabled", "false"));
            c.register(DefaultPropertyConfig.class); c.refresh();
            assertFalse(c.containsBean("product")); assertEquals(0, made);
        }
        try (var c = new AnnotationConfigApplicationContext(MissingConfig.class)) {
            assertSame(c.getBean("product"), c.getBean("alias")); assertEquals(1, made);
        }
        assertEquals(1, closed);
    }

    private static void compare(Class<?> fixture, Consumer<AnnotationConfigApplicationContext> setup,
                                boolean expected) throws Exception {
        try (var nativeContext = new AnnotationConfigApplicationContext()) {
            setup.accept(nativeContext); nativeContext.register(fixture); nativeContext.refresh();
            assertEquals(expected, nativeContext.containsBean("product"), "native " + fixture.getSimpleName());
            assertEquals(expected, nativeContext.isAlias("alias"));
        }
        try (Scope scope = new Scope(setup)) {
            assertTrue(scope.reload(bytes(fixture)), RestartLedger.digest().toString());
            assertEquals(expected, scope.context.containsBean("product"), "added " + fixture.getSimpleName());
            assertEquals(expected, scope.context.isAlias("alias"));
        }
    }
    private static void properties(AnnotationConfigApplicationContext c, Map<String, Object> values) {
        c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", values));
    }
    private static class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final SpringAddedBeanReloader reloader;
        Scope(Consumer<AnnotationConfigApplicationContext> setup) throws Exception {
            LookupCapture.store(Config.class, MethodHandles.privateLookupIn(Config.class, MethodHandles.lookup()));
            setup.accept(context); context.registerBean("config", Config.class); context.refresh();
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PlatformContext.class}, (p, m, a) -> m.getName().equals("getAllApplicationContexts") ? List.of(context) : null);
            reloader = new SpringAddedBeanReloader(platform);
        }
        boolean reload(byte[] bytes) {
            return reloader.reloadBeanMethods(Config.class, Set.of("product:()" + Type.getDescriptor(Product.class)), bytes);
        }
        @Override public void close() { context.close(); }
    }
    private static byte[] bytes(Class<?> fixture) throws Exception {
        ClassNode source = read(Config.class);
        source.methods.stream().filter(m -> m.name.equals("product")).findFirst().orElseThrow().visibleAnnotations =
                read(fixture).methods.stream().filter(m -> m.name.equals("product")).findFirst().orElseThrow().visibleAnnotations;
        ClassWriter writer = new ClassWriter(0); source.accept(writer); return writer.toByteArray();
    }
    private static ClassNode read(Class<?> type) throws Exception {
        try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            ClassNode node = new ClassNode(); new ClassReader(Objects.requireNonNull(in).readAllBytes()).accept(node, 0); return node;
        }
    }
}
