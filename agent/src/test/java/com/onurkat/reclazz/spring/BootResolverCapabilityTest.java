/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BootResolverCapabilityTest {
    private static final String FACTORY = "org.springframework.boot.context.properties.source.ConfigurationPropertySources";
    private static final String PREFIX = "org.springframework.boot.context.properties.source.";
    public static class Context extends AnnotationConfigApplicationContext { public Context() { } }
    public static class RelaxedFields {
        @Value("${a.b-c:fallback}") String direct;
        @Value("${alias}") String alias;
    }
    public static class RawField { @Value("${alias}") String value; }

    @Test void bootResolverVersionIsNotGated() throws Exception {
        var loader = new FixtureLoader(null);
        try (var context = context(loader, true, RelaxedFields.class)) {
            Object resolver = PropertyChangeCheck.environmentResolver(loader, context.getEnvironment());
            assertEquals(PREFIX + "ConfigurationPropertySourcesPropertyResolver", resolver.getClass().getName());
            assertEquals("fixture-unlisted", resolver.getClass().getPackage().getImplementationVersion());
            assertTrue(PropertyChangeCheck.bootResolver(loader, context.getEnvironment()));
            var fields = context.getBean(RelaxedFields.class);
            assertEquals("old", fields.direct); assertEquals("old", fields.alias);
            var result = new SpringPropertyRebinder(List.of(context)).apply(Map.of("a.bC", "new"));
            assertEquals(PropertyChangeOutcome.State.APPLIED, result.state(), result.findings().toString());
            assertEquals(2, result.valueFields());
            assertEquals("new", fields.direct); assertEquals("new", fields.alias);
            assertEquals("new", context.getEnvironment().resolveRequiredPlaceholders("${alias}"));
        }
    }

    @Test void bootFactoryAbsentFallsBackToRawPath() throws Exception {
        for (String missing : List.of("attach", "createPropertyResolver")) {
            var loader = new FixtureLoader(missing);
            try (var context = context(loader, false, RawField.class)) {
                assertFalse(PropertyChangeCheck.bootResolver(loader, context.getEnvironment()), missing);
                var field = context.getBean(RawField.class);
                assertEquals("7", field.value);
                var result = new SpringPropertyRebinder(List.of(context)).apply(Map.of("raw.key", "8"));
                assertEquals(PropertyChangeOutcome.State.APPLIED, result.state(), missing + result.findings());
                assertEquals(1, result.valueFields());
                assertEquals("8", field.value);
                assertEquals("8", context.getEnvironment().resolveRequiredPlaceholders("${alias}"));
                assertSame(field, context.getBean(RawField.class));
            }
        }
    }

    private static AnnotationConfigApplicationContext context(FixtureLoader loader, boolean isolatedFactory,
                                                              Class<?> field) throws Exception {
        var context = (AnnotationConfigApplicationContext) loader.loadClass(Context.class.getName()).getConstructor().newInstance();
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("values", Map.of(
                "a.bC", "old", "raw.key", "7", "alias", isolatedFactory ? "${a.b-c:fallback}" : "${raw.key}")));
        Class<?> factory = isolatedFactory ? loader.loadClass(FACTORY) : Class.forName(FACTORY);
        Object resolver = factory.getMethod("createPropertyResolver", MutablePropertySources.class)
                .invoke(null, environment.getPropertySources());
        // Use the real native resolver, with only factory availability/package metadata varied.
        var member = AbstractEnvironment.class.getDeclaredField("propertyResolver");
        member.setAccessible(true); member.set(environment, resolver);
        factory.getMethod("attach", Environment.class).invoke(null, environment);
        context.setEnvironment(environment); context.register(field); context.refresh();
        return context;
    }

    /** Native Boot bytecode in an isolated package; no reimplementation of its resolution policy. */
    private static final class FixtureLoader extends ClassLoader {
        private final String missing;
        FixtureLoader(String missing) { super(BootResolverCapabilityTest.class.getClassLoader()); this.missing = missing; }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(PREFIX) && !name.equals(Context.class.getName())) return super.loadClass(name, resolve);
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (input == null) throw new ClassNotFoundException(name);
                    byte[] bytes = input.readAllBytes();
                    if (name.equals(FACTORY) && missing != null) {
                        var node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
                        assertTrue(node.methods.removeIf(method -> method.name.equals(missing)), missing);
                        var writer = new ClassWriter(0); node.accept(writer); bytes = writer.toByteArray();
                    }
                    String pkg = name.substring(0, name.lastIndexOf('.'));
                    if (getDefinedPackage(pkg) == null)
                        definePackage(pkg, null, null, null, null, "fixture-unlisted", null, null);
                    type = defineClass(name, bytes, 0, bytes.length);
                } catch (java.io.IOException failure) { throw new ClassNotFoundException(name, failure); }
            }
            if (resolve) resolveClass(type);
            return type;
        }
    }
}
