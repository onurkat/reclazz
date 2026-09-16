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
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jms.annotation.*;
import org.springframework.jms.config.*;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.support.MessageBuilder;

import javax.jms.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SpringJmsRequestReplyTest {
    static BrokerService broker; static String brokerUrl;
    @BeforeAll static void startBroker() throws Exception {
        broker = new BrokerService(); broker.setBrokerName("jms-replies-" + UUID.randomUUID());
        broker.setPersistent(false); broker.setUseJmx(false); broker.setUseShutdownHook(false);
        var connector = broker.addConnector("tcp://127.0.0.1:0"); broker.start(); broker.waitUntilStarted();
        brokerUrl = connector.getPublishableConnectString();
    }
    @AfterAll static void stopBroker() throws Exception { broker.stop(); broker.waitUntilStopped(); }
    @AfterEach void clearDiagnostics() { RestartLedger.clear(); }

    @org.springframework.stereotype.Component public static class Owner {
        final AtomicInteger calls = new AtomicInteger();
        public String text(String value) { calls.incrementAndGet(); return value.equals("null") ? null : "reply:" + value; }
        public byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        public Map<String,String> map(String value) { return Map.of("value", value); }
        public Message jms(String value, Session session) throws JMSException { return session.createTextMessage("jms:" + value); }
        public org.springframework.messaging.Message<String> spring(String value) {
            return MessageBuilder.withPayload("spring:" + value).setHeader("replyKind", "wrapped").build();
        }
        public String retry(String value, Message request) throws JMSException {
            if (calls.incrementAndGet() == 1) throw new java.lang.IllegalStateException("retry once");
            return "retry:" + value + ":" + request.getJMSRedelivered();
        }
    }
    public static class Native {
        @JmsListener(id="native", destination="${request.queue}") @SendTo("${reply.queue}")
        public String text(String value) { return "reply:" + value; }
    }
    @Configuration(proxyBeanMethods=false) @EnableJms static class Config {
        @Bean DefaultJmsListenerContainerFactory jmsListenerContainerFactory() {
            var f = new DefaultJmsListenerContainerFactory();
            var cf = new ActiveMQConnectionFactory(brokerUrl);
            cf.getRedeliveryPolicy().setInitialRedeliveryDelay(0);
            f.setConnectionFactory(cf); f.setSessionTransacted(true); f.setReceiveTimeout(50L); return f;
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void defaultDestinationReplyToAndCorrelationMatchNativeSpring(boolean nativeControl) throws Exception {
        try (var s = new Scope(nativeControl)) {
            if (!nativeControl) assertTrue(s.register("text", "${reply.queue}"));
            Message defaultReply = s.exchange("hello", false, null);
            assertEquals("reply:hello", ((TextMessage) defaultReply).getText());
            Message override = s.exchange("override", true, "caller-correlation");
            assertEquals("reply:override", ((TextMessage) override).getText());
            assertTrue(s.errors.isEmpty());
        }
    }

    @Test void replyToNeedsNoSendToAnnotation() throws Exception {
        try (var s = new Scope(false)) {
            assertTrue(s.register("text", null));
            assertEquals("reply:direct", ((TextMessage) s.exchange("direct", true, null)).getText());
        }
    }

    @Test void converterPreservesBytesMapAndNativeAndSpringMessages() throws Exception {
        try (var s = new Scope(false)) {
            for (String method : List.of("bytes", "map", "jms", "spring")) {
                assertTrue(s.reloader.beforeBeanRefresh(Owner.class));
                assertTrue(s.register(method, "${reply.queue}"));
                Message response = s.exchange("payload", false, null);
                switch (method) {
                    case "bytes" -> {
                        var bytes = (BytesMessage) response; byte[] payload = new byte[(int) bytes.getBodyLength()];
                        assertEquals(payload.length, bytes.readBytes(payload));
                        assertEquals("payload", new String(payload, StandardCharsets.UTF_8));
                    }
                    case "map" -> assertEquals("payload", ((MapMessage) response).getString("value"));
                    case "jms" -> assertEquals("jms:payload", ((TextMessage) response).getText());
                    case "spring" -> {
                        assertEquals("spring:payload", ((TextMessage) response).getText());
                        assertEquals("wrapped", response.getStringProperty("replyKind"));
                    }
                }
            }
        }
    }

    @Test void nullResultSendsNothingAfterTheBodyRuns() throws Exception {
        try (var s = new Scope(false)) {
            assertTrue(s.register("text", "${reply.queue}"));
            try (Connection connection = new ActiveMQConnectionFactory(brokerUrl).createConnection();
                 Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                connection.start(); var destination = session.createQueue(s.reply);
                var consumer = session.createConsumer(destination);
                session.createProducer(session.createQueue(s.request)).send(session.createTextMessage("null"));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (s.owner().calls.get() == 0 && System.nanoTime() < until) Thread.sleep(10);
                assertEquals(1, s.owner().calls.get()); assertNull(consumer.receive(300));
            }
            assertTrue(s.errors.isEmpty());
        }
    }

    @Test void failedTransactedAttemptProducesOneReplyOnlyAfterRedelivery() throws Exception {
        try (var s = new Scope(false)) {
            assertTrue(s.register("retry", "${reply.queue}"));
            assertEquals("retry:work:true", ((TextMessage) s.exchange("work", false, "retry-id")).getText());
            assertEquals(2, s.owner().calls.get()); assertEquals(1, s.errors.size());
        }
    }

    @Test void invalidSendToLeavesNoContainerAndNextCorrectedSaveRecovers() throws Exception {
        try (var s = new Scope(false)) {
            byte[] invalid = saved("text", s.request, List.of("first", "second"));
            assertFalse(s.reloader.reloadJmsListeners(Owner.class, added(invalid), invalid));
            assertTrue(s.registry.getListenerContainerIds().isEmpty());
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("one destination")));
            assertTrue(s.register("text", "${reply.queue}"));
            assertEquals("reply:fixed", ((TextMessage) s.exchange("fixed", false, null)).getText());
        }
    }

    @Test void unsupportedResultsAndAdviceStayRefused() throws Exception {
        for (String result : List.of("I", "Ljava/lang/Object;", "Ljava/util/concurrent/Future;",
                "Ljava/util/concurrent/CompletableFuture;", "Lorg/reactivestreams/Publisher;")) {
            var n = read(saved("text", "input", List.of("output")));
            var m = n.methods.stream().filter(x -> x.name.equals("text")).findFirst().orElseThrow();
            m.desc = "(Ljava/lang/String;)" + result;
            byte[] bytes = write(n);
            assertFalse(AddedJmsListenerAdapter.inspect(bytes, added(bytes)).refused().isEmpty());
        }
        var n = read(saved("text", "input", List.of("output")));
        n.methods.stream().filter(x -> x.name.equals("text")).findFirst().orElseThrow().visibleAnnotations
                .add(new AnnotationNode("Lorg/springframework/scheduling/annotation/Async;"));
        byte[] bytes = write(n);
        assertFalse(AddedJmsListenerAdapter.inspect(bytes, added(bytes)).refused().isEmpty());
    }

    static final class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final String request = "request-" + UUID.randomUUID(), reply = "reply-" + UUID.randomUUID();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final JmsListenerEndpointRegistry registry; final SpringJmsReloader reloader;
        Scope(boolean nativeControl) throws Exception {
            LookupCapture.store(Owner.class, MethodHandles.privateLookupIn(Owner.class, MethodHandles.lookup()));
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("reply-test",
                    Map.of("request.queue", request, "reply.queue", reply)));
            context.register(Config.class); context.registerBean("owner", Owner.class);
            if (nativeControl) context.registerBean("native", Native.class);
            context.refresh();
            context.getBean(DefaultJmsListenerContainerFactory.class).setErrorHandler(errors::add);
            registry = context.getBean(JmsListenerEndpointRegistry.class);
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlatformContext.class},
                    (p,m,a) -> m.getName().equals("getAllApplicationContexts") ? List.of(context) : null);
            reloader = new SpringJmsReloader(platform);
        }
        Owner owner() { return context.getBean(Owner.class); }
        boolean register(String method, String sendTo) throws Exception {
            byte[] bytes = saved(method, request, sendTo == null ? null : List.of(sendTo));
            return reloader.reloadJmsListeners(Owner.class, added(bytes), bytes);
        }
        Message exchange(String value, boolean override, String correlation) throws Exception {
            try (Connection connection = new ActiveMQConnectionFactory(brokerUrl).createConnection();
                 Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                connection.start();
                Destination destination = override ? session.createTemporaryQueue() : session.createQueue(reply);
                var consumer = session.createConsumer(destination);
                var input = session.createTextMessage(value);
                if (override) input.setJMSReplyTo(destination);
                if (correlation != null) input.setJMSCorrelationID(correlation);
                session.createProducer(session.createQueue(request)).send(input);
                Message result = consumer.receive(5000); assertNotNull(result, errors.toString());
                assertEquals(correlation == null ? input.getJMSMessageID() : correlation, result.getJMSCorrelationID());
                assertNull(consumer.receive(200), "duplicate reply");
                if (override) assertNull(session.createConsumer(session.createQueue(reply)).receive(200), "JMSReplyTo must override SendTo");
                return result;
            }
        }
        public void close() { context.close(); }
    }

    static byte[] saved(String method, String request, List<String> replies) throws Exception {
        ClassNode n;
        try (var in = Owner.class.getResourceAsStream("/" + Owner.class.getName().replace('.', '/') + ".class")) { n = read(in.readAllBytes()); }
        var m = n.methods.stream().filter(x -> x.name.equals(method)).findFirst().orElseThrow();
        var listener = new AnnotationNode(AddedJmsListenerAdapter.JMS);
        listener.values = new ArrayList<>(List.of("id", "added", "destination", request));
        m.visibleAnnotations = new ArrayList<>(List.of(listener));
        if (replies != null) {
            var send = new AnnotationNode("Lorg/springframework/messaging/handler/annotation/SendTo;");
            send.values = new ArrayList<>(List.of("value", replies)); m.visibleAnnotations.add(send);
        }
        return write(n);
    }
    static Set<String> added(byte[] bytes) {
        var keys = new HashSet<String>();
        for (var m : read(bytes).methods) if (m.visibleAnnotations != null && m.visibleAnnotations.stream().anyMatch(a -> a.desc.equals(AddedJmsListenerAdapter.JMS)))
            keys.add(m.name + ":" + m.desc);
        return keys;
    }
    static ClassNode read(byte[] bytes) { var n = new ClassNode(); new ClassReader(bytes).accept(n, 0); return n; }
    static byte[] write(ClassNode n) { var w = new ClassWriter(0); n.accept(w); return w.toByteArray(); }
}
