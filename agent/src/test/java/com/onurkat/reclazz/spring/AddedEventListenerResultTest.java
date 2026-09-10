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
import org.springframework.context.ApplicationEvent;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class AddedEventListenerResultTest {
    public record Reply(String value) { }
    public static class ReplyEvent extends ApplicationEvent {
        public ReplyEvent(Object source) { super(source); }
    }
    public static class Handlers {
        int calls;
        Object result = new Reply("first");
        private Reply produce(String request) { calls++; return (Reply) result; }
        public Object erased(String request) { calls++; return result; }
        public ReplyEvent applicationEvent(String request) { calls++; return (ReplyEvent) result; }
        public Reply second(String request) { calls++; return new Reply("second"); }
        public Reply nothing(String request) { calls++; return null; }
        public Reply fail(String request) { calls++; throw new IllegalArgumentException("handler failed"); }
        public int primitive(String request) { calls++; return 1; }
        public Reply[] array(String request) { calls++; return null; }
        public List<Reply> generic(String request) { calls++; return null; }
        public List collection(String request) { calls++; return null; }
        public Iterable iterable(String request) { calls++; return null; }
        public Map map(String request) { calls++; return null; }
        public java.util.stream.BaseStream stream(String request) { calls++; return null; }
        public CompletionStage stage(String request) { calls++; return null; }
        public Future future(String request) { calls++; return null; }
        public Flow.Publisher publisher(String request) { calls++; return null; }
    }
    public static class Receiver {
        final List<Object> received = new ArrayList<>();
        @EventListener public void reply(Reply reply) { received.add(reply); }
        @EventListener public void event(ReplyEvent reply) { received.add(reply); }
    }
    @AfterEach void clear() { RestartLedger.clear(); }

    @Test
    void returnedEventReachesExistingListenerOnce() throws Exception {
        try (Scope scope = new Scope()) {
            scope.ok(annotated("produce"));
            scope.context.publishEvent("go");
            assertEquals(1, scope.handler().calls);
            scope.assertResult(scope.handler().result);
        }
    }

    @Test
    void applicationEventIdentityIsPreserved() throws Exception {
        try (Scope scope = new Scope()) {
            ReplyEvent event = new ReplyEvent("original-source");
            scope.handler().result = event;
            scope.ok(annotated("applicationEvent"));
            scope.context.publishEvent("go");
            scope.assertResult(event);
        }
    }

    @Test
    void nullAndRejectedConditionDoNotPublishAnotherEvent() throws Exception {
        try (Scope scope = new Scope()) {
            scope.ok(annotated("nothing", "condition", "#a0 == 'go'"));
            scope.context.publishEvent("skip");
            assertEquals(0, scope.handler().calls);
            scope.context.publishEvent("go");
            assertEquals(1, scope.handler().calls);
            assertTrue(scope.receiver.received.isEmpty());
            assertTrue(scope.results.isEmpty());
        }
    }

    @Test
    void handlerExceptionIsPropagatedWithoutPublishingAResult() throws Exception {
        try (Scope scope = new Scope()) {
            scope.ok(annotated("fail"));
            var failure = assertThrows(IllegalArgumentException.class, () -> scope.context.publishEvent("go"));
            assertEquals("handler failed", failure.getMessage());
            assertEquals(1, scope.handler().calls);
            assertTrue(scope.results.isEmpty());
        }
    }

    @Test
    void absentSingletonProducesNothingAndReplacementIsUsed() throws Exception {
        try (Scope scope = new Scope()) {
            scope.ok(annotated("produce"));
            Handlers original = scope.handler();
            scope.context.getDefaultListableBeanFactory().destroySingleton("handlers");
            scope.context.publishEvent("absent");
            assertNull(scope.context.getBeanFactory().getSingleton("handlers"));
            assertEquals(0, original.calls);
            assertTrue(scope.results.isEmpty());
            Handlers next = new Handlers();
            next.result = new Reply("replacement");
            scope.context.getBeanFactory().registerSingleton("handlers", next);
            scope.context.publishEvent("go");
            scope.assertResult(next.result);
            assertEquals(0, original.calls);
        }
    }

    @Test
    void aReplacementProxyIsSkippedWithoutProducingAResult() throws Exception {
        try (Scope scope = new Scope()) {
            scope.ok(annotated("produce"));
            scope.context.getDefaultListableBeanFactory().destroySingleton("handlers");
            Handlers replacement = new Handlers();
            var proxy = new org.springframework.aop.framework.ProxyFactory(replacement);
            proxy.setProxyTargetClass(true);
            // A non-standard advisor stays refused; a standard tx/cache proxy is
            // unwrapped and supported instead.
            proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) call -> call.proceed());
            scope.context.getBeanFactory().registerSingleton("handlers", proxy.getProxy());
            scope.context.publishEvent("go");
            assertTrue(scope.results.isEmpty());
            assertEquals(0, replacement.calls);
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("paused")));
        }
    }

    @Test
    void repeatedReloadsDoNotDuplicateResultsAndRemovalStopsThem() throws Exception {
        try (Scope scope = new Scope()) {
            for (int i = 0; i < 3; i++) {
                scope.ok(annotated("produce"));
                scope.context.publishEvent("go");
                assertEquals(i + 1, scope.results.size());
                assertEquals(i + 1, scope.receiver.received.size());
            }
            scope.ok(original());
            scope.context.publishEvent("removed");
            assertEquals(3, scope.results.size());
        }
    }

    @Test
    void conditionsAndOrderRemainWithSpring() throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = annotated("produce", "condition", "#a0 == 'go'");
            method(source, "produce").visibleAnnotations.add(order(10));
            method(source, "second").visibleAnnotations = new ArrayList<>(List.of(event("condition", "#p0 == 'go'"), order(-10)));
            scope.ok(source);
            scope.context.publishEvent("skip");
            assertTrue(scope.results.isEmpty());
            scope.context.publishEvent("go");
            assertEquals(List.of(new Reply("second"), new Reply("first")), scope.receiver.received);
        }
    }

    @Test
    void independentContextsReceiveTheirOwnResults() throws Exception {
        try (Scope one = new Scope(); Scope two = new Scope()) {
            one.ok(annotated("produce"));
            two.ok(annotated("produce"));
            one.context.publishEvent("one");
            one.assertResult(one.handler().result);
            assertTrue(two.results.isEmpty());
            two.context.publishEvent("two");
            two.assertResult(two.handler().result);
            assertNotSame(one.handler().result, two.handler().result);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"primitive", "array", "generic", "collection", "iterable", "map", "stream", "stage", "future", "publisher"})
    void unsupportedDeclaredResultsAreRefusedBeforeInvocation(String name) throws Exception {
        try (Scope scope = new Scope()) {
            assertFalse(scope.reload(annotated(name)));
            assertFalse(RestartLedger.digest().isEmpty(), name);
            scope.context.publishEvent("go");
            assertEquals(0, scope.handler().calls);
            assertTrue(scope.results.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"array", "primitiveArray", "collection", "iterable", "map", "stream", "stage", "future", "publisher"})
    void unsupportedActualResultsHiddenBehindObjectAreNotPublished(String kind) throws Exception {
        try (Scope scope = new Scope()) {
            Reply reply = new Reply("must-not-be-published");
            scope.handler().result = switch (kind) {
                case "array" -> new Reply[]{reply};
                case "primitiveArray" -> new int[]{1, 2};
                case "collection" -> List.of(reply);
                case "iterable" -> (Iterable<Reply>) () -> List.of(reply).iterator();
                case "map" -> Map.of("reply", reply);
                case "stream" -> java.util.stream.Stream.of(reply);
                case "stage" -> CompletableFuture.completedFuture(reply);
                case "future" -> new FutureTask<>(() -> reply);
                case "publisher" -> (Flow.Publisher<Reply>) subscriber -> fail("publisher must not be subscribed");
                default -> throw new AssertionError(kind);
            };
            scope.ok(annotated("erased"));
            var failure = assertThrows(IllegalArgumentException.class, () -> scope.context.publishEvent("go"));
            assertTrue(failure.getMessage().contains("single event"), failure.getMessage());
            assertTrue(scope.results.isEmpty(), "neither container nor contents may be published");
            assertTrue(scope.receiver.received.isEmpty());
            scope.handler().result = reply;
            scope.context.publishEvent("recovered");
            scope.assertResult(reply);
        }
    }

    @Test
    void reactivePublisherMarkerInAnotherLoaderIsRejectedThroughItsSubinterface() throws Exception {
        // Only the interface hierarchy is needed to test refusal; no reactive
        // library is installed or subscribed, and Spring's loader cannot see it.
        class PublisherLoader extends ClassLoader {
            Class<?> contract(String name, String... parents) {
                ClassWriter writer = new ClassWriter(0);
                writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                        name.replace('.', '/'), null, "java/lang/Object", parents);
                writer.visitEnd();
                byte[] bytes = writer.toByteArray();
                return defineClass(name, bytes, 0, bytes.length);
            }
        }
        PublisherLoader loader = new PublisherLoader();
        loader.contract("org.reactivestreams.Publisher");
        Class<?> derived = loader.contract("fixture.DerivedPublisher", "org/reactivestreams/Publisher");
        try (Scope scope = new Scope()) {
            scope.handler().result = Proxy.newProxyInstance(loader, new Class<?>[]{derived},
                    (proxy, method, args) -> { throw new AssertionError("publisher must not be invoked"); });
            scope.ok(annotated("erased"));
            var failure = assertThrows(IllegalArgumentException.class, () -> scope.context.publishEvent("go"));
            assertTrue(failure.getMessage().contains("single event"));
            assertTrue(scope.results.isEmpty());
        }
    }

    private static final class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final Receiver receiver = new Receiver();
        final List<Object> results = new ArrayList<>();
        final SpringEventReloader reloader;
        Scope() throws Exception {
            LookupCapture.store(Handlers.class, MethodHandles.privateLookupIn(Handlers.class, MethodHandles.lookup()));
            context.getBeanFactory().registerSingleton("handlers", new Handlers());
            context.getBeanFactory().registerSingleton("receiver", receiver);
            context.refresh();
            context.addApplicationListener(event -> {
                if (event instanceof PayloadApplicationEvent<?> payload && !(payload.getPayload() instanceof String)) results.add(payload.getPayload());
                else if (event instanceof ReplyEvent) results.add(event);
            });
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PlatformContext.class}, (p, m, a) -> m.getName().equals("getAllApplicationContexts") ? List.of(context) : null);
            reloader = new SpringEventReloader(platform);
        }
        Handlers handler() { return (Handlers) context.getBeanFactory().getSingleton("handlers"); }
        boolean reload(ClassNode source) {
            RestartLedger.clear();
            Set<String> added = new HashSet<>();
            for (var method : source.methods) added.add(method.name + ":" + method.desc);
            ClassWriter writer = new ClassWriter(0);
            source.accept(writer);
            return reloader.reloadEventListeners(Handlers.class, added, writer.toByteArray());
        }
        void ok(ClassNode source) { assertTrue(reload(source), RestartLedger.digest().toString()); }
        void assertResult(Object expected) {
            assertEquals(1, results.size());
            assertEquals(1, receiver.received.size());
            assertSame(expected, results.get(0));
            assertSame(expected, receiver.received.get(0));
        }
        @Override public void close() { context.close(); }
    }
    private static ClassNode annotated(String name, Object... values) throws Exception {
        ClassNode source = original();
        method(source, name).visibleAnnotations = new ArrayList<>(List.of(event(values)));
        return source;
    }
    private static AnnotationNode event(Object... values) {
        var node = new AnnotationNode(AddedEventListenerAdapter.EVENT);
        node.values = new ArrayList<>(Arrays.asList(values));
        return node;
    }
    private static AnnotationNode order(int value) {
        var node = new AnnotationNode("Lorg/springframework/core/annotation/Order;");
        node.values = new ArrayList<>(List.of("value", value));
        return node;
    }
    private static MethodNode method(ClassNode source, String name) {
        return source.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static ClassNode original() throws Exception {
        try (var in = Handlers.class.getResourceAsStream("/" + Handlers.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(in);
            ClassNode source = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(source, 0);
            return source;
        }
    }
}
