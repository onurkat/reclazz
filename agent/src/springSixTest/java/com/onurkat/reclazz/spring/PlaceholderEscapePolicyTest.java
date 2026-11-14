/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.AbstractPropertyResolver;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PlaceholderEscapePolicyTest {
    @Test void defaultPolicyTracesIndirectAndEscapedValuesLikeNative() throws Exception {
        var reads = new ArrayList<String>();
        var values = Map.of("a", "${leaf}", "leaf", "value", "b", "fallback");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new PropertySource<Object>("recording") {
                @Override public Object getProperty(String name) { reads.add(name); return values.get(name); }
            });
            context.refresh();
            for (String expression : List.of("${a}", "${a:${b}}", "\\${a}")) {
                reads.clear();
                context.getEnvironment().resolveRequiredPlaceholders(expression);
                var nativeReads = List.copyOf(reads);
                var trace = new PropertyValueDependencies(context, Map.of("unrelated", "new"));
                reads.clear();
                assertFalse(trace.test(expression));
                assertEquals(nativeReads, reads, expression);
            }
            assertTrue(new PropertyValueDependencies(context, Map.of("leaf", "new")).test("${a}"));
        }
    }

    @Test void preparedEnvironmentUsesSamePolicy() throws Exception {
        try (var context = context()) {
            Object candidate = PropertyChangeCheck.candidateEnvironment(context.getClass().getClassLoader(),
                    context.getEnvironment(), Map.of("leaf", "new"));
            assertTrue(new PropertyValueDependencies(context, Map.of("leaf", "new"), candidate).test("${alias}"));
        }
    }

    @Test void liveCustomEscapeIsUncheckable() throws Exception { mismatch('!'); }
    @Test void liveNullEscapeIsUncheckable() throws Exception { mismatch(null); }

    private static void mismatch(Character escape) throws Exception {
        Field member;
        try { member = AbstractPropertyResolver.class.getDeclaredField("escapeCharacter"); }
        catch (NoSuchFieldException legacy) { assumeTrue(false, "Native resolver has no escape policy"); return; }
        member.setAccessible(true);
        try (var context = context()) {
            var getter = org.springframework.core.env.AbstractEnvironment.class.getDeclaredMethod("getPropertyResolver");
            getter.setAccessible(true);
            Object resolver = getter.invoke(context.getEnvironment());
            assumeTrue(!Objects.equals(escape, member.get(resolver)), "Requires different native default");
            context.getEnvironment().getClass().getMethod("setEscapeCharacter", Character.class)
                    .invoke(context.getEnvironment(), escape);
            Object candidate = PropertyChangeCheck.candidateEnvironment(context.getClass().getClassLoader(),
                    context.getEnvironment(), Map.of("leaf", "new"));
            for (Object prepared : new Object[]{null, candidate}) {
                var failure = assertThrows(IllegalStateException.class,
                        () -> new PropertyValueDependencies(context, Map.of("leaf", "new"), prepared));
                assertTrue(failure.getMessage().contains("escape policy"), failure.toString());
            }
            var result = new PropertyChangeCheck().check(List.of(context), Map.of("leaf", "new"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, result.state(), result.findings().toString());
            assertTrue(result.findings().toString().contains("escape policy"));
            assertEquals("old", context.getEnvironment().getProperty("leaf"));
        }
    }

    @Test void budgetAndCycleStillRefuse() throws Exception {
        for (String value : List.of("${alias}", "x".repeat(8193))) {
            try (var context = context()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("invalid", Map.of("alias", value)));
                var trace = new PropertyValueDependencies(context, Map.of("unrelated", "new"));
                assertThrows(IllegalStateException.class, () -> trace.test("${alias}"));
            }
        }
    }

    private static AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("values", Map.of("alias", "${leaf}", "leaf", "old")));
        context.refresh();
        return context;
    }
}
