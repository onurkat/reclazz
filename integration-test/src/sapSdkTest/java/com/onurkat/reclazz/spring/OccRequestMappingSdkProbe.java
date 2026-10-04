/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.util.Reflect;
import de.hybris.platform.commercewebservices.core.request.mapping.handler.CommerceHandlerMapping;
import de.hybris.platform.commerceservices.request.mapping.annotation.RequestMappingOverride;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/** Genuine SDK mapping; synthetic configuration boundary, no tenant or application startup. */
public class OccRequestMappingSdkProbe {
    private static Instrumentation instrumentation;
    private static int assertions;
    private static Path variants;
    private static Class<?> high, low, base, other;

    public static void premain(String arguments, Instrumentation value) { instrumentation = value; }

    public static class Mapping extends CommerceHandlerMapping {
        boolean failAfterInit;
        CountDownLatch entered, release;
        public Mapping() { super("v2"); }
        @Override protected Integer getMethodPriorityValue(Method method) {
            // The actual priority selection/annotation classes remain SAP's. Only
            // property lookup is replaced: no claim of SAP property-file reload.
            var annotation = method.getAnnotation(RequestMappingOverride.class);
            return annotation == null ? null : Integer.valueOf(annotation.priorityProperty());
        }
        @Override protected void initHandlerMethods() {
            if (entered != null) {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("rebuild not released"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }
            super.initHandlerMethods();
            if (failAfterInit) { failAfterInit = false; throw new IllegalStateException("synthetic registration failure"); }
        }
    }
    public static class MissingLock extends Mapping {
        @SuppressWarnings("unused") private final Object mappingRegistry = new Object();
    }
    public static class InspectionFailure extends Mapping {
        boolean fail;
        @Override public Map<org.springframework.web.servlet.mvc.method.RequestMappingInfo, HandlerMethod> getHandlerMethods() {
            if (fail) throw new IllegalStateException("synthetic inspection failure");
            return super.getHandlerMethods();
        }
    }
    private static void require(boolean value, String reason) {
        assertions++;
        if (!value) throw new AssertionError(reason);
    }
    private static void redefine(Class<?> type, String variant) throws Exception {
        instrumentation.redefineClasses(new ClassDefinition(type,
                Files.readAllBytes(variants.resolve(variant + "/com/example/" + type.getSimpleName() + ".class"))));
        org.springframework.util.ReflectionUtils.clearCache();
        org.springframework.core.annotation.AnnotationUtils.clearCache();
    }
    private static GenericApplicationContext context() {
        var context = new GenericApplicationContext();
        context.registerBean("base", base); context.registerBean("low", low);
        context.registerBean("high", high); context.registerBean("other", other);
        context.refresh();
        return context;
    }
    private static <T extends RequestMappingHandlerMapping> T initialize(T mapping, GenericApplicationContext context) {
        mapping.setApplicationContext(context); mapping.afterPropertiesSet(); return mapping;
    }
    private static String selected(RequestMappingHandlerMapping mapping, String path) throws Exception {
        // Real Spring request lookup, rather than trusting registry entry counts.
        var chain = mapping.getHandler(new MockHttpServletRequest("GET", path));
        return chain == null ? "none" : ((HandlerMethod) chain.getHandler()).getBeanType().getSimpleName();
    }
    private static void winner(RequestMappingHandlerMapping mapping, String path, String expected) throws Exception {
        require(selected(mapping, path).equals(expected), path + " expected " + expected + " got " + selected(mapping, path));
    }
    private static PlatformContext platform(List<GenericApplicationContext> contexts) {
        return (PlatformContext) Proxy.newProxyInstance(OccRequestMappingSdkProbe.class.getClassLoader(),
                new Class<?>[]{PlatformContext.class}, (p, m, a) -> switch (m.getName()) {
                    case "getAllApplicationContexts" -> contexts;
                    case "getApplicationContext" -> contexts.get(0);
                    default -> null;
                });
    }
    private static SpringMvcReloader reloader(List<GenericApplicationContext> contexts) {
        return new SpringMvcReloader(platform(contexts));
    }
    private static void unchanged(Map<?, ?> before, Mapping mapping) {
        require(before.equals(mapping.getHandlerMethods()), "previous mappings changed on refusal/rollback");
    }
    public static void main(String[] args) throws Exception {
        variants = Path.of(args[0]); high = Class.forName("com.example.High");
        low = Class.forName("com.example.Low"); base = Class.forName("com.example.Base");
        other = Class.forName("com.example.Other");
        for (String scenario : List.of("unchanged", "removed", "moved", "priority", "added", "noOverride", "apiVersion", "baseFallback")) {
            redefine(low, "initial"); redefine(high, scenario.equals("added") ? "other" : "initial");
            try (var context = context()) {
                Mapping mapping = initialize(new Mapping(), context);
                context.getBeanFactory().registerSingleton("mapping", mapping);
                winner(mapping, "/item", scenario.equals("added") ? "Low" : "High");
                String variant = scenario.equals("unchanged") || scenario.equals("added") ? "initial"
                        : scenario.equals("baseFallback") ? "removed" : scenario;
                redefine(high, variant);
                if (scenario.equals("baseFallback")) redefine(low, "removed");
                for (int repeat = 0; repeat < 3; repeat++) {
                    RestartLedger.clear();
                    require(reloader(List.of(context)).reloadMappings(high), "rebuild refused: " + scenario);
                    var fresh = initialize(new Mapping(), context);
                    winner(mapping, "/item", selected(fresh, "/item"));
                    winner(mapping, "/moved", selected(fresh, "/moved"));
                    winner(mapping, "/other", "Other");
                    require(mapping.getOverriddenRequestMapping().equals(fresh.getOverriddenRequestMapping()), "stale priority table");
                    require(RestartLedger.size() == 0, "unexpected restart: " + scenario);
                }
            }
        }
        redefine(low, "initial"); redefine(high, "initial");
        try (var context = context()) {
            Mapping mapping = initialize(new Mapping(), context);
            var before = Map.copyOf(mapping.getHandlerMethods());
            var priorities = Map.copyOf(mapping.getOverriddenRequestMapping());
            for (boolean ambiguous : List.of(true, false)) {
                redefine(high, ambiguous ? "tie" : "moved");
                mapping.failAfterInit = !ambiguous;
                require(!new SpringMvcReloader(null).rescan(mapping, "high", high), "failed rebuild reported success");
                unchanged(before, mapping);
                require(priorities.equals(mapping.getOverriddenRequestMapping()), "priority rollback failed");
                winner(mapping, "/item", "High"); winner(mapping, "/other", "Other");
                require(RestartLedger.digest().stream().anyMatch(s -> s.contains("SAP OCC mappings need a restart")), "missing failure diagnostic");
                redefine(high, "initial");
                require(new SpringMvcReloader(null).rescan(mapping, "high", high), "recovery failed");
                require(RestartLedger.size() == 0, "recovered registry concern not cleared");
            }
        }
        try (var context = context()) {
            for (Mapping mapping : List.of(initialize(new MissingLock(), context), initialize(new InspectionFailure(), context))) {
                var before = Map.copyOf(mapping.getHandlerMethods());
                if (mapping instanceof InspectionFailure broken) broken.fail = true;
                require(!new SpringMvcReloader(null).rescan(mapping, "high", high), "unsafe inspection/lock accepted");
                if (mapping instanceof InspectionFailure broken) broken.fail = false;
                unchanged(before, mapping);
            }
        }
        // An already registered generated adapter must never disappear in a bean-only rebuild.
        try (var context = context()) {
            Mapping mapping = initialize(new Mapping(), context);
            Object adapter;
            try { adapter = AddedEndpointAdapter.create(high, context.getBean("high"),
                    Files.readAllBytes(variants.resolve("addedMethod/com/example/High.class")), Set.of("added:()Ljava/lang/String;"), 900); }
            catch (Throwable failure) { throw new RuntimeException(failure); }
            var detect = Reflect.findMethod(mapping.getClass(), "detectHandlerMethods", Object.class);
            detect.setAccessible(true); detect.invoke(mapping, adapter);
            var before = Map.copyOf(mapping.getHandlerMethods());
            require(!new SpringMvcReloader(null).rescan(mapping, "high", high), "existing adapter must prevent rebuild");
            unchanged(before, mapping); winner(mapping, "/added", "High$$ReclazzEndpoints$v900");
        }
        // Refuse before the first rescan; remember suppressed/unregistered attempts too.
        try (var context = context(); var healthy = context()) {
            Mapping mapping = initialize(new Mapping(), context);
            Mapping healthyMapping = initialize(new Mapping(), healthy);
            context.getBeanFactory().registerSingleton("mapping", mapping);
            healthy.getBeanFactory().registerSingleton("mapping", healthyMapping);
            var before = Map.copyOf(mapping.getHandlerMethods());
            RestartLedger.clear();
            require(!reloader(List.of(context)).reloadMappings(high, Set.of("added:()Ljava/lang/String;")), "new synthetic handler accepted");
            unchanged(before, mapping);
            require(!reloader(List.of(context)).registerAddedEndpoints(high, Set.of("added:()Ljava/lang/String;"),
                    Files.readAllBytes(variants.resolve("addedMethod/com/example/High.class"))), "adapter installation accepted");
            require(!reloader(List.of(context, healthy)).reloadMappings(high), "partial context success hides SAP refusal");
            unchanged(before, mapping); winner(healthyMapping, "/item", "High");
            require(RestartLedger.size() == 1, "healthy context cleared another registry concern");
        }
        // Exercise the production coordinator: it must pass added signatures BEFORE
        // rescanning, otherwise the visible path change has already removed /item.
        try (var context = context()) {
            Mapping mapping = initialize(new Mapping(), context);
            context.getBeanFactory().registerSingleton("mapping", mapping);
            var before = Map.copyOf(mapping.getHandlerMethods());
            RestartLedger.clear();
            redefine(high, "moved");
            new SpringReloadOrchestrator(platform(List.of(context))).onClassReloaded(
                    high.getName(), high, true, true, true, Set.of("added:()Ljava/lang/String;"),
                    Files.readAllBytes(variants.resolve("addedMethod/com/example/High.class")));
            unchanged(before, mapping);
            winner(mapping, "/item", "High"); winner(mapping, "/moved", "none");
            winner(mapping, "/added", "none");
            require(RestartLedger.digest().stream().anyMatch(s -> s.contains("SAP OCC mappings need a restart")),
                    "coordinator did not report the added-endpoint boundary");
        }
        redefine(high, "initial");
        // Request lookups cannot observe the unregistered interval during the complete rebuild.
        try (var context = context()) {
            Mapping mapping = initialize(new Mapping(), context);
            mapping.entered = new CountDownLatch(1); mapping.release = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> rebuild = pool.submit(() -> new SpringMvcReloader(null).rescan(mapping, "high", high));
                require(mapping.entered.await(5, TimeUnit.SECONDS), "rebuild did not start");
                CountDownLatch reading = new CountDownLatch(1);
                Future<String> read = pool.submit(() -> { reading.countDown(); return selected(mapping, "/item"); });
                require(reading.await(5, TimeUnit.SECONDS), "reader did not start");
                boolean waited = false;
                try { read.get(100, TimeUnit.MILLISECONDS); } catch (TimeoutException expected) { waited = true; }
                require(waited, "request observed partial mapping registry");
                mapping.release.countDown();
                require(rebuild.get(5, TimeUnit.SECONDS), "concurrent rebuild failed");
                require(read.get(5, TimeUnit.SECONDS).equals("High"), "request lost handler");
            } finally { mapping.release.countDown(); pool.shutdownNow(); }
        }
        // The ordinary Spring implementation keeps the existing single-controller path.
        try (var context = new GenericApplicationContext()) {
            context.registerBean("other", other); context.refresh();
            var mapping = initialize(new RequestMappingHandlerMapping(), context);
            require(new SpringMvcReloader(null).rescan(mapping, "other", other), "generic MVC rescan failed");
            winner(mapping, "/other", "Other");
        }
        System.out.println("PASS: " + assertions + " OCC request mapping assertions; Spring " + org.springframework.core.SpringVersion.getVersion());
    }
}
