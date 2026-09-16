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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class AddedFactoryValueArgumentsTest {
    static int built, initialized, closed, effects;
    @BeforeEach void reset() { built = initialized = closed = effects = 0; RestartLedger.clear(); }
    @AfterEach void clear() { RestartLedger.clear(); }
    public static class Dependency { }
    public static class Product implements AutoCloseable {
        final long millis;
        final int rate;
        final String label;
        final Dependency dependency;
        public Product(@Value("#{${cfg.ctorOnly:1} * 999L}") long millis, int rate, String label, Dependency dependency) {
            if (label.equals("throw")) throw new IllegalStateException("fixture factory failure");
            built++; this.millis = millis; this.rate = rate; this.label = label; this.dependency = dependency;
        }
        public void init() { initialized++; }
        @Override public void close() { closed++; }
    }
    public static class Holder {
        Product product;
        @Value("${cfg.label}") String label = "old";
        Holder(Product product) { this.product = product; }
    }
    @Configuration(proxyBeanMethods = false)
    public static class Config {
        public Product product(@Value("#{${cfg.seconds:5} * 1000L}") long millis,
                @Value("#{100 / ${cfg.divisor:5}}") int rate, @Value("${cfg.label}") String label,
                Dependency dependency) {
            return new Product(millis, rate, label, dependency);
        }
        public static Product staticProduct(@Value("#{${cfg.seconds:5} * 1000L}") long millis,
                @Value("#{100 / ${cfg.divisor:5}}") int rate, @Value("${cfg.label}") String label,
                Dependency dependency) {
            return new Product(millis, rate, label, dependency);
        }
    }
    public static int sideEffect() { effects++; return 10; }

    @Test void mergedDefinitionRetainsMetadataAndSupplierIdentity() {
        try (var context = ComputedValueConstructorsTest.context()) {
            Object token = new Object();
            Supplier<String> supplier = () -> "value";
            RootBeanDefinition definition = new RootBeanDefinition(String.class);
            definition.setAttribute("fixture.metadata", token); definition.setInstanceSupplier(supplier);
            context.registerBeanDefinition("probe", definition);
            var merged = (RootBeanDefinition) context.getBeanFactory().getMergedBeanDefinition("probe");
            assertSame(token, merged.getAttribute("fixture.metadata"));
            assertSame(supplier, merged.getInstanceSupplier());
            assertEquals("value", context.getBean("probe"));
        }
    }

    @Test void savedFactoryArgumentsRecreateAndHealHolder() throws Exception {
        try (Scope scope = new Scope()) {
            Product original = scope.product();
            Holder holder = new Holder(original);
            scope.context.getBeanFactory().registerSingleton("holder", holder);
            var check = new PropertyChangeCheck().check(List.of(scope.context), Map.of("cfg.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, check.state(), check.findings().toString());
            assertEquals(1, built); assertSame(original, scope.product());
            var changed = scope.apply(Map.of("cfg.seconds", "8", "cfg.divisor", "4", "cfg.label", "new"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, changed.state(), changed.findings().toString());
            assertEquals(List.of("product"), changed.rebuilt());
            assertNotSame(original, scope.product());
            assertSame(scope.product(), holder.product); assertEquals("new", holder.label);
            assertSame(scope.product(), scope.context.getBean("alias"));
            assertSame(original.dependency, scope.product().dependency);
            assertEquals(8000, scope.product().millis); assertEquals(25, scope.product().rate);
            assertEquals("new", scope.product().label);
            assertEquals(2, built); assertEquals(2, initialized); assertEquals(1, closed);
        }
        assertEquals(2, closed);
    }

    @Test void staticAddedFactoryRecreatesViaCurrentArguments() throws Exception {
        try (Scope scope = new Scope(false, "staticProduct")) {
            Product original = scope.product();
            var outcome = scope.apply(Map.of("cfg.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, outcome.state(), outcome.findings().toString());
            assertEquals(List.of("product"), outcome.rebuilt());
            assertNotSame(original, scope.product()); assertEquals(8000, scope.product().millis);
            assertEquals(1, closed);
        }
    }

    @Test void invalidCandidatePreservesAllLiveValues() throws Exception {
        try (Scope scope = new Scope()) {
            Product original = scope.product(); Holder holder = new Holder(original);
            scope.context.getBeanFactory().registerSingleton("holder", holder);
            var entered = new java.util.concurrent.atomic.AtomicBoolean();
            var outcome = new SpringPropertyRebinder(List.of(scope.context)).apply(
                    Map.of("cfg.seconds", "8", "cfg.divisor", "0", "cfg.label", "bad"),
                    work -> { entered.set(true); work.run(); }, () -> fail("invalid candidate was accepted"));
            assertEquals(PropertyChangeOutcome.State.REJECTED, outcome.state(), outcome.findings().toString());
            assertFalse(entered.get()); assertSame(original, scope.product());
            assertEquals("old", holder.label); assertEquals("5", scope.context.getEnvironment().getProperty("cfg.seconds"));
            assertEquals(1, built); assertEquals(0, closed);
            assertEquals(PropertyChangeOutcome.State.APPLIED, scope.apply(Map.of("cfg.seconds", "8")).state());
            assertEquals(8000, scope.product().millis);
        }
    }

    @Test void unchangedUnsafeArgumentHoldsCandidate() throws Exception {
        try (Scope scope = new Scope()) {
            Product original = scope.product();
            // Install the unsafe expression once; checking the next candidate must not run it.
            assertTrue(scope.reload(bytes("#{T(" + AddedFactoryValueArgumentsTest.class.getName() + ").sideEffect()}", false)),
                    RestartLedger.digest().toString());
            int callsBefore = effects; Product current = scope.product();
            assertNotSame(original, current);
            var outcome = scope.apply(Map.of("cfg.divisor", "4", "cfg.label", "held"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), outcome.findings().toString());
            assertEquals(callsBefore, effects, "precheck must not execute unchanged unsafe factory arguments");
            assertSame(current, scope.product());
            assertEquals("5", scope.context.getEnvironment().getProperty("cfg.divisor"));
        }
    }

    @Test void suppliedMetadataOwnsCreationInsteadOfProductConstructor() throws Exception {
        try (Scope scope = new Scope()) {
            Product original = scope.product();
            var outcome = scope.apply(Map.of("cfg.ctorOnly", "7"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, outcome.state());
            assertTrue(outcome.rebuilt().isEmpty()); assertSame(original, scope.product()); assertEquals(1, built);
        }
    }

    @Test void changedSupplierOrDefinitionPolicyIsUncheckable() throws Exception {
        for (boolean merged : List.of(false, true)) {
            try (Scope scope = new Scope()) {
                Product original = scope.product();
                var definition = (RootBeanDefinition) (merged
                        ? scope.context.getBeanFactory().getMergedBeanDefinition("product")
                        : scope.context.getBeanDefinition("product"));
                definition.setInstanceSupplier(() -> { fail("foreign supplier executed during check"); return original; });
                var outcome = scope.apply(Map.of("cfg.seconds", "8"));
                assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), outcome.findings().toString());
                assertSame(original, scope.product());
                assertEquals("5", scope.context.getEnvironment().getProperty("cfg.seconds"));
            }
        }
    }

    @Test void copiedOrMutatedDefinitionCannotBorrowCreationOwnership() throws Exception {
        for (String mode : List.of("copied", "scope", "arguments", "class")) {
            try (Scope scope = new Scope()) {
                RootBeanDefinition original = (RootBeanDefinition) scope.context.getBeanDefinition("product");
                switch (mode) {
                    case "copied" -> {
                        scope.context.registerBeanDefinition("product", new RootBeanDefinition(original));
                        scope.product();
                    }
                    case "scope" -> original.setScope("prototype");
                    case "arguments" -> original.getConstructorArgumentValues().addIndexedArgumentValue(0, 99L);
                    case "class" -> original.setBeanClass(String.class);
                    default -> throw new AssertionError(mode);
                }
                Object current = scope.context.getBeanFactory().getSingleton("product");
                int count = built;
                var outcome = scope.apply(Map.of("cfg.seconds", "8"));
                assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), mode + outcome.findings());
                assertSame(current, scope.context.getBeanFactory().getSingleton("product"));
                assertEquals(count, built); assertEquals("5", scope.context.getEnvironment().getProperty("cfg.seconds"));
            }
        }
    }

    @Test void customResolverIsUncheckableWithoutInvokingIt() throws Exception {
        try (Scope scope = new Scope()) {
            Object original = scope.product();
            scope.context.getBeanFactory().setBeanExpressionResolver(new org.springframework.context.expression.StandardBeanExpressionResolver() {
                @Override public Object evaluate(String value, org.springframework.beans.factory.config.BeanExpressionContext context) {
                    fail("custom resolver executed during precheck"); return null;
                }
            });
            var outcome = scope.apply(Map.of("cfg.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), outcome.findings().toString());
            assertSame(original, scope.product());
        }
    }

    @Test void untouchedAndUninstantiatedProductsStayUntouched() throws Exception {
        try (Scope scope = new Scope(true)) {
            assertEquals(0, built);
            assertEquals(PropertyChangeOutcome.State.APPLIED, scope.apply(Map.of("cfg.seconds", "8")).state());
            assertEquals(0, built);
            assertEquals(8000, scope.product().millis);
            Product original = scope.product();
            assertEquals(PropertyChangeOutcome.State.APPLIED, scope.apply(Map.of("unrelated", "value")).state());
            assertSame(original, scope.product());
            assertEquals(PropertyChangeOutcome.State.APPLIED, scope.apply(Map.of("cfg.seconds", "9")).state());
            assertNotSame(original, scope.product()); assertEquals(9000, scope.product().millis);
        }
    }

    @Test void newConfigurationMetadataReplacesOldValueDependencies() throws Exception {
        try (Scope scope = new Scope()) {
            assertTrue(scope.reload(bytes("#{${cfg.next:6} * 1000L}", false)));
            Product current = scope.product(); assertEquals(6000, current.millis);
            assertEquals(PropertyChangeOutcome.State.APPLIED, scope.apply(Map.of("cfg.seconds", "8")).state());
            assertSame(current, scope.product());
            assertEquals(PropertyChangeOutcome.State.APPLIED, scope.apply(Map.of("cfg.next", "9")).state());
            assertNotSame(current, scope.product()); assertEquals(9000, scope.product().millis);
        }
    }

    @Test void creationFailureReportsPartial() throws Exception {
        try (Scope scope = new Scope()) {
            var outcome = scope.apply(Map.of("cfg.label", "throw"));
            assertEquals(PropertyChangeOutcome.State.PARTIAL, outcome.state(), outcome.findings().toString());
            assertEquals(1, closed);
            assertFalse(scope.context.getBeanFactory().containsSingleton("product"));
            assertEquals("throw", scope.context.getEnvironment().getProperty("cfg.label"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, scope.apply(Map.of("cfg.label", "recovered")).state());
            assertFalse(scope.context.getBeanFactory().containsSingleton("product"), "missing singletons are not eagerly recreated");
            assertEquals("recovered", scope.product().label);
            assertSame(scope.product(), scope.context.getBean("alias"));
        }
    }

    @Test void removedFactoryLeavesNoPropertyWork() throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode node = read();
            assertTrue(scope.reload(write(node)));
            assertFalse(scope.context.containsBean("product")); assertFalse(scope.context.isAlias("alias"));
            var outcome = scope.apply(Map.of("cfg.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, outcome.state());
            assertTrue(outcome.rebuilt().isEmpty()); assertEquals(1, closed);
        }
    }

    @Test void proxyProductIsUncheckable() throws Exception {
        try (Scope scope = new Scope(true)) {
            scope.context.getBeanFactory().addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!name.equals("product")) return bean;
                    var proxy = new org.springframework.aop.framework.ProxyFactory(bean);
                    proxy.setProxyTargetClass(true); return proxy.getProxy();
                }
            });
            Object original = scope.product();
            var outcome = scope.apply(Map.of("cfg.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), outcome.findings().toString());
            assertSame(original, scope.product());
        }
    }

    private static class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = ComputedValueConstructorsTest.context();
        final SpringAddedBeanReloader reloader;
        final String methodName;
        Scope() throws Exception { this(false); }
        Scope(boolean lazy) throws Exception { this(lazy, "product"); }
        Scope(boolean lazy, String methodName) throws Exception {
            this.methodName = methodName;
            LookupCapture.store(Config.class, MethodHandles.privateLookupIn(Config.class, MethodHandles.lookup()));
            context.registerBean("dependency", Dependency.class); context.registerBean("config", Config.class);
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PlatformContext.class}, (p, m, a) -> m.getName().equals("getAllApplicationContexts") ? List.of(context) : null);
            reloader = new SpringAddedBeanReloader(platform);
            assertTrue(reload(bytes("#{${cfg.seconds:5} * 1000L}", lazy, methodName)), RestartLedger.digest().toString());
        }
        boolean reload(byte[] bytes) {
            ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
            return reloader.reloadBeanMethods(Config.class, Set.of(node.methods.stream().filter(m -> m.name.equals(methodName))
                    .map(m -> m.name + ":" + m.desc).findFirst().orElseThrow()), bytes);
        }
        Product product() { return context.getBean("product", Product.class); }
        PropertyChangeOutcome apply(Map<String, String> changed) { return new SpringPropertyRebinder(List.of(context)).apply(changed); }
        @Override public void close() { context.close(); }
    }
    private static byte[] bytes(String expression, boolean lazy) throws Exception {
        return bytes(expression, lazy, "product");
    }
    private static byte[] bytes(String expression, boolean lazy, String methodName) throws Exception {
        ClassNode node = read(); MethodNode method = node.methods.stream().filter(m -> m.name.equals(methodName)).findFirst().orElseThrow();
        AnnotationNode bean = new AnnotationNode(AddedBeanAdapter.BEAN);
        bean.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of("product", "alias")), "initMethod", "init"));
        method.visibleAnnotations = new ArrayList<>(List.of(bean));
        if (lazy) method.visibleAnnotations.add(new AnnotationNode(Type.getDescriptor(Lazy.class)));
        method.visibleParameterAnnotations[0].get(0).values = new ArrayList<>(List.of("value", expression));
        return write(node);
    }
    private static ClassNode read() throws Exception {
        try (var in = Config.class.getResourceAsStream("/" + Config.class.getName().replace('.', '/') + ".class")) {
            ClassNode node = new ClassNode(); new ClassReader(Objects.requireNonNull(in).readAllBytes()).accept(node, 0); return node;
        }
    }
    private static byte[] write(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
}
