/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AddedBeanSelectionTest {
    private static final String PRIMARY = Type.getDescriptor(Primary.class);
    private static final String QUALIFIER = Type.getDescriptor(Qualifier.class);
    public interface Transport { String name(); }
    public record Wire(String name) implements Transport { }
    public static class Product implements AutoCloseable {
        final Transport transport;
        int closed;
        Product(Transport transport) { this.transport = transport; }
        public void close() { closed++; }
    }
    public static class Holder {
        @Autowired Transport selected;
        @Autowired @Qualifier("fast") Transport qualified;
    }
    @Qualifier("fast")
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
    public @interface Fast { }

    @Configuration(proxyBeanMethods = false)
    public static class Config {
        int calls;
        // Consumers deliberately precede providers in the saved bytecode.
        private Product client(Transport candidate) { calls++; return new Product(candidate); }
        private Product qualified(@Qualifier("fast") Transport candidate) { calls++; return new Product(candidate); }
        private Product empty(@Qualifier Transport candidate) { calls++; return new Product(candidate); }
        public Transport first() { return new Wire("first"); }
        public Transport second() { return new Wire("second"); }
    }

    @AfterEach void clear() { RestartLedger.clear(); }

    @Test
    void primarySelectsAnAddedCandidate() throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = beans("client", "first", "second");
            annotate(source, "second", PRIMARY);
            scope.reloadSuccessfully(source);
            assertSame(scope.context.getBean("second"), scope.product("client").transport);
            assertSame(scope.context.getBean("second"), scope.context.getBean(Transport.class));
        }
    }

    @Test
    void qualifierValueDiffersFromBeanName() throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = beans("client", "qualified", "first", "second");
            annotate(source, "first", PRIMARY);
            annotate(source, "second", QUALIFIER, "value", "fast");
            scope.reloadSuccessfully(source);
            assertSame(scope.context.getBean("first"), scope.product("client").transport);
            assertSame(scope.context.getBean("second"), scope.product("qualified").transport);
            assertFalse(scope.context.containsBean("fast"), "qualifier must not create a bean alias");
            Holder holder = scope.context.getAutowireCapableBeanFactory().createBean(Holder.class);
            assertSame(scope.context.getBean("first"), holder.selected);
            assertSame(scope.context.getBean("second"), holder.qualified);
        }
    }

    @Test
    void primaryEditsRemovalAndAmbiguityDoNotKeepStaleSelection() throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = beans("client", "first", "second");
            annotate(source, "first", PRIMARY);
            scope.reloadSuccessfully(source);
            Product first = scope.product("client");
            assertEquals("first", first.transport.name());
            remove(source, "first", PRIMARY);
            annotate(source, "second", PRIMARY);
            scope.reloadSuccessfully(source);
            assertEquals(1, first.closed);
            Product second = scope.product("client");
            assertEquals("second", second.transport.name());
            remove(source, "second", PRIMARY);
            assertFalse(scope.reload(source));
            scope.assertFailure("NoUniqueBeanDefinitionException");
            assertEquals(1, second.closed);
            assertFalse(scope.context.containsBean("client"));
            annotate(source, "first", PRIMARY);
            annotate(source, "second", PRIMARY);
            assertFalse(scope.reload(source));
            scope.assertFailure("NoUniqueBeanDefinitionException");
            assertEquals(2, scope.context.getBean(Config.class).calls, "ambiguous arguments must not invoke factory");
            remove(source, "first", PRIMARY);
            scope.reloadSuccessfully(source);
            assertSame(scope.context.getBean("second"), scope.product("client").transport);
        }
    }

    @Test
    void qualifierEditsRemovalAndRecoveryUseFreshMetadata() throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = beans("qualified", "first", "second");
            annotate(source, "first", QUALIFIER, "value", "fast");
            scope.reloadSuccessfully(source);
            Product first = scope.product("qualified");
            assertEquals("first", first.transport.name());
            remove(source, "first", QUALIFIER);
            annotate(source, "first", QUALIFIER, "value", "slow");
            annotate(source, "second", QUALIFIER, "value", "fast");
            scope.reloadSuccessfully(source);
            assertEquals(1, first.closed);
            Product second = scope.product("qualified");
            assertEquals("second", second.transport.name());
            remove(source, "second", QUALIFIER);
            assertFalse(scope.reload(source));
            scope.assertFailure("NoSuchBeanDefinitionException");
            assertEquals(1, second.closed);
            assertFalse(scope.context.containsBean("qualified"));
            assertEquals(2, scope.context.getBean(Config.class).calls);
            remove(source, "first", QUALIFIER);
            annotate(source, "first", QUALIFIER, "value", "fast");
            scope.reloadSuccessfully(source);
            assertSame(scope.context.getBean("first"), scope.product("qualified").transport);
        }
    }

    @Test
    void primaryBreaksTiesWithinMatchingQualifiers() throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = beans("qualified", "first", "second");
            annotate(source, "first", QUALIFIER, "value", "fast");
            annotate(source, "second", QUALIFIER, "value", "fast");
            assertFalse(scope.reload(source));
            scope.assertFailure("NoUniqueBeanDefinitionException");
            annotate(source, "second", PRIMARY);
            scope.reloadSuccessfully(source);
            assertSame(scope.context.getBean("second"), scope.product("qualified").transport);
        }
    }

    @Test
    void defaultEmptyQualifierIsDifferentFromAnAbsentQualifier() throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = beans("empty", "first", "second");
            annotate(source, "second", QUALIFIER);
            scope.reloadSuccessfully(source);
            assertSame(scope.context.getBean("second"), scope.product("empty").transport);
            remove(source, "second", QUALIFIER);
            assertFalse(scope.reload(source));
            scope.assertFailure("NoSuchBeanDefinitionException");
        }
    }

    @Test
    void externallyReplacedDefinitionAndItsMetadataRemainUntouched() throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = beans("client", "first", "second");
            annotate(source, "first", PRIMARY);
            scope.reloadSuccessfully(source);
            var factory = scope.context.getDefaultListableBeanFactory();
            RootBeanDefinition external = new RootBeanDefinition(Transport.class);
            Wire wire = new Wire("external");
            external.setInstanceSupplier(() -> wire);
            external.setPrimary(true);
            external.addQualifier(new AutowireCandidateQualifier(Qualifier.class, "external"));
            factory.registerBeanDefinition("first", external);
            assertSame(wire, scope.context.getBean("first"));
            assertFalse(scope.reload(source), "owned reload must report the external name collision");
            assertSame(external, factory.getBeanDefinition("first"));
            assertSame(wire, scope.product("client").transport);
            assertEquals("external", external.getQualifier(Qualifier.class.getName()).getAttribute("value"));
            assertTrue(external.isPrimary());
        }
    }

    @Test
    void metadataStaysInsideItsContext() throws Exception {
        try (Scope one = new Scope(); Scope two = new Scope()) {
            ClassNode source = beans("client", "first", "second");
            annotate(source, "first", PRIMARY);
            one.reloadSuccessfully(source);
            remove(source, "first", PRIMARY);
            annotate(source, "second", PRIMARY);
            two.reloadSuccessfully(source);
            assertEquals("first", one.product("client").transport.name());
            assertEquals("second", two.product("client").transport.name());
            assertNotSame(one.context.getBean("first"), two.context.getBean("first"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"classPrimary", "classQualifier", "composed", "webScope"})
    void unsupportedPoliciesStillRefuseRegistration(String kind) throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = beans("first");
            switch (kind) {
                case "classPrimary" -> source.visibleAnnotations.add(new AnnotationNode(PRIMARY));
                case "classQualifier" -> source.visibleAnnotations.add(new AnnotationNode(QUALIFIER));
                case "composed" -> annotate(source, "first", Type.getDescriptor(Fast.class));
                case "webScope" -> annotate(source, "first", Type.getDescriptor(org.springframework.context.annotation.Scope.class), "value", "request");
            }
            assertFalse(scope.reload(source));
            assertFalse(scope.context.containsBean("first"));
            assertFalse(RestartLedger.digest().isEmpty());
        }
    }

    private static final class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final SpringAddedBeanReloader reloader;
        Scope() throws Exception {
            LookupCapture.store(Config.class, MethodHandles.privateLookupIn(Config.class, MethodHandles.lookup()));
            context.registerBean("config", Config.class);
            context.refresh();
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PlatformContext.class}, (p, m, a) -> m.getName().equals("getAllApplicationContexts") ? List.of(context) : null);
            reloader = new SpringAddedBeanReloader(platform);
        }
        boolean reload(ClassNode source) {
            RestartLedger.clear();
            Set<String> added = new HashSet<>();
            for (var m : source.methods) added.add(m.name + ":" + m.desc);
            ClassWriter writer = new ClassWriter(0);
            source.accept(writer);
            return reloader.reloadBeanMethods(Config.class, added, writer.toByteArray());
        }
        void reloadSuccessfully(ClassNode source) { assertTrue(reload(source), RestartLedger.digest().toString()); }
        Product product(String name) { return context.getBean(name, Product.class); }
        void assertFailure(String cause) {
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains(cause)), RestartLedger.digest().toString());
        }
        @Override public void close() { context.close(); }
    }

    private static ClassNode beans(String... names) throws Exception {
        try (var in = Config.class.getResourceAsStream("/" + Config.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(in);
            ClassNode source = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(source, 0);
            for (String name : names) annotate(source, name, AddedBeanAdapter.BEAN);
            return source;
        }
    }
    private static MethodNode method(ClassNode source, String name) {
        return source.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static void annotate(ClassNode source, String name, String descriptor, Object... values) {
        var method = method(source, name);
        if (method.visibleAnnotations == null) method.visibleAnnotations = new ArrayList<>();
        AnnotationNode annotation = new AnnotationNode(descriptor);
        if (values.length > 0) annotation.values = new ArrayList<>(Arrays.asList(values));
        method.visibleAnnotations.add(annotation);
    }
    private static void remove(ClassNode source, String name, String descriptor) {
        method(source, name).visibleAnnotations.removeIf(a -> a.desc.equals(descriptor));
    }
}
