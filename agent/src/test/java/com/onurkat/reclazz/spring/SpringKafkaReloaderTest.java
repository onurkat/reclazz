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
