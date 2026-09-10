/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.context.annotation.Configuration;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** inspect() parses @Lazy/@Scope/@Profile on added factories and refuses the unsupported subset. */
class AddedBeanConditionalTest {
    private static final String LAZY = "Lorg/springframework/context/annotation/Lazy;";
    private static final String SCOPE = "Lorg/springframework/context/annotation/Scope;";
    private static final String PROFILE = "Lorg/springframework/context/annotation/Profile;";
    private static final String CONDITIONAL = "Lorg/springframework/context/annotation/Conditional;";
    private static final String PROXY_MODE = "Lorg/springframework/context/annotation/ScopedProxyMode;";

    @Configuration(proxyBeanMethods = false)
    public static class Config {
        public Object plain() { return new Object(); }
        public Object lazyBean() { return new Object(); }
        public Object protoBean() { return new Object(); }
        public Object prodBean() { return new Object(); }
        public Object scopedProxyBean() { return new Object(); }
        public Object requestBean() { return new Object(); }
        public Object conditionalBean() { return new Object(); }
    }

    @Test
    void defaultsAreEagerSingletonWithoutProfiles() throws Exception {
        AddedBeanAdapter.Factory factory = only("plain");
        assertFalse(factory.lazy());
        assertEquals("singleton", factory.scope());
        assertFalse(factory.prototype());
        assertTrue(factory.profiles().isEmpty());
    }

    @Test
    void lazyScopeAndProfileAreParsed() throws Exception {
        ClassNode source = beans("lazyBean", "protoBean", "prodBean");
        method(source, "lazyBean").visibleAnnotations.add(new AnnotationNode(LAZY));
        AnnotationNode scope = new AnnotationNode(SCOPE); scope.values = new ArrayList<>(List.of("value", "prototype"));
        method(source, "protoBean").visibleAnnotations.add(scope);
        AnnotationNode profile = new AnnotationNode(PROFILE); profile.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of("prod", "!test"))));
        method(source, "prodBean").visibleAnnotations.add(profile);

        var plan = AddedBeanAdapter.inspect(write(source), signatures(source));
        assertTrue(plan.refused().isEmpty(), plan.refused()::toString);
        assertTrue(factory(plan, "lazyBean").lazy());
        assertTrue(factory(plan, "protoBean").prototype());
        assertEquals(List.of("prod", "!test"), factory(plan, "prodBean").profiles());
    }

    @Test
    void explicitLazyFalseStaysEager() throws Exception {
        ClassNode source = beans("lazyBean");
        AnnotationNode lazy = new AnnotationNode(LAZY); lazy.values = new ArrayList<>(List.of("value", Boolean.FALSE));
        method(source, "lazyBean").visibleAnnotations.add(lazy);
        assertFalse(factory(AddedBeanAdapter.inspect(write(source), signatures(source)), "lazyBean").lazy());
    }

    @Test
    void scopedProxyRequestScopeAndConditionalAreRefused() throws Exception {
        ClassNode source = beans("scopedProxyBean", "requestBean", "conditionalBean");
        AnnotationNode proxy = new AnnotationNode(SCOPE);
        proxy.values = new ArrayList<>(List.of("value", "prototype", "proxyMode", new String[]{PROXY_MODE, "TARGET_CLASS"}));
        method(source, "scopedProxyBean").visibleAnnotations.add(proxy);
        AnnotationNode request = new AnnotationNode(SCOPE); request.values = new ArrayList<>(List.of("value", "request"));
        method(source, "requestBean").visibleAnnotations.add(request);
        method(source, "conditionalBean").visibleAnnotations.add(new AnnotationNode(CONDITIONAL));

        var plan = AddedBeanAdapter.inspect(write(source), signatures(source));
        assertTrue(plan.factories().isEmpty(), "no unsupported factory should register");
        assertEquals(3, plan.refused().size(), plan.refused()::toString);
        assertTrue(plan.refused().stream().anyMatch(r -> r.contains("scopedProxyBean") && r.contains("scoped-proxy")), plan.refused()::toString);
        assertTrue(plan.refused().stream().anyMatch(r -> r.contains("requestBean") && r.contains("singleton and prototype")), plan.refused()::toString);
        assertTrue(plan.refused().stream().anyMatch(r -> r.contains("conditionalBean")), plan.refused()::toString);
    }

    private static AddedBeanAdapter.Factory only(String name) throws Exception {
        ClassNode source = beans(name);
        return factory(AddedBeanAdapter.inspect(write(source), signatures(source)), name);
    }
    private static AddedBeanAdapter.Factory factory(AddedBeanAdapter.Plan plan, String name) {
        return plan.factories().stream().filter(f -> f.method().name.equals(name)).findFirst().orElseThrow();
    }
    private static ClassNode beans(String... names) throws Exception {
        try (var in = Config.class.getResourceAsStream("/" + Config.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(in);
            ClassNode source = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(source, 0);
            for (String name : names)
                method(source, name).visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(AddedBeanAdapter.BEAN)));
            return source;
        }
    }
    private static Set<String> signatures(ClassNode source) {
        Set<String> added = new HashSet<>();
        for (var m : source.methods) if (m.visibleAnnotations != null) added.add(m.name + ":" + m.desc);
        return added;
    }
    private static MethodNode method(ClassNode source, String name) {
        return source.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
