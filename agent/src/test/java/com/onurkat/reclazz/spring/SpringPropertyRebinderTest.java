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

    /**
     * A {@code @Value} constructor parameter has no field to write into: by
     * the time the bean exists the value is already inside whatever the
     * constructor did with it. That used to be reported as out of reach, next
     * to a path that answers the identical shape for
     * {@code @ConfigurationProperties} by rebuilding the bean. It is the same
     * answer here, so what has to be right is the decision about which beans
     * get rebuilt: rebuilding one that did not read a changed key throws away
     * a live instance for nothing.
     */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.PARAMETER)
    @interface Value {
        String value();
    }

    static class ReadsTimeout {
        ReadsTimeout(@Value("${demo.timeout}") int timeout) {
        }
    }

    static class ReadsTimeoutWithDefault {
        ReadsTimeoutWithDefault(@Value("${demo.timeout:5000}") int timeout) {
        }
    }

    static class ReadsSomethingElse {
        ReadsSomethingElse(@Value("${demo.other}") String other) {
        }
    }

    static class ReadsThroughSpel {
        ReadsThroughSpel(@Value("#{${demo.timeout} * 2}") int doubled) {
        }
    }

    static class ReadsNothing {
        ReadsNothing(String plain) {
        }
    }

    /** The annotated constructor is not the one Spring would pick. */
    static class AnnotatedOnASecondConstructor {
        AnnotatedOnASecondConstructor() {
        }

        AnnotatedOnASecondConstructor(@Value("${demo.timeout}") int timeout) {
        }
    }

    private static boolean takes(Class<?> type) throws Exception {
        return SpringPropertyRebinder.takesChangedValue(type,
                Map.of("demo.timeout", "250"), Value.class, Value.class.getMethod("value"));
    }

    @Test
    void aConstructorReadingAChangedKeyIsRebuilt() throws Exception {
        assertTrue(takes(ReadsTimeout.class));
        assertTrue(takes(ReadsTimeoutWithDefault.class),
                "a default in the placeholder does not stop it reading the key");
        assertTrue(takes(AnnotatedOnASecondConstructor.class),
                "which constructor Spring picked is not recorded anywhere reachable, "
                + "so every one of them is looked at");
    }

    @Test
    void aConstructorReadingSomethingElseIsLeftAlone() throws Exception {
        assertFalse(takes(ReadsSomethingElse.class));
        assertFalse(takes(ReadsNothing.class));
    }

    /**
     * The same policy the field path states: re-evaluating an arbitrary
     * expression is running application code at a moment it did not choose,
     * and a rebuild would do exactly that through the constructor.
     */
    @Test
    void aSpelParameterIsLeftAloneLikeASpelField() throws Exception {
        assertFalse(takes(ReadsThroughSpel.class));
    }

    /** What the save reached decides whether a restart warning is printed at all. */
    @Test
    void aRebuiltBeanCountsAsTakingEffect() {
        assertTrue(new SpringPropertyRebinder.Applied(List.of(), 0, List.of("clientConfig"))
                .tookEffect(), "a rebuilt bean is a value that is live");
        assertFalse(new SpringPropertyRebinder.Applied(List.of(), 0, List.of()).tookEffect());
    }
}
