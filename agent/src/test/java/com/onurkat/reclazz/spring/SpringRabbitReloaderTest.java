/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.MethodRabbitListenerEndpoint;
import com.onurkat.reclazz.util.Reflect;
import com.onurkat.reclazz.transform.RabbitConsumerTransformer;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.context.annotation.*;
import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.amqp.rabbit.config.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SpringRabbitReloaderTest {
    private static final String STRING = "(Ljava/lang/String;)V";
    private static final Set<String> ADDED = Set.of("first:" + STRING, "second:" + STRING, "blocked:" + STRING);
    static CountDownLatch entered, release;
    @BeforeAll static void installTracking() throws Exception {
        net.bytebuddy.agent.ByteBuddyAgent.install().addTransformer(new RabbitConsumerTransformer());
        Class<?> worker = Class.forName(RabbitConsumerTransformer.TARGET.replace('/', '.'));
        assertTrue(com.onurkat.reclazz.bootstrap.RabbitConsumerBridge.isHooked(worker));
    }
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
    @Configuration(proxyBeanMethods=false) @EnableRabbit static class Config {
        @Bean SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(CachingConnectionFactory cf) {
            var factory = new SimpleRabbitListenerContainerFactory();
            factory.setConnectionFactory(cf);
            factory.setChannelTransacted(true); factory.setReceiveTimeout(50L); factory.setAutoStartup(false);
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
    static class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final RabbitListenerEndpointRegistry registry;
        final PlatformContext platform;
        final SpringRabbitReloader reloader;
        Scope() throws Exception { this(false); }
        Scope(boolean kafka) throws Exception { this(kafka, new CachingConnectionFactory("127.0.0.1", 1), false); }
        Scope(boolean kafka, CachingConnectionFactory cf, boolean start) throws Exception {
            context.registerBean("rabbitConnectionFactory", CachingConnectionFactory.class, () -> cf);
            LookupCapture.store(Owner.class, MethodHandles.privateLookupIn(Owner.class, MethodHandles.lookup()));
            if (kafka) context.register(KafkaConfig.class);
            context.register(Config.class); context.registerBean("owner", Owner.class); context.refresh();
            registry = context.getBean(RabbitListenerEndpointRegistry.class);
            assertTrue(Reflect.writeField(registry, "contextRefreshed", start));
            platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlatformContext.class},
                    (p,m,a) -> m.getName().equals("getAllApplicationContexts") ? List.of(context) : null);
            reloader = new SpringRabbitReloader(platform, 200);
        }
        Owner owner() { return context.getBean(Owner.class); }
        SimpleRabbitListenerContainerFactory factory() { return context.getBean(SimpleRabbitListenerContainerFactory.class); }
        SimpleMessageListenerContainer container(String id) { return (SimpleMessageListenerContainer) registry.getListenerContainer(id); }
        @Override public void close() { context.close(); }
    }
    public static class IdlessOwner {
        @RabbitListener(queues = "unused")
        public void first(String value) { }
    }
    public static class ConfiguredOwner {
        @RabbitListener(id = "configured-original", queues = "unused", ackMode = "AUTO", autoStartup = "false")
        public void first(String value) { }
    }
    static java.util.stream.Stream<Class<?>> existingOwnerTypes() {
        return java.util.stream.Stream.of(IdlessOwner.class, ConfiguredOwner.class);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("existingOwnerTypes")
    void existingListenerMetadataDoesNotBlockOrdinaryRecreation(Class<?> type) throws Exception {
        try (var s = new Scope()) {
            s.context.registerBean("existingOwner", type);
            Object old = s.context.getBean("existingOwner");
            assertEquals(1, s.registry.getListenerContainerIds().size());
            String id = s.registry.getListenerContainerIds().iterator().next();
            var prior = s.container(id);
            byte[] bytes;
            try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) { bytes = in.readAllBytes(); }
            assertTrue(s.reloader.beforeBeanRefresh(type, bytes), RestartLedger.digest().toString());
            assertTrue(s.registry.getListenerContainerIds().isEmpty());
            assertFalse(prior.isActive()); assertFalse(prior.isRunning());
            s.context.getDefaultListableBeanFactory().destroySingleton("existingOwner");
            Object fresh = s.context.getBean("existingOwner");
            assertNotSame(old, fresh);
            assertEquals(1, s.registry.getListenerContainerIds().size());
            var current = s.container(s.registry.getListenerContainerIds().iterator().next());
            assertNotSame(prior, current);
            assertArrayEquals(new String[]{"unused"}, current.getQueueNames());
            assertEquals(org.springframework.amqp.core.AcknowledgeMode.AUTO, current.getAcknowledgeMode());
            assertFalse(current.isAutoStartup());
            if (type == IdlessOwner.class) assertFalse(s.registry.getListenerContainerIds().contains(id));
            else assertEquals(Set.of("configured-original"), s.registry.getListenerContainerIds());
        }
    }
    @Test void actualSpringAdapterUsesPrivateMethodAndCurrentSingleton() throws Exception {
        try (var s = new Scope()) {
            assertTrue(s.reloader.reloadRabbitListeners(Owner.class, ADDED, annotated("second", "added")));
            var c = s.container("added"); assertTrue(c.isChannelTransacted());
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
            assertTrue(s.reloader.reloadRabbitListeners(Owner.class, ADDED, annotated("second", "added")));
            var added = s.container("added");
            assertTrue(s.reloader.beforeBeanRefresh(Owner.class));
            assertEquals(Set.of("unrelated"), s.registry.getListenerContainerIds());
            assertSame(unrelated, s.container("unrelated")); assertFalse(unrelated.isActive());
            for (var c : List.of(original, added)) { assertFalse(c.isActive()); assertEquals(0, c.getActiveConsumerCount()); }
            assertTrue(s.reloader.reloadRabbitListeners(Owner.class, ADDED, annotated("second", "added")));
            assertNotSame(added, s.container("added"));
            assertTrue(s.reloader.beforeBeanRefresh(Owner.class));
            assertFalse(s.reloader.reloadRabbitListeners(Owner.class, Set.of(), original()));
        }
    }
    @Test void duplicateIdCannotStealAnotherBeansContainer() throws Exception {
        try (var s = new Scope()) {
            register(s, "used", new Other(), "first"); var prior = s.container("used");
            assertFalse(s.reloader.reloadRabbitListeners(Owner.class, ADDED, annotated("first", "used")));
            assertSame(prior, s.container("used")); assertFalse(prior.isActive());
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("already belongs")));
        }
    }
    @Test void secondRealRegistrationFailureCleansOnlyThisAttempt() throws Exception {
        try (var s = new Scope()) {
            register(s, "other", new Other(), "first"); var prior = s.container("other");
            var factory = new DefaultMessageHandlerMethodFactory(); factory.afterPropertiesSet();
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            var created = new ArrayList<SimpleMessageListenerContainer>();
            s.context.getBean(RabbitListenerAnnotationBeanPostProcessor.class).setMessageHandlerMethodFactory((bean, method) -> {
                attempts.incrementAndGet();
                if (method.getName().equals("second")) {
                    created.add(s.container("first-id")); throw new IllegalArgumentException("second registration fails");
                }
                return factory.createInvocableHandlerMethod(bean, method);
            });
            var n = read(original()); method(n,"first").visibleAnnotations = new ArrayList<>(List.of(annotation("first-id")));
            method(n,"second").visibleAnnotations = new ArrayList<>(List.of(annotation("second-id")));
            assertFalse(s.reloader.reloadRabbitListeners(Owner.class, ADDED, write(n)));
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
            assertFalse(s.reloader.reloadRabbitListeners(Owner.class, ADDED, annotated("first", "id")));
            assertTrue(s.registry.getListenerContainerIds().isEmpty());
        }
    }
    public static class CustomProcessor extends RabbitListenerAnnotationBeanPostProcessor { }
    @Test void unsupportedInfrastructureDoesNotBlockAnUnrelatedBean() throws Exception {
        try (var s = new Scope()) {
            var bf = s.context.getDefaultListableBeanFactory();
            String name = s.context.getBeanNamesForType(RabbitListenerAnnotationBeanPostProcessor.class)[0];
            bf.destroySingleton(name); bf.registerSingleton(name, new CustomProcessor());
            assertTrue(s.reloader.beforeBeanRefresh(Other.class));
            assertFalse(s.reloader.beforeBeanRefresh(Owner.class, annotated("first", "added")));
        }
    }
    @Test void mixedBrokerRefusalPrecedesAnyRabbitOrKafkaRetirement() throws Exception {
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
            assertFalse(s.reloader.beforeBeanRefresh(Owner.class, annotated("second", "added"), new SpringKafkaReloader(s.platform), null));
            assertSame(prior, s.container("original")); assertFalse(prior.isActive());
            assertSame(kafkaPrior, kr.getListenerContainer("kafka-original"));
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("mixed Rabbit/Kafka/JMS")));
        }
    }
    @Test void unsupportedFactoriesAreRefusedBeforeRegistration() throws Exception {
        try (var s = new Scope()) {
            s.factory().setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
            assertFalse(s.reloader.reloadRabbitListeners(Owner.class, ADDED, annotated("first", "id")));
            assertTrue(s.registry.getListenerContainerIds().isEmpty());
            s.factory().setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.AUTO);
            s.factory().setAdviceChain((org.aopalliance.intercept.MethodInterceptor) invocation -> invocation.proceed());
            assertFalse(s.reloader.reloadRabbitListeners(Owner.class, ADDED, annotated("first", "id")));
            assertTrue(s.registry.getListenerContainerIds().isEmpty());
            s.factory().setAdviceChain(); s.factory().setTaskExecutor(Runnable::run);
            assertFalse(s.reloader.reloadRabbitListeners(Owner.class, ADDED, annotated("first", "id")));
        }
    }
    @Test void invalidMetadataCannotCreatePartialRegistration() throws Exception {
        try (var s = new Scope()) {
            var n=read(annotated("first", "id")); var a=annotation("second-id");
            a.values.addAll(List.of("concurrency", "9999999999999999")); method(n,"second").visibleAnnotations=new ArrayList<>(List.of(a));
            assertFalse(s.reloader.reloadRabbitListeners(Owner.class, ADDED, write(n)));
            assertTrue(s.registry.getListenerContainerIds().isEmpty());
        }
    }
    @Test void unsupportedShapesAndMethodAdviceAreExplicitlyRefused() throws Exception {
        for (var pair : List.of(List.of("autoStartup","false"),List.of("id","${id}"), List.of("concurrency","3-1"))) {
            var n=read(annotated("first","id")); var a=method(n,"first").visibleAnnotations.get(0);
            a.values.addAll(pair); assertFalse(AddedRabbitListenerAdapter.inspect(write(n),ADDED).refused().isEmpty());
        }
        var n=read(annotated("first","id")); method(n,"first").access |= Opcodes.ACC_STATIC;
        assertFalse(AddedRabbitListenerAdapter.inspect(write(n),ADDED).refused().isEmpty());
        n=read(annotated("first","id")); method(n,"first").visibleAnnotations.add(new AnnotationNode("Lorg/springframework/transaction/annotation/Transactional;"));
        assertFalse(AddedRabbitListenerAdapter.inspect(write(n),ADDED).refused().isEmpty());
        n=read(annotated("first","id")); n.signature="<T:Ljava/lang/Object;>Ljava/lang/Object;";
        assertFalse(AddedRabbitListenerAdapter.inspect(write(n),ADDED).refused().isEmpty());
    }
    @Test void refusedSaveDoesNotRetireExistingConsumers() throws Exception {
        try (var s = new Scope()) {
            register(s, "original", s.owner(), "first"); register(s, "foreign", new Other(), "first");
            var original = s.container("original"); var foreign = s.container("foreign");
            assertFalse(s.reloader.beforeBeanRefresh(Owner.class, annotated("second", "foreign"), ADDED));
            assertSame(original, s.container("original")); assertSame(foreign, s.container("foreign"));
            var n = read(annotated("second", "added"));
            method(n, "second").visibleAnnotations.add(new AnnotationNode("Lorg/springframework/transaction/annotation/Transactional;"));
            assertFalse(s.reloader.beforeBeanRefresh(Owner.class, write(n), ADDED));
            assertSame(original, s.container("original"));
            s.factory().setContainerCustomizer(c -> { });
            assertFalse(s.reloader.beforeBeanRefresh(Owner.class, annotated("second", "added"), ADDED));
            assertSame(original, s.container("original"));
        }
    }
    public static class CustomAdapter extends MessagingMessageListenerAdapter { }
    @Test void customExistingAdapterIsRefusedBeforeRetirement() throws Exception {
        try (var s = new Scope()) {
            register(s, "original", s.owner(), "first"); var c = s.container("original");
            var handler = new DefaultMessageHandlerMethodFactory(); handler.afterPropertiesSet();
            var adapter = new CustomAdapter();
            adapter.setHandlerAdapter(new org.springframework.amqp.rabbit.listener.adapter.HandlerAdapter(
                    handler.createInvocableHandlerMethod(s.owner(), Owner.class.getMethod("first", String.class))));
            c.setMessageListener(adapter);
            assertFalse(s.reloader.beforeBeanRefresh(Owner.class)); assertSame(c, s.container("original"));
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("custom Rabbit listener adapter")));
        }
    }
    @Test void existingContainerAdviceIsRefusedBeforeRetirement() throws Exception {
        try (var s = new Scope()) {
            register(s, "original", s.owner(), "first"); var original = s.container("original");
            original.setAdviceChain((org.aopalliance.intercept.MethodInterceptor) invocation -> invocation.proceed());
            assertFalse(s.reloader.beforeBeanRefresh(Owner.class));
            assertSame(original, s.container("original"));
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("advice")));
        }
    }
    @Test void explicitFactoryAndConcurrencyUseSpringSettings() throws Exception {
        try (var s = new Scope()) {
            var n = read(annotated("first", "configured"));
            method(n, "first").visibleAnnotations.get(0).values.addAll(List.of(
                    "concurrency", "2-3", "containerFactory", "rabbitListenerContainerFactory"));
            assertTrue(s.reloader.reloadRabbitListeners(Owner.class, ADDED, write(n)));
            var c = s.container("configured");
            assertEquals(2, Reflect.readField(c, "concurrentConsumers"));
            assertEquals(3, Reflect.readField(c, "maxConcurrentConsumers"));
            assertFalse(c.isAutoStartup()); assertFalse(c.isRunning());
            assertArrayEquals(new String[]{"unused"}, c.getQueueNames());
        }
    }
    public static class Other { public void first(String value) { } }
    static void register(Scope s,String id,Object bean,String name) throws Exception {
        var endpoint=new MethodRabbitListenerEndpoint(); endpoint.setId(id); endpoint.setQueueNames("unused");
        endpoint.setBean(bean); endpoint.setMethod(bean.getClass().getDeclaredMethod(name,String.class));
        var factory=new DefaultMessageHandlerMethodFactory(); factory.afterPropertiesSet(); endpoint.setMessageHandlerMethodFactory(factory);
        s.registry.registerListenerContainer(endpoint,s.factory(),false);
    }
    private static void invoke(SimpleMessageListenerContainer c,String text) throws Exception {
        var properties = new org.springframework.amqp.core.MessageProperties();
        properties.setContentType(org.springframework.amqp.core.MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
        var message = new org.springframework.amqp.core.Message(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);
        ((MessagingMessageListenerAdapter)c.getMessageListener()).onMessage(message,null);
    }
    private static byte[] original() throws Exception {
        try(var in=Owner.class.getResourceAsStream("/"+Owner.class.getName().replace('.','/')+".class")) { return in.readAllBytes(); }
    }
    static byte[] annotated(String name,String id) throws Exception {
        var n=read(original()); method(n,name).visibleAnnotations=new ArrayList<>(List.of(annotation(id))); return write(n);
    }
    private static AnnotationNode annotation(String id) {
        var a=new AnnotationNode(AddedRabbitListenerAdapter.RABBIT); a.values=new ArrayList<>(List.of("id",id,"queues",new ArrayList<>(List.of("unused")))); return a;
    }
    private static ClassNode read(byte[] bytes) { var n=new ClassNode(); new ClassReader(bytes).accept(n,0); return n; }
    private static MethodNode method(ClassNode n,String name) { return n.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow(); }
    private static byte[] write(ClassNode n) { var w=new ClassWriter(0);n.accept(w);return w.toByteArray(); }
}
