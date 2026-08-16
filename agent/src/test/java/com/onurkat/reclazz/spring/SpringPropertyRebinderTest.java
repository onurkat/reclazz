/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring Boot binds a properties file into objects once, at startup, so an
 * edit afterwards reaches nothing. Two halves have to hold for a save to mean
 * something: the Environment has to answer with the new value, and the beans
 * that already read it have to be asked again.
 *
 * The binding half needs Spring Boot and is verified against a running
 * application. What is here is the half that can be: the Environment, and the
 * decision about which beans can be rebound at all. That decision is the one
 * worth protecting, because getting it wrong means reporting a value as live
 * when the object still holds the old one.
 */
class SpringPropertyRebinderTest {

    @Test
    void theChangedValueIsWhatTheEnvironmentAnswers() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().getPropertySources().addLast(
                new MapPropertySource("application.properties", Map.of("demo.timeout", "1000")));

        new SpringPropertyRebinder(List.of(context))
                .updateEnvironment(context, Map.of("demo.timeout", "250"));

        assertEquals("250", context.getEnvironment().getProperty("demo.timeout"),
                "the file's own source is still there and still says 1000, so the "
                + "new one has to come first");
    }

    /**
     * Ten saves must not leave ten layers behind, each shadowing the last: the
     * one to trust is the newest, and the rest are a leak.
     */
    @Test
    void repeatedSavesReplaceRatherThanStack() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        SpringPropertyRebinder rebinder = new SpringPropertyRebinder(List.of(context));

        rebinder.updateEnvironment(context, Map.of("demo.mode", "safe"));
        rebinder.updateEnvironment(context, Map.of("demo.mode", "fast"));
        rebinder.updateEnvironment(context, Map.of("demo.other", "x"));

        assertEquals("fast", context.getEnvironment().getProperty("demo.mode"));
        assertEquals("x", context.getEnvironment().getProperty("demo.other"),
                "a later save must not drop what an earlier one set");
        assertEquals(1, countReclazzSources(context),
                "one source, replaced in place");
    }

    /**
     * A record has no setter to write into: the values became constructor
     * arguments of an object that already exists. Spring's post-processor
     * accepts the request and does nothing, so reporting it as rebound would
     * leave the developer believing a value that is not live.
     */
    @Test
    void aRecordIsRecognisedAsBeyondRebinding() {
        assertTrue(SpringPropertyRebinder.isConstructorBound(new FixedTuning("one")));
        assertFalse(SpringPropertyRebinder.isConstructorBound(new Tuning()),
                "setters are exactly what makes rebinding possible");
    }

    @Test
    void anExplicitlyAnnotatedConstructorCountsToo() {
        assertTrue(SpringPropertyRebinder.isConstructorBound(new ExplicitlyBound("x")));
    }

    @Test
    void onlyTheBeansTheSaveConcernsAreTouched() {
        Set<String> changed = Set.of("demo.tuning.mode");

        assertTrue(SpringPropertyRebinder.affects(changed, "demo.tuning"));
        assertFalse(SpringPropertyRebinder.affects(changed, "server"),
                "rebinding the server's own properties on an unrelated save would "
                + "put back whatever else had been set at runtime");
        assertTrue(SpringPropertyRebinder.affects(changed, ""),
                "a bean binding the root prefix reads everything");
    }

    record FixedTuning(String label) {}

    static class Tuning {
        private String mode;
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
    }

    static class ExplicitlyBound {
        private final String label;
        @ConstructorBinding
        ExplicitlyBound(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    /** Stands in for Boot's annotation, which is not on this test classpath. */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.CONSTRUCTOR)
    @interface ConstructorBinding {}

    private static long countReclazzSources(GenericApplicationContext context) {
        long count = 0;
        for (org.springframework.core.env.PropertySource<?> source : context.getEnvironment().getPropertySources()) {
            if (source.getName().startsWith("reclazz")) count++;
        }
        return count;
    }
}
