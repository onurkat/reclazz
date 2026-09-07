/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.validation.annotation.Validated;
import javax.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

public class PropertyChangeCheckTest {
    @ConfigurationProperties("svc") @Validated
    public static class ServiceProperties {
        private String url;
        @Min(1) private int timeout;
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }
    }
    public static class ValueReader {
        @Value("${svc.timeout}") int timeout;
        @Value("${svc.url}") String url;
    }
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ServiceProperties.class)
    static class Config {}

    static AnnotationConfigApplicationContext context(Class<?>... config) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("original",
                Map.of("svc.url", "old", "svc.timeout", "10", "rec.timeout", "10")));
        context.register(config);
        context.refresh();
        return context;
    }

    @Test void invalidTimeoutKeepsTheWholeRunningConfiguration() {
        try (var context = context(Config.class, ValueReader.class)) {
            new SpringPropertyRebinder(List.of(context)).apply(Map.of("svc.url", "new", "svc.timeout", "abc"));
            assertAll(
                    () -> assertEquals("old", context.getEnvironment().getProperty("svc.url")),
                    () -> assertEquals("10", context.getEnvironment().getProperty("svc.timeout")),
                    () -> assertEquals("old", context.getBean(ServiceProperties.class).getUrl()),
                    () -> assertEquals(10, context.getBean(ServiceProperties.class).getTimeout()),
                    () -> assertEquals("old", context.getBean(ValueReader.class).url),
                    () -> assertEquals(10, context.getBean(ValueReader.class).timeout));
        }
    }

    @Test void fallbackValidationRejectsWithoutAValidatorBean() {
        try (var context = context(Config.class, ValueReader.class)) {
            assertTrue(context.getBeansOfType(org.springframework.validation.Validator.class).isEmpty());
            new SpringPropertyRebinder(List.of(context)).apply(Map.of("svc.timeout", "0"));
            assertEquals("10", context.getEnvironment().getProperty("svc.timeout"));
            assertEquals(10, context.getBean(ServiceProperties.class).getTimeout());
            assertEquals(10, context.getBean(ValueReader.class).timeout);
        }
    }

    @Test void cleanChangeUpdatesBeansAndDirectFields() {
        try (var context = context(Config.class, ValueReader.class)) {
            new SpringPropertyRebinder(List.of(context)).apply(Map.of("svc.url", "new", "svc.timeout", "50"));
            assertEquals("new", context.getBean(ServiceProperties.class).getUrl());
            assertEquals(50, context.getBean(ServiceProperties.class).getTimeout());
            assertEquals("new", context.getBean(ValueReader.class).url);
            assertEquals(50, context.getBean(ValueReader.class).timeout);
        }
    }

    private static PropertyChangeCheck.Result check(AnnotationConfigApplicationContext context, Map<String, String> values) {
        return new PropertyChangeCheck().check(List.of(context), values);
    }

    @ConfigurationProperties("rec")
    public record RecordProperties(int timeout) implements org.springframework.validation.Validator {
        public boolean supports(Class<?> type) { return type == RecordProperties.class; }
        public void validate(Object target, org.springframework.validation.Errors errors) {
            if (timeout < 1 || timeout > 100) errors.rejectValue("timeout", "range");
        }
    }
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RecordProperties.class)
    static class Records {}

    @Test void selfValidatingRecordUsesTheNewInstance() {
        try (var context = context(Records.class)) {
            for (String invalid : List.of("0", "200")) {
                var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("rec.timeout", invalid));
                assertEquals(PropertyChangeOutcome.State.REJECTED, outcome.state(), outcome.findings().toString());
                assertEquals(10, context.getBean(RecordProperties.class).timeout());
            }
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("rec.timeout", "50"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, outcome.state(), outcome.findings().toString());
            assertEquals(50, context.getBean(RecordProperties.class).timeout());
        }
    }

    @Test void rejectedCheckDoesNotWriteBootTrackingOrAnyLiveValue() {
        try (var context = context(Config.class, Records.class, ValueReader.class)) {
            var tracking = org.springframework.boot.context.properties.BoundConfigurationProperties.get(context);
            var before = Map.copyOf(tracking.getAll());
            var result = check(context, Map.of("svc.url", "new", "svc.timeout", "20", "rec.timeout", "0"));
            assertEquals(PropertyChangeOutcome.State.REJECTED, result.state(), result.findings().toString());
            assertAll(
                    () -> assertEquals(before, tracking.getAll()),
                    () -> assertEquals("old", context.getEnvironment().getProperty("svc.url")),
                    () -> assertEquals("10", context.getEnvironment().getProperty("svc.timeout")),
                    () -> assertEquals("old", context.getBean(ServiceProperties.class).getUrl()),
                    () -> assertEquals(10, context.getBean(ServiceProperties.class).getTimeout()),
                    () -> assertEquals(10, context.getBean(RecordProperties.class).timeout()),
                    () -> assertEquals(10, context.getBean(ValueReader.class).timeout));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomPolicy {
        @org.springframework.context.annotation.Bean
        static org.springframework.validation.Validator configurationPropertiesValidator() {
            return new org.springframework.validation.Validator() {
                public boolean supports(Class<?> type) { return ServiceProperties.class.isAssignableFrom(type); }
                public void validate(Object target, org.springframework.validation.Errors errors) {
                    if (((ServiceProperties) target).getTimeout() > 30) errors.rejectValue("timeout", "customLimit");
                }
            };
        }
        @org.springframework.context.annotation.Bean
        static java.util.concurrent.atomic.AtomicInteger checks() { return new java.util.concurrent.atomic.AtomicInteger(); }
        @org.springframework.context.annotation.Bean
        static org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor advisor(
                java.util.concurrent.atomic.AtomicInteger checks) {
            return handler -> { checks.incrementAndGet(); return handler; };
        }
    }

    @Test void namedValidatorAndAdvisorAreHonoured() {
        try (var context = context(Config.class, CustomPolicy.class)) {
            var calls = context.getBean(java.util.concurrent.atomic.AtomicInteger.class);
            int before = calls.get();
            var result = check(context, Map.of("svc.timeout", "50"));
            assertEquals(PropertyChangeOutcome.State.REJECTED, result.state(), result.findings().toString());
            assertTrue(calls.get() > before);
            assertEquals(10, context.getBean(ServiceProperties.class).getTimeout());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Placeholders {
        @org.springframework.context.annotation.Bean
        static org.springframework.context.support.PropertySourcesPlaceholderConfigurer placeholders() {
            return new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
        }
    }

    @Test void placeholdersUseCandidateKeysEvenWithALiveConfigurer() {
        try (var context = context(Config.class, Placeholders.class, ValueReader.class)) {
            var result = check(context, Map.of("svc.timeout", "${svc.limit}", "svc.limit", "abc"));
            assertEquals(PropertyChangeOutcome.State.REJECTED, result.state(), result.findings().toString());
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(
                    Map.of("svc.url", "${svc.host}/api", "svc.host", "candidate", "svc.timeout", "20"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, outcome.state(), outcome.findings().toString());
            assertEquals("candidate/api", context.getBean(ServiceProperties.class).getUrl());
            assertEquals("candidate/api", context.getBean(ValueReader.class).url);
        }
    }

    @ConfigurationProperties(prefix = "svc", ignoreUnknownFields = false)
    public static class StrictProperties extends ServiceProperties {}
    @ConfigurationProperties(prefix = "svc", ignoreInvalidFields = true)
    public static class LenientProperties {
        private int timeout = 10;
        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }
    }
    @Configuration(proxyBeanMethods = false) @EnableConfigurationProperties(StrictProperties.class)
    static class Strict {}
    @Configuration(proxyBeanMethods = false) @EnableConfigurationProperties(LenientProperties.class)
    static class Lenient {}

    @Test void bootIgnoreFlagsArePreserved() {
        try (var context = context(Strict.class)) {
            var result = check(context, Map.of("svc.typo", "20"));
            assertEquals(PropertyChangeOutcome.State.REJECTED, result.state(), result.findings().toString());
        }
        try (var context = context(Lenient.class)) {
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("svc.timeout", "abc"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, outcome.state(), outcome.findings().toString());
            assertEquals(10, context.getBean(LenientProperties.class).getTimeout());
        }
    }

    @ConfigurationProperties("svc")
    public static class NoDefaultConstructor extends ServiceProperties {
        public NoDefaultConstructor(String ignored) { }
    }
    @Configuration(proxyBeanMethods = false) @EnableConfigurationProperties
    static class NoDefault {
        @org.springframework.context.annotation.Bean
        NoDefaultConstructor unsupported() { return new NoDefaultConstructor("constructed by factory"); }
    }

    @Test void unsupportedScratchConstructorHoldsTheChange() {
        try (var context = context(NoDefault.class)) {
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("svc.timeout", "20"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), outcome.findings().toString());
            assertEquals("10", context.getEnvironment().getProperty("svc.timeout"));
            assertEquals(10, context.getBean(NoDefaultConstructor.class).getTimeout());
        }
    }

    @Test void missingInternalBinderHoldsTheChange() throws Exception {
        String name = PropertyProbeContext.class.getName();
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
                if (className.endsWith(".ConfigurationPropertiesBinder")) throw new ClassNotFoundException(className);
                if (!className.equals(name)) return super.loadClass(className, resolve);
                Class<?> found = findLoadedClass(name);
                if (found != null) return found;
                try (var input = getResourceAsStream(name.replace('.', '/') + ".class")) {
                    byte[] bytes = input.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (java.io.IOException failure) { throw new ClassNotFoundException(name, failure); }
            }
        };
        var constructor = loader.loadClass(name).getDeclaredConstructor();
        constructor.setAccessible(true);
        try (var context = (AnnotationConfigApplicationContext) constructor.newInstance()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("original",
                    Map.of("svc.url", "old", "svc.timeout", "10")));
            context.register(Config.class);
            context.refresh();
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("svc.timeout", "20"));
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE, outcome.state(), outcome.findings().toString());
            assertEquals("10", context.getEnvironment().getProperty("svc.timeout"));
        }
    }

    @ConfigurationProperties("svc")
    public static class FailsOnlyOnLiveApply extends ServiceProperties {
        boolean fail;
        @Override public void setTimeout(int timeout) {
            if (fail) throw new IllegalStateException("live setter failure");
            super.setTimeout(timeout);
        }
    }
    @Configuration(proxyBeanMethods = false) @EnableConfigurationProperties(FailsOnlyOnLiveApply.class)
    static class Partial {}

    @Test void postCheckFailureIsPartialAndNeverAccepted() {
        try (var context = context(Partial.class)) {
            context.getBean(FailsOnlyOnLiveApply.class).fail = true;
            var accepted = new java.util.concurrent.atomic.AtomicBoolean();
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("svc.timeout", "20"),
                    Runnable::run, () -> accepted.set(true));
            assertEquals(PropertyChangeOutcome.State.PARTIAL, outcome.state(), outcome.findings().toString());
            assertFalse(accepted.get());
            assertTrue(outcome.findings().stream().anyMatch(s -> s.contains("FailsOnlyOnLiveApply")));
            assertEquals("20", context.getEnvironment().getProperty("svc.timeout"));
        }
    }

    public static class ConstructorReader {
        final int timeout;
        ConstructorReader(@Value("${svc.timeout}") int timeout) { this.timeout = timeout; }
    }
    @Test void directValuesAreCheckedWithoutBootConfigurationBeans() {
        try (var context = context(ValueReader.class, ConstructorReader.class)) {
            var outcome = new SpringPropertyRebinder(List.of(context)).apply(Map.of("svc.timeout", "abc"));
            assertEquals(PropertyChangeOutcome.State.REJECTED, outcome.state(), outcome.findings().toString());
            assertTrue(outcome.findings().stream().anyMatch(s -> s.contains(".<init>[0]")));
            assertEquals(10, context.getBean(ConstructorReader.class).timeout);
            assertEquals(10, context.getBean(ValueReader.class).timeout);
        }
    }
}

class PropertyProbeContext extends AnnotationConfigApplicationContext { }
