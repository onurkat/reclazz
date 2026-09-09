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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AddedBeanArgumentsTest {
    public interface Transport { String name(); }
    public record Wire(String name) implements Transport { }
    public static class Token { }
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.PARAMETER)
    public @interface Nullable { }
    public static class Product implements AutoCloseable {
        final Transport transport;
        final Token token;
        int closed;
        Product(Transport transport, Token token) { this.transport = transport; this.token = token; }
        public void close() { closed++; }
    }
    @Configuration(proxyBeanMethods = false)
    public static class Config {
        int calls;
        private Product client(Transport transport, Token token) { calls++; return new Product(transport, token); }
        public Product one(Transport transport) { calls++; return new Product(transport, null); }
        public Product qualified(@Qualifier("blue") Transport transport) { return new Product(transport, null); }
        public Product secondQualified(Token token, @Qualifier("blue") Transport transport) { return new Product(transport, token); }
        public Transport transport() { return new Wire("added"); }
        public Object primitive(int number) { return number; }
        public Object array(Transport[] values) { return values; }
        public Object generic(List<Transport> values) { return values; }
        public Object rawList(List values) { return values; }
        public Object rawMap(Map values) { return values; }
        public Object optional(Optional value) { return value; }
        public Object provider(org.springframework.beans.factory.ObjectProvider value) { return value; }
        public Object value(@org.springframework.beans.factory.annotation.Value("${key}") String value) { return value; }
        public Object lazy(@Lazy Transport value) { return value; }
        public Object nullable(@Nullable Transport value) { return value; }
    }
    @AfterEach void clear() { RestartLedger.clear(); }

    @Test
    void requiredReferencesAreResolvedThroughSpring() throws Exception {
        try (Scope scope = new Scope()) {
            Wire wire = new Wire("blue");
            Token token = new Token();
            scope.context.getBeanFactory().registerSingleton("wire", wire);
            scope.context.getBeanFactory().registerSingleton("token", token);
            assertTrue(scope.reload(bytes("client")), RestartLedger.digest().toString());
            Product product = scope.context.getBean("client", Product.class);
            assertSame(wire, product.transport);
            assertSame(token, product.token);
            assertSame(product, scope.context.getBean("client"));
        }
    }

    @Test
    void dependencyDeclaredLaterInTheSameSaveIsAvailable() throws Exception {
        try (Scope scope = new Scope()) {
            byte[] bytes = bytes("one", "transport");
            for (int i = 0; i < 2; i++) {
                assertTrue(scope.reload(bytes));
                assertSame(scope.context.getBean("transport"), scope.context.getBean("one", Product.class).transport);
            }
        }
    }

    @Test
    void qualifierOverridesPrimaryAndPrimarySelectsAnUnqualifiedParameter() throws Exception {
        try (Scope scope = new Scope()) {
            scope.context.registerBean("red", Transport.class, () -> new Wire("red"), d -> d.setPrimary(true));
            scope.context.registerBean("blue", Transport.class, () -> new Wire("blue"));
            scope.context.registerBean("token", Token.class);
            assertTrue(scope.reload(bytes("one", "qualified", "secondQualified")));
            assertEquals("red", scope.context.getBean("one", Product.class).transport.name());
            assertEquals("blue", scope.context.getBean("qualified", Product.class).transport.name());
            assertEquals("blue", scope.context.getBean("secondQualified", Product.class).transport.name());
            assertSame(scope.context.getBean("token"), scope.context.getBean("secondQualified", Product.class).token);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void originalParameterNamesBreakTies(boolean debugOnly) throws Exception {
        try (Scope scope = new Scope()) {
            scope.context.registerBean("transport", Transport.class, () -> new Wire("named"));
            scope.context.registerBean("other", Transport.class, () -> new Wire("other"));
            ClassNode source = read(bytes("one"));
            MethodNode method = method(source, "one");
            if (debugOnly) {
                method.parameters = null;
                assertTrue(method.localVariables.stream().anyMatch(v -> v.index == 1 && v.name.equals("transport")));
            } else method.parameters = List.of(new ParameterNode("transport", 0));
            assertTrue(scope.reload(write(source)));
            assertEquals("named", scope.context.getBean("one", Product.class).transport.name());
        }
    }

    @Test
    void missingNamesDoNotInventANameAndUniqueTypeStillWorks() throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = read(bytes("one"));
            method(source, "one").parameters = null;
            method(source, "one").localVariables = null;
            scope.context.registerBean("__reclazz$arg0", Transport.class, () -> new Wire("first"));
            scope.context.registerBean("other", Transport.class, () -> new Wire("other"));
            assertFalse(scope.reload(write(source)));
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("NoUniqueBeanDefinitionException")), RestartLedger.digest().toString());
            assertFalse(scope.context.containsBeanDefinition("one"));
            scope.context.removeBeanDefinition("other");
            assertTrue(scope.reload(write(source)));
            assertEquals("first", scope.context.getBean("one", Product.class).transport.name());
        }
    }

    @Test
    void missingArgumentDoesNotCallFactoryAndCanRecover() throws Exception {
        try (Scope scope = new Scope()) {
            scope.context.registerBean("wire", Transport.class, () -> new Wire("live"));
            Object wire = scope.context.getBean("wire");
            assertFalse(scope.reload(bytes("client")));
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("NoSuchBeanDefinitionException")), RestartLedger.digest().toString());
            assertFalse(scope.context.containsBeanDefinition("client"));
            assertEquals(0, scope.context.getBean(Config.class).calls);
            assertSame(wire, scope.context.getBean("wire"));
            scope.context.registerBean("token", Token.class);
            assertTrue(scope.reload(bytes("client")));
            assertEquals(1, scope.context.getBean(Config.class).calls);
        }
    }

    @Test
    void recreationUsesCurrentDependencyAndRegistersDestructionEdges() throws Exception {
        try (Scope scope = new Scope()) {
            scope.context.registerBean("wire", Transport.class, () -> new Wire("fresh"));
            scope.context.getBean("wire"); // resolution sees an already cached candidate
            assertTrue(scope.reload(bytes("one")));
            var factory = scope.context.getDefaultListableBeanFactory();
            Product old = scope.context.getBean("one", Product.class);
            assertTrue(Arrays.asList(factory.getDependentBeans("wire")).contains("one"));
            factory.destroySingleton("wire");
            assertEquals(1, old.closed);
            assertNull(factory.getSingleton("one"));
            Product next = scope.context.getBean("one", Product.class);
            assertNotSame(old.transport, next.transport);
            assertSame(scope.context.getBean("wire"), next.transport);
            assertTrue(scope.reload(bytes("one")), "recreated products must remain owned");
        }
    }

    @Test
    void parentCandidatesAndProxyIdentityArePreserved() throws Exception {
        try (Scope scope = new Scope(); var parent = new AnnotationConfigApplicationContext()) {
            var proxyFactory = new org.springframework.aop.framework.ProxyFactory(new Wire("proxy"));
            Object proxy = proxyFactory.getProxy();
            parent.getBeanFactory().registerSingleton("wire", proxy);
            parent.refresh();
            scope.context.setParent(parent);
            assertTrue(scope.reload(bytes("one")));
            assertSame(proxy, scope.context.getBean("one", Product.class).transport);
        }
    }

    @Test
    void nullCandidateIsNotPassedToARequiredParameter() throws Exception {
        try (Scope scope = new Scope()) {
            scope.context.registerBean("wire", Transport.class, () -> null);
            assertFalse(scope.reload(bytes("one")));
            assertFalse(scope.context.containsBeanDefinition("one"));
            assertEquals(0, scope.context.getBean(Config.class).calls);
        }
    }

    @Test
    void hiddenMetadataNeverEntersTheGlobalParameterNameDiscoverer() throws Exception {
        try (Scope scope = new Scope()) {
            scope.context.getDefaultListableBeanFactory().setParameterNameDiscoverer(new org.springframework.core.ParameterNameDiscoverer() {
                @Override public String[] getParameterNames(java.lang.reflect.Method method) {
                    assertFalse(method.getDeclaringClass().isHidden(), "global discovery must not retain hidden metadata");
                    return null;
                }
                @Override public String[] getParameterNames(java.lang.reflect.Constructor<?> constructor) { return null; }
            });
            scope.context.getBeanFactory().registerSingleton("transport", new Wire("named"));
            scope.context.getBeanFactory().registerSingleton("other", new Wire("other"));
            assertTrue(scope.reload(bytes("one")));
            assertEquals("named", scope.context.getBean("one", Product.class).transport.name());
        }
    }

    @Test
    void dependencyResolutionStaysInsideItsContext() throws Exception {
        try (Scope one = new Scope(); Scope two = new Scope()) {
            one.context.getBeanFactory().registerSingleton("wire", new Wire("one"));
            two.context.getBeanFactory().registerSingleton("wire", new Wire("two"));
            assertTrue(one.reload(bytes("one")));
            assertTrue(two.reload(bytes("one")));
            assertEquals("one", one.context.getBean("one", Product.class).transport.name());
            assertEquals("two", two.context.getBean("one", Product.class).transport.name());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"primitive", "array", "generic", "rawList", "rawMap", "optional", "provider", "value", "lazy", "nullable"})
    void unsupportedParametersAreNamedWithoutRegistering(String name) throws Exception {
        try (Scope scope = new Scope()) {
            assertFalse(scope.reload(bytes(name)));
            assertFalse(scope.context.containsBeanDefinition(name));
            assertTrue(RestartLedger.size() > 0);
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
        boolean reload(byte[] bytes) {
            Set<String> added = new HashSet<>();
            for (var m : read(bytes).methods) added.add(m.name + ":" + m.desc);
            return reloader.reloadBeanMethods(Config.class, added, bytes);
        }
        @Override public void close() { context.close(); }
    }
    private static byte[] bytes(String... names) throws Exception {
        try (var in = Config.class.getResourceAsStream("/" + Config.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(in);
            ClassNode source = read(in.readAllBytes());
            for (String name : names) method(source, name).visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(AddedBeanAdapter.BEAN)));
            return write(source);
        }
    }
    private static MethodNode method(ClassNode source, String name) {
        return source.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
