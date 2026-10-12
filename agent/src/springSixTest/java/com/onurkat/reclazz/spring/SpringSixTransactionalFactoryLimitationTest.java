/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListenerFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Characterizes an observed compatibility defect; passing this does not certify delivery. */
class SpringSixTransactionalFactoryLimitationTest {
    public static class Handler {
        int calls;
        public void receive(String message) { calls++; }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class NativeTransactions { }

    @AfterEach void clear() { RestartLedger.clear(); }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeTransactionFactoryIsRefusedAndAddedCallbacksStaySilent(boolean transactional) throws Exception {
        RestartLedger.clear();
        LookupCapture.store(Handler.class, MethodHandles.privateLookupIn(Handler.class, MethodHandles.lookup()));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(NativeTransactions.class);
            context.registerBean("handler", Handler.class);
            context.refresh();
            List<String> factories = context.getBeansOfType(EventListenerFactory.class).values().stream()
                    .map(f -> f.getClass().getName()).toList();
            assertTrue(factories.contains("org.springframework.transaction.annotation.RestrictedTransactionalEventListenerFactory"),
                    factories.toString());
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PlatformContext.class}, (p, m, a) ->
                            m.getName().equals("getAllApplicationContexts") ? List.of(context) : null);
            ClassNode metadata = new ClassNode();
            try (var input = Handler.class.getResourceAsStream("/" + Handler.class.getName().replace('.', '/') + ".class")) {
                assertNotNull(input);
                new ClassReader(input.readAllBytes()).accept(metadata, 0);
            }
            AnnotationNode annotation = new AnnotationNode(transactional
                    ? AddedEventListenerAdapter.TRANSACTIONAL : AddedEventListenerAdapter.EVENT);
            if (transactional) annotation.values = new ArrayList<>(List.of("fallbackExecution", true));
            metadata.methods.stream().filter(m -> m.name.equals("receive")).findFirst().orElseThrow()
                    .visibleAnnotations = new ArrayList<>(List.of(annotation));
            ClassWriter writer = new ClassWriter(0); metadata.accept(writer);
            assertFalse(new SpringEventReloader(platform).reloadEventListeners(Handler.class,
                    Set.of("receive:(Ljava/lang/String;)V"), writer.toByteArray()));
            assertTrue(RestartLedger.digest().stream().anyMatch(line -> line.contains("custom event listener factories")),
                    RestartLedger.digest().toString());
            context.publishEvent("outside");
            var data = new org.h2.jdbcx.JdbcDataSource();
            data.setURL("jdbc:h2:mem:factory_limit_" + java.util.UUID.randomUUID());
            new TransactionTemplate(new DataSourceTransactionManager(data)).execute(status -> {
                context.publishEvent("inside"); return null;
            });
            assertEquals(0, context.getBean(Handler.class).calls,
                    "measured defect: native transaction infrastructure prevents added delivery");
        }
    }
}
