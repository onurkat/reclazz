/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

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
 * SAP Commerce's OCC converter contract, as 2211 implements it: one MOXy
 * context per response type in {@code jaxbContexts}, each mapping the types
 * reachable from it. The stubs carry exactly the members the eviction reads.
 */
class JaxbContextCachesTest {

    @TempDir Path dir;
    private URLClassLoader loader;
    private GenericApplicationContext context;

    @BeforeEach
    void build() throws Exception {
        ApplicationContextHolder.clear();
        stub("de.hybris.platform.webservicescommons.jaxb.Jaxb2HttpMessageConverter", """
                package de.hybris.platform.webservicescommons.jaxb;
                public class Jaxb2HttpMessageConverter extends org.springframework.http.converter.StringHttpMessageConverter {
                    private final java.util.concurrent.ConcurrentMap<Class, Object> jaxbContexts = new java.util.concurrent.ConcurrentHashMap<>();
                }
                """);
        stub("com.example.MoxyContext", """
                package com.example;
                public class MoxyContext {
                    private final Xml xml;
                    public MoxyContext(Class<?>... mapped) { xml = new Xml(mapped); }
                    public Xml getXMLContext() { return xml; }
                    public static class Xml {
                        private final java.util.List<Descriptor> descriptors = new java.util.ArrayList<>();
                        Xml(Class<?>... mapped) { for (Class<?> type : mapped) descriptors.add(new Descriptor(type)); }
                        public java.util.List<Descriptor> getDescriptors() { return descriptors; }
                    }
                    public static class Descriptor {
                        private final Class<?> type;
                        Descriptor(Class<?> type) { this.type = type; }
                        public Class<?> getJavaClass() { return type; }
                    }
                }
                """);
        stub("com.example.EntryWsDTO", "package com.example; public class EntryWsDTO {}");
        stub("com.example.ProductWsDTO", "package com.example; public class ProductWsDTO extends EntryWsDTO {}");
        stub("com.example.CartWsDTO", "package com.example; public class CartWsDTO {}");
        stub("com.example.TitleWsDTO", "package com.example; public class TitleWsDTO {}");
        compile();
        loader = new URLClassLoader(new java.net.URL[]{dir.resolve("classes").toUri().toURL()}, getClass().getClassLoader());
        context = new GenericApplicationContext();
        context.refresh();
        ApplicationContextHolder.register(context);
    }

    @AfterEach
    void close() throws Exception {
        ApplicationContextHolder.clear();
        if (context != null) context.close();
        if (loader != null) loader.close();
    }

    @Test
    void aContextMappingTheChangedTypeOrASubclassIsDroppedAndTheOthersKept() throws Exception {
        Object converter = converter();
        context.getBeanFactory().registerSingleton("jsonHttpMessageConverter", converter);
        Map<Class<?>, Object> cache = cache(converter);
        cache.put(type("CartWsDTO"), moxy("CartWsDTO", "ProductWsDTO"));   // reaches the change through a field
        cache.put(type("ProductWsDTO"), moxy("ProductWsDTO"));             // a subclass of the change
        cache.put(type("TitleWsDTO"), moxy("TitleWsDTO"));                 // unrelated

        assertEquals(2, JaxbContextCaches.evict(type("EntryWsDTO")));

        assertEquals(Set.of(type("TitleWsDTO")), cache.keySet());
    }

    @Test
    void aConverterOnlyAnMvcAdapterHoldsIsFoundToo() throws Exception {
        Object converter = converter();
        RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();
        @SuppressWarnings("unchecked")
        List<HttpMessageConverter<?>> converters = List.of((HttpMessageConverter<?>) converter);
        adapter.setMessageConverters(converters);
        context.getBeanFactory().registerSingleton("requestMappingHandlerAdapter", adapter);
        cache(converter).put(type("CartWsDTO"), moxy("CartWsDTO"));

        assertEquals(1, JaxbContextCaches.evict(type("CartWsDTO")));
        assertTrue(cache(converter).isEmpty());
    }

    @Test
    void aClassNoConverterMapsChangesNothing() throws Exception {
        Object converter = converter();
        context.getBeanFactory().registerSingleton("jsonHttpMessageConverter", converter);
        cache(converter).put(type("TitleWsDTO"), moxy("TitleWsDTO"));

        assertEquals(0, JaxbContextCaches.evict(type("CartWsDTO")));
        assertEquals(1, cache(converter).size());
    }

    private Object converter() throws Exception {
        return loader.loadClass("de.hybris.platform.webservicescommons.jaxb.Jaxb2HttpMessageConverter")
                .getConstructor().newInstance();
    }

    @SuppressWarnings("unchecked")
    private Map<Class<?>, Object> cache(Object converter) throws Exception {
        var field = converter.getClass().getDeclaredField("jaxbContexts");
        field.setAccessible(true);
        return (Map<Class<?>, Object>) field.get(converter);
    }

    private Object moxy(String... mapped) throws Exception {
        Class<?>[] types = new Class<?>[mapped.length];
        for (int i = 0; i < mapped.length; i++) types[i] = type(mapped[i]);
        return loader.loadClass("com.example.MoxyContext").getConstructor(Class[].class).newInstance((Object) types);
    }

    private Class<?> type(String simpleName) throws Exception {
        return loader.loadClass("com.example." + simpleName);
    }

    private final List<Path> sources = new ArrayList<>();

    private void stub(String className, String source) throws Exception {
        Path file = dir.resolve("src").resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        sources.add(file);
    }

    private void compile() {
        List<String> args = new ArrayList<>(List.of("-proc:none", "-d", dir.resolve("classes").toString(),
                "-cp", System.getProperty("java.class.path")));
        sources.forEach(source -> args.add(source.toString()));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)),
                "SDK contract stubs compile");
    }
}
