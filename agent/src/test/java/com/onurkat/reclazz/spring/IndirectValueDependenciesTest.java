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

class IndirectValueDependenciesTest {
    public static class Fields {
        @Value("${client.seconds}") int seconds;
        @Value("#{${client.seconds} * 1000L}") long millis;
        @Value("#{100 / ${client.divisor}}") int rate;
        @Value("${label}") String label;
    }
    public static class Client {
        static int constructions;
        final long millis;
        final int rate;
        Client(@Value("#{${client.seconds} * 1000L}") long millis,
               @Value("#{100 / ${client.divisor}}") int rate) {
            constructions++; this.millis = millis; this.rate = rate;
        }
    }
    public static class Holder {
        Client client;
        Holder(Client client) { this.client = client; }
    }
    static Map<String, Object> properties() {
        return Map.of("client.seconds", "${middle.seconds}", "middle.seconds", "${shared.seconds}",
                "client.divisor", "${shared.divisor}", "shared.seconds", "5", "shared.divisor", "5", "label", "old");
    }
    static AnnotationConfigApplicationContext context(Map<String, Object> values, Class<?>... types) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("original", values));
        if (types.length > 0) context.register(types);
        context.refresh();
        return context;
    }

    @Test void sourceChangeUpdatesFieldsAndRebuildsConstructor() {
        Client.constructions = 0;
        try (var context = context(properties(), Fields.class, Client.class)) {
            var original = context.getBean(Client.class);
            var fields = context.getBean(Fields.class);
            var holder = new Holder(original);
            context.getBeanFactory().registerSingleton("holder", holder);
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("shared.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, outcome.state(), outcome.findings().toString());
            assertAll(
                    () -> assertEquals(8, fields.seconds),
                    () -> assertEquals(8000, fields.millis),
                    () -> assertEquals(8000, context.getBean(Client.class).millis),
                    () -> assertNotSame(original, context.getBean(Client.class)),
                    () -> assertSame(context.getBean(Client.class), holder.client),
                    () -> assertSame(fields, context.getBean(Fields.class)),
                    () -> assertEquals(2, Client.constructions));
            assertEquals(2, outcome.valueFields());
            assertEquals(1, outcome.rebuilt().size());
            assertEquals("${middle.seconds}", context.getEnvironment().getPropertySources().get("original").getProperty("client.seconds"));
            assertNull(context.getEnvironment().getPropertySources().get(SpringPropertyRebinder.SOURCE_NAME).getProperty("client.seconds"));
        }
    }

    @Test void invalidIndirectValueHoldsAllKeys() {
        Client.constructions = 0;
        try (var context = context(properties(), Fields.class, Client.class)) {
            var original = context.getBean(Client.class);
            var accepted = new AtomicBoolean();
            var entered = new AtomicBoolean();
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(
                    Map.of("shared.seconds", "8", "shared.divisor", "0", "label", "new"),
                    work -> { entered.set(true); work.run(); }, () -> accepted.set(true));
            assertEquals(PropertyChangeOutcome.State.REJECTED, outcome.state(), outcome.findings().toString());
            assertFalse(entered.get()); assertFalse(accepted.get());
            assertSame(original, context.getBean(Client.class));
            assertEquals(1, Client.constructions);
            assertEquals(5000, context.getBean(Fields.class).millis);
            assertEquals("old", context.getBean(Fields.class).label);
            assertEquals("5", context.getEnvironment().getProperty("shared.seconds"));
            assertEquals("5", context.getEnvironment().getProperty("shared.divisor"));
            assertEquals("old", context.getEnvironment().getProperty("label"));
        }
    }

    public static class DirectClient {
        final int seconds;
        DirectClient(@Value("${client.seconds}") int seconds) { this.seconds = seconds; }
    }
    @Test void directConstructorPlaceholdersAlsoFollowTheChain() {
        try (var context = context(properties(), DirectClient.class)) {
            var original = context.getBean(DirectClient.class);
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("shared.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, outcome.state(), outcome.findings().toString());
            assertEquals(8, context.getBean(DirectClient.class).seconds);
            assertNotSame(original, context.getBean(DirectClient.class));
        }
    }

    @Test void retargetingAnAliasDropsItsPreviousDependencyWithoutFlatteningIt() {
        var values = new java.util.HashMap<>(properties());
        values.put("alternate.seconds", "11");
        try (var context = context(values, Fields.class, Client.class)) {
            var rebinder = new SpringPropertyRebinder(List.of(context));
            assertEquals(PropertyChangeOutcome.State.APPLIED,
                    rebinder.apply(Map.of("middle.seconds", "${alternate.seconds}")).state());
            assertEquals(11000, context.getBean(Fields.class).millis);
            var replacement = context.getBean(Client.class);
            assertEquals(11000, replacement.millis);
            var ignored = rebinder.apply(Map.of("shared.seconds", "9"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, ignored.state());
            assertTrue(ignored.rebuilt().isEmpty());
            assertEquals(0, ignored.valueFields());
            assertSame(replacement, context.getBean(Client.class));
            assertEquals(PropertyChangeOutcome.State.APPLIED,
                    rebinder.apply(Map.of("alternate.seconds", "12")).state());
            assertEquals(12000, context.getBean(Fields.class).millis);
            assertEquals(12000, context.getBean(Client.class).millis);
            var overlay = context.getEnvironment().getPropertySources().get(SpringPropertyRebinder.SOURCE_NAME);
            assertEquals("${alternate.seconds}", overlay.getProperty("middle.seconds"));
            assertNull(overlay.getProperty("client.seconds"));
        }
    }

    public static class Dynamic {
        final int seconds;
        Dynamic(@Value("${route.${profile}.seconds:${fallback.seconds}}") int seconds) { this.seconds = seconds; }
    }
    @Test void nestedKeysAndDefaultsUseTheCurrentlySelectedRoute() {
        Map<String, Object> values = Map.of("profile", "east", "route.east.seconds", "${shared.seconds}",
                "route.west.seconds", "11", "shared.seconds", "5", "fallback.seconds", "7");
        try (var context = context(values, Dynamic.class)) {
            var rebinder = new SpringPropertyRebinder(List.of(context));
            assertEquals(5, context.getBean(Dynamic.class).seconds);
            assertEquals(PropertyChangeOutcome.State.APPLIED, rebinder.apply(Map.of("shared.seconds", "8")).state());
            assertEquals(8, context.getBean(Dynamic.class).seconds);
            assertEquals(PropertyChangeOutcome.State.APPLIED, rebinder.apply(Map.of("profile", "west")).state());
            var west = context.getBean(Dynamic.class);
            assertEquals(11, west.seconds);
            assertEquals(PropertyChangeOutcome.State.APPLIED, rebinder.apply(Map.of("shared.seconds", "9")).state());
            assertSame(west, context.getBean(Dynamic.class));
            assertEquals(PropertyChangeOutcome.State.APPLIED, rebinder.apply(Map.of("profile", "missing")).state());
            assertEquals(7, context.getBean(Dynamic.class).seconds);
            assertEquals(PropertyChangeOutcome.State.APPLIED, rebinder.apply(Map.of("fallback.seconds", "13")).state());
            assertEquals(13, context.getBean(Dynamic.class).seconds);
        }
    }

    @Test void higherPriorityLiteralMasksTheLowerAliasAndUnrelatedCyclesAreNotScanned() {
        var values = new java.util.HashMap<>(properties());
        values.put("client.seconds", "7");
        values.put("cycle.a", "${cycle.b}"); values.put("cycle.b", "${cycle.a}");
        try (var context = context(values, Fields.class, Client.class)) {
            context.getEnvironment().getPropertySources().addLast(new MapPropertySource("lower",
                    Map.of("client.seconds", "${shared.seconds}")));
            var original = context.getBean(Client.class);
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("shared.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, outcome.state(), outcome.findings().toString());
            assertEquals(0, outcome.valueFields()); assertTrue(outcome.rebuilt().isEmpty());
            assertSame(original, context.getBean(Client.class));
            assertEquals(7000, context.getBean(Fields.class).millis);
        }
    }

    @Test void introducedCycleRejectsAndCorrectionRecovers() {
        try (var context = context(properties(), Fields.class, Client.class)) {
            var rebinder = new SpringPropertyRebinder(List.of(context));
            var original = context.getBean(Client.class);
            var outcome = rebinder.apply(Map.of("shared.seconds", "${client.seconds}", "label", "cyclic"));
            assertEquals(PropertyChangeOutcome.State.REJECTED, outcome.state(), outcome.findings().toString());
            assertSame(original, context.getBean(Client.class));
            assertEquals("old", context.getBean(Fields.class).label);
            assertEquals("5", context.getEnvironment().getProperty("shared.seconds"));
            assertEquals(PropertyChangeOutcome.State.APPLIED,
                    rebinder.apply(Map.of("shared.seconds", "8", "label", "recovered")).state());
            assertEquals(8000, context.getBean(Client.class).millis);
            assertEquals("recovered", context.getBean(Fields.class).label);
        }
    }

    public static class ComputedOnly {
        @Value("#{${client.seconds} * 1000L}") long millis;
    }
    public static class Effects {
        static int calls;
        public static int touch() { calls++; return 8; }
    }
    @Test void indirectUnsupportedExpressionIsHeldWithoutCallingIt() {
        try (var context = context(properties(), ComputedOnly.class, Client.class)) {
            int before = Effects.calls;
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("shared.seconds",
                    "T(" + Effects.class.getName() + ").touch()", "label", "new"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), outcome.findings().toString());
            assertEquals(before, Effects.calls);
            assertEquals(5000, context.getBean(ComputedOnly.class).millis);
            assertEquals(5000, context.getBean(Client.class).millis);
            assertEquals("old", context.getEnvironment().getProperty("label"));
        }
    }

    @Test void contextsKeepSeparateDependenciesAndAllHoldOnOneFailure() {
        var independent = new java.util.HashMap<>(properties());
        independent.put("client.seconds", "7");
        try (var one = context(properties(), Fields.class, Client.class);
             var two = context(independent, Fields.class, Client.class)) {
            var unaffected = two.getBean(Client.class);
            var rebinder = new SpringPropertyRebinder(List.of(one, two));
            assertEquals(PropertyChangeOutcome.State.APPLIED, rebinder.apply(Map.of("shared.seconds", "8")).state());
            assertEquals(8000, one.getBean(Client.class).millis);
            assertSame(unaffected, two.getBean(Client.class));
            var outcome = rebinder.apply(Map.of("shared.seconds", "'bad'", "label", "bad"));
            assertEquals(PropertyChangeOutcome.State.REJECTED, outcome.state());
            assertEquals("old", one.getBean(Fields.class).label);
            assertEquals("old", two.getBean(Fields.class).label);
            assertEquals("8", two.getEnvironment().getProperty("shared.seconds"));
        }
    }

    public static class Text {
        @Value("${message}") String value;
    }
    @Test void unprovableDependenciesHoldRatherThanSilentlySkipping() {
        for (String shape : List.of("cycle", "depth", "length")) {
            var values = new java.util.HashMap<String, Object>();
            values.put("message", "start");
            try (var context = context(values, Text.class)) {
                // Simulate sources changed outside Reclazz after Spring injection.
                if (shape.equals("cycle")) values.put("message", "${message}");
                else if (shape.equals("length")) values.put("message", "x".repeat(8193));
                else {
                    values.put("message", "${link.0}");
                    for (int n = 0; n < 140; n++) values.put("link." + n, "${link." + (n + 1) + "}");
                    values.put("link.140", "done");
                }
                var accepted = new AtomicBoolean();
                var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("unrelated", "new"),
                        Runnable::run, () -> accepted.set(true));
                assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), shape + outcome.findings());
                assertFalse(accepted.get());
                assertEquals("start", context.getBean(Text.class).value);
                assertNull(context.getEnvironment().getProperty("unrelated"));
            }
        }
    }

    @Test void nonEnumerableSourcesWorkAndUnreadableOnesHold() {
        var broken = new AtomicBoolean();
        try (var context = context(Map.of("shared.message", "old"))) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.PropertySource<Object>("remote") {
                @Override public Object getProperty(String name) {
                    if (!name.equals("message")) return null;
                    if (broken.get()) throw new IllegalStateException("source temporarily unavailable");
                    return "${shared.message}";
                }
            });
            context.registerBean(Text.class);
            var original = context.getBean(Text.class);
            var rebinder = new SpringPropertyRebinder(List.of(context));
            assertEquals(PropertyChangeOutcome.State.APPLIED, rebinder.apply(Map.of("shared.message", "new")).state());
            assertEquals("new", original.value);
            broken.set(true);
            var outcome = rebinder.apply(Map.of("shared.message", "later"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), outcome.findings().toString());
            assertEquals("new", original.value);
            assertEquals("new", context.getEnvironment().getProperty("shared.message"));
        }
    }

    @Test void bootAttachedSourcesDoNotSendTheCandidateBackToLiveValues() {
        try (var context = context(properties(), Fields.class, Client.class)) {
            org.springframework.boot.context.properties.source.ConfigurationPropertySources.attach(context.getEnvironment());
            assertEquals(PropertyChangeOutcome.State.APPLIED,
                    new SpringPropertyRebinder(List.of(context)).apply(Map.of("shared.seconds", "8")).state());
            assertEquals(8000, context.getBean(Fields.class).millis);
            assertEquals(8000, context.getBean(Client.class).millis);
        }
    }
}
