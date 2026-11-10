/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import org.junit.jupiter.api.Test;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AwaitEventPatternsTest {
    @Test void actualPackagedEmitterOutputMatchesCompileAndBothReloadPatterns() throws Exception {
        List<List<String>> events = new ArrayList<>();
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(System.getProperty("reclazz.agent.jar")).toUri().toURL()})) {
            Class<?> reporter = loader.loadClass("com.onurkat.reclazz.ui.StatusReporter");
            Class<?> listener = loader.loadClass("com.onurkat.reclazz.ui.StatusReporter$StatusListener");
            Object capture = Proxy.newProxyInstance(loader, new Class<?>[]{listener}, (proxy, method, args) -> {
                if (method.getName().equals("onEvent")) events.add(List.of((String)args[0], (String)args[1]));
                if (method.getName().equals("equals")) return proxy == args[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                return null;
            });
            reporter.getMethod("addListener", listener).invoke(null, capture);
            try {
                reporter.getMethod("compile", String.class, long.class).invoke(null, "Service.java", 2L);
                reporter.getMethod("reload", String.class, long.class).invoke(null, AwaitReloadTest.OWNER, 3L);
                reporter.getMethod("structuralReload", String.class, long.class).invoke(null, AwaitReloadTest.OWNER, 4L);
            } finally { reporter.getMethod("removeListener", listener).invoke(null, capture); }
        }
        assertEquals(3, events.size());
        for (var emitted : events) {
            var observed = EventObservation.describe(new EventSubscription.Event(1, emitted.get(0), emitted.get(1), null));
            assertNotEquals("unknown", observed.get("type").getAsString(), observed.toString());
        }
        assertEquals(List.of("COMPILE", "Compiled Service.java (2ms)"), events.get(0));
        assertTrue(AwaitReload.compile(events.get(0).get(1), "Service.java"));
        for (var e : events.subList(1, 3)) assertTrue(AwaitReload.reload(e.get(0), e.get(1), AwaitReloadTest.OWNER), e.toString());
    }

    @Test void everyParsedFailurePatternIsHeldToItsRealProducer() throws Exception {
        Path source = Path.of("..", "agent", "src", "main", "java", "com", "onurkat", "reclazz");
        Map<String, List<String>> patterns = Map.of(
            "agent/ReclazzAgent.java", List.of("StatusReporter.error(\"Compilation failed:\")", "result.getErrors().forEach(err -> StatusReporter.error(\"  \" + err))", "StatusReporter.error(\"Hot-swap failed for \" + className", "StatusReporter.compile(file.getFileName().toString()"),
            "compiler/IncrementalCompiler.java", List.of("name + \":\" + d.getLineNumber() + \": \""),
            "spring/SpringBeanReloader.java", List.of("StatusReporter.error(\"Failed to refresh Spring bean for \" + className + \":"),
            "reload/StructuralReloader.java", List.of("StatusReporter.warn(className + \": superclass change not applied; restart required. \"", "\"Still extends \" + oldSuper + \" instead of \" + newSuper"));
        for (var producer : patterns.entrySet()) {
            String text = Files.readString(source.resolve(producer.getKey()));
            for (String literal : producer.getValue()) assertTrue(text.contains(literal), producer.getKey() + ": producer changed: " + literal);
        }
    }

    @Test void boundariesMatchOwnersLiterallyAndNeverAcceptInnerDisplayNames() {
        String owner = AwaitReloadTest.OWNER;
        for (String suffix : List.of("", " (1ms)", ": effects", " (+1 method, 1ms): effects")) {
            assertTrue(AwaitReload.reload("RELOAD", "Reloaded " + owner + suffix, owner));
            assertTrue(AwaitReload.reload("STRUCTURAL_RELOAD", "Structural reload: " + owner + suffix, owner));
        }
        for (String tail : List.of(owner + "Other (1ms)", owner + "$Inner (1ms)", owner + " (inner class) (1ms)"))
            assertFalse(AwaitReload.reload("RELOAD", "Reloaded " + tail, owner));
        assertFalse(AwaitReload.reload("INFO", "Reloaded " + owner, owner));
        assertFalse(AwaitReload.compile("Compiled Service.javaOther (1ms)", "Service.java"));
        assertFalse(AwaitReload.compile("Compiled Other.java (1ms)", "Service.java"));
    }
}
