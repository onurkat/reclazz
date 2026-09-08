/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ComputedValueConstructorsTest {
    public static class Client {
        static int constructions;
        final long millis;
        final int rate;
        Client(@Value("#{${cfg.seconds:5} * 1000L}") long millis,
               @Value("#{100 / ${cfg.divisor:5}}") int rate) {
            constructions++;
            this.millis = millis;
            this.rate = rate;
        }
    }
    public static class Holder {
        Client client;
        @Value("${cfg.label}") String label;
        Holder(Client client) { this.client = client; }
    }

    static AnnotationConfigApplicationContext context(Class<?>... types) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("original",
                Map.of("cfg.seconds", "5", "cfg.divisor", "5", "cfg.label", "old")));
        if (types.length > 0) context.register(types);
        context.refresh();
        return context;
    }

    @Test void validSaveRebuildsAndHealsExistingHolder() {
        Client.constructions = 0;
        try (var context = context(Client.class)) {
            Client original = context.getBean(Client.class);
            // An existing singleton with a stored reference, without a Spring
            // dependency edge: destruction cannot recreate this holder for us.
            Holder holder = new Holder(original);
            context.getBeanFactory().registerSingleton("holder", holder);
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(
                    Map.of("cfg.seconds", "8", "cfg.divisor", "4"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, outcome.state(), outcome.findings().toString());
            assertEquals(8000, context.getBean(Client.class).millis);
            assertEquals(25, context.getBean(Client.class).rate);
            assertNotSame(original, context.getBean(Client.class));
            assertSame(holder, context.getBean("holder"));
            assertSame(context.getBean(Client.class), holder.client);
            assertEquals(2, Client.constructions);
            assertEquals(List.of(context.getBeanNamesForType(Client.class)[0]), outcome.rebuilt());
        }
    }

    @Test void invalidSaveDoesNotRunConstructorOrChangeAnyLiveValue() {
        Client.constructions = 0;
        try (var context = context(Client.class, Holder.class)) {
            Client original = context.getBean(Client.class);
            Holder holder = context.getBean(Holder.class);
            var accepted = new AtomicBoolean();
            var entered = new AtomicBoolean();
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(
                    Map.of("cfg.seconds", "8", "cfg.divisor", "0", "cfg.label", "new"),
                    work -> { entered.set(true); work.run(); }, () -> accepted.set(true));
            assertEquals(PropertyChangeOutcome.State.REJECTED, outcome.state(), outcome.findings().toString());
            assertFalse(entered.get());
            assertFalse(accepted.get());
            assertSame(original, context.getBean(Client.class));
            assertSame(holder, context.getBean(Holder.class));
            assertEquals(1, Client.constructions);
            assertEquals(5000, holder.client.millis);
            assertEquals("old", holder.label);
            assertEquals("5", context.getEnvironment().getProperty("cfg.seconds"));
            assertEquals("5", context.getEnvironment().getProperty("cfg.divisor"));
            assertEquals("old", context.getEnvironment().getProperty("cfg.label"));
        }
    }

    @Test void checkDoesNotConstructAndUnrelatedSaveDoesNotReplace() {
        Client.constructions = 0;
        try (var context = context(Client.class)) {
            Client original = context.getBean(Client.class);
            var check = new PropertyChangeCheck().check(List.of(context), Map.of("cfg.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, check.state(), check.findings().toString());
            assertEquals(1, Client.constructions);
            assertSame(original, context.getBean(Client.class));
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.label", "new"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, outcome.state());
            assertTrue(outcome.rebuilt().isEmpty());
            assertSame(original, context.getBean(Client.class));
            assertEquals(1, Client.constructions);
        }
    }

    public static class ByteClient {
        final byte value;
        ByteClient(@Value("#{${cfg.seconds}}") byte value) { this.value = value; }
    }
    public static class DoubleClient {
        final double value;
        DoubleClient(@Value("#{1.0 / ${cfg.divisor}}") double value) { this.value = value; }
    }
    @Test void syntaxConversionAndNonFiniteResultsHoldEveryContextAndRetry() {
        try (var one = context(ByteClient.class); var two = context(Client.class)) {
            var rebinder = new SpringPropertyRebinder(List.of(one, two));
            Object original = two.getBean(Client.class);
            for (String invalid : List.of(")", "'bad'", "1000")) {
                var outcome = rebinder.apply(Map.of("cfg.seconds", invalid, "cfg.label", "new"));
                assertEquals(PropertyChangeOutcome.State.REJECTED, outcome.state(), outcome.findings().toString());
                assertEquals(5, one.getBean(ByteClient.class).value);
                assertSame(original, two.getBean(Client.class));
                assertEquals("old", one.getEnvironment().getProperty("cfg.label"));
                assertEquals("old", two.getEnvironment().getProperty("cfg.label"));
            }
            assertEquals(PropertyChangeOutcome.State.APPLIED, rebinder.apply(Map.of("cfg.seconds", "8")).state());
            assertEquals(8, one.getBean(ByteClient.class).value);
            assertEquals(8000, two.getBean(Client.class).millis);
        }
        try (var context = context(DoubleClient.class)) {
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.divisor", "0"));
            assertEquals(PropertyChangeOutcome.State.REJECTED, outcome.state());
            assertEquals(0.2, context.getBean(DoubleClient.class).value);
        }
    }

    public static class Effects {
        static int calls;
        public static int touch() { calls++; return 1; }
    }
    @Test void forbiddenBranchesCannotRunApplicationCodeOrConstructors() {
        Client.constructions = 0;
        try (var context = context(Client.class)) {
            for (String expression : List.of("T(" + Effects.class.getName() + ").touch()",
                    "false ? T(" + Effects.class.getName() + ").touch() : 8", "@effects.value")) {
                int before = Effects.calls;
                var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.seconds", expression));
                assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), outcome.findings().toString());
                assertTrue(outcome.findings().stream().anyMatch(s -> s.contains(".<init>[0]")));
                assertEquals(before, Effects.calls);
                assertEquals(1, Client.constructions);
                assertEquals("5", context.getEnvironment().getProperty("cfg.seconds"));
            }
        }
    }

    public static class UnchangedUnsafeArgument {
        UnchangedUnsafeArgument(@Value("${cfg.label}") String label,
                @Value("#{T(com.onurkat.reclazz.spring.ComputedValueConstructorsTest$Effects).touch()}") int unused) { }
    }
    @Test void changingDirectArgumentChecksTheUnchangedExpressionToo() {
        try (var context = context(UnchangedUnsafeArgument.class)) {
            Object original = context.getBean(UnchangedUnsafeArgument.class);
            int before = Effects.calls;
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.label", "new"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), outcome.findings().toString());
            assertTrue(outcome.findings().stream().anyMatch(s -> s.contains(".<init>[1]")));
            assertEquals(before, Effects.calls);
            assertSame(original, context.getBean(UnchangedUnsafeArgument.class));
            assertEquals("old", context.getEnvironment().getProperty("cfg.label"));
        }
    }

    public static class Dependency { }
    public static class MixedClient {
        final Dependency dependency;
        final long millis;
        final String label;
        MixedClient(Dependency dependency, @Value("#{${cfg.seconds:5} * 1000L}") long millis,
                    @Value("${cfg.label}") String label) {
            this.dependency = dependency; this.millis = millis; this.label = label;
        }
    }
    @Test void dependenciesAndMixedArgumentsSurviveRepeatedSaves() {
        try (var context = context(Dependency.class, MixedClient.class)) {
            var dependency = context.getBean(Dependency.class);
            var rebinder = new SpringPropertyRebinder(List.of(context));
            assertEquals(PropertyChangeOutcome.State.APPLIED, rebinder.apply(Map.of("cfg.seconds", "8")).state());
            assertSame(dependency, context.getBean(MixedClient.class).dependency);
            assertEquals(8000, context.getBean(MixedClient.class).millis);
            assertEquals(PropertyChangeOutcome.State.APPLIED, rebinder.apply(Map.of("cfg.label", "new")).state());
            assertEquals("new", context.getBean(MixedClient.class).label);
            assertEquals(8000, context.getBean(MixedClient.class).millis);
            assertSame(dependency, context.getBean(MixedClient.class).dependency);
            int before = Effects.calls;
            var held = rebinder.apply(Map.of("cfg.label", "#{T(" + Effects.class.getName() + ").touch()}"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, held.state(), held.findings().toString());
            assertEquals(before, Effects.calls);
            assertEquals("new", context.getBean(MixedClient.class).label);
        }
    }

    public static class Ambiguous {
        Ambiguous() { }
        Ambiguous(@Value("#{${cfg.seconds} * 1000}") int value) { }
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class Factory {
        @org.springframework.context.annotation.Bean
        Client factoryClient() { return new Client(5000, 20); }
    }
    @Test void ambiguousAndFactoryConstructionAreHeldWithoutRecreation() {
        for (Class<?> configuration : List.of(Ambiguous.class, Factory.class)) {
            try (var context = context(configuration)) {
                var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.seconds", "8"));
                assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), outcome.findings().toString());
                String reason = configuration == Ambiguous.class ? "exactly one constructor" : "factory methods";
                assertTrue(outcome.findings().stream().anyMatch(s -> s.contains(reason)), outcome.findings().toString());
                assertEquals("5", context.getEnvironment().getProperty("cfg.seconds"));
            }
        }
    }

    @Test void manualSuppliedAndExplicitArgumentsAreHeld() {
        for (String shape : List.of("manual", "supplier", "explicit")) {
            try (var context = context()) {
                if (shape.equals("manual")) {
                    context.getBeanFactory().registerSingleton("client", new Client(5000, 20));
                } else if (shape.equals("supplier")) {
                    context.registerBean("client", Client.class, () -> new Client(5000, 20));
                } else {
                    var definition = new org.springframework.beans.factory.support.RootBeanDefinition(Client.class);
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(0, 5000L);
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(1, 20);
                    context.registerBeanDefinition("client", definition);
                }
                Object original = context.getBean("client");
                int before = Client.constructions;
                var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.seconds", "8"));
                assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), shape + outcome.findings());
                assertSame(original, context.getBean("client"));
                assertEquals(before, Client.constructions);
                assertEquals("5", context.getEnvironment().getProperty("cfg.seconds"));
            }
        }
    }

    @Test void proxyAndUnavailableConstructorCacheAreHeld() {
        try (var context = context()) {
            var factory = new org.springframework.aop.framework.ProxyFactory(new Client(5000, 20));
            context.getBeanFactory().registerSingleton("client", factory.getProxy());
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), outcome.findings().toString());
            assertTrue(outcome.findings().stream().anyMatch(s -> s.contains("unproxied")));
        }
        try (var context = context(Client.class)) {
            String name = context.getBeanNamesForType(Client.class)[0];
            Object definition = context.getBeanFactory().getMergedBeanDefinition(name);
            Object original = context.getBean(Client.class);
            assertTrue(com.onurkat.reclazz.util.Reflect.writeField(definition, "preparedConstructorArguments", null));
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), outcome.findings().toString());
            assertSame(original, context.getBean(Client.class));
        }
    }

    public static class Converted {
        final int value;
        Converted(@Value("#{'${cfg.label}' ? 1 : 0}") int value) { this.value = value; }
    }
    @Test void customConversionMatchesSpringConstructorInjection() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var conversion = new org.springframework.core.convert.support.DefaultConversionService();
            conversion.addConverter(String.class, Boolean.class, text -> text.equals("new"));
            context.getBeanFactory().setConversionService(conversion);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("original", Map.of("cfg.label", "old")));
            context.register(Converted.class);
            context.refresh();
            assertEquals(0, context.getBean(Converted.class).value);
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.label", "new"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, outcome.state(), outcome.findings().toString());
            assertEquals(1, context.getBean(Converted.class).value);
        }
    }

    public static class FailsOnRecreation {
        static int calls;
        FailsOnRecreation(@Value("#{${cfg.seconds} * 1000L}") long millis) {
            calls++;
            if (millis == 8000) throw new IllegalStateException("constructor cannot accept 8000");
        }
    }
    @Test void liveConstructorFailureIsPartialAndNotAccepted() {
        FailsOnRecreation.calls = 0;
        try (var context = context(FailsOnRecreation.class)) {
            var accepted = new AtomicBoolean();
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.seconds", "8"),
                    Runnable::run, () -> accepted.set(true));
            assertEquals(PropertyChangeOutcome.State.PARTIAL, outcome.state(), outcome.findings().toString());
            assertFalse(accepted.get());
            assertEquals(2, FailsOnRecreation.calls);
            assertEquals("8", context.getEnvironment().getProperty("cfg.seconds"));
            assertFalse(outcome.findings().isEmpty());
        }
    }

    public static class NonScalar {
        NonScalar(@Value("#{${cfg.seconds}}") Object value) { }
    }
    public static class Template {
        Template(@Value("timeout=#{${cfg.seconds}}") String value) { }
    }
    @org.springframework.boot.context.properties.ConfigurationProperties("separate")
    public static class BootBound {
        BootBound(@Value("#{${cfg.seconds}}") int value) { }
    }
    @Test void nonScalarTemplatesAndBootBindingAreNamedAsUnsupported() {
        for (Class<?> type : List.of(NonScalar.class, Template.class, BootBound.class)) {
            try (var context = context(type)) {
                Object original = context.getBean(type);
                var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.seconds", "8"));
                assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), type + outcome.findings().toString());
                assertTrue(outcome.findings().stream().anyMatch(s -> s.contains(".<init>[0]")));
                assertSame(original, context.getBean(type));
                assertEquals("5", context.getEnvironment().getProperty("cfg.seconds"));
            }
        }
    }

    @Test void customResolverIsNotInvokedBeforeRejection() {
        Client.constructions = 0;
        try (var context = context(Client.class)) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            context.getBeanFactory().setBeanExpressionResolver((expression, beanContext) -> {
                calls.incrementAndGet(); return 8000L;
            });
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), outcome.findings().toString());
            assertEquals(0, calls.get());
            assertEquals(1, Client.constructions);
        }
    }
}
