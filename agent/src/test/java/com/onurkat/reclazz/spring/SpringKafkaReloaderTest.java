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
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.listener.adapter.RecordMessagingMessageListenerAdapter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpringKafkaReloaderTest {
    private static final String STRING = "(Ljava/lang/String;)V";
    private static final Set<String> ADDED = Set.of("first:" + STRING, "second:" + STRING);
    @org.springframework.stereotype.Component public static class Owner {
        final List<String> calls = new ArrayList<>();
        String topic = "route-one", group = "group-one";
        int evaluations;
        public String getTopic() { return topic; }
        public String getGroup() { return group; }
        public List<String> getTopics() { return List.of(topic, "route-two"); }
        public String getCountedTopic() { evaluations++; return topic; }
        public void first(String value) { calls.add("first:" + value); }
        private void second(String value) { calls.add("second:" + value); }
        public void record(ConsumerRecord<Integer,String> value) { calls.add("record:" + value.value()); }
    }
    @Configuration(proxyBeanMethods=false) @EnableKafka static class Config {
        @Bean ConcurrentKafkaListenerContainerFactory<Integer,String> kafkaListenerContainerFactory() {
            var factory = new ConcurrentKafkaListenerContainerFactory<Integer,String>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(Map.of("bootstrap.servers", "127.0.0.1:1",
                    "key.deserializer", org.apache.kafka.common.serialization.IntegerDeserializer.class,
                    "value.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class)));
            factory.setAutoStartup(false); return factory;
        }
    }
    private static class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final KafkaListenerEndpointRegistry registry;
        final SpringKafkaReloader reloader;
        Scope() throws Exception {
            LookupCapture.store(Owner.class, MethodHandles.privateLookupIn(Owner.class, MethodHandles.lookup()));
            context.register(Config.class); context.registerBean("owner", Owner.class); context.refresh();
            registry = context.getBean(KafkaListenerEndpointRegistry.class); registry.setAlwaysStartAfterRefresh(false);
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlatformContext.class},
                    (p,m,a) -> m.getName().equals("getAllApplicationContexts") ? List.of(context) : null);
            reloader = new SpringKafkaReloader(platform);
        }
        Owner owner() { return context.getBean(Owner.class); }
        ConcurrentKafkaListenerContainerFactory<?,?> factory() { return context.getBean(ConcurrentKafkaListenerContainerFactory.class); }
        @Override public void close() { context.close(); }
    }
    @AfterEach void clearDiagnostics() { RestartLedger.clear(); }

    @Test void standardProcessorConvertsMessagesAndPrivateDelegateUsesCurrentSingleton() throws Exception {
        try (var scope = new Scope()) {
            assertTrue(scope.reloader.reloadKafkaListeners(Owner.class, ADDED, annotated("second", "added")));
            var c = scope.registry.getListenerContainer("added"); assertNotNull(c); assertFalse(c.isRunning());
            Owner old = scope.owner(); invoke(c, "one"); assertEquals(List.of("second:one"), old.calls);
            scope.context.getDefaultListableBeanFactory().destroySingleton("owner");
            Owner replacement = scope.owner(); invoke(c, "two");
            assertEquals(List.of("second:two"), replacement.calls); assertEquals(List.of("second:one"), old.calls);
            scope.context.getDefaultListableBeanFactory().destroySingleton("owner");
            assertThrows(Exception.class, () -> invoke(c, "missing"));
            assertNull(scope.context.getBeanFactory().getSingleton("owner"), "callback must not create or silently acknowledge a missing bean");
        }
    }
    @Test void originalAndAddedContainersAreRemovedBeforeRecreationAndUnrelatedOneSurvives() throws Exception {
        try (var scope = new Scope()) {
            register(scope, "original", scope.owner(), "first");
            register(scope, "unrelated", new Other(), "first");
            var other = scope.registry.getListenerContainer("unrelated");
            assertTrue(scope.reloader.reloadKafkaListeners(Owner.class, ADDED, annotated("second", "added")));
            for (int i = 0; i < 3; i++) {
                var old = scope.registry.getListenerContainer("added");
                assertTrue(scope.reloader.beforeBeanRefresh(Owner.class));
                assertEquals(Set.of("unrelated"), scope.registry.getListenerContainerIds());
                assertSame(other, scope.registry.getListenerContainer("unrelated")); assertFalse(old.isRunning());
                assertTrue(scope.reloader.reloadKafkaListeners(Owner.class, ADDED, annotated("second", "added")));
            }
            assertTrue(scope.reloader.beforeBeanRefresh(Owner.class));
            assertFalse(scope.reloader.reloadKafkaListeners(Owner.class, Set.of(), original()));
            assertEquals(Set.of("unrelated"), scope.registry.getListenerContainerIds());
        }
    }
    @Test void duplicateIdCannotStealAnotherBeansContainer() throws Exception {
        try (var scope = new Scope()) {
            register(scope, "used", new Other(), "first"); var prior = scope.registry.getListenerContainer("used");
            assertFalse(scope.reloader.reloadKafkaListeners(Owner.class, ADDED, annotated("first", "used")));
            assertSame(prior, scope.registry.getListenerContainer("used"));
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("already belongs")));
        }
    }
    @Test void invalidDeclarationsDoNotStartAPartialRegistration() throws Exception {
        try (var scope = new Scope()) {
            register(scope, "unrelated", new Other(), "first"); var prior = scope.registry.getListenerContainer("unrelated");
            var node = read(original());
            method(node, "first").visibleAnnotations = new ArrayList<>(List.of(annotation("first-id")));
            var second = annotation("second-id"); second.values.addAll(List.of("concurrency", "999999999999999999"));
            // A malformed declaration fails preflight; it must not leave even the first callback registered.
            method(node, "second").visibleAnnotations = new ArrayList<>(List.of(second));
            assertFalse(scope.reloader.reloadKafkaListeners(Owner.class, ADDED, write(node)));
            assertEquals(Set.of("unrelated"), scope.registry.getListenerContainerIds());
            assertSame(prior, scope.registry.getListenerContainer("unrelated"));
        }
    }
    @Test void actualSecondRegistrationFailureRemovesTheFirstAndKeepsUnrelatedContainer() throws Exception {
        try (var scope = new Scope()) {
            register(scope, "unrelated", new Other(), "first"); var prior = scope.registry.getListenerContainer("unrelated");
            var standard = new org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory();
            standard.afterPropertiesSet();
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            scope.context.getBean(org.springframework.kafka.annotation.KafkaListenerAnnotationBeanPostProcessor.class)
                    .setMessageHandlerMethodFactory((bean, method) -> {
                        attempts.incrementAndGet();
                        if (method.getName().equals("second")) throw new IllegalArgumentException("second registration fails");
                        return standard.createInvocableHandlerMethod(bean, method);
                    });
            var node = read(original());
            method(node, "first").visibleAnnotations = new ArrayList<>(List.of(annotation("first-id")));
            method(node, "second").visibleAnnotations = new ArrayList<>(List.of(annotation("second-id")));
            assertFalse(scope.reloader.reloadKafkaListeners(Owner.class, ADDED, write(node)));
            assertEquals(2, attempts.get(), "both real Spring registration paths must be reached");
            assertEquals(Set.of("unrelated"), scope.registry.getListenerContainerIds());
            assertSame(prior, scope.registry.getListenerContainer("unrelated"));
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("second registration fails")));
        }
    }

    @Test void proxyIsRefusedAndNoAdviceIsBypassed() throws Exception {
        try (var scope = new Scope()) {
            Owner target = scope.owner(); scope.context.getDefaultListableBeanFactory().destroySingleton("owner");
            var proxy = new org.springframework.aop.framework.ProxyFactory(target); proxy.setProxyTargetClass(true);
            scope.context.getBeanFactory().registerSingleton("owner", proxy.getProxy());
            assertFalse(scope.reloader.reloadKafkaListeners(Owner.class, ADDED, annotated("first", "added")));
            assertTrue(scope.registry.getListenerContainerIds().isEmpty()); assertTrue(target.calls.isEmpty());
        }
    }
    @Test void filteredFactoriesAreRefusedBeforeCreatingAnyContainer() throws Exception {
        try (var scope = new Scope()) {
            scope.factory().setRecordFilterStrategy(record -> false);
            assertFalse(scope.reloader.reloadKafkaListeners(Owner.class, ADDED, annotated("first", "added")));
            assertTrue(scope.registry.getListenerContainerIds().isEmpty());
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("recordFilterStrategy")));
        }
    }
    @Test void aWrappedExistingListenerIsRefusedBeforeUnregisteringIt() throws Exception {
        try (var scope = new Scope()) {
            scope.factory().setRecordFilterStrategy(record -> false);
            register(scope, "wrapped", scope.owner(), "first"); var prior = scope.registry.getListenerContainer("wrapped");
            assertFalse(scope.reloader.beforeBeanRefresh(Owner.class));
            assertSame(prior, scope.registry.getListenerContainer("wrapped"));
        }
    }
    @Test void concreteConsumerRecordGenericMetadataReachesTheRealKafkaAdapter() throws Exception {
        try (var scope = new Scope()) {
            String desc = "(Lorg/apache/kafka/clients/consumer/ConsumerRecord;)V";
            assertTrue(scope.reloader.reloadKafkaListeners(Owner.class, Set.of("record:" + desc), annotated("record", "records")));
            invoke(scope.registry.getListenerContainer("records"), "body"); assertEquals(List.of("record:body"), scope.owner().calls);
        }
    }
    public static class CustomProcessor extends org.springframework.kafka.annotation.KafkaListenerAnnotationBeanPostProcessor<Integer,String> { }
    @Test void unsupportedKafkaInfrastructureDoesNotBlockAnUnrelatedBeanReload() throws Exception {
        try (var scope = new Scope()) {
            String name = scope.context.getBeanNamesForType(org.springframework.kafka.annotation.KafkaListenerAnnotationBeanPostProcessor.class)[0];
            scope.context.getDefaultListableBeanFactory().destroySingleton(name);
            scope.context.getBeanFactory().registerSingleton(name, new CustomProcessor());
            assertTrue(scope.reloader.beforeBeanRefresh(Other.class), "unrelated bean must retain ordinary refresh with unsupported Kafka infrastructure");
            assertFalse(scope.reloader.beforeBeanRefresh(Owner.class, annotated("first", "new-id")),
                    "a newly declared Kafka callback still needs verified infrastructure");
        }
    }

    @Test void interruptedStopRetainsRegistryOwnershipAndCanBeRetried() throws Exception {
        try (var scope = new Scope()) {
            assertTrue(scope.reloader.reloadKafkaListeners(Owner.class, ADDED, annotated("first", "added")));
            var prior = scope.registry.getListenerContainer("added");
            try {
                Thread.currentThread().interrupt();
                assertFalse(scope.reloader.beforeBeanRefresh(Owner.class));
                assertTrue(Thread.currentThread().isInterrupted());
                assertSame(prior, scope.registry.getListenerContainer("added"));
            } finally { Thread.interrupted(); }
            assertTrue(scope.reloader.beforeBeanRefresh(Owner.class));
            assertTrue(scope.registry.getListenerContainerIds().isEmpty());
        }
    }

    public static class NativeExpressionOwner extends Owner {
        @org.springframework.kafka.annotation.KafkaListener(id = "native-expression", topics = "${route.expression}",
                groupId = "#{__listener.group}", autoStartup = "false")
        public void receive(String value) { }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"#{__listener.topic}", "#{__listener.topics}",
            "#{@routing}", "#{'${route.names}'.split(',')}", "#{{@routing, __listener.topic}}"})
    void topicAndGroupExpressionsMatchNativeStartup(String expression) throws Exception {
        String[] expected; String group;
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "routes", Map.of("route.expression", expression, "route.names", "route-one,route-two")));
            context.registerBean("routing", String.class, () -> "named-route");
            context.register(Config.class, NativeExpressionOwner.class);
            context.refresh();
            var container = context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("native-expression");
            expected = container.getContainerProperties().getTopics(); group = container.getGroupId();
            assertNotNull(expected); assertTrue(expected.length > 0);
        }
        try (var s = new Scope()) {
            s.context.getBeanFactory().registerSingleton("routing", "named-route");
            s.context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "routes", Map.of("route.names", "route-one,route-two")));
            assertTrue(s.reloader.reloadKafkaListeners(Owner.class, ADDED,
                    expressionBytes("first", "added", expression, "#{__listener.group}")), RestartLedger.digest().toString());
            var c = s.registry.getListenerContainer("added");
            assertArrayEquals(expected, c.getContainerProperties().getTopics());
            assertEquals(group, c.getGroupId());
            invoke(c, "body"); assertEquals(List.of("first:body"), s.owner().calls);
            assertNull(listenerScope(s).resolveContextualObject("__listener"));
        }
    }

    @Test void expressionScopeRestoresItsPreviousBindingAfterSuccessAndFailure() throws Exception {
        try (var s = new Scope()) {
            var scope = listenerScope(s);
            Object outer = new Object();
            var add = com.onurkat.reclazz.util.Reflect.findMethod(scope.getClass(), "addListener", String.class, Object.class);
            assertNotNull(add); add.invoke(scope, "__listener", outer);
            add.invoke(scope, "unrelated", outer);
            assertTrue(s.reloader.reloadKafkaListeners(Owner.class, ADDED,
                    expressionBytes("first", "added", "#{__listener.topic}", "#{__listener.group}")));
            assertSame(outer, scope.resolveContextualObject("__listener"));
            assertSame(outer, scope.resolveContextualObject("unrelated"));
            assertTrue(s.reloader.beforeBeanRefresh(Owner.class));
            assertFalse(s.reloader.reloadKafkaListeners(Owner.class, ADDED,
                    expressionBytes("first", "added", "#{42}", "group")));
            assertNull(s.registry.getListenerContainer("added"));
            assertSame(outer, scope.resolveContextualObject("__listener"));
            assertSame(outer, scope.resolveContextualObject("unrelated"));
        }
    }

    @Test void expressionRoutingBelongsToEachContextAndReevaluatesOnSave() throws Exception {
        try (var one = new Scope(); var two = new Scope()) {
            two.owner().topic = "other-topic"; two.owner().group = "other-group";
            byte[] bytes = expressionBytes("first", "added", "#{__listener.topic}", "#{__listener.group}");
            assertTrue(one.reloader.reloadKafkaListeners(Owner.class, ADDED, bytes));
            assertTrue(two.reloader.reloadKafkaListeners(Owner.class, ADDED, bytes));
            var original = one.registry.getListenerContainer("added");
            one.owner().topic = "edited-topic"; one.owner().group = "edited-group";
            assertArrayEquals(new String[]{"route-one"}, original.getContainerProperties().getTopics());
            assertTrue(one.reloader.beforeBeanRefresh(Owner.class));
            assertTrue(one.reloader.reloadKafkaListeners(Owner.class, ADDED, bytes));
            var changed = one.registry.getListenerContainer("added");
            assertNotSame(original, changed);
            assertArrayEquals(new String[]{"edited-topic"}, changed.getContainerProperties().getTopics());
            assertEquals("edited-group", changed.getGroupId());
            assertArrayEquals(new String[]{"other-topic"}, two.registry.getListenerContainer("added").getContainerProperties().getTopics());
            assertEquals("other-group", two.registry.getListenerContainer("added").getGroupId());
        }
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidExpressions() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("#{42}", "group"),
                org.junit.jupiter.params.provider.Arguments.of("#{broken(}", "group"),
                org.junit.jupiter.params.provider.Arguments.of("#{@missingRoute}", "group"),
                org.junit.jupiter.params.provider.Arguments.of("topic", "#{42}"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("invalidExpressions")
    void expressionFailuresCleanPartialRegistrationsAndScope(String topic, String group) throws Exception {
        try (var s = new Scope()) {
            register(s, "unrelated", new Other(), "first"); var unrelated = s.registry.getListenerContainer("unrelated");
            var n = read(expressionBytes("first", "first-id", "#{__listener.countedTopic}", "group"));
            method(n, "second").visibleAnnotations = method(read(expressionBytes("second", "second-id", topic, group)), "second").visibleAnnotations;
            assertFalse(s.reloader.reloadKafkaListeners(Owner.class, ADDED, write(n)));
            assertEquals(1, s.owner().evaluations, "the first registration must reach native expression resolution");
            assertEquals(Set.of("unrelated"), s.registry.getListenerContainerIds());
            assertSame(unrelated, s.registry.getListenerContainer("unrelated"));
            assertNull(listenerScope(s).resolveContextualObject("__listener"));
            assertTrue(s.reloader.reloadKafkaListeners(Owner.class, ADDED,
                    expressionBytes("first", "first-id", "#{__listener.topic}", "#{__listener.group}")));
            invoke(s.registry.getListenerContainer("first-id"), "recovered");
            assertEquals(List.of("first:recovered"), s.owner().calls);
        }
    }

    private static org.springframework.beans.factory.config.Scope listenerScope(Scope s) {
        Object processor = s.context.getBean(org.springframework.kafka.annotation.KafkaListenerAnnotationBeanPostProcessor.class);
        return (org.springframework.beans.factory.config.Scope) com.onurkat.reclazz.util.Reflect.readField(processor, "listenerScope");
    }
    private static byte[] expressionBytes(String name, String id, String topic, String group) throws Exception {
        var n = read(annotated(name, id)); var a = method(n, name).visibleAnnotations.get(0);
        a.values.set(a.values.indexOf("topics") + 1, new ArrayList<>(List.of(topic)));
        a.values.addAll(List.of("groupId", group)); return write(n);
    }

    @Test void unsupportedOptionsShapesAndExtraAdviceAreExplicitlyRejected() throws Exception {
        for (String option : List.of("topicPattern", "topicPartitions", "properties", "beanRef", "batch", "errorHandler")) {
            var node = read(annotated("first", "id")); var a = method(node, "first").visibleAnnotations.get(0); a.values.addAll(List.of(option, "x"));
            assertFalse(AddedKafkaListenerAdapter.inspect(write(node), ADDED).refused().isEmpty(), option);
        }
        var node = read(annotated("first", "id")); method(node, "first").access |= Opcodes.ACC_STATIC;
        assertFalse(AddedKafkaListenerAdapter.inspect(write(node), ADDED).refused().isEmpty());
        node = read(annotated("first", "id")); method(node, "first").visibleAnnotations.add(new AnnotationNode("Lorg/springframework/transaction/annotation/Transactional;"));
        assertFalse(AddedKafkaListenerAdapter.inspect(write(node), ADDED).refused().isEmpty());
        node = read(annotated("first", "${id}")); assertFalse(AddedKafkaListenerAdapter.inspect(write(node), ADDED).refused().isEmpty());
        node = read(annotated("first", "id")); node.signature = "<T:Ljava/lang/Object;>Ljava/lang/Object;";
        assertFalse(AddedKafkaListenerAdapter.inspect(write(node), ADDED).refused().isEmpty());
    }
    public static class Other { public void first(String value) { } }
    private static void register(Scope scope, String id, Object bean, String method) throws Exception {
        var endpoint = new MethodKafkaListenerEndpoint<Integer,String>(); endpoint.setId(id); endpoint.setGroupId(id);
        endpoint.setTopics("unused"); endpoint.setBean(bean); endpoint.setMethod(bean.getClass().getDeclaredMethod(method, String.class));
        endpoint.setMessageHandlerMethodFactory(scope.context.getBean(org.springframework.kafka.annotation.KafkaListenerAnnotationBeanPostProcessor.class).getMessageHandlerMethodFactory());
        scope.registry.registerListenerContainer(endpoint, scope.factory(), false);
    }
    @SuppressWarnings("unchecked") private static void invoke(MessageListenerContainer container, String body) {
        ((RecordMessagingMessageListenerAdapter<Integer,String>) container.getContainerProperties().getMessageListener())
                .onMessage(new ConsumerRecord<>("unused", 0, 0, 1, body), null, null);
    }
    private static byte[] original() throws Exception {
        try (var stream = Owner.class.getResourceAsStream("/" + Owner.class.getName().replace('.', '/') + ".class")) { return stream.readAllBytes(); }
    }
    private static byte[] annotated(String name, String id) throws Exception {
        var node = read(original()); method(node, name).visibleAnnotations = new ArrayList<>(List.of(annotation(id))); return write(node);
    }
    private static AnnotationNode annotation(String id) {
        var a = new AnnotationNode(AddedKafkaListenerAdapter.KAFKA);
        a.values = new ArrayList<>(List.of("id", id, "topics", List.of("unused"), "autoStartup", "false")); return a;
    }
    private static MethodNode method(ClassNode node, String name) { return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(); }
    private static ClassNode read(byte[] bytes) { var n = new ClassNode(); new ClassReader(bytes).accept(n, 0); return n; }
    private static byte[] write(ClassNode node) { var w = new ClassWriter(0); node.accept(w); return w.toByteArray(); }
}
