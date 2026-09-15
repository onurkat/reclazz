/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.util.Reflect;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FactoryValueArgumentsTest {
    public static class Dependency { }
    public static class Product implements InitializingBean, DisposableBean {
        static int built, initialized, destroyed;
        final long millis;
        final int rate;
        final String label;
        final Dependency dependency;
        // This annotation describes a constructor Spring does not call for an
        // @Bean product. The factory's parameter metadata must take precedence.
        Product(@Value("#{${cfg.seconds} * 999L}") long millis, int rate, String label, Dependency dependency) {
            built++;
            this.millis = millis; this.rate = rate; this.label = label; this.dependency = dependency;
        }
        @Override public void afterPropertiesSet() { initialized++; }
        @Override public void destroy() { destroyed++; }
    }
    public static class Holder {
        Product product;
        @Value("${cfg.label}") String label;
        Holder(Product product) { this.product = product; this.label = "old"; }
    }
    @Configuration
    static class Full {
        @Bean Dependency dependency() { return new Dependency(); }
        @Bean({"product", "client"}) Product product(Dependency dependency,
                @Value("#{${cfg.seconds:5} * 1000L}") long millis,
                @Value("#{100 / ${cfg.divisor:5}}") int rate, @Value("${cfg.label}") String label) {
            return new Product(millis, rate, label, dependency);
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class StaticFactory {
        @Bean static Product product(@Value("#{${cfg.seconds:5} * 1000L}") long millis) {
            return new Product(millis, 20, "old", null);
        }
    }
    static AnnotationConfigApplicationContext context(Class<?> configuration) {
        Product.built = Product.initialized = Product.destroyed = 0;
        return ComputedValueConstructorsTest.context(configuration);
    }
    static PropertyChangeOutcome apply(AnnotationConfigApplicationContext context, Map<String, String> changed) {
        return new SpringPropertyRebinder(List.of(context)).apply(changed);
    }

    @Test void fullFactoryRecreatesOnceWithDependenciesLifecycleAliasesAndHealedHolder() {
        try (var context = context(Full.class)) {
            Product original = context.getBean(Product.class);
            Holder holder = new Holder(original);
            context.getBeanFactory().registerSingleton("holder", holder);
            Object dependency = context.getBean(Dependency.class);
            var first = apply(context, Map.of("cfg.seconds", "8", "cfg.divisor", "4", "cfg.label", "new"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, first.state(), first.findings().toString());
            Product current = context.getBean(Product.class);
            assertNotSame(original, current);
            assertEquals(8000, current.millis);
            assertEquals(25, current.rate);
            assertEquals("new", current.label);
            assertEquals("new", holder.label);
            assertSame(dependency, current.dependency);
            assertSame(current, context.getBean("client"));
            assertSame(current, holder.product);
            assertEquals(List.of("product"), first.rebuilt());
            assertEquals(2, Product.built);
            assertEquals(2, Product.initialized);
            assertEquals(1, Product.destroyed);
            assertEquals(PropertyChangeOutcome.State.APPLIED, apply(context, Map.of("cfg.seconds", "9")).state());
            assertEquals(9000, holder.product.millis);
            assertEquals(3, Product.built);
            assertEquals(2, Product.destroyed);
        }
    }

    @Test void staticFactoryIsCheckedWithoutInvokingItAndUnrelatedKeysDoNotRebuild() {
        try (var context = context(StaticFactory.class)) {
            Object original = context.getBean("product");
            var check = new PropertyChangeCheck().check(List.of(context), Map.of("cfg.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, check.state(), check.findings().toString());
            assertEquals(1, Product.built);
            assertSame(original, context.getBean("product"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, apply(context, Map.of("cfg.label", "new")).state());
            assertSame(original, context.getBean("product"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, apply(context, Map.of("cfg.seconds", "8")).state());
            assertEquals(8000, context.getBean(Product.class).millis);
        }
    }

    @Test void invalidArgumentHoldsAllLiveValuesBeforeBoundaryThenCorrectedSaveWorks() {
        try (var context = context(Full.class)) {
            Object original = context.getBean("product");
            Holder holder = new Holder((Product) original);
            context.getBeanFactory().registerSingleton("holder", holder);
            var boundary = new AtomicBoolean();
            var accepted = new AtomicBoolean();
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(
                    Map.of("cfg.seconds", "8", "cfg.divisor", "0", "cfg.label", "bad"),
                    work -> { boundary.set(true); work.run(); }, () -> accepted.set(true));
            assertEquals(PropertyChangeOutcome.State.REJECTED, outcome.state(), outcome.findings().toString());
            assertFalse(boundary.get());
            assertFalse(accepted.get());
            assertSame(original, context.getBean("product"));
            assertEquals(1, Product.built);
            assertEquals(0, Product.destroyed);
            assertEquals("old", holder.label);
            assertEquals("5", context.getEnvironment().getProperty("cfg.seconds"));
            assertTrue(outcome.findings().stream().anyMatch(s -> s.contains("product.product[2]")), outcome.findings().toString());
            assertEquals(PropertyChangeOutcome.State.APPLIED, apply(context, Map.of("cfg.seconds", "8", "cfg.divisor", "4")).state());
            assertEquals(8000, context.getBean(Product.class).millis);
        }
    }

    @Test void placeholderResolvedToUnsafeExpressionCannotRunCodeOrLifecycle() {
        try (var context = context(Full.class)) {
            int before = ComputedValueConstructorsTest.Effects.calls;
            var result = apply(context, Map.of("cfg.label",
                    "#{T(com.onurkat.reclazz.spring.ComputedValueConstructorsTest$Effects).touch()}"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, result.state(), result.findings().toString());
            assertTrue(result.findings().stream().anyMatch(s -> s.contains("product.product[3]")), result.findings().toString());
            assertEquals(before, ComputedValueConstructorsTest.Effects.calls);
            assertEquals(1, Product.built);
            assertEquals(0, Product.destroyed);
            assertEquals("old", context.getEnvironment().getProperty("cfg.label"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Unsafe {
        @Bean Product product(@Value("${cfg.label}") String label,
                @Value("#{T(com.onurkat.reclazz.spring.ComputedValueConstructorsTest$Effects).touch()}") int count) {
            return new Product(count, 20, label, null);
        }
    }
    @Test void unchangedUnsafeArgumentIsAlsoChecked() {
        try (var context = context(Unsafe.class)) {
            int before = ComputedValueConstructorsTest.Effects.calls;
            var result = apply(context, Map.of("cfg.label", "new"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, result.state(), result.findings().toString());
            assertEquals(before, ComputedValueConstructorsTest.Effects.calls);
            assertEquals(1, Product.built);
            assertEquals(0, Product.destroyed);
        }
    }

    @Test void unavailableOrResolvedArgumentCacheAndSupplierPoliciesHoldBeforeDestruction() {
        for (String policy : List.of("prepared", "resolved", "supplier", "constant", "wrongParameter")) {
            try (var context = context(Full.class)) {
                var definition = (RootBeanDefinition) context.getBeanFactory().getMergedBeanDefinition("product");
                if (policy.equals("prepared")) assertTrue(Reflect.writeField(definition, "preparedConstructorArguments", null));
                if (policy.equals("resolved")) assertTrue(Reflect.writeField(definition, "resolvedConstructorArguments", new Object[4]));
                if (policy.equals("supplier")) definition.setInstanceSupplier(() -> null);
                if (policy.equals("constant") || policy.equals("wrongParameter")) {
                    Object[] prepared = (Object[]) Reflect.readField(definition, "preparedConstructorArguments");
                    assertNotNull(prepared);
                    prepared[1] = policy.equals("constant") ? 5000L : prepared[2];
                }
                var result = apply(context, Map.of("cfg.seconds", "8"));
                assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, result.state(), policy + result.findings());
                assertEquals(1, Product.built);
                assertEquals(0, Product.destroyed);
                assertTrue(result.findings().stream().anyMatch(s -> s.contains("factory")), result.findings().toString());
                assertEquals("5", context.getEnvironment().getProperty("cfg.seconds"));
            }
        }
    }

    @Test void proxyProductIsExplicitlyHeld() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!name.equals("product")) return bean;
                    var proxy = new org.springframework.aop.framework.ProxyFactory(bean);
                    proxy.setProxyTargetClass(true);
                    return proxy.getProxy();
                }
            });
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "original", Map.of("cfg.seconds", "5", "cfg.divisor", "5", "cfg.label", "old")));
            context.register(Full.class);
            context.refresh();
            Object original = context.getBean("product");
            var result = apply(context, Map.of("cfg.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, result.state(), result.findings().toString());
            assertTrue(result.findings().stream().anyMatch(s -> s.contains("unproxied")), result.findings().toString());
            assertSame(original, context.getBean("product"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Deferred {
        @Bean @Lazy Product lazy(@Value("${cfg.seconds}") long seconds) { return new Product(seconds, 0, "", null); }
        @Bean @Scope("prototype") Product prototype(@Value("${cfg.seconds}") long seconds) { return new Product(seconds, 0, "", null); }
    }
    @Test void lazyAndPrototypeAreNotInstantiatedByCheckOrApplication() {
        try (var context = context(Deferred.class)) {
            var result = apply(context, Map.of("cfg.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, result.state(), result.findings().toString());
            assertEquals(0, Product.built);
            assertTrue(result.rebuilt().isEmpty());
            assertEquals(8, ((Product) context.getBean("lazy")).millis);
            assertEquals(8, ((Product) context.getBean("prototype")).millis);
        }
    }

    record Plain(long timeout) { }
    @Configuration(proxyBeanMethods = false)
    static class PlainFactory {
        @Bean Plain plain(@Value("${cfg.seconds}") long timeout) { return new Plain(timeout); }
    }
    @Test void ordinaryProductWithoutInjectionAnnotationsAndIndirectValuesRefresh() {
        try (var context = context(PlainFactory.class)) {
            Object original = context.getBean(Plain.class);
            assertEquals(PropertyChangeOutcome.State.APPLIED, apply(context,
                    Map.of("cfg.seconds", "${cfg.actual}", "cfg.actual", "8")).state());
            assertNotSame(original, context.getBean(Plain.class));
            assertEquals(8, context.getBean(Plain.class).timeout());
            assertEquals(PropertyChangeOutcome.State.APPLIED, apply(context, Map.of("cfg.actual", "9")).state());
            assertEquals(9, context.getBean(Plain.class).timeout());
            var bad = apply(context, Map.of("cfg.actual", "not-a-number"));
            assertEquals(PropertyChangeOutcome.State.REJECTED, bad.state(), bad.findings().toString());
            assertEquals(9, context.getBean(Plain.class).timeout());
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class Collections {
        @Bean Plain plain(@Value("#{ {${cfg.seconds}} }") List<Integer> values) { return new Plain(values.get(0)); }
    }
    @Test void genericCollectionArgumentIsHeldBeforeNativeRecreation() {
        try (var context = context(Collections.class)) {
            Object original = context.getBean("plain");
            var result = apply(context, Map.of("cfg.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, result.state(), result.findings().toString());
            assertTrue(result.findings().stream().anyMatch(s -> s.contains("primitive")), result.findings().toString());
            assertSame(original, context.getBean("plain"));
        }
    }

    @Test void customResolverCannotExecuteDuringDirectPlaceholderPrecheck() {
        try (var context = context(PlainFactory.class)) {
            Object original = context.getBean("plain");
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            context.getBeanFactory().setBeanExpressionResolver((text, beanContext) -> {
                calls.incrementAndGet(); return "8";
            });
            var result = apply(context, Map.of("cfg.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, result.state(), result.findings().toString());
            assertEquals(0, calls.get());
            assertSame(original, context.getBean("plain"));
        }
    }
    public static class SeparateFactory implements org.springframework.beans.factory.FactoryBean<Plain> {
        final Plain product;
        SeparateFactory(long value) { product = new Plain(value); }
        @Override public Plain getObject() { return product; }
        @Override public Class<?> getObjectType() { return Plain.class; }
    }
    @Configuration(proxyBeanMethods = false)
    static class FactoryBeanConfig {
        @Bean SeparateFactory plain(@Value("${cfg.seconds}") long seconds) { return new SeparateFactory(seconds); }
    }
    @Test void factoryBeanOwnershipIsNotConfusedWithItsProduct() {
        try (var context = context(FactoryBeanConfig.class)) {
            Object original = context.getBean("plain");
            Object factory = context.getBean("&plain");
            var result = apply(context, Map.of("cfg.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, result.state(), result.findings().toString());
            assertTrue(result.findings().stream().anyMatch(s -> s.contains("FactoryBean")), result.findings().toString());
            assertSame(original, context.getBean("plain"));
            assertSame(factory, context.getBean("&plain"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Failing {
        static int calls;
        @Bean Product product(@Value("${cfg.seconds}") long seconds) {
            calls++;
            if (seconds == 8) throw new IllegalStateException("factory refuses eight");
            return new Product(seconds, 0, "", null);
        }
    }
    @Test void liveFailureIsPartialDoesNotAcceptAndDoesNotClaimOldBeanSurvived() {
        try (var context = context(Failing.class)) {
            Failing.calls = 1;
            var accepted = new AtomicBoolean();
            var result = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.seconds", "8"),
                    Runnable::run, () -> accepted.set(true));
            assertEquals(PropertyChangeOutcome.State.PARTIAL, result.state(), result.findings().toString());
            assertFalse(accepted.get());
            assertEquals(2, Failing.calls);
            assertEquals(1, Product.destroyed);
            assertFalse(context.getBeanFactory().containsSingleton("product"));
            assertEquals("8", context.getEnvironment().getProperty("cfg.seconds"));
        }
    }
}
