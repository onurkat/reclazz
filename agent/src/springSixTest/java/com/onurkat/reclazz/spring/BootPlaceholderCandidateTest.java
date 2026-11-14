/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import static org.junit.jupiter.api.Assertions.*;

class BootPlaceholderCandidateTest {
    @TempDir Path tmp;
    static class BootEnvironment extends StandardEnvironment {
        @Override protected ConfigurablePropertyResolver createPropertyResolver(MutablePropertySources sources) {
            return ConfigurationPropertySources.createPropertyResolver(sources);
        }
    }
    static class Fields {
        @Value("${a.b-c:fallback}") String direct;
        @Value("${alias}") String alias;
        @Value("${missing:${alias}}") String nested;
    }
    static class ConstructorValue {
        final String value;
        ConstructorValue(@Value("${alias}") String value) { this.value = value; }
    }
    record FactoryValue(String value) { }
    @Configuration(proxyBeanMethods=false) static class Factory {
        @Bean FactoryValue factoryValue(@Value("${alias}") String value) { return new FactoryValue(value); }
    }
    private AnnotationConfigApplicationContext context(Map<String,Object> data, Path file, boolean masked,
                                                       Class<?>... beans) throws Exception {
        var context = new AnnotationConfigApplicationContext();
        context.setEnvironment(new BootEnvironment());
        var sources = context.getEnvironment().getPropertySources();
        for (var source : sources.stream().toList()) sources.remove(source.getName());
        if (file != null) new PropertiesPropertySourceLoader().load("owned", new FileSystemResource(file))
                .forEach(sources::addLast);
        else sources.addLast(new MapPropertySource("values", data));
        sources.addLast(new MapPropertySource("stable", Map.of("alias", "${a.b-c:fallback}")));
        if (masked) sources.addFirst(new MapPropertySource("commandLineArgs", Map.of("a.b-c", "masked")));
        ConfigurationPropertySources.attach(context.getEnvironment());
        context.register(beans); context.refresh(); return context;
    }
    private Path file() throws Exception {
        Path file = tmp.resolve("application.properties");
        Files.writeString(file, "a.bC=old\n"); return file;
    }
    private static void applied(PropertyChangeOutcome result) {
        assertEquals(PropertyChangeOutcome.State.APPLIED, result.state(), result.findings().toString());
    }
    private static void fields(AnnotationConfigApplicationContext c, String expected) {
        Fields f = c.getBean(Fields.class);
        assertEquals(expected, f.direct); assertEquals(expected, f.alias); assertEquals(expected, f.nested);
        assertEquals(expected, c.getEnvironment().resolveRequiredPlaceholders("${alias}"));
    }
    @Test void relaxedOverlayUpdatesDirectAliasAndNestedFields() throws Exception {
        try (var c = context(Map.of("a.bC", "old"), null, false, Fields.class)) {
            fields(c, "old");
            var result = new SpringPropertyRebinder(List.of(c)).apply(Map.of("a.bC", "new"));
            applied(result); assertEquals(3, result.valueFields()); fields(c, "new");
            applied(new SpringPropertyRebinder(List.of(c)).apply(Map.of("a.bC", "again")));
            fields(c, "again");
            var unchanged = new SpringPropertyRebinder(List.of(c)).apply(Map.of("a.bC", "again"));
            applied(unchanged); assertEquals(0, unchanged.valueFields());
        }
    }
    @Test void deletionAliasFieldUpdatedThroughFileRoute() throws Exception {
        Path file = file();
        try (var c = context(Map.of(), file, false, Fields.class)) {
            fields(c, "old"); Files.writeString(file, "");
            var result = new SpringPropertyFiles().apply(file, List.of(c), Runnable::run).outcome();
            applied(result); assertEquals(3, result.valueFields()); fields(c, "fallback");
        }
    }
    @Test void changedWinningSourceApplied() throws Exception {
        Path file = file();
        try (var c = context(Map.of(), file, false, Fields.class)) {
            Files.writeString(file, "a.bC=new\n");
            var result = new SpringPropertyFiles().apply(file, List.of(c), Runnable::run).outcome();
            applied(result); assertEquals(3, result.valueFields()); fields(c, "new");
        }
    }
    @Test void higherPrecedenceSourceMasksChangedFileKey() throws Exception {
        Path file = file();
        try (var c = context(Map.of(), file, true, Fields.class, ConstructorValue.class, Factory.class)) {
            Object ctor = c.getBean(ConstructorValue.class), factory = c.getBean(FactoryValue.class);
            Files.writeString(file, "");
            var result = new SpringPropertyFiles().apply(file, List.of(c), Runnable::run).outcome();
            applied(result); assertEquals(0, result.valueFields()); assertTrue(result.rebuilt().isEmpty());
            fields(c, "masked"); assertSame(ctor, c.getBean(ConstructorValue.class));
            assertSame(factory, c.getBean(FactoryValue.class));
        }
    }
    @Test void deletionAliasConstructorBeanRecreated() throws Exception {
        Path file = file();
        try (var c = context(Map.of(), file, false, ConstructorValue.class)) {
            var old = c.getBean(ConstructorValue.class); assertEquals("old", old.value);
            Files.writeString(file, "");
            var result = new SpringPropertyFiles().apply(file, List.of(c), Runnable::run).outcome();
            applied(result); assertEquals(1, result.rebuilt().size());
            assertNotSame(old, c.getBean(ConstructorValue.class));
            assertEquals("fallback", c.getBean(ConstructorValue.class).value);
        }
    }
    @Test void deletionAliasFactoryParameterRecreated() throws Exception {
        Path file = file();
        try (var c = context(Map.of(), file, false, Factory.class)) {
            var old = c.getBean(FactoryValue.class); assertEquals("old", old.value());
            Files.writeString(file, "");
            var result = new SpringPropertyFiles().apply(file, List.of(c), Runnable::run).outcome();
            applied(result); assertEquals(1, result.rebuilt().size());
            assertNotSame(old, c.getBean(FactoryValue.class));
            assertEquals("fallback", c.getBean(FactoryValue.class).value());
        }
    }
    @Test void unrelatedOverlayDoesNotReinjectOrRecreate() throws Exception {
        try (var c = context(Map.of("a.bC", "old"), null, false, Fields.class, ConstructorValue.class, Factory.class)) {
            Object ctor = c.getBean(ConstructorValue.class), factory = c.getBean(FactoryValue.class);
            var result = new SpringPropertyRebinder(List.of(c)).apply(Map.of("unrelated", "new"));
            applied(result); assertEquals(0, result.valueFields()); assertTrue(result.rebuilt().isEmpty());
            fields(c, "old"); assertSame(ctor, c.getBean(ConstructorValue.class));
            assertSame(factory, c.getBean(FactoryValue.class));
        }
    }
    @Test void candidateAdapterIsDetachedFromLiveSources() throws Exception {
        try (var c = context(Map.of("a.bC", "old"), null, false, Fields.class)) {
            var candidate = (ConfigurableEnvironment) PropertyChangeCheck.candidateEnvironment(
                    c.getClass().getClassLoader(), c.getEnvironment(), Map.of("a.bC", "new"));
            assertNotSame(c.getEnvironment().getPropertySources().get("configurationProperties"),
                    candidate.getPropertySources().get("configurationProperties"));
            assertEquals("new", candidate.resolveRequiredPlaceholders("${alias}")); fields(c, "old");
        }
    }
    static class Escaped { @Value("\\${alias}") String value; }
    @Test void nativeEscapeGrammarPreserved() throws Exception {
        try (var c = context(Map.of("a.bC", "old"), null, false, Escaped.class)) {
            assertEquals(c.getEnvironment().resolveRequiredPlaceholders("\\${alias}"), c.getBean(Escaped.class).value);
            applied(new SpringPropertyRebinder(List.of(c)).apply(Map.of("a.bC", "new")));
            assertEquals(c.getEnvironment().resolveRequiredPlaceholders("\\${alias}"), c.getBean(Escaped.class).value);
        }
    }
    static class UnknownEnvironment extends StandardEnvironment {
        @Override protected ConfigurablePropertyResolver createPropertyResolver(MutablePropertySources sources) {
            return new PropertySourcesPropertyResolver(sources) { };
        }
    }
    @Test void legacyPathCustomResolverRefused() throws Exception {
        try (var c = context(Map.of("a.bC", "old"), null, false, Fields.class)) {
            var unknown = new UnknownEnvironment();
            unknown.getPropertySources().addFirst(new MapPropertySource("old", Map.of("a.bC", "old")));
            c.setEnvironment(unknown);
            var result = new SpringPropertyRebinder(List.of(c)).apply(Map.of("a.bC", "new"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, result.state());
            assertEquals("old", unknown.getProperty("a.bC"));
            assertEquals("old", c.getBean(Fields.class).alias);
        }
    }
    @Test void cyclesAndOversizedValuesRefusedBeforeMutation() throws Exception {
        for (String value : List.of("${alias}", "x".repeat(8193))) {
            try (var c = context(Map.of("a.bC", "old"), null, false, Fields.class)) {
                var result = new SpringPropertyRebinder(List.of(c)).apply(Map.of("a.bC", value));
                assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, result.state(), result.findings().toString());
                fields(c, "old");
            }
        }
    }
    @Test void unreadableSourceRefusedBeforeMutation() throws Exception {
        try (var c = context(Map.of("a.bC", "old"), null, false, Fields.class)) {
            c.getEnvironment().getPropertySources().addFirst(new PropertySource<Object>("unreadable") {
                @Override public Object getProperty(String name) { throw new IllegalStateException("unreadable fixture"); }
            });
            var result = new SpringPropertyRebinder(List.of(c)).apply(Map.of("a.bC", "new"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, result.state());
            assertEquals("old", c.getBean(Fields.class).alias);
        }
    }
    @Test void selectionFromOtherContextRefusedBeforeMutation() throws Exception {
        try (var first = context(Map.of("a.bC", "old"), null, false, Fields.class);
             var second = context(Map.of("a.bC", "old"), null, false, Fields.class)) {
            var changed = Map.of("a.bC", "new");
            var check = new PropertyChangeCheck().check(List.of(first), changed);
            assertTrue(check.passed(), check.findings().toString());
            var result = new SpringPropertyRebinder(List.of(second)).applyLive(changed, Map.of(), true, check.selection());
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, result.state()); fields(second, "old");
            result = new SpringPropertyRebinder(List.of(first)).applyLive(Map.of("a.bC", "foreign"), Map.of(), true, check.selection());
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, result.state()); fields(first, "old");
        }
    }
    @Test void overlayRecreatesConstructorAndFactoryAgainstNewSources() throws Exception {
        try (var c = context(Map.of("a.bC", "old"), null, false, ConstructorValue.class, Factory.class)) {
            Object ctor = c.getBean(ConstructorValue.class), factory = c.getBean(FactoryValue.class);
            var result = new SpringPropertyRebinder(List.of(c)).apply(Map.of("a.bC", "new"));
            applied(result); assertEquals(2, result.rebuilt().size());
            assertNotSame(ctor, c.getBean(ConstructorValue.class)); assertEquals("new", c.getBean(ConstructorValue.class).value);
            assertNotSame(factory, c.getBean(FactoryValue.class)); assertEquals("new", c.getBean(FactoryValue.class).value());
        }
    }

    public static class RejectLiveFieldConversion extends org.springframework.beans.SimpleTypeConverter {
        @Override public <T> T convertIfNecessary(Object value, Class<T> type, java.lang.reflect.Field field) {
            throw new IllegalStateException("live-only fixture failure");
        }
    }
    @Test void partialFieldFailureRetriesIdenticalFileSave() throws Exception {
        Path file = file();
        try (var c = context(Map.of(), file, false, Fields.class)) {
            var reload = new SpringPropertyFiles();
            var factory = c.getBeanFactory();
            Files.writeString(file, "");
            var result = reload.apply(file, List.of(c), action -> {
                factory.setTypeConverter(new RejectLiveFieldConversion());
                action.run();
                factory.setTypeConverter(null);
            }).outcome();
            assertEquals(PropertyChangeOutcome.State.PARTIAL, result.state());
            assertEquals("old", c.getBean(Fields.class).alias);
            assertEquals("fallback", c.getEnvironment().resolveRequiredPlaceholders("${alias}"));
            result = reload.apply(file, List.of(c), Runnable::run).outcome();
            applied(result); assertEquals(3, result.valueFields()); fields(c, "fallback");
        }
    }

    @Test void partialFieldFailureRetriesIdenticalOverlayWithNewRebinder() throws Exception {
        try (var c = context(Map.of("a.bC", "old"), null, false, Fields.class)) {
            var factory = c.getBeanFactory();
            var changed = Map.of("a.bC", "new");
            var result = new SpringPropertyRebinder(List.of(c)).apply(changed, action -> {
                factory.setTypeConverter(new RejectLiveFieldConversion());
                action.run();
                factory.setTypeConverter(null);
            }, () -> { });
            assertEquals(PropertyChangeOutcome.State.PARTIAL, result.state());
            assertEquals("old", c.getBean(Fields.class).alias);
            assertEquals("new", c.getEnvironment().resolveRequiredPlaceholders("${alias}"));
            result = new SpringPropertyRebinder(List.of(c)).apply(changed);
            applied(result); assertEquals(3, result.valueFields()); fields(c, "new");
        }
    }

    @Test void nativeBootEscapeMismatchRefused() throws Exception {
        java.lang.reflect.Method setter;
        try { setter = AbstractEnvironment.class.getMethod("setEscapeCharacter", Character.class); }
        catch (NoSuchMethodException legacy) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Native legacy resolver has no escape policy"); return;
        }
        for (Character escape : new Character[]{'!', null}) {
            try (var c = context(Map.of("a.bC", "old"), null, false, Fields.class)) {
                setter.invoke(c.getEnvironment(), escape);
                var result = new SpringPropertyRebinder(List.of(c)).apply(Map.of("a.bC", "new"));
                assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, result.state(), result.findings().toString());
                assertEquals("old", c.getBean(Fields.class).alias);
                assertEquals("old", c.getEnvironment().getProperty("a.bC"));
            }
        }
    }

}
