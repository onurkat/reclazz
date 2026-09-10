/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SpringAddedBeanTest {
    @Configuration(proxyBeanMethods = false)
    public static class Config {
        int version = 1;
        boolean fail;
        private Product first() {
            if (fail) throw new IllegalStateException("factory failure");
            return new Product(version);
        }
        public Product second() { return new Product(2); }
        public Dependency dependency() { return new Dependency(); }
        public Object nothing() { return null; }
        public Object infrastructure() { return (org.springframework.beans.factory.config.BeanPostProcessor) new org.springframework.beans.factory.config.BeanPostProcessor() { }; }
        public void bad() { }
        public int primitive() { return 1; }
        public Product argument(int value) { return new Product(value); }
        public native Product nativeFactory();
        public List<String> generic() { return List.of(); }
    }

    public static class Dependency { }
    public static class Product implements AutoCloseable {
        @Autowired Dependency dependency;
        final int value;
        int initialized;
        int closed;
        public Product(int value) { this.value = value; }
        public int value() { return value; }
        public void initialize() { initialized++; }
        public void close() { closed++; }
    }

    @AfterEach void clearLedger() { RestartLedger.clear(); }

    @Test
    void registersInEveryContextInjectsAndRunsLifecycleOnce() throws Exception {
        try (Scope one = new Scope(); Scope two = new Scope()) {
            var reloader = reloader(one, two);
            for (int version = 1; version <= 3; version++) {
                List<Product> old = new ArrayList<>();
                for (Scope scope : List.of(one, two)) {
                    if (scope.context.containsBean("product")) old.add(scope.context.getBean("product", Product.class));
                    scope.context.getBean(Config.class).version = version;
                }
                assertTrue(reload(reloader, annotated("first", "name", List.of("product"), "initMethod", "initialize")));
                for (Product product : old) assertEquals(1, product.closed);
                for (Scope scope : List.of(one, two)) {
                    Product product = scope.context.getBean("product", Product.class);
                    assertEquals(version, product.value);
                    assertEquals(1, product.initialized);
                    assertSame(scope.context.getBean(Dependency.class), product.dependency);
                    assertSame(product, scope.context.getBean("product"));
                }
            }
            Product last = one.context.getBean("product", Product.class);
            assertTrue(reloader.reloadBeanMethods(Config.class, Set.of(), original()));
            assertFalse(one.context.containsBean("product"));
            assertFalse(two.context.containsBean("product"));
            assertEquals(1, last.closed);
        }
    }

    @Test
    void unrelatedContextsDoNotProduceAFalseRestartWarning() throws Exception {
        try (Scope home = new Scope(); Scope unrelated = new Scope(false)) {
            RestartLedger.clear();
            assertTrue(reload(reloader(home, unrelated), annotated("first")));
            assertTrue(home.context.containsBean("first"));
            assertFalse(unrelated.context.containsBean("first"));
            assertEquals(0, RestartLedger.size(), RestartLedger.digest().toString());
        }
    }

    @Test
    void definitionsFromTheSameSaveAreAvailableBeforeProductsAreInitialized() throws Exception {
        try (Scope scope = new Scope()) {
            scope.context.removeBeanDefinition("dependency");
            ClassNode source = read(annotated("first"));
            method(source, "dependency").visibleAnnotations = new ArrayList<>(List.of(bean()));
            assertTrue(source.methods.indexOf(method(source, "first")) < source.methods.indexOf(method(source, "dependency")),
                    "the consumer must be declared before its new dependency to prove ordering");
            var reloader = reloader(scope);
            for (int i = 0; i < 2; i++) {
                assertTrue(reload(reloader, write(source)));
                assertSame(scope.context.getBean("dependency"), scope.context.getBean("first", Product.class).dependency);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"definition", "singleton", "alias", "parent"})
    void nameCollisionsNeverOverwriteExistingBeans(String kind) throws Exception {
        try (Scope scope = new Scope(); var parent = new AnnotationConfigApplicationContext()) {
            Object existing = new Object();
            var factory = scope.context.getDefaultListableBeanFactory();
            switch (kind) {
                case "definition" -> scope.context.registerBean("first", Object.class, () -> existing);
                case "singleton" -> factory.registerSingleton("first", existing);
                case "alias" -> { factory.registerSingleton("existing", existing); factory.registerAlias("existing", "first"); }
                case "parent" -> {
                    parent.getBeanFactory().registerSingleton("first", existing);
                    parent.refresh();
                    scope.context.setParent(parent);
                }
            }
            assertFalse(reload(reloader(scope), annotated("first")));
            assertSame(existing, scope.context.getBean("first"));
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("already in use")));
        }
    }

    @Test
    void externallyReplacedDefinitionAndSingletonAreNotRemoved() throws Exception {
        for (boolean definition : List.of(false, true)) {
            try (Scope scope = new Scope()) {
                var reloader = reloader(scope);
                assertTrue(reload(reloader, annotated("first")));
                Object existing = new Object();
                var factory = scope.context.getDefaultListableBeanFactory();
                if (definition) scope.context.registerBean("first", Object.class, () -> existing);
                else { factory.destroySingleton("first"); factory.registerSingleton("first", existing); }
                assertTrue(reloader.reloadBeanMethods(Config.class, Set.of(), original()));
                assertSame(existing, scope.context.getBean("first"));
                assertFalse(reload(reloader, annotated("first")));
                assertSame(existing, scope.context.getBean("first"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void springRecreationOfAnOwnedProductDoesNotBecomeANameCollision(boolean proxied) throws Exception {
        try (Scope scope = new Scope()) {
            var factory = scope.context.getDefaultListableBeanFactory();
            if (proxied) factory.addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof Product)) return bean;
                    var proxy = new org.springframework.aop.framework.ProxyFactory(bean);
                    proxy.setProxyTargetClass(true);
                    return proxy.getProxy();
                }
            });
            int processors = factory.getBeanPostProcessorCount();
            var reloader = reloader(scope);
            assertTrue(reload(reloader, annotated("first")));
            Product old = scope.context.getBean("first", Product.class);
            factory.destroySingleton("first");
            scope.context.getBean(Config.class).version = 9;
            Product recreated = scope.context.getBean("first", Product.class);
            assertNotSame(old, recreated);
            assertEquals(9, recreated.value());
            scope.context.getBean(Config.class).version = 10;
            assertTrue(reload(reloader, annotated("first")), "a product recreated by our own definition is still owned");
            assertEquals(10, scope.context.getBean("first", Product.class).value());
            assertEquals(processors + 1, factory.getBeanPostProcessorCount(), "one ownership observer per factory, not per save");
            assertTrue(reloader.reloadBeanMethods(Config.class, Set.of(), original()));
            assertFalse(scope.context.containsBean("first"));
        }
    }

    @Test
    void aFactoryFailureLeavesNoDefinitionAndTheNextSaveRecovers() throws Exception {
        try (Scope scope = new Scope()) {
            var reloader = reloader(scope);
            Object unrelated = scope.context.getBean(Dependency.class);
            scope.context.getBean(Config.class).fail = true;
            assertFalse(reload(reloader, annotated("first")));
            assertFalse(scope.context.containsBeanDefinition("first"));
            assertSame(unrelated, scope.context.getBean(Dependency.class));
            scope.context.getBean(Config.class).fail = false;
            assertTrue(reload(reloader, annotated("first")));
            Product old = scope.context.getBean("first", Product.class);
            scope.context.getBean(Config.class).fail = true;
            assertFalse(reload(reloader, annotated("first")));
            assertEquals(1, old.closed, "replacement is live destruction, not rollback");
            assertFalse(scope.context.containsBeanDefinition("first"));
            assertSame(unrelated, scope.context.getBean(Dependency.class));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"nothing", "infrastructure"})
    void nullAndHiddenInfrastructureProductsAreRefused(String method) throws Exception {
        try (Scope scope = new Scope()) {
            assertFalse(reload(reloader(scope), annotated(method)));
            assertFalse(scope.context.containsBeanDefinition(method));
        }
    }

    @Test
    void failedInitIsCleanedUpAndExplicitEmptyDestroyIsRespected() throws Exception {
        try (Scope scope = new Scope()) {
            var reloader = reloader(scope);
            assertFalse(reload(reloader, annotated("first", "initMethod", "doesNotExist")));
            assertFalse(scope.context.containsBeanDefinition("first"));
            assertTrue(reload(reloader, annotated("first", "destroyMethod", "")));
            Product product = scope.context.getBean("first", Product.class);
            reloader.reloadBeanMethods(Config.class, Set.of(), original());
            assertEquals(0, product.closed);
        }
    }

    @Test
    void aNewNameRemovesOnlyTheOldOwnedName() throws Exception {
        try (Scope scope = new Scope()) {
            var reloader = reloader(scope);
            assertTrue(reload(reloader, annotated("first", "value", List.of("old"))));
            Product old = scope.context.getBean("old", Product.class);
            assertTrue(reload(reloader, annotated("first", "name", List.of("new"))));
            assertFalse(scope.context.containsBean("old"));
            assertEquals(1, old.closed);
            assertEquals(1, scope.context.getBean("new", Product.class).value);
        }
    }

    @Test
    void duplicateNamesAreRefusedBeforeEitherFactoryRuns() throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = read(annotated("first", "name", List.of("shared")));
            method(source, "second").visibleAnnotations = new ArrayList<>(List.of(bean("name", List.of("shared"))));
            assertFalse(reload(reloader(scope), write(source)));
            assertFalse(scope.context.containsBean("shared"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad", "primitive", "argument", "nativeFactory", "generic"})
    void unsupportedSignaturesAreNamed(String name) throws Exception {
        var plan = inspect(annotated(name));
        assertTrue(plan.factories().isEmpty());
        assertEquals(1, plan.refused().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Conditional", "DependsOn", "Role"})
    void additionalMethodMetadataIsNeverSilentlyIgnored(String annotation) throws Exception {
        ClassNode source = read(annotated("first"));
        method(source, "first").visibleAnnotations.add(new AnnotationNode("Lorg/springframework/context/annotation/" + annotation + ";"));
        assertEquals(1, inspect(write(source)).refused().size());
    }

    @Test
    void privateFullFactoryInheritanceAndClassPoliciesAreRefused() throws Exception {
        for (String kind : List.of("proxy", "super", "annotation")) {
            ClassNode source = read(annotated("first"));
            if (kind.equals("proxy")) source.visibleAnnotations.get(0).values = null;
            if (kind.equals("super")) source.superName = "example/Base";
            if (kind.equals("annotation")) source.visibleAnnotations.add(new AnnotationNode("Lorg/springframework/context/annotation/Lazy;"));
            assertEquals(1, inspect(write(source)).refused().size(), kind);
        }
    }

    @Test
    void invalidAliasesConflictingNamesAndExtraBeanOptionsAreRefused() throws Exception {
        for (Object[] attributes : List.of(new Object[]{"name", List.of("a", "&b")},
                new Object[]{"name", List.of("a"), "value", List.of("b")},
                new Object[]{"autowireCandidate", false}, new Object[]{"name", List.of(" ")})) {
            assertEquals(1, inspect(annotated("first", attributes)).refused().size());
        }
    }

    @Test
    void adapterCallsCurrentConfigurationAndHidesItsInjectedFields() throws Throwable {
        try (Scope scope = new Scope()) {
            var adapter = AddedBeanAdapter.create(Config.class, () -> scope.context.getBean(Config.class),
                    inspect(annotated("first")).factories().get(0));
            assertTrue(adapter.getClass().isHidden());
            assertTrue(Arrays.stream(adapter.getClass().getDeclaredFields()).allMatch(f -> f.getName().startsWith("__reclazz$")));
            assertEquals(1, ((Product) adapter.get()).value);
            scope.context.getDefaultListableBeanFactory().destroySingleton("config");
            scope.context.getBean(Config.class).version = 42;
            assertEquals(42, ((Product) adapter.get()).value);
        }
    }

    @Test
    void runtimeProxiesPrototypesAndAmbiguousConfigurationsAreRefused() throws Exception {
        for (String kind : List.of("proxy", "prototype", "ambiguous")) {
            try (Scope scope = new Scope()) {
                var factory = scope.context.getDefaultListableBeanFactory();
                if (kind.equals("ambiguous")) factory.registerSingleton("another", new Config());
                else {
                    factory.destroySingleton("config");
                    if (kind.equals("proxy")) {
                        var proxy = new org.springframework.aop.framework.ProxyFactory(new Config());
                        proxy.setProxyTargetClass(true);
                        factory.registerSingleton("config", proxy.getProxy());
                    } else {
                        var definition = new RootBeanDefinition(Config.class);
                        definition.setScope("prototype");
                        scope.context.registerBeanDefinition("config", definition);
                    }
                }
                assertFalse(reload(reloader(scope), annotated("first")), kind);
                assertFalse(scope.context.containsBean("first"), kind);
            }
        }
    }

    private static final class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        Scope() throws Exception { this(true); }
        Scope(boolean configuration) throws Exception {
            LookupCapture.store(Config.class, MethodHandles.privateLookupIn(Config.class, MethodHandles.lookup()));
            if (configuration) context.registerBean("config", Config.class);
            context.registerBean("dependency", Dependency.class);
            context.refresh();
        }
        @Override public void close() { context.close(); }
    }

    private static SpringAddedBeanReloader reloader(Scope... scopes) {
        List<Object> contexts = Arrays.stream(scopes).map(s -> (Object) s.context).toList();
        PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(SpringAddedBeanTest.class.getClassLoader(),
                new Class<?>[]{PlatformContext.class}, (p, m, a) -> m.getName().equals("getAllApplicationContexts") ? contexts : null);
        return new SpringAddedBeanReloader(platform);
    }
    private static boolean reload(SpringAddedBeanReloader reloader, byte[] bytes) {
        return reloader.reloadBeanMethods(Config.class, signatures(bytes), bytes);
    }
    private static AddedBeanAdapter.Plan inspect(byte[] bytes) { return AddedBeanAdapter.inspect(bytes, signatures(bytes)); }
    private static Set<String> signatures(byte[] bytes) {
        Set<String> added = new HashSet<>();
        for (var method : read(bytes).methods) added.add(method.name + ":" + method.desc);
        return added;
    }
    private static byte[] original() throws Exception {
        try (var stream = Config.class.getResourceAsStream("/" + Config.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(stream);
            return stream.readAllBytes();
        }
    }
    private static byte[] annotated(String method, Object... attributes) throws Exception {
        ClassNode source = read(original());
        method(source, method).visibleAnnotations = new ArrayList<>(List.of(bean(attributes)));
        return write(source);
    }
    private static AnnotationNode bean(Object... attributes) {
        var annotation = new AnnotationNode(AddedBeanAdapter.BEAN);
        annotation.values = new ArrayList<>(Arrays.asList(attributes));
        return annotation;
    }
    private static MethodNode method(ClassNode source, String name) {
        return source.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static ClassNode read(byte[] bytes) {
        var source = new ClassNode();
        new ClassReader(bytes).accept(source, 0);
        return source;
    }
    private static byte[] write(ClassNode source) {
        ClassWriter writer = new ClassWriter(0);
        source.accept(writer);
        return writer.toByteArray();
    }
}
