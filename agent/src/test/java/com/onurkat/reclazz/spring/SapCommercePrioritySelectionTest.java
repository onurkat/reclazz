/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The OCC registry's priority contract, as SAP Commerce 2211 implements it:
 * each {@code @RequestMappingOverride} handler's priority is read with
 * {@code Config.getInt} when the registry is built, the highest one for a
 * mapping is registered and the others are dropped. {@code Config} needs a
 * tenant on the calling thread. The stubs model exactly that and nothing more;
 * the SDK probe runs the same selection against SAP's own class.
 */
class SapCommercePrioritySelectionTest {

    @TempDir Path dir;
    private URLClassLoader loader;
    private GenericApplicationContext parent;
    private GenericApplicationContext context;
    private RequestMappingHandlerMapping mapping;

    @BeforeEach
    void build() throws Exception {
        RestartLedger.clear();
        ApplicationContextHolder.clear();
        stub("de.hybris.platform.core.Registry", """
                package de.hybris.platform.core;
                public class Registry {
                    public static boolean active;
                    public static boolean hasCurrentTenant() { return active; }
                    public static void activateMasterTenant() { active = true; }
                }
                """);
        stub("de.hybris.platform.util.Config", """
                package de.hybris.platform.util;
                public class Config {
                    public static final java.util.Map<String, Integer> VALUES = new java.util.HashMap<>();
                    public static int getInt(String key, int fallback) {
                        if (!de.hybris.platform.core.Registry.active) throw new IllegalStateException("no tenant active");
                        return VALUES.getOrDefault(key, fallback);
                    }
                }
                """);
        stub("de.hybris.platform.commerceservices.request.mapping.annotation.RequestMappingOverride", """
                package de.hybris.platform.commerceservices.request.mapping.annotation;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
                public @interface RequestMappingOverride { String priorityProperty() default ""; }
                """);
        stub("de.hybris.platform.commercewebservices.core.request.mapping.handler.CommerceHandlerMapping", """
                package de.hybris.platform.commercewebservices.core.request.mapping.handler;
                import de.hybris.platform.commerceservices.request.mapping.annotation.RequestMappingOverride;
                import java.lang.reflect.Method;
                import org.springframework.core.annotation.AnnotationUtils;
                import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
                public class CommerceHandlerMapping
                        extends org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping {
                    private final java.util.Map<RequestMappingInfo, Integer> overriddenRequestMapping = new java.util.HashMap<>();
                    private boolean ancestors;
                    @Override public void setDetectHandlerMethodsInAncestorContexts(boolean value) {
                        ancestors = value;
                        super.setDetectHandlerMethodsInAncestorContexts(value);
                    }
                    @Override protected void initHandlerMethods() {
                        String[] names = ancestors
                                ? org.springframework.beans.factory.BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
                                        getApplicationContext(), Object.class)
                                : getApplicationContext().getBeanNamesForType(Object.class);
                        for (String name : names) {
                            Class<?> type = getApplicationContext().getType(name);
                            if (type == null || !isHandler(type)) continue;
                            for (Method method : type.getDeclaredMethods()) {
                                Integer priority = getMethodPriorityValue(method);
                                if (priority == null) continue;
                                RequestMappingInfo info = super.getMappingForMethod(method, type);
                                if (info != null) overriddenRequestMapping.merge(info, priority, Math::max);
                            }
                        }
                        super.initHandlerMethods();
                    }
                    @Override protected RequestMappingInfo getMappingForMethod(Method method, Class<?> type) {
                        RequestMappingInfo info = super.getMappingForMethod(method, type);
                        if (info == null) return null;
                        Integer highest = overriddenRequestMapping.get(info);
                        return highest == null || highest.equals(getMethodPriorityValue(method)) ? info : null;
                    }
                    protected Integer getMethodPriorityValue(Method method) {
                        RequestMappingOverride override = AnnotationUtils.findAnnotation(method, RequestMappingOverride.class);
                        if (override == null) return null;
                        String key = override.priorityProperty();
                        if (key.isEmpty()) key = "requestMappingOverride." + method.getDeclaringClass().getName()
                                + "." + method.getName() + ".priority";
                        return de.hybris.platform.util.Config.getInt(key, 0);
                    }
                }
                """);
        for (String side : List.of("A", "B")) {
            String key = side.equals("A") ? "priorityProperty = \"winnerA.priority\"" : "";
            stub("com.example.Winner" + side, """
                    package com.example;
                    @org.springframework.web.bind.annotation.RestController
                    public class Winner%s {
                        @org.springframework.web.bind.annotation.GetMapping("/winner")
                        @de.hybris.platform.commerceservices.request.mapping.annotation.RequestMappingOverride(%s)
                        public String winner() { return "%s"; }
                    }
                    """.formatted(side, key, side));
        }
        compile();
        loader = new URLClassLoader(new java.net.URL[]{dir.resolve("classes").toUri().toURL()}, getClass().getClassLoader());
        config().put("winnerA.priority", 10);
        config().put("requestMappingOverride.com.example.WinnerB.winner.priority", 20);

        // OCC's shape: the controllers in the web application's context, the
        // registry in the dispatcher's child context, searching its parents.
        // Built the way a web application starts: with its loader as the
        // thread's context loader, and no loader set on the contexts.
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            parent = new GenericApplicationContext();
            parent.registerBean("winnerA", loader.loadClass("com.example.WinnerA"));
            parent.registerBean("winnerB", loader.loadClass("com.example.WinnerB"));
            parent.refresh();
            context = new GenericApplicationContext(parent);
            context.refresh();
            mapping = (RequestMappingHandlerMapping) loader.loadClass(
                    "de.hybris.platform.commercewebservices.core.request.mapping.handler.CommerceHandlerMapping")
                    .getConstructor().newInstance();
            mapping.setDetectHandlerMethodsInAncestorContexts(true);
            tenant(true);
            mapping.setApplicationContext(context);
            mapping.afterPropertiesSet();
        } finally {
            thread.setContextClassLoader(previous);
        }
        context.getBeanFactory().registerSingleton("commerceHandlerMapping", mapping);
        ApplicationContextHolder.register(context);
        // The reload thread: a file-watcher thread, with no tenant of its own.
        tenant(false);
    }

    @AfterEach
    void close() throws Exception {
        ApplicationContextHolder.clear();
        RestartLedger.clear();
        if (context != null) context.close();
        if (parent != null) parent.close();
        if (loader != null) loader.close();
    }

    @Test
    void aClassReloadRebuildsTheSelectionOnAThreadWithoutATenant() throws Exception {
        assertEquals("WinnerB", winner());
        config().put("winnerA.priority", 30);

        assertTrue(SapCommerceMappings.rescan(mapping, loader.loadClass("com.example.WinnerA")),
                RestartLedger.digest().toString());

        assertEquals("WinnerA", winner());
        assertEquals(0, RestartLedger.size(), RestartLedger.digest().toString());
    }

    @Test
    void aChangedPriorityPropertySelectsTheHandlerItNowFavours() throws Exception {
        assertEquals("WinnerB", winner());
        config().put("winnerA.priority", 30);
        assertEquals("WinnerB", winner(), "the registry holds what it read at startup");

        assertEquals(1, SapCommerceMappings.reselect(Set.of("winnerA.priority")));
        assertEquals("WinnerA", winner());

        // A default key, derived from the class and method the way the registry derives it.
        config().put("requestMappingOverride.com.example.WinnerB.winner.priority", 50);
        assertEquals(1, SapCommerceMappings.reselect(Set.of("requestMappingOverride.com.example.WinnerB.winner.priority")));
        assertEquals("WinnerB", winner());
    }

    @Test
    void aPropertyNoHandlerReadsLeavesTheRegistryAlone() throws Exception {
        Map<?, ?> before = Map.copyOf(mapping.getHandlerMethods());
        assertEquals(0, SapCommerceMappings.reselect(Set.of("unrelated.key", "winnerA")));
        assertEquals(before, mapping.getHandlerMethods());
    }

    private String winner() throws Exception {
        var chain = mapping.getHandler(new MockHttpServletRequest("GET", "/winner"));
        return chain == null ? "none" : ((HandlerMethod) chain.getHandler()).getBeanType().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> config() throws Exception {
        return (Map<String, Integer>) loader.loadClass("de.hybris.platform.util.Config").getField("VALUES").get(null);
    }

    private void tenant(boolean active) throws Exception {
        loader.loadClass("de.hybris.platform.core.Registry").getField("active").setBoolean(null, active);
    }

    private final List<Path> sources = new ArrayList<>();

    private void stub(String className, String source) throws Exception {
        Path file = dir.resolve("src").resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        sources.add(file);
    }

    private void compile() {
        List<String> args = new ArrayList<>(List.of("-proc:none", "-parameters", "-d", dir.resolve("classes").toString(),
                "-cp", System.getProperty("java.class.path")));
        sources.forEach(source -> args.add(source.toString()));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)),
                "SDK contract stubs compile");
    }
}
