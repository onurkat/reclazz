/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.context.annotation.*;
import org.springframework.jms.annotation.*;
import org.springframework.jms.config.*;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.listener.adapter.MessagingMessageListenerAdapter;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SpringJmsReloaderTest {
    private static final String STRING = "(Ljava/lang/String;)V";
    private static final Set<String> ADDED = Set.of("first:" + STRING, "second:" + STRING, "blocked:" + STRING);
    private static BrokerService broker;
    private static String brokerUrl;
    private static CountDownLatch entered, release;
    @BeforeAll static void startBroker() throws Exception {
        broker = new BrokerService(); broker.setBrokerName("jms-unit-" + UUID.randomUUID());
        broker.setPersistent(false); broker.setUseJmx(false); broker.setUseShutdownHook(false);
        var connector = broker.addConnector("tcp://127.0.0.1:0"); broker.start(); broker.waitUntilStarted();
        brokerUrl = connector.getPublishableConnectString();
    }
    @AfterAll static void stopBroker() throws Exception { broker.stop(); broker.waitUntilStopped(); }
    @AfterEach void clearDiagnostics() { RestartLedger.clear(); }
    @org.springframework.stereotype.Component public static class Owner {
        final List<String> calls = new CopyOnWriteArrayList<>();
        public void first(String value) { calls.add("first:" + value); }
        private void second(String value) { calls.add("second:" + value); }
        public void blocked(String value) {
            entered.countDown();
            boolean interrupted = false;
            // Model application work which cannot finish just because Spring
            // interrupts its consumer during shutdown. Only the test releases it.
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException e) { interrupted = true; }
            }
            calls.add(value);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    @Configuration(proxyBeanMethods=false) @EnableJms static class Config {
        @Bean DefaultJmsListenerContainerFactory jmsListenerContainerFactory() {
            var factory = new DefaultJmsListenerContainerFactory();
            factory.setConnectionFactory(new ActiveMQConnectionFactory(brokerUrl));
            factory.setSessionTransacted(true); factory.setReceiveTimeout(50L);
            return factory;
        }
    }
    @Configuration(proxyBeanMethods=false) @org.springframework.kafka.annotation.EnableKafka static class KafkaConfig {
        @Bean org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Integer,String> kafkaListenerContainerFactory() {
            var f = new org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Integer,String>();
            f.setConsumerFactory(new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(Map.of(
                "bootstrap.servers", "127.0.0.1:1", "key.deserializer", org.apache.kafka.common.serialization.IntegerDeserializer.class,
                "value.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class)));
            f.setAutoStartup(false); return f;
        }
    }
    private static class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final JmsListenerEndpointRegistry registry;
        final PlatformContext platform;
        final SpringJmsReloader reloader;
        Scope() throws Exception { this(false); }
        Scope(boolean kafka) throws Exception { this(kafka, false); }
        Scope(boolean kafka, boolean rabbit) throws Exception {
            if (rabbit) {
                SpringRabbitReloaderTest.installTracking();
                context.register(SpringRabbitReloaderTest.Config.class);
                context.registerBean("rabbitConnectionFactory", org.springframework.amqp.rabbit.connection.CachingConnectionFactory.class,
                        () -> new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("127.0.0.1", 1));
            }
            LookupCapture.store(Owner.class, MethodHandles.privateLookupIn(Owner.class, MethodHandles.lookup()));
            if (kafka) context.register(KafkaConfig.class);
            context.register(Config.class); context.registerBean("owner", Owner.class); context.refresh();
            registry = context.getBean(JmsListenerEndpointRegistry.class);
            platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlatformContext.class},
                    (p,m,a) -> m.getName().equals("getAllApplicationContexts") ? List.of(context) : null);
            reloader = new SpringJmsReloader(platform, 200);
        }
        Owner owner() { return context.getBean(Owner.class); }
        DefaultJmsListenerContainerFactory factory() { return context.getBean(DefaultJmsListenerContainerFactory.class); }
        DefaultMessageListenerContainer container(String id) { return (DefaultMessageListenerContainer) registry.getListenerContainer(id); }
        @Override public void close() { context.close(); }
    }
    @Test void actualSpringAdapterUsesPrivateMethodAndCurrentSingleton() throws Exception {
        try (var s = new Scope()) {
            assertTrue(s.reloader.reloadJmsListeners(Owner.class, ADDED, annotated("second", "added")));
            var c = s.container("added"); assertTrue(c.isSessionTransacted());
            Owner old = s.owner(); invoke(c, "one"); assertEquals(List.of("second:one"), old.calls);
            s.context.getDefaultListableBeanFactory().destroySingleton("owner");
            Owner fresh = new Owner(); s.context.getDefaultListableBeanFactory().registerSingleton("owner", fresh);
            invoke(c, "two"); assertEquals(List.of("second:two"), fresh.calls); assertEquals(1, old.calls.size());
            s.context.getDefaultListableBeanFactory().destroySingleton("owner");
            assertThrows(Exception.class, () -> invoke(c, "missing"));
            assertNull(s.context.getDefaultListableBeanFactory().getSingleton("owner"));
        }
    }
    @Test void ownedContainersAreDestroyedAndUnrelatedIdentitySurvives() throws Exception {
        try (var s = new Scope()) {
            register(s, "original", s.owner(), "first"); register(s, "unrelated", new Other(), "first");
            var original = s.container("original"); var unrelated = s.container("unrelated");
            assertTrue(s.reloader.reloadJmsListeners(Owner.class, ADDED, annotated("second", "added")));
            var added = s.container("added");
            assertTrue(s.reloader.beforeBeanRefresh(Owner.class));
            assertEquals(Set.of("unrelated"), s.registry.getListenerContainerIds());
            assertSame(unrelated, s.container("unrelated")); assertTrue(unrelated.isActive());
            for (var c : List.of(original, added)) { assertFalse(c.isActive()); assertEquals(0, c.getScheduledConsumerCount()); }
            assertTrue(s.reloader.reloadJmsListeners(Owner.class, ADDED, annotated("second", "added")));
            assertNotSame(added, s.container("added"));
            assertTrue(s.reloader.beforeBeanRefresh(Owner.class));
            assertFalse(s.reloader.reloadJmsListeners(Owner.class, Set.of(), original()));
        }
    }
    @Test void duplicateIdCannotStealAnotherBeansContainer() throws Exception {
        try (var s = new Scope()) {
            register(s, "used", new Other(), "first"); var prior = s.container("used");
            assertFalse(s.reloader.reloadJmsListeners(Owner.class, ADDED, annotated("first", "used")));
            assertSame(prior, s.container("used")); assertTrue(prior.isActive());
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("already belongs")));
        }
    }
    @Test void secondRealRegistrationFailureCleansOnlyThisAttempt() throws Exception {
        try (var s = new Scope()) {
            register(s, "other", new Other(), "first"); var prior = s.container("other");
            var factory = new DefaultMessageHandlerMethodFactory(); factory.afterPropertiesSet();
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            var created = new ArrayList<DefaultMessageListenerContainer>();
            s.context.getBean(JmsListenerAnnotationBeanPostProcessor.class).setMessageHandlerMethodFactory((bean, method) -> {
                attempts.incrementAndGet();
                if (method.getName().equals("second")) {
                    created.add(s.container("first-id")); throw new IllegalArgumentException("second registration fails");
                }
                return factory.createInvocableHandlerMethod(bean, method);
            });
            var n = read(original()); method(n,"first").visibleAnnotations = new ArrayList<>(List.of(annotation("first-id")));
            method(n,"second").visibleAnnotations = new ArrayList<>(List.of(annotation("second-id")));
            assertFalse(s.reloader.reloadJmsListeners(Owner.class, ADDED, write(n)));
            assertEquals(2, attempts.get()); assertEquals(Set.of("other"), s.registry.getListenerContainerIds());
            assertSame(prior, s.container("other")); assertEquals(1, created.size());
            assertNotNull(created.get(0)); assertFalse(created.get(0).isActive());
        }
    }
    @Test void proxyReceiverIsRefusedWithoutBypassingAdvice() throws Exception {
        try (var s = new Scope()) {
            var pf = new org.springframework.aop.framework.ProxyFactory(s.owner()); pf.setProxyTargetClass(true);
            s.context.getDefaultListableBeanFactory().destroySingleton("owner");
            s.context.getDefaultListableBeanFactory().registerSingleton("owner", pf.getProxy());
            assertFalse(s.reloader.reloadJmsListeners(Owner.class, ADDED, annotated("first", "id")));
            assertTrue(s.registry.getListenerContainerIds().isEmpty());
        }
    }
    public static class CustomProcessor extends JmsListenerAnnotationBeanPostProcessor { }
    @Test void unsupportedInfrastructureDoesNotBlockAnUnrelatedBean() throws Exception {
        try (var s = new Scope()) {
            var bf = s.context.getDefaultListableBeanFactory();
            String name = s.context.getBeanNamesForType(JmsListenerAnnotationBeanPostProcessor.class)[0];
            bf.destroySingleton(name); bf.registerSingleton(name, new CustomProcessor());
            assertTrue(s.reloader.beforeBeanRefresh(Other.class));
            assertFalse(s.reloader.beforeBeanRefresh(Owner.class, annotated("first", "added")));
        }
    }
    @Test void mixedBrokerRefusalPrecedesAnyJmsOrKafkaRetirement() throws Exception {
        try (var s = new Scope(true)) {
            register(s, "original", s.owner(), "first"); var prior = s.container("original");
            var kr = s.context.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class);
            var endpoint = new org.springframework.kafka.config.MethodKafkaListenerEndpoint<Integer,String>();
            endpoint.setId("kafka-original"); endpoint.setGroupId("test"); endpoint.setTopics("unused");
            endpoint.setBean(s.owner()); endpoint.setMethod(Owner.class.getMethod("first", String.class));
            var handler = new DefaultMessageHandlerMethodFactory(); handler.afterPropertiesSet(); endpoint.setMessageHandlerMethodFactory(handler);
            kr.registerListenerContainer(endpoint, s.context.getBean(org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory.class), false);
            var kafkaPrior = kr.getListenerContainer("kafka-original");
            assertNotNull(kafkaPrior);
            // No Kafka annotation in saved bytes: discover the actual existing
            // Spring container's owner, including an annotation removed by a save.
            assertFalse(s.reloader.beforeBeanRefresh(Owner.class, annotated("second", "added"), new SpringKafkaReloader(s.platform)));
            assertSame(prior, s.container("original")); assertTrue(prior.isActive());
            assertSame(kafkaPrior, kr.getListenerContainer("kafka-original"));
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("mixed Kafka/JMS")));
        }
    }
    @Test void mixedRabbitJmsOwnerWithRemovedAnnotationsIsRefusedBeforeEitherRetires() throws Exception {
        try (var s = new Scope(false, true)) {
            register(s, "jms-original", s.owner(), "first"); var jms = s.container("jms-original");
            var registry = s.context.getBean(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry.class);
            var endpoint = new org.springframework.amqp.rabbit.listener.MethodRabbitListenerEndpoint();
            endpoint.setId("rabbit-original"); endpoint.setQueueNames("unused"); endpoint.setBean(s.owner());
            endpoint.setMethod(Owner.class.getMethod("first", String.class));
            var handler = new DefaultMessageHandlerMethodFactory(); handler.afterPropertiesSet(); endpoint.setMessageHandlerMethodFactory(handler);
            registry.registerListenerContainer(endpoint, s.context.getBean(org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory.class), false);
            var rabbit = registry.getListenerContainer("rabbit-original");
            assertFalse(new SpringRabbitReloader(s.platform, 200).beforeBeanRefresh(Owner.class, original(), null, s.reloader));
            assertSame(jms, s.container("jms-original")); assertTrue(jms.isActive());
            assertSame(rabbit, registry.getListenerContainer("rabbit-original"));
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("mixed Rabbit/Kafka/JMS")));
        }
    }
    @Test void unfinishedShutdownAndRepeatedWaitKeepRegistryOwnership() throws Exception {
        entered = new CountDownLatch(1); release = new CountDownLatch(1);
        try (var s = new Scope()) {
            String id = "blocked-" + UUID.randomUUID();
            assertTrue(s.reloader.reloadJmsListeners(Owner.class, ADDED, annotated("blocked", id)));
            var c = s.container(id);
            new JmsTemplate(new ActiveMQConnectionFactory(brokerUrl)).convertAndSend("unused", "blocked");
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(s.reloader.beforeBeanRefresh(Owner.class));
                assertSame(c, s.container(id));
                assertFalse(s.reloader.beforeBeanRefresh(Owner.class), "a repeated wait must not mistake stopping for stopped");
                assertSame(c, s.container(id));
                Thread.currentThread().interrupt();
                assertFalse(s.reloader.beforeBeanRefresh(Owner.class)); assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); release.countDown(); }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (c.getActiveConsumerCount() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(s.reloader.beforeBeanRefresh(Owner.class));
            assertNull(s.container(id)); assertFalse(c.isActive()); assertEquals(0,c.getScheduledConsumerCount());
        } finally { release.countDown(); }
    }
    @Test void unsupportedFactoriesAreRefusedBeforeRegistration() throws Exception {
        try (var s = new Scope()) {
            s.factory().setSubscriptionDurable(true);
            assertFalse(s.reloader.reloadJmsListeners(Owner.class, ADDED, annotated("first", "id")));
            assertTrue(s.registry.getListenerContainerIds().isEmpty());
            s.factory().setSubscriptionDurable(false); s.factory().setTaskExecutor(Runnable::run);
            assertFalse(s.reloader.reloadJmsListeners(Owner.class, ADDED, annotated("first", "id")));
            assertTrue(s.registry.getListenerContainerIds().isEmpty());
        }
    }
    @Test void selectorAndConcurrencyUseTheActualFactoryAndBroker() throws Exception {
        try (var s = new Scope()) {
            String queue = "selected-" + UUID.randomUUID();
            var n = read(annotated("first", "selected"));
            method(n,"first").visibleAnnotations.get(0).values.addAll(List.of(
                    "destination", queue, "selector", "kind = 'accepted'", "concurrency", "1-2", "containerFactory", "jmsListenerContainerFactory"));
            assertTrue(s.reloader.reloadJmsListeners(Owner.class, ADDED, write(n)));
            var c = s.container("selected");
            assertEquals("kind = 'accepted'", c.getMessageSelector());
            assertEquals(1, c.getConcurrentConsumers()); assertEquals(2, c.getMaxConcurrentConsumers());
            var producer = new JmsTemplate(new ActiveMQConnectionFactory(brokerUrl));
            producer.convertAndSend(queue, "rejected", m -> { m.setStringProperty("kind", "rejected"); return m; });
            producer.convertAndSend(queue, "accepted", m -> { m.setStringProperty("kind", "accepted"); return m; });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (s.owner().calls.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(List.of("first:accepted"), s.owner().calls);
            producer.setReceiveTimeout(1000);
            assertEquals("rejected", producer.receiveSelectedAndConvert(queue, "kind = 'rejected'"));
        }
    }
    public static class CustomAdapter extends MessagingMessageListenerAdapter { }
    @Test void customExistingAdapterIsRefusedBeforeContainerShutdown() throws Exception {
        try (var s = new Scope()) {
            register(s, "original", s.owner(), "first"); var c = s.container("original");
            var factory = new DefaultMessageHandlerMethodFactory(); factory.afterPropertiesSet();
            var adapter = new CustomAdapter(); adapter.setHandlerMethod(factory.createInvocableHandlerMethod(s.owner(), Owner.class.getMethod("first", String.class)));
            c.setMessageListener(adapter);
            assertFalse(s.reloader.beforeBeanRefresh(Owner.class));
            assertSame(c, s.container("original")); assertTrue(c.isActive());
        }
    }
    @Test void invalidMetadataCannotCreatePartialRegistration() throws Exception {
        try (var s = new Scope()) {
            var n=read(annotated("first", "id")); var a=annotation("second-id");
            a.values.addAll(List.of("concurrency", "9999999999999999")); method(n,"second").visibleAnnotations=new ArrayList<>(List.of(a));
            assertFalse(s.reloader.reloadJmsListeners(Owner.class, ADDED, write(n)));
            assertTrue(s.registry.getListenerContainerIds().isEmpty());
        }
    }
    @Test void unsupportedShapesAndMethodAdviceAreExplicitlyRefused() throws Exception {
        for (var pair : List.of(List.of("subscription","durable"),List.of("id","${id}"), List.of("concurrency","3-1"))) {
            var n=read(annotated("first","id")); var a=method(n,"first").visibleAnnotations.get(0);
            a.values.addAll(pair); assertFalse(AddedJmsListenerAdapter.inspect(write(n),ADDED).refused().isEmpty());
        }
        var n=read(annotated("first","id")); method(n,"first").access |= Opcodes.ACC_STATIC;
        assertFalse(AddedJmsListenerAdapter.inspect(write(n),ADDED).refused().isEmpty());
        n=read(annotated("first","id")); method(n,"first").visibleAnnotations.add(new AnnotationNode("Lorg/springframework/transaction/annotation/Transactional;"));
        assertFalse(AddedJmsListenerAdapter.inspect(write(n),ADDED).refused().isEmpty());
        n=read(annotated("first","id")); n.signature="<T:Ljava/lang/Object;>Ljava/lang/Object;";
        assertFalse(AddedJmsListenerAdapter.inspect(write(n),ADDED).refused().isEmpty());
    }
    public static class Other { public void first(String value) { } }
    private static void register(Scope s,String id,Object bean,String name) throws Exception {
        var endpoint=new MethodJmsListenerEndpoint(); endpoint.setId(id); endpoint.setDestination("unused");
        endpoint.setBean(bean); endpoint.setMethod(bean.getClass().getDeclaredMethod(name,String.class));
        var factory=new DefaultMessageHandlerMethodFactory(); factory.afterPropertiesSet(); endpoint.setMessageHandlerMethodFactory(factory);
        s.registry.registerListenerContainer(endpoint,s.factory(),false);
    }
    private static void invoke(DefaultMessageListenerContainer c,String text) throws Exception {
        var message=new ActiveMQTextMessage(); message.setText(text);
        ((MessagingMessageListenerAdapter)c.getMessageListener()).onMessage(message,null);
    }
    private static byte[] original() throws Exception {
        try(var in=Owner.class.getResourceAsStream("/"+Owner.class.getName().replace('.','/')+".class")) { return in.readAllBytes(); }
    }
    private static byte[] annotated(String name,String id) throws Exception {
        var n=read(original()); method(n,name).visibleAnnotations=new ArrayList<>(List.of(annotation(id))); return write(n);
    }
    private static AnnotationNode annotation(String id) {
        var a=new AnnotationNode(AddedJmsListenerAdapter.JMS); a.values=new ArrayList<>(List.of("id",id,"destination","unused")); return a;
    }
    private static ClassNode read(byte[] bytes) { var n=new ClassNode(); new ClassReader(bytes).accept(n,0); return n; }
    private static MethodNode method(ClassNode n,String name) { return n.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow(); }
    private static byte[] write(ClassNode n) { var w=new ClassWriter(0);n.accept(w);return w.toByteArray(); }
}
