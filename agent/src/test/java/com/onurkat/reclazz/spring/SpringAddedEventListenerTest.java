/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ApplicationListenerMethodAdapter;
import org.springframework.context.event.DefaultEventListenerFactory;
import org.springframework.context.event.EventListenerMethodProcessor;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SpringAddedEventListenerTest {
    public static class Handlers {
        final List<String> calls = new ArrayList<>();
        public void first(String message) { calls.add("first:" + message); }
        private void second(String message) { calls.add("second:" + message); }
        public void onApplicationEvent(ApplicationEvent event) { calls.add("event:" + event.getSource()); }
        public void bad() { }
        public int result(String message) { return 1; }
        public static void staticMethod(String message) { }
        public void primitive(int event) { }
        public void generic(List<String> event) { }
        public void fail(String event) { throw new IllegalArgumentException(event); }
    }
    private static final String STRING = "(Ljava/lang/String;)V";
    private static final Set<String> ADDED = Set.of("first:" + STRING, "second:" + STRING);

    @Test
    void everySingletonInEveryContextRunsExactlyOnceAndRemovalStopsAll() throws Exception {
        try (Scope one = new Scope(); Scope two = new Scope()) {
            one.context.getBeanFactory().registerSingleton("another", new Handlers());
            var reloader = reloader(one, two);
            byte[] bytes = annotated("first", "condition", "#a0 == 'yes'");
            for (int i = 1; i <= 3; i++) {
                assertTrue(reloader.reloadEventListeners(Handlers.class, ADDED, bytes));
                for (Scope scope : List.of(one, two)) {
                    scope.context.publishEvent("no");
                    scope.context.publishEvent(123);
                    scope.context.publishEvent("yes");
                    for (Handlers bean : scope.context.getBeansOfType(Handlers.class).values())
                        assertEquals(Collections.nCopies(i, "first:yes"), bean.calls);
                    assertEquals(scope.initialRegistrySize, scope.context.getApplicationListeners().size(),
                            "added adapters must not accumulate in the context's static listener registry");
                }
            }
            assertTrue(reloader.reloadEventListeners(Handlers.class, Set.of(), original()));
            for (Scope scope : List.of(one, two)) {
                scope.context.publishEvent("yes");
                for (Handlers bean : scope.context.getBeansOfType(Handlers.class).values()) assertEquals(3, bean.calls.size());
            }
        }
    }

    @Test
    void currentSingletonIsUsedAndAnAbsentOneIsNotCreated() throws Exception {
        try (Scope scope = new Scope()) {
            assertTrue(reloader(scope).reloadEventListeners(Handlers.class, ADDED, annotated("first")));
            Handlers old = scope.context.getBean(Handlers.class);
            scope.context.publishEvent("old");
            scope.context.getDefaultListableBeanFactory().destroySingleton("handlers");
            scope.context.publishEvent("absent");
            assertNull(scope.context.getBeanFactory().getSingleton("handlers"));
            Handlers replacement = new Handlers();
            scope.context.getBeanFactory().registerSingleton("handlers", replacement);
            scope.context.publishEvent("new");
            assertEquals(List.of("first:old"), old.calls);
            assertEquals(List.of("first:new"), replacement.calls);
        }
    }

    @Test
    void namedConditionsBeanReferencesAndOrderAreHandledBySpring() throws Exception {
        try (Scope scope = new Scope()) {
            scope.context.getBeanFactory().registerSingleton("allowed", "yes");
            ClassNode source = read(annotated("first", "condition", "#message == @allowed"));
            var first = method(source, "first");
            first.parameters = List.of(new ParameterNode("message", 0));
            AnnotationNode order = new AnnotationNode("Lorg/springframework/core/annotation/Order;");
            order.values = new ArrayList<>(List.of("value", 10));
            first.visibleAnnotations.add(order);
            var second = method(source, "second");
            second.visibleAnnotations = new ArrayList<>(List.of(event("condition", "#p0 == 'yes'")));
            order = new AnnotationNode("Lorg/springframework/core/annotation/Order;");
            order.values = new ArrayList<>(List.of("value", -10));
            second.visibleAnnotations.add(order);
            assertTrue(reloader(scope).reloadEventListeners(Handlers.class, ADDED, write(source)));
            scope.context.publishEvent("no");
            scope.context.publishEvent("yes");
            assertEquals(List.of("second:yes", "first:yes"), scope.context.getBean(Handlers.class).calls);
        }
    }

    @Test
    void debugParameterNamesAreCarriedWhenMethodParametersAreAbsent() throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = read(annotated("first", "condition", "#message == 'yes'"));
            var first = method(source, "first");
            first.parameters = null;
            assertTrue(first.localVariables.stream().anyMatch(v -> v.index == 1 && v.name.equals("message")),
                    "this fixture must actually carry a debug parameter name");
            assertTrue(reloader(scope).reloadEventListeners(Handlers.class, ADDED, write(source)));
            scope.context.publishEvent("yes");
            assertEquals(List.of("first:yes"), scope.context.getBean(Handlers.class).calls);
        }
    }

    @Test
    void anApplicationEventParameterDoesNotOverrideTheSpringDeliveryMethod() throws Exception {
        try (Scope scope = new Scope()) {
            assertTrue(reloader(scope).reloadEventListeners(Handlers.class,
                    Set.of("onApplicationEvent:(Lorg/springframework/context/ApplicationEvent;)V"),
                    annotated("onApplicationEvent")));
            scope.context.publishEvent(new ApplicationEvent("source") { });
            assertEquals(List.of("event:source"), scope.context.getBean(Handlers.class).calls);
        }
    }

    @Test
    void failedPreparationLeavesNoPartialAddedListenersAndAValidSaveRecovers() throws Exception {
        try (Scope scope = new Scope()) {
            var reloader = reloader(scope);
            ClassNode source = read(annotated("first"));
            // Conflicting aliases are rejected by Spring while constructing the second adapter.
            method(source, "second").visibleAnnotations = new ArrayList<>(List.of(event(
                    "classes", List.of(Type.getType(String.class)), "value", List.of(Type.getType(Integer.class)))));
            reloader.reloadEventListeners(Handlers.class, ADDED, write(source));
            scope.context.publishEvent("no-partial");
            assertEquals(List.of(), scope.context.getBean(Handlers.class).calls);
            assertTrue(reloader.reloadEventListeners(Handlers.class, ADDED, annotated("first")));
            scope.context.publishEvent("recovered");
            assertEquals(List.of("first:recovered"), scope.context.getBean(Handlers.class).calls);
        }
    }

    @Test
    void unsupportedSignaturesAndAdviceAreNamed() throws Exception {
        for (String name : List.of("bad", "result", "staticMethod", "primitive", "generic")) {
            byte[] bytes = annotated(name);
            var method = method(read(bytes), name);
            var plan = AddedEventListenerAdapter.inspect(bytes, Set.of(name + ":" + method.desc));
            assertTrue(plan.methods().isEmpty(), name);
            assertEquals(1, plan.refused().size(), name);
        }
        for (boolean onClass : List.of(false, true)) {
            ClassNode source = read(annotated("first"));
            var advice = new AnnotationNode("Lorg/springframework/scheduling/annotation/Async;");
            if (onClass) source.visibleAnnotations = new ArrayList<>(List.of(advice));
            else method(source, "first").visibleAnnotations.add(advice);
            assertEquals(1, AddedEventListenerAdapter.inspect(write(source), ADDED).refused().size());
        }
    }

    @Test
    void proxiesAndPrototypesAreRefusedAndPreviousRegistrationIsRemoved() throws Exception {
        for (boolean proxy : List.of(false, true)) {
            try (Scope scope = new Scope()) {
                var reloader = reloader(scope);
                assertTrue(reloader.reloadEventListeners(Handlers.class, ADDED, annotated("first")));
                Handlers old = scope.context.getBean(Handlers.class);
                scope.context.getDefaultListableBeanFactory().destroySingleton("handlers");
                if (proxy) {
                    var factory = new org.springframework.aop.framework.ProxyFactory(new Handlers());
                    factory.setProxyTargetClass(true);
                    // A non-standard advisor stays refused; a standard tx/cache
                    // proxy is now unwrapped and supported.
                    factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) call -> call.proceed());
                    scope.context.getBeanFactory().registerSingleton("handlers", factory.getProxy());
                } else {
                    var definition = new org.springframework.beans.factory.support.RootBeanDefinition(Handlers.class);
                    definition.setScope("prototype");
                    scope.context.registerBeanDefinition("handlers", definition);
                }
                RestartLedger.clear();
                reloader.reloadEventListeners(Handlers.class, ADDED, annotated("first"));
                scope.context.publishEvent("ignored");
                assertEquals(List.of(), old.calls);
                assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains(proxy ? "advisor" : "singleton")));
                if (!proxy) assertNull(scope.context.getBeanFactory().getSingleton("handlers"));
            } finally { RestartLedger.clear(); }
        }
    }

    public static class CustomFactory extends DefaultEventListenerFactory { }

    @Test
    void customFactoryIsNotSilentlyBypassed() throws Exception {
        try (Scope scope = new Scope(true)) {
            RestartLedger.clear();
            reloader(scope).reloadEventListeners(Handlers.class, ADDED, annotated("first"));
            scope.context.publishEvent("ignored");
            assertEquals(List.of(), scope.context.getBean(Handlers.class).calls);
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("custom event listener factories")));
        } finally { RestartLedger.clear(); }
    }

    @Test
    void userExceptionIsPropagatedBySpring() throws Exception {
        try (Scope scope = new Scope()) {
            assertTrue(reloader(scope).reloadEventListeners(Handlers.class, Set.of("fail:" + STRING), annotated("fail")));
            var failure = assertThrows(IllegalArgumentException.class, () -> scope.context.publishEvent("failure"));
            assertEquals("failure", failure.getMessage());
        }
    }

    @Test
    void existingAdapterSkipsAReplacementProxyAndClosedContexts() throws Exception {
        try (Scope scope = new Scope()) {
            assertTrue(reloader(scope).reloadEventListeners(Handlers.class, ADDED, annotated("first")));
            Handlers old = scope.context.getBean(Handlers.class);
            var multicaster = scope.context.getBean("applicationEventMulticaster",
                    org.springframework.context.event.ApplicationEventMulticaster.class);
            scope.context.getDefaultListableBeanFactory().destroySingleton("handlers");
            Handlers target = new Handlers();
            var factory = new org.springframework.aop.framework.ProxyFactory(target);
            factory.setProxyTargetClass(true);
            // A non-standard advisor stays refused; the paused report follows.
            factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) call -> call.proceed());
            scope.context.getBeanFactory().registerSingleton("handlers", factory.getProxy());
            RestartLedger.clear();
            scope.context.publishEvent("proxy");
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("paused")));
            scope.context.close();
            multicaster.multicastEvent(new org.springframework.context.PayloadApplicationEvent<>(this, "closed"));
            assertEquals(List.of(), old.calls);
            assertEquals(List.of(), target.calls);
        } finally { RestartLedger.clear(); }
    }

    @Test
    void hiddenAdapterKeepsMetadataOutOfTheProcessorsExpressionCache() throws Throwable {
        try (Scope scope = new Scope()) {
            Object adapter = AddedEventListenerAdapter.create(Handlers.class, "handlers", Handlers::new,
                    AddedEventListenerAdapter.inspect(annotated("first", "id", "orders-created"), ADDED)).get(0);
            assertTrue(adapter.getClass().isHidden());
            assertEquals("orders-created", ((ApplicationListenerMethodAdapter) adapter).getListenerId());
            assertTrue(Arrays.stream(adapter.getClass().getDeclaredFields()).allMatch(f -> f.getName().startsWith("__reclazz$")));
            Object processor = scope.context.getBean(EventListenerMethodProcessor.class);
            Object evaluator = com.onurkat.reclazz.util.Reflect.readField(processor, "evaluator");
            Object cache = com.onurkat.reclazz.util.Reflect.readField(evaluator, "conditionCache");
            assertInstanceOf(Map.class, cache);
            int before = ((Map<?, ?>) cache).size();
            var reloader = reloader(scope);
            for (int i = 0; i < 3; i++) {
                assertTrue(reloader.reloadEventListeners(Handlers.class, ADDED, annotated("first", "condition", "#a0 == 'yes'")));
                scope.context.publishEvent("yes");
            }
            assertEquals(before, ((Map<?, ?>) cache).size(), "the processor must not retain each generation's hidden Method");
        }
    }

    private static final class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final int initialRegistrySize;
        Scope() throws Exception { this(false); }
        Scope(boolean custom) throws Exception {
            LookupCapture.store(Handlers.class, MethodHandles.privateLookupIn(Handlers.class, MethodHandles.lookup()));
            context.getBeanFactory().registerSingleton("handlers", new Handlers());
            if (custom) context.registerBean("custom", CustomFactory.class);
            context.refresh();
            initialRegistrySize = context.getApplicationListeners().size();
        }
        @Override public void close() { context.close(); }
    }

    private static SpringEventReloader reloader(Scope... scopes) {
        List<Object> contexts = Arrays.stream(scopes).map(s -> (Object) s.context).toList();
        PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(SpringAddedEventListenerTest.class.getClassLoader(),
                new Class<?>[]{PlatformContext.class}, (p, m, a) -> m.getName().equals("getAllApplicationContexts") ? contexts : null);
        return new SpringEventReloader(platform);
    }
    private static byte[] original() throws Exception {
        try (var stream = Handlers.class.getResourceAsStream("/" + Handlers.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(stream);
            return stream.readAllBytes();
        }
    }
    private static byte[] annotated(String name, Object... attributes) throws Exception {
        ClassNode source = read(original());
        method(source, name).visibleAnnotations = new ArrayList<>(List.of(event(attributes)));
        return write(source);
    }
    private static AnnotationNode event(Object... attributes) {
        var annotation = new AnnotationNode(AddedEventListenerAdapter.EVENT);
        annotation.values = new ArrayList<>(Arrays.asList(attributes));
        return annotation;
    }
    private static MethodNode method(ClassNode source, String name) {
        return source.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static ClassNode read(byte[] bytes) {
        ClassNode source = new ClassNode();
        new ClassReader(bytes).accept(source, 0);
        return source;
    }
    private static byte[] write(ClassNode source) {
        ClassWriter writer = new ClassWriter(0);
        source.accept(writer);
        return writer.toByteArray();
    }
}
