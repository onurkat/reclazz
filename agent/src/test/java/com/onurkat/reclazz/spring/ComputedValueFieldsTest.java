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

import static org.junit.jupiter.api.Assertions.*;

class ComputedValueFieldsTest {
    public static class Reader {
        @Value("#{${cfg.seconds} * 1000L}") long millis;
        @Value("#{100 / ${cfg.divisor}}") int rate;
        @Value("${cfg.label}") String label;
    }

    @Test
    void validSaveUpdatesComputedFieldsAndPreservesTheSingleton() {
        try (var context = context(Reader.class)) {
            Reader bean = context.getBean(Reader.class);
            assertEquals(5000, bean.millis);
            assertEquals(20, bean.rate);
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(
                    Map.of("cfg.seconds", "8", "cfg.divisor", "4", "cfg.label", "new"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, outcome.state(), outcome.findings().toString());
            assertSame(bean, context.getBean(Reader.class));
            assertEquals(8000, bean.millis, "computed field must follow the property without a restart");
            assertEquals(25, bean.rate);
            assertEquals("new", bean.label);
            assertEquals(3, outcome.valueFields());
        }
    }

    @Test
    void divisionByZeroRejectsAllCandidateKeysBeforeAnyLiveWrite() {
        try (var context = context(Reader.class)) {
            var accepted = new java.util.concurrent.atomic.AtomicBoolean();
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(
                    Map.of("cfg.seconds", "8", "cfg.divisor", "0", "cfg.label", "new"),
                    Runnable::run, () -> accepted.set(true));
            assertEquals(PropertyChangeOutcome.State.REJECTED, outcome.state(), outcome.findings().toString());
            assertFalse(accepted.get());
            Reader bean = context.getBean(Reader.class);
            assertEquals(5000, bean.millis);
            assertEquals(20, bean.rate);
            assertEquals("old", bean.label);
            assertEquals("5", context.getEnvironment().getProperty("cfg.seconds"));
            assertEquals("5", context.getEnvironment().getProperty("cfg.divisor"));
            assertEquals("old", context.getEnvironment().getProperty("cfg.label"));
        }
    }

    public static class Calculations {
        @Value("#{${cfg.seconds} >= 5 && ${cfg.divisor} != 0}") boolean enabled;
        @Value("#{${cfg.seconds} > 5 ? 'long' : 'short'}") String category;
        @Value("#{${cfg.seconds} + ${cfg.missing:${cfg.divisor}}}") int total;
        @Value("#{${cfg.seconds} / 2.0}") double half;
        @Value("#{'${cfg.label}' + '-ok'}") String label;
        @Value("#{'${cfg.label}' ?: 'fallback'}") String fallback;
    }

    @Test
    void operatorsDefaultsAndNestedPlaceholdersFollowSpringsValues() {
        try (var context = context(Calculations.class)) {
            var bean = context.getBean(Calculations.class);
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(
                    Map.of("cfg.seconds", "8", "cfg.divisor", "4", "cfg.label", ""));
            assertEquals(PropertyChangeOutcome.State.APPLIED, outcome.state(), outcome.findings().toString());
            assertTrue(bean.enabled);
            assertEquals("long", bean.category);
            assertEquals(12, bean.total);
            assertEquals(4.0, bean.half);
            assertEquals("-ok", bean.label);
            assertEquals("fallback", bean.fallback);
            assertEquals(6, outcome.valueFields());
        }
    }

    public static class ByteReader {
        @Value("#{${cfg.seconds}}") byte small;
    }

    public static class DoubleReader {
        @Value("#{1.0 / ${cfg.divisor}}") double inverse;
    }

    @Test
    void syntaxConversionAndNonFiniteResultsRejectBeforeWriting() {
        for (String invalid : List.of(")", "'not-a-number'", "1000")) {
            try (var context = context(ByteReader.class)) {
                var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.seconds", invalid));
                assertEquals(PropertyChangeOutcome.State.REJECTED, outcome.state(), outcome.findings().toString());
                assertEquals(5, context.getBean(ByteReader.class).small);
                assertEquals("5", context.getEnvironment().getProperty("cfg.seconds"));
            }
        }
        try (var context = context(DoubleReader.class)) {
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.divisor", "0"));
            assertEquals(PropertyChangeOutcome.State.REJECTED, outcome.state(), outcome.findings().toString());
            assertEquals(0.2, context.getBean(DoubleReader.class).inverse);
            assertEquals("5", context.getEnvironment().getProperty("cfg.divisor"));
        }
    }

    public static class ExpressionReader {
        @Value("#{${cfg.expression:1}}") int value;
    }

    public static class Effects {
        static int calls;
        public Effects() { calls++; }
        public static int touch() { calls++; return 2; }
        public int next() { calls++; return 3; }
        public int getValue() { calls++; return 4; }
    }

    @Test
    void everyForbiddenBranchIsCheckedBeforeAnyApplicationCodeCanRun() {
        String name = Effects.class.getName();
        List<String> expressions = List.of("T(" + name + ").touch()", "@effects.next()", "@effects.value",
                "new " + name + "()", "#root", "#x=1",
                "'a' matches 'a'", "'abc'.length()", "systemProperties['java.version']",
                "false ? T(" + name + ").touch() : 1");
        try (var context = context(ExpressionReader.class)) {
            context.getBeanFactory().registerSingleton("effects", new Effects());
            int before = Effects.calls;
            for (String expression : expressions) {
                var accepted = new java.util.concurrent.atomic.AtomicBoolean();
                var outcome = new SpringPropertyRebinder(List.of(context)).apply(
                        Map.of("cfg.expression", expression, "cfg.label", "new"),
                        Runnable::run, () -> accepted.set(true));
                assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), expression + outcome.findings());
                assertFalse(accepted.get());
                String member = context.getBeanNamesForType(ExpressionReader.class)[0] + ".value";
                assertTrue(outcome.findings().stream().anyMatch(s -> s.contains(member)), outcome.findings().toString());
                assertEquals(before, Effects.calls, expression);
                assertEquals(1, context.getBean(ExpressionReader.class).value);
                assertEquals("old", context.getEnvironment().getProperty("cfg.label"));
                assertNull(context.getEnvironment().getProperty("cfg.expression"));
            }
        }
    }

    @Test
    void largeOrDeepExpressionsAreHeldWithoutLiveChanges() {
        try (var context = context(ExpressionReader.class)) {
            for (String expression : List.of("1+".repeat(1100) + "1", "1+".repeat(40) + "1")) {
                var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.expression", expression));
                assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), outcome.findings().toString());
                assertEquals(1, context.getBean(ExpressionReader.class).value);
                assertNull(context.getEnvironment().getProperty("cfg.expression"));
            }
        }
    }

    @Test
    void customResolverAndParserAreNotInvokedOrSilentlyReplaced() {
        for (boolean customParser : List.of(false, true)) {
            try (var context = context(Reader.class)) {
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                if (customParser) {
                    var resolver = new org.springframework.context.expression.StandardBeanExpressionResolver();
                    resolver.setExpressionParser(new org.springframework.expression.spel.standard.SpelExpressionParser() {
                        @Override public org.springframework.expression.spel.standard.SpelExpression parseRaw(String expression) {
                            calls.incrementAndGet();
                            return super.parseRaw(expression);
                        }
                    });
                    context.getBeanFactory().setBeanExpressionResolver(resolver);
                } else {
                    context.getBeanFactory().setBeanExpressionResolver((expression, beanContext) -> {
                        calls.incrementAndGet(); return 999;
                    });
                }
                var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.seconds", "8"));
                assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), outcome.findings().toString());
                assertEquals(0, calls.get());
                assertEquals(5000, context.getBean(Reader.class).millis);
            }
        }
    }

    public static class TemplateReader {
        @Value("delay=#{${cfg.seconds}}") String value;
    }
    public static class ObjectReader {
        @Value("#{${cfg.seconds}}") Object value;
    }
    public static class FinalReader {
        @Value("#{${cfg.seconds}}") final Integer value = -1;
    }

    @Test
    void nonScalarAndFinalFieldsAreNamedAsUnsupported() {
        for (Class<?> type : List.of(ObjectReader.class, FinalReader.class)) {
            try (var context = context(type)) {
                var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.seconds", "8"));
                assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), outcome.findings().toString());
                assertEquals("5", context.getEnvironment().getProperty("cfg.seconds"));
            }
        }
        // A mixed text and #{...} template into a scalar field is supported.
        try (var context = context(TemplateReader.class)) {
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.seconds", "8"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, outcome.state(), outcome.findings().toString());
            assertEquals("delay=8", context.getBean(TemplateReader.class).value);
        }
    }

    @Test
    void oneInvalidContextHoldsEveryContextAndValidRetryUpdatesBoth() {
        try (var one = context(ByteReader.class); var two = context(Reader.class)) {
            var rebinder = new SpringPropertyRebinder(List.of(one, two));
            var rejected = rebinder.apply(Map.of("cfg.seconds", "1000", "cfg.label", "new"));
            assertEquals(PropertyChangeOutcome.State.REJECTED, rejected.state());
            assertEquals("5", one.getEnvironment().getProperty("cfg.seconds"));
            assertEquals("5", two.getEnvironment().getProperty("cfg.seconds"));
            assertEquals("old", two.getBean(Reader.class).label);
            assertEquals(PropertyChangeOutcome.State.APPLIED, rebinder.apply(Map.of("cfg.seconds", "8")).state());
            assertEquals(8, one.getBean(ByteReader.class).small);
            assertEquals(8000, two.getBean(Reader.class).millis);
        }
    }

    @Test
    void unrelatedPropertiesDoNotOverwriteAComputedField() {
        try (var context = context(Reader.class)) {
            Reader bean = context.getBean(Reader.class);
            bean.millis = 77;
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.label", "new"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, outcome.state());
            assertEquals(1, outcome.valueFields());
            assertEquals(77, bean.millis);
        }
    }

    public static class ConvertedCondition {
        @Value("#{'${cfg.label}' ? 1 : 0}") int value;
    }

    @Test
    void expressionConversionsUseTheSameServiceAsSpringStartup() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var conversion = new org.springframework.core.convert.support.DefaultConversionService();
            conversion.addConverter(String.class, Boolean.class, text -> text.equals("new"));
            context.getBeanFactory().setConversionService(conversion);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("original", Map.of("cfg.label", "old")));
            context.register(ConvertedCondition.class);
            context.refresh();
            assertEquals(0, context.getBean(ConvertedCondition.class).value);
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("cfg.label", "new"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, outcome.state(), outcome.findings().toString());
            assertEquals(1, context.getBean(ConvertedCondition.class).value);
        }
    }

    private static AnnotationConfigApplicationContext context(Class<?>... types) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("original",
                Map.of("cfg.seconds", "5", "cfg.divisor", "5", "cfg.label", "old")));
        context.register(types);
        context.refresh();
        return context;
    }
}
