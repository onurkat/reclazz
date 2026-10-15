/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.util.Reflect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the existing 24 native delegation/expression/refusal assertions on Kafka 3.2.4 too. */
class ModernKafkaListenerGuardTest extends SpringKafkaReloaderTest {
    public static class CustomFactory extends ConcurrentKafkaListenerContainerFactory<Integer,String> { }

    @ParameterizedTest @ValueSource(strings={"filter","batch","customizer","custom"})
    void modernFactoryOptionsRemainRefused(String option) throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var factory = context.getBean(ConcurrentKafkaListenerContainerFactory.class);
            switch (option) {
                case "filter" -> factory.setRecordFilterStrategy(record -> false);
                case "batch" -> factory.setBatchListener(true);
                case "customizer" -> factory.setContainerCustomizer(container -> { });
                case "custom" -> factory = new CustomFactory();
                default -> fail(option);
            }
            Object selected = factory;
            var failure = assertThrows(InvocationTargetException.class, () -> checkFactory(selected));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains(switch(option) {
                case "filter" -> "recordFilterStrategy";
                case "batch" -> "batch Kafka factories";
                case "customizer" -> "containerCustomizer";
                default -> "standard ConcurrentKafkaListenerContainerFactory";
            }), failure.getCause().toString());
            assertTrue(context.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class)
                    .getListenerContainerIds().isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings={"3.2.4","3.2.5","unknown"})
    void missingRetryFieldIsAllowedOnlyForTheInspectedPackageVersion(String declaredVersion) throws Exception {
        URL[] urls = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(Path::of).map(p -> { try { return p.toUri().toURL(); }
                    catch (Exception failure) { throw new IllegalArgumentException(failure); } }).toArray(URL[]::new);
        // This changes package metadata only; it is not a claim of testing another Kafka release.
        try (var loader = new VersionLoader(urls, declaredVersion.equals("unknown") ? null : declaredVersion)) {
            Class<?> type = Class.forName("org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory", true, loader);
            assertEquals(declaredVersion.equals("unknown") ? null : declaredVersion, type.getPackage().getImplementationVersion());
            assertNull(Reflect.findField(type, "retryTemplate"));
            assertThrows(NoSuchFieldException.class, () -> type.getSuperclass().getDeclaredField("retryTemplate"));
            Object factory = type.getConstructor().newInstance();
            if (declaredVersion.equals("3.2.4")) checkFactory(factory);
            else {
                var failure = assertThrows(InvocationTargetException.class, () -> checkFactory(factory));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                assertTrue(failure.getCause().getMessage().contains("retryTemplate"), failure.getCause().toString());
            }
        }
    }

    public static final class FactoryContext {
        private final Object factory;
        FactoryContext(Object factory) { this.factory = factory; }
        public Object getBean(String name) { assertEquals("factory", name); return factory; }
    }
    public static final class ProcessorName { public final String defaultContainerFactoryBeanName = "factory"; }
    private static void checkFactory(Object factory) throws Exception {
        Class<?> infrastructure = Class.forName(SpringKafkaReloader.class.getName()+"$Infrastructure");
        var ctor = infrastructure.getDeclaredConstructor(Object.class,Object.class,Object.class); ctor.setAccessible(true);
        Object scope = ctor.newInstance(new FactoryContext(factory),new ProcessorName(),null);
        var m = new MethodNode(); m.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(AddedKafkaListenerAdapter.KAFKA)));
        var check = SpringKafkaReloader.class.getDeclaredMethod("requireFactory",infrastructure,MethodNode.class); check.setAccessible(true);
        check.invoke(null,scope,m);
    }
    private static final class VersionLoader extends URLClassLoader {
        VersionLoader(URL[] urls,String version) {
            super(urls,ClassLoader.getPlatformClassLoader());
            definePackage("org.springframework.kafka.config",null,null,null,null,version,null,null);
        }
    }
}
