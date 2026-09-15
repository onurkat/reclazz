/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.*;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.context.annotation.*;

import java.lang.invoke.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AddedFullConfigurationArgumentsTest {
    public record Wire(String name) { }
    public record Box(Wire wire, int count) { }
    public record Rich(List<Wire> wires, org.springframework.beans.factory.ObjectProvider<Wire> provider, long size) { }
    @Configuration
    public static class Config {
        static Runnable hook;
        public Box single(@Qualifier("wire") Wire wire, @Value("5") int count) { return new Box(wire, count); }
        public Box prototype(@Qualifier("wire") Wire wire, @Value("5") int count) {
            if (hook != null) hook.run();
            return new Box(wire, count);
        }
        public Rich rich(List<Wire> wires, org.springframework.beans.factory.ObjectProvider<Wire> provider,
                         @Value("12") long size) { return new Rich(wires, provider, size); }
    }
    @Configuration
    public static class Native {
        @Bean @Lazy public Box nativeSingle(@Qualifier("wire") Wire wire, @Value("5") int count) { return new Box(wire, count); }
        @Bean @Scope("prototype") public Box nativePrototype(@Qualifier("wire") Wire wire, @Value("5") int count) { return new Box(wire, count); }
    }
    @AfterEach void reset() {
        Config.hook = null;
        AddedBeanBridge.publish(Config.class, Map.of());
        RestartLedger.clear();
    }

    @Test void containerResolvesDependenciesValuesAndConcreteGenerics() throws Exception {
        try (var scope = new TestScope()) {
            scope.install("single", "rich");
            assertEquals(scope.nativeConfig().nativeSingle(null, 0), scope.context.getBean("single"));
            Rich rich = scope.context.getBean("rich", Rich.class);
            assertEquals(List.of(scope.wire), rich.wires());
            assertSame(scope.wire, rich.provider().getObject());
            assertEquals(12L, rich.size());
            assertTrue(Arrays.asList(scope.context.getBeanFactory().getDependenciesForBean("single")).contains("wire"));
        }
    }
    @Test void explicitGenericAndWidePrimitiveArgumentsReachTheFullFactory() throws Throwable {
        try (var scope = new TestScope()) {
            scope.install("rich");
            Rich original = scope.context.getBean("rich", Rich.class);
            scope.context.getDefaultListableBeanFactory().destroySingleton("rich");
            List<Wire> supplied = List.of(new Wire("supplied"));
            MethodType type = MethodType.methodType(Rich.class, List.class,
                    org.springframework.beans.factory.ObjectProvider.class, long.class);
            var direct = new ConstantCallSite(MethodHandles.lookup().findVirtual(Config.class, "rich", type));
            var call = AddedBeanBridge.call(Config.class, InjectedNames.siteKey("rich",
                    InjectedNames.descHash(type.toMethodDescriptorString())), direct).dynamicInvoker();
            Rich result = (Rich) call.invokeWithArguments(scope.context.getBean(Config.class), supplied, original.provider(), 91L);
            assertSame(supplied, result.wires());
            assertSame(original.provider(), result.provider());
            assertEquals(91L, result.size());
            assertSame(result, scope.context.getBean("rich"));
        }
    }
    @Test void firstExplicitSingletonCallAndLaterIdentityMatchNativeFactory() throws Throwable {
        try (var scope = new TestScope()) {
            scope.install("single");
            Wire supplied = new Wire("explicit");
            Box actual = scope.call("single", supplied, 41);
            Box expected = scope.nativeConfig().nativeSingle(supplied, 41);
            assertEquals(expected, actual);
            assertSame(supplied, actual.wire());
            assertSame(actual, scope.call("single", new Wire("ignored"), 99));
            assertSame(expected, scope.nativeConfig().nativeSingle(new Wire("ignored"), 99));
            assertSame(actual, scope.context.getBean("alias"));
        }
    }
    @Test void singletonNullStubResolvesAllArgumentsLikeSpring() throws Throwable {
        try (var scope = new TestScope()) {
            scope.install("single");
            Box actual = scope.call("single", null, 77);
            Box expected = scope.nativeConfig().nativeSingle(null, 77);
            assertEquals(expected, actual);
            assertSame(scope.wire, actual.wire());
            assertEquals(5, actual.count());
        }
    }
    @Test void prototypesKeepExplicitNullAndEveryCallArgumentsLikeSpring() throws Throwable {
        try (var scope = new TestScope()) {
            scope.install("prototype");
            for (Wire wire : Arrays.asList(new Wire("explicit"), null)) {
                Box actual = scope.call("prototype", wire, 27);
                assertEquals(scope.nativeConfig().nativePrototype(wire, 27), actual);
                assertSame(wire, actual.wire());
                assertNotSame(actual, scope.call("prototype", wire, 27));
            }
            assertEquals(new Box(scope.wire, 5), scope.context.getBean("prototype"));
        }
    }
    @Test void nestedReferencesRestoreTheOuterInvocationAndOrdinaryLookupsResolveNormally() throws Throwable {
        try (var scope = new TestScope()) {
            scope.install("prototype", "single", "rich");
            List<Box> nested = new ArrayList<>();
            Config.hook = () -> {
                Config.hook = null;
                try { nested.add(scope.call("single", new Wire("inner"), 33)); }
                catch (Throwable failure) { throw new AssertionError(failure); }
                assertThrows(org.springframework.beans.factory.BeanCurrentlyInCreationException.class,
                        () -> scope.context.getBean("prototype"));
                Rich rich = scope.context.getBean("rich", Rich.class);
                nested.add(new Box(rich.provider().getObject(), (int) rich.size()));
            };
            Wire outer = new Wire("outer");
            assertEquals(new Box(outer, 22), scope.call("prototype", outer, 22));
            assertEquals(List.of(new Box(new Wire("inner"), 33), new Box(scope.wire, 12)), nested);
            assertEquals(new Box(scope.wire, 5), scope.context.getBean("prototype"));
        }
    }
    @Test void exceptionBeforeSupplierConsumptionCannotLeakArgumentsToNextLookup() throws Throwable {
        try (var scope = new TestScope()) {
            scope.install("prototype");
            AtomicBoolean fail = new AtomicBoolean(true);
            scope.context.getBeanFactory().addBeanPostProcessor(new InstantiationAwareBeanPostProcessor() {
                @Override public Object postProcessBeforeInstantiation(Class<?> type, String name) {
                    if (name.equals("prototype") && fail.getAndSet(false)) throw new IllegalStateException("before factory failure");
                    return null;
                }
            });
            assertThrows(Throwable.class, () -> scope.call("prototype", new Wire("must-not-leak"), 88));
            assertEquals(new Box(scope.wire, 5), scope.context.getBean("prototype"));
        }
    }
    @Test void aDifferentContextCannotConsumeTheCallersUnconsumedArguments() throws Throwable {
        try (var one = new TestScope(); var two = new TestScope()) {
            one.install("single"); two.install("single");
            List<Box> seen = new ArrayList<>();
            one.context.getBeanFactory().addBeanPostProcessor(new InstantiationAwareBeanPostProcessor() {
                @Override public Object postProcessBeforeInstantiation(Class<?> type, String name) {
                    if (name.equals("single")) seen.add(two.context.getBean("single", Box.class));
                    return null;
                }
            });
            Wire explicit = new Wire("one-only");
            assertEquals(new Box(explicit, 71), one.call("single", explicit, 71));
            assertEquals(List.of(new Box(two.wire, 5)), seen);
            assertNotSame(one.wire, two.wire);
        }
    }
    @Test void concurrentPrototypeCallsKeepTheirOwnArguments() throws Exception {
        try (var scope = new TestScope()) {
            scope.install("prototype");
            var barrier = new CyclicBarrier(2);
            Config.hook = () -> {
                try { barrier.await(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
            };
            var pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<Box>> results = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    final int count = i + 1;
                    results.add(pool.submit(() -> {
                        try { return scope.call("prototype", new Wire("thread" + count), count); }
                        catch (Throwable failure) { throw new AssertionError(failure); }
                    }));
                }
                assertEquals(new Box(new Wire("thread1"), 1), results.get(0).get(15, TimeUnit.SECONDS));
                assertEquals(new Box(new Wire("thread2"), 2), results.get(1).get(15, TimeUnit.SECONDS));
            } finally { pool.shutdownNow(); Config.hook = null; }
        }
    }
    @Test void missingFactoryPreservesTheNativeExceptionType() throws Exception {
        try (var scope = new TestScope()) {
            scope.install("single");
            scope.context.getDefaultListableBeanFactory().removeBeanDefinition("single");
            scope.context.getDefaultListableBeanFactory().removeBeanDefinition("nativeSingle");
            assertThrows(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,
                    () -> scope.nativeConfig().nativeSingle(new Wire("missing"), 9));
            assertThrows(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,
                    () -> scope.call("single", new Wire("missing"), 9));
        }
    }
    @Test void manuallyConstructedConfigurationKeepsPlainJavaArguments() throws Throwable {
        try (var scope = new TestScope()) {
            scope.install("single");
            MethodHandle call = route("single");
            Config manual = new Config();
            Box first = (Box) call.invokeWithArguments(manual, null, 71);
            assertEquals(new Box(null, 71), first);
            assertNotSame(first, call.invokeWithArguments(manual, null, 71));
            assertFalse(scope.context.getBeanFactory().containsSingleton("single"));
        }
    }

    private static final class TestScope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final Wire wire = new Wire("resolved");
        final SpringAddedBeanReloader reloader;
        TestScope() {
            LookupCapture.store(Config.class, MethodHandles.lookup());
            context.registerBean("wire", Wire.class, () -> wire);
            context.register(Config.class, Native.class);
            context.refresh();
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(PlatformContext.class.getClassLoader(),
                    new Class[]{PlatformContext.class}, (p, m, a) -> m.getName().equals("getAllApplicationContexts") ? List.of(context) : null);
            reloader = new SpringAddedBeanReloader(platform);
        }
        Native nativeConfig() { return context.getBean(Native.class); }
        void install(String... names) throws Exception {
            byte[] bytes = annotated(names);
            ClassNode source = new ClassNode(); new ClassReader(bytes).accept(source, 0);
            Set<String> keys = new HashSet<>();
            for (var method : source.methods) if (List.of(names).contains(method.name)) keys.add(method.name + ":" + method.desc);
            assertTrue(reloader.reloadBeanMethods(Config.class, keys, bytes), RestartLedger.digest().toString());
        }
        Box call(String name, Wire wire, int count) throws Throwable {
            return (Box) route(name).invokeWithArguments(context.getBean(Config.class), wire, count);
        }
        @Override public void close() { context.close(); }
    }
    private static MethodHandle route(String name) throws Exception {
        MethodType type = MethodType.methodType(Box.class, Wire.class, int.class);
        var direct = new ConstantCallSite(MethodHandles.lookup().findVirtual(Config.class, name, type));
        return AddedBeanBridge.call(Config.class, InjectedNames.siteKey(name,
                InjectedNames.descHash(type.toMethodDescriptorString())), direct).dynamicInvoker();
    }
    private static byte[] annotated(String... names) throws Exception {
        var source = new ClassNode();
        try (var in = Config.class.getResourceAsStream("/" + Config.class.getName().replace('.', '/') + ".class")) {
            new ClassReader(Objects.requireNonNull(in).readAllBytes()).accept(source, 0);
        }
        for (var method : source.methods) if (List.of(names).contains(method.name)) {
            if (method.visibleAnnotations == null) method.visibleAnnotations = new ArrayList<>();
            var bean = new AnnotationNode(AddedBeanAdapter.BEAN);
            if (method.name.equals("single")) bean.values = new ArrayList<>(List.of("name", List.of("single", "alias")));
            method.visibleAnnotations.add(bean);
            method.visibleAnnotations.add(new AnnotationNode("Lorg/springframework/context/annotation/Lazy;"));
            if (method.name.equals("prototype")) {
                var scope = new AnnotationNode("Lorg/springframework/context/annotation/Scope;");
                scope.values = new ArrayList<>(List.of("value", "prototype")); method.visibleAnnotations.add(scope);
            }
        }
        var writer = new ClassWriter(0); source.accept(writer); return writer.toByteArray();
    }
}
