/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodNode;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.retry.support.RetryTemplate;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class KafkaLegacyFactoryGuardTest {
    @Test void configuredLegacyRetryRemainsRefused() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(SpringKafkaReloaderTest.Config.class)) {
            var factory = context.getBean(ConcurrentKafkaListenerContainerFactory.class);
            assertEquals("2.9.13", factory.getClass().getPackage().getImplementationVersion());
            factory.setRetryTemplate(new RetryTemplate());
            Class<?> infrastructure = Class.forName(SpringKafkaReloader.class.getName()+"$Infrastructure");
            var ctor = infrastructure.getDeclaredConstructor(Object.class,Object.class,Object.class); ctor.setAccessible(true);
            Object scope = ctor.newInstance(context,new ProcessorName(),null);
            var method = new MethodNode(); method.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(AddedKafkaListenerAdapter.KAFKA)));
            var check = SpringKafkaReloader.class.getDeclaredMethod("requireFactory",infrastructure,MethodNode.class); check.setAccessible(true);
            var failure = assertThrows(InvocationTargetException.class, () -> check.invoke(null,scope,method));
            assertInstanceOf(IllegalStateException.class,failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("retryTemplate"),failure.getCause().toString());
            assertTrue(context.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class).getListenerContainerIds().isEmpty());
        }
    }
    public static final class ProcessorName {
        public final String defaultContainerFactoryBeanName="kafkaListenerContainerFactory";
    }
}
