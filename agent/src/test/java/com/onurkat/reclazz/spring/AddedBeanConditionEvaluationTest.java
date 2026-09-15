/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.MethodMetadata;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Shared with the isolated Spring 6 graph. Unit fixtures invoke real existing bodies. */
public class AddedBeanConditionEvaluationTest {
    static int made, closed, conditionCalls;
    @BeforeEach void reset() { made = closed = conditionCalls = 0; RestartLedger.clear(); }
    @AfterEach void clear() { RestartLedger.clear(); }

    public static class Product implements AutoCloseable {
        final String value;
        Product(String value) { this.value = value; made++; }
        @Override public void close() { closed++; }
    }
    @Configuration(proxyBeanMethods = false)
    public static class Config {
        public Product client() { return new Product("client"); }
        public Product client(String wire) { return new Product(wire); }
        public static Product staticClient() { return new Product("static"); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class NativeConfig {
        @Bean({"client", "alias"}) @Conditional(Policy.class)
        public Product client() { return new Product("client"); }
    }

    public static class Policy implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            conditionCalls++;
            MethodMetadata method = assertInstanceOf(MethodMetadata.class, metadata);
            assertEquals(context.getEnvironment().getProperty("owner"), method.getDeclaringClassName());
            assertTrue(Set.of("client", "staticClient").contains(method.getMethodName()));
            assertEquals(Product.class.getName(), method.getReturnTypeName());
            assertEquals(method.getMethodName().equals("staticClient"), method.isStatic());
            assertNotNull(metadata.getAnnotationAttributes(Bean.class.getName()));
            assertTrue(context.getRegistry().containsBeanDefinition("wire"));
            assertNotNull(context.getBeanFactory());
            assertSame(context.getBeanFactory().getBeanClassLoader(), context.getClassLoader());
            assertTrue(context.getResourceLoader().getResource("fixture:feature").exists());
            String resource = context.getEnvironment().getProperty("resource");
            if (resource != null) assertTrue(context.getResourceLoader().getResource(resource).exists());
            String mode = context.getEnvironment().getProperty("mode");
            if ("error".equals(mode)) throw new IllegalStateException("fixture condition error");
            return "on".equals(mode);
        }
    }
    public static class ParseOnly implements ConfigurationCondition {
        @Override public ConfigurationPhase getConfigurationPhase() { return ConfigurationPhase.PARSE_CONFIGURATION; }
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
            throw new AssertionError("a Bean method must not evaluate parse-only conditions");
        }
    }
    public static class RegisterVeto implements ConfigurationCondition {
        @Override public ConfigurationPhase getConfigurationPhase() { return ConfigurationPhase.REGISTER_BEAN; }
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) { return false; }
    }
    public static class EarlyThrow implements Condition, Ordered {
        @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
            conditionCalls++;
            throw new IllegalStateException("ordered condition must run before an inactive Profile");
        }
    }

    @Test
    void directConditionsMatchNativeStartupAndReceiveRealMethodMetadata() throws Exception {
        for (String mode : List.of("off", "on")) {
            try (var nativeContext = new AnnotationConfigApplicationContext()) {
                configure(nativeContext, new HashMap<>(Map.of("mode", mode, "owner", NativeConfig.class.getName())));
                nativeContext.register(NativeConfig.class);
                nativeContext.refresh();
                assertEquals(mode.equals("on"), nativeContext.containsBean("client"));
            }
            try (Scope scope = new Scope(mode)) {
                int calls = conditionCalls;
                assertTrue(scope.reload(bytes("client", "()", Policy.class)), RestartLedger.digest().toString());
                assertEquals(calls + 1, conditionCalls, "Bean conditions run in REGISTER_BEAN only");
                assertEquals(mode.equals("on"), scope.context.containsBean("client"));
                assertEquals(mode.equals("on"), scope.context.isAlias("alias"));
            }
        }
    }

    @Test
    void conditionsReevaluateOnSaveAndOwnProductsAndAliasesRetireOnFalseOrError() throws Exception {
        byte[] bytes = bytes("client", "()", Policy.class);
        try (Scope scope = new Scope("on")) {
            assertTrue(scope.reload(bytes));
            Object first = scope.context.getBean("client");
            assertSame(first, scope.context.getBean("alias"));
            assertEquals(1, made);
            scope.properties.put("mode", "off");
            assertTrue(scope.reload(bytes));
            assertFalse(scope.context.containsBean("client"));
            assertFalse(scope.context.isAlias("alias"));
            assertEquals(1, closed);
            scope.properties.put("mode", "error");
            assertFalse(scope.reload(bytes));
            assertFalse(scope.context.containsBean("client"));
            assertFalse(scope.context.isAlias("alias"));
            assertEquals(1, made);
            assertTrue(RestartLedger.digest().toString().contains("fixture condition error"));
            scope.properties.put("mode", "on");
            assertTrue(scope.reload(bytes));
            assertNotSame(first, scope.context.getBean("client"));
            assertSame(scope.context.getBean("client"), scope.context.getBean("alias"));
            assertEquals(2, made);
            assertEquals("wire", scope.context.getBean("wire"));
        }
        assertEquals(2, closed);
    }

    @Test
    void beanMethodsIgnoreParseConditionsButHonorRegistrationConditions() throws Exception {
        try (Scope scope = new Scope("on")) {
            assertTrue(scope.reload(bytes("client", "()", ParseOnly.class)), RestartLedger.digest().toString());
            assertTrue(scope.context.containsBean("client"));
            assertTrue(scope.reload(bytes("client", "()", RegisterVeto.class)));
            assertFalse(scope.context.containsBean("client"));
            assertEquals(1, made);
            assertEquals(1, closed);
        }
    }

    @Test
    void conditionalAndProfileShareNativeOrdering() throws Exception {
        ClassNode source = read(bytes("client", "()", EarlyThrow.class));
        MethodNode target = source.methods.stream().filter(m -> m.name.equals("client") && m.desc.startsWith("()")).findFirst().orElseThrow();
        AnnotationNode profile = new AnnotationNode(Type.getDescriptor(Profile.class));
        profile.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of("never-active"))));
        target.visibleAnnotations.add(profile);
        try (Scope scope = new Scope("on")) {
            assertFalse(scope.reload(write(source)));
            assertEquals(1, conditionCalls, "an inactive Profile must not bypass the earlier custom condition");
            assertEquals(0, made);
        }
    }

    @Test
    void savedDescriptorSelectsTheExactOverloadAndStaticMetadataIsPreserved() throws Exception {
        try (Scope scope = new Scope("on")) {
            assertTrue(scope.reload(bytes("client", "(Ljava/lang/String;)", Policy.class)), RestartLedger.digest().toString());
            assertEquals("wire", scope.context.getBean("client", Product.class).value);
            assertTrue(scope.reload(bytes("staticClient", "()", Policy.class)), RestartLedger.digest().toString());
            assertEquals("static", scope.context.getBean("client", Product.class).value);
        }
    }

    @Test
    void differentContextsEvaluateTheirOwnEnvironment() throws Exception {
        try (Scope enabled = new Scope("on"); Scope disabled = new Scope("off")) {
            byte[] bytes = bytes("client", "()", Policy.class);
            assertTrue(enabled.reload(bytes));
            assertTrue(disabled.reload(bytes));
            assertTrue(enabled.context.containsBean("client"));
            assertFalse(disabled.context.containsBean("client"));
            assertEquals(1, made);
        }
    }

    @Test
    void aSharedConfigurationStillUsesItsOwningContextsApplicationLoader() throws Exception {
        try (Scope scope = new Scope("on")) {
            String resourceName = "reclazz-condition-child-only.fixture";
            assertNull(Config.class.getClassLoader().getResource(resourceName));
            var existingResource = Objects.requireNonNull(Product.class.getResource("AddedBeanConditionEvaluationTest.class"));
            ClassLoader child = new ClassLoader(Config.class.getClassLoader()) {
                @Override public java.net.URL getResource(String name) {
                    return name.equals(resourceName) ? existingResource : super.getResource(name);
                }
            };
            scope.context.getBeanFactory().setBeanClassLoader(child);
            scope.properties.put("resource", "classpath:" + resourceName);
            ClassLoader callerLoader = Thread.currentThread().getContextClassLoader();
            assertTrue(scope.reload(bytes("client", "()", Policy.class)), RestartLedger.digest().toString());
            assertSame(callerLoader, Thread.currentThread().getContextClassLoader());
            assertTrue(scope.context.containsBean("client"));
            scope.context.addProtocolResolver((location, loader) -> {
                if (location.equals("fixture:error")) {
                    assertSame(child, Thread.currentThread().getContextClassLoader());
                    throw new IllegalStateException("fixture resource error");
                }
                return null;
            });
            scope.properties.put("resource", "fixture:error");
            assertFalse(scope.reload(bytes("client", "()", Policy.class)));
            assertTrue(RestartLedger.digest().toString().contains("fixture resource error"));
            assertSame(callerLoader, Thread.currentThread().getContextClassLoader());
            assertFalse(scope.context.containsBean("client"));
        }
    }

    private static void configure(AnnotationConfigApplicationContext context, Map<String, Object> properties) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", properties));
        context.addProtocolResolver((location, loader) -> location.equals("fixture:feature") ? new ByteArrayResource(new byte[]{1}) : null);
        context.registerBean("wire", String.class, () -> "wire");
    }

    private static final class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final Map<String, Object> properties = new HashMap<>();
        final SpringAddedBeanReloader reloader;
        Scope(String mode) throws Exception {
            LookupCapture.store(Config.class, MethodHandles.privateLookupIn(Config.class, MethodHandles.lookup()));
            properties.put("mode", mode); properties.put("owner", Config.class.getName());
            configure(context, properties);
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

    private static byte[] bytes(String name, String descriptorPrefix, Class<?> condition) throws Exception {
        byte[] original;
        try (var in = Config.class.getResourceAsStream("/" + Config.class.getName().replace('.', '/') + ".class")) {
            original = Objects.requireNonNull(in).readAllBytes();
        }
        ClassNode source = read(original);
        MethodNode method = source.methods.stream().filter(m -> m.name.equals(name) && m.desc.startsWith(descriptorPrefix)).findFirst().orElseThrow();
        AnnotationNode bean = new AnnotationNode(AddedBeanAdapter.BEAN);
        bean.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of("client", "alias"))));
        AnnotationNode conditional = new AnnotationNode(Type.getDescriptor(Conditional.class));
        conditional.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getType(condition)))));
        method.visibleAnnotations = new ArrayList<>(List.of(bean, conditional));
        return write(source);
    }
    private static ClassNode read(byte[] bytes) {
        ClassNode source = new ClassNode(); new ClassReader(bytes).accept(source, 0); return source;
    }
    private static byte[] write(ClassNode source) {
        ClassWriter writer = new ClassWriter(0); source.accept(writer); return writer.toByteArray();
    }
}
