/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.bootstrap.RequestGate;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.hybris.PropertyFileSnapshots;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.spring.SpringPropertyRebinder;
import com.onurkat.reclazz.spring.PropertyChangeOutcome;
import com.onurkat.reclazz.spring.PropertyChangeCheckTest.ServiceProperties;
import com.onurkat.reclazz.watcher.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PropertyChangeAcceptanceTest {
    @TempDir Path tmp;
    @Configuration(proxyBeanMethods = false) @EnableConfigurationProperties(ServiceProperties.class)
    static class Config { }

    @Test void aWaitingCandidateKeepsItsOwnLoggerAndBaseline() throws Exception {
        try (var context = context(); var logger = logger()) {
            Path file = tmp.resolve("application.properties");
            Files.writeString(file, content("10", "INFO"));
            ReclazzAgent.baselinePropertyFile(file);
            var read = new CountDownLatch(1);
            var platform = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlatformContext.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getAllApplicationContexts")) {
                            read.countDown(); return List.of(context);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            Object previousPlatform = swap("platformContext", platform);
            Object previousConfig = swap("agentConfig", AgentConfig.parse("reloadBoundary=request"));
            Object previousInstrumentation = swap("instrumentation", logger.instrumentation());
            try {
                RequestGate.global().installed();
                RequestGate.global().enter();
                var failure = new AtomicReference<Throwable>();
                Thread writer = new Thread(() -> {
                    try { handle(file); } catch (Throwable t) { failure.set(t); }
                });
                try {
                    Files.writeString(file, content("20", "DEBUG"));
                    writer.start();
                    assertTrue(read.await(10, TimeUnit.SECONDS));
                    Files.writeString(file, content("abc", "TRACE"));
                    assertEquals("INFO", logger.level());
                    assertEquals("10", snapshots().current(file).get("svc.timeout"));
                } finally {
                    RequestGate.global().exit();
                    writer.join(10_000);
                }
                assertFalse(writer.isAlive());
                assertNull(failure.get());
                assertEquals(20, context.getBean(ServiceProperties.class).getTimeout());
                assertEquals("20", snapshots().current(file).get("svc.timeout"));
                assertEquals("DEBUG", logger.level(), "A must not reread B's TRACE level");
                handle(file);
                assertEquals("20", snapshots().current(file).get("svc.timeout"));
                assertEquals("DEBUG", logger.level());
                assertEquals(2, snapshots().pending(file).changed().size());
                Files.writeString(file, content("30", "TRACE"));
                handle(file);
                assertEquals(30, context.getBean(ServiceProperties.class).getTimeout());
                assertEquals("TRACE", logger.level());
                assertTrue(snapshots().pending(file).changed().isEmpty());
            } finally {
                swap("platformContext", previousPlatform);
                swap("agentConfig", previousConfig);
                swap("instrumentation", previousInstrumentation);
            }
        }
    }

    @Test void interruptedBoundaryDoesNotAcceptOrApplyLoggerChanges() throws Exception {
        try (var context = context(); var logger = logger()) {
            Path file = tmp.resolve("application.properties");
            var snapshots = new PropertyFileSnapshots();
            Files.writeString(file, content("10", "INFO"));
            snapshots.baseline(file);
            Files.writeString(file, content("20", "DEBUG"));
            var candidate = snapshots.pending(file);
            RequestGate.global().installed();
            RequestGate.global().enter();
            var entered = new CountDownLatch(1);
            var outcome = new AtomicReference<PropertyChangeOutcome>();
            var failure = new AtomicReference<Throwable>();
            Thread writer = new Thread(() -> {
                try {
                    outcome.set(new SpringPropertyRebinder(List.of(context)).apply(candidate.changed(), work -> {
                        entered.countDown(); RequestReloadBoundary.run(work);
                    }, () -> {
                        snapshots.accept(candidate);
                        new com.onurkat.reclazz.reload.LoggingReloader(logger.instrumentation())
                                .applyLevels(Map.of("app", "DEBUG"));
                    }));
                } catch (Throwable t) { failure.set(t); }
            });
            try {
                writer.start();
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                writer.interrupt();
                writer.join(10_000);
                assertFalse(writer.isAlive());
                assertNull(failure.get());
                assertEquals(PropertyChangeOutcome.State.NOT_RUN, outcome.get().state());
                assertEquals("10", snapshots.current(file).get("svc.timeout"));
                assertEquals("10", context.getEnvironment().getProperty("svc.timeout"));
                assertEquals("INFO", logger.level());
                assertEquals(2, snapshots.pending(file).changed().size());
            } finally { RequestGate.global().exit(); }
        }
    }

    @Test void sapWithoutConfigStillAppliesItsLoggerLevels() throws Exception {
        try (var logger = logger()) {
            Path file = Files.createDirectories(tmp.resolve("hybris/config")).resolve("local.properties");
            Files.writeString(file, "logging.level.app=INFO\n");
            ReclazzAgent.baselinePropertyFile(file);
            var platform = new com.onurkat.reclazz.platform.HybrisPlatformContext(tmp, AgentConfig.parse("")) {
                @Override public Object getApplicationContext() { return null; }
            };
            Object previousPlatform = swap("platformContext", platform);
            Object previousInstrumentation = swap("instrumentation", logger.instrumentation());
            try {
                Files.writeString(file, "logging.level.app=DEBUG\n");
                handle(file);
                assertEquals("DEBUG", logger.level());
                assertTrue(snapshots().pending(file).changed().isEmpty());
            } finally {
                swap("platformContext", previousPlatform);
                swap("instrumentation", previousInstrumentation);
            }
        }
    }

    private static AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("original",
                Map.of("svc.url", "old", "svc.timeout", "10")));
        context.register(Config.class); context.refresh(); return context;
    }
    private static String content(String timeout, String level) {
        return "svc.url=old\nsvc.timeout=" + timeout + "\nlogging.level.app=" + level + "\n";
    }
    private static Object swap(String name, Object value) throws Exception {
        Field field = ReclazzAgent.class.getDeclaredField(name); field.setAccessible(true);
        Object previous = field.get(null); field.set(null, value); return previous;
    }
    private static PropertyFileSnapshots snapshots() throws Exception {
        Field field = ReclazzAgent.class.getDeclaredField("propertySnapshots"); field.setAccessible(true);
        return (PropertyFileSnapshots) field.get(null);
    }
    private static void handle(Path file) throws Exception {
        Method method = ReclazzAgent.class.getDeclaredMethod("handlePropertiesChange", ChangeEvent.class);
        method.setAccessible(true);
        method.invoke(null, new ChangeEvent(file, ChangeEvent.Type.MODIFIED, "app", "classes"));
    }

    /** A private logging fixture, discoverable through the same loaded-class path as production. */
    private LoggerFixture logger() throws Exception {
        Path sources = Files.createDirectories(tmp.resolve("logger"));
        Path level = sources.resolve("Level.java"), config = sources.resolve("Configurator.java");
        Files.writeString(level, """
                package org.apache.logging.log4j;
                public enum Level { INFO, DEBUG, TRACE;
                    public static Level toLevel(String value, Level fallback) { return valueOf(value); }
                }
                """);
        Files.writeString(config, """
                package org.apache.logging.log4j.core.config;
                public class Configurator {
                    public static String level = "INFO";
                    public static void setLevel(String logger, org.apache.logging.log4j.Level value) { level = value.name(); }
                }
                """);
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", sources.toString(), level.toString(), config.toString()));
        var loader = new java.net.URLClassLoader(new java.net.URL[]{sources.toUri().toURL()}, getClass().getClassLoader());
        return new LoggerFixture(loader, loader.loadClass("org.apache.logging.log4j.Level"),
                loader.loadClass("org.apache.logging.log4j.core.config.Configurator"));
    }
    private record LoggerFixture(java.net.URLClassLoader loader, Class<?> levelType,
                                 Class<?> config) implements AutoCloseable {
        java.lang.instrument.Instrumentation instrumentation() {
            return (java.lang.instrument.Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{java.lang.instrument.Instrumentation.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getAllLoadedClasses")) return new Class<?>[]{levelType, config};
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
        String level() throws Exception { return (String) config.getField("level").get(null); }
        public void close() throws Exception { loader.close(); }
    }
}
