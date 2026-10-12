/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.util.Reflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListenerFactory;
import org.springframework.context.event.EventListenerMethodProcessor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.RestrictedTransactionalEventListenerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Native Spring 6 transaction infrastructure, with real delivery and refusal controls. */
class SpringSixTransactionalFactoryReloadTest {
    public static class Handler {
        int calls;
        public void receive(String message) { calls++; }
    }
    public static class CustomRestrictedFactory extends RestrictedTransactionalEventListenerFactory { }
    public static class InvalidNativeListener {
        @TransactionalEventListener @Transactional
        public void receive(String message) { }
    }
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class NativeTransactions { }

    @AfterEach void clear() { RestartLedger.clear(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void nativeFactoryDeliversAddedCallbacksAcrossCommitRollbackAndRepeatedSaves(boolean transactional) throws Exception {
        try (var scope = new Scope()) {
            for (int save = 0; save < 3; save++) {
                assertTrue(scope.reload(bytes(transactional, null)));
                assertEquals(0, RestartLedger.size(), RestartLedger.digest().toString());
                int before = scope.handler().calls;
                scope.context.publishEvent("outside");
                assertEquals(before + 1, scope.handler().calls, "ordinary or explicit fallback delivery");
                scope.tx.execute(status -> {
                    scope.context.publishEvent("commit");
                    assertEquals(before + (transactional ? 1 : 2), scope.handler().calls,
                            "transactional callback must wait for AFTER_COMMIT");
                    return null;
                });
                assertEquals(before + 2, scope.handler().calls);
                scope.tx.execute(status -> {
                    scope.context.publishEvent("rollback"); status.setRollbackOnly(); return null;
                });
                assertEquals(before + (transactional ? 2 : 3), scope.handler().calls,
                        "rollback must not invoke an AFTER_COMMIT listener");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"custom", "duplicate", "mixed", "order"})
    void nativeFactoryRecognitionDoesNotBypassCustomDuplicateOrOrderGuards(String shape) throws Exception {
        try (var scope = new Scope()) {
            // Register first, then break the infrastructure. Refusal must retire the old adapter too.
            assertTrue(scope.reload(bytes(true, null)));
            var processor = scope.context.getBean(EventListenerMethodProcessor.class);
            @SuppressWarnings("unchecked")
            List<EventListenerFactory> factories = (List<EventListenerFactory>) Reflect.readField(processor, "eventListenerFactories");
            var replacement = new ArrayList<>(factories);
            switch (shape) {
                case "custom" -> replacement.add(new CustomRestrictedFactory());
                case "duplicate" -> replacement.add(factories.stream()
                        .filter(f -> f.getClass() == RestrictedTransactionalEventListenerFactory.class).findFirst().orElseThrow());
                case "mixed" -> replacement.add(new TransactionalEventListenerFactory());
                case "order" -> Collections.reverse(replacement);
                default -> throw new AssertionError(shape);
            }
            // The native processor holds a mutable ordered ArrayList on the measured graph.
            factories.clear(); factories.addAll(replacement);
            scope.reload(bytes(true, null));
            String reason = switch (shape) {
                case "custom" -> "custom event listener factories";
                case "order" -> "must precede";
                default -> "duplicate event listener factories";
            };
            assertTrue(RestartLedger.digest().stream().anyMatch(line -> line.contains(reason)),
                    RestartLedger.digest().toString());
            scope.context.publishEvent("outside");
            scope.tx.execute(status -> { scope.context.publishEvent("inside"); return null; });
            assertEquals(0, scope.handler().calls, "no previous or replacement callback may survive refusal");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"REQUIRED", "REQUIRES_NEW", "NOT_SUPPORTED"})
    void additionalTransactionAdviceOnAddedPhaseCallbacksRemainsRefused(String propagation) throws Exception {
        try (var scope = new Scope()) {
            assertFalse(scope.reload(bytes(true, propagation)));
            assertTrue(RestartLedger.digest().stream().anyMatch(line -> line.contains("additional advice")),
                    RestartLedger.digest().toString());
            scope.context.publishEvent("outside");
            scope.tx.execute(status -> { scope.context.publishEvent("inside"); return null; });
            assertEquals(0, scope.handler().calls);
        }
    }

    @Test void nativeRestrictedFactoryRejectsInvalidPropagationAsTheReferenceControl() throws Exception {
        var factory = new RestrictedTransactionalEventListenerFactory();
        var method = InvalidNativeListener.class.getMethod("receive", String.class);
        assertTrue(factory.supportsMethod(method));
        assertThrows(IllegalStateException.class, () -> factory.createApplicationListener("invalid", InvalidNativeListener.class, method));
    }

    private static final class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final TransactionTemplate tx;
        final SpringEventReloader reloader;
        Scope() throws Exception {
            RestartLedger.clear();
            LookupCapture.store(Handler.class, MethodHandles.privateLookupIn(Handler.class, MethodHandles.lookup()));
            context.register(NativeTransactions.class); context.registerBean("handler", Handler.class); context.refresh();
            assertTrue(context.getBeansOfType(EventListenerFactory.class).values().stream()
                    .anyMatch(f -> f.getClass() == RestrictedTransactionalEventListenerFactory.class));
            var data = new org.h2.jdbcx.JdbcDataSource();
            data.setURL("jdbc:h2:mem:factory_reload_" + java.util.UUID.randomUUID());
            tx = new TransactionTemplate(new DataSourceTransactionManager(data));
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PlatformContext.class}, (p, m, a) ->
                            m.getName().equals("getAllApplicationContexts") ? List.of(context) : null);
            reloader = new SpringEventReloader(platform);
        }
        Handler handler() { return context.getBean(Handler.class); }
        boolean reload(byte[] bytes) {
            return reloader.reloadEventListeners(Handler.class, Set.of("receive:(Ljava/lang/String;)V"), bytes);
        }
        @Override public void close() { context.close(); }
    }

    private static byte[] bytes(boolean transactional, String propagation) throws Exception {
        ClassNode metadata = new ClassNode();
        try (var input = Handler.class.getResourceAsStream("/" + Handler.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(input); new ClassReader(input.readAllBytes()).accept(metadata, 0);
        }
        var annotations = new ArrayList<AnnotationNode>();
        var listener = new AnnotationNode(transactional ? AddedEventListenerAdapter.TRANSACTIONAL : AddedEventListenerAdapter.EVENT);
        if (transactional) listener.values = new ArrayList<>(List.of("fallbackExecution", true));
        annotations.add(listener);
        if (propagation != null) {
            var advice = new AnnotationNode("Lorg/springframework/transaction/annotation/Transactional;");
            advice.values = new ArrayList<>(List.of("propagation", new String[]{
                    "Lorg/springframework/transaction/annotation/Propagation;", propagation}));
            annotations.add(advice);
        }
        metadata.methods.stream().filter(m -> m.name.equals("receive")).findFirst().orElseThrow().visibleAnnotations = annotations;
        ClassWriter writer = new ClassWriter(0); metadata.accept(writer); return writer.toByteArray();
    }
}
