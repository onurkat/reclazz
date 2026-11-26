/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import com.onurkat.reclazz.ui.RestartLedger;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.lang.reflect.Proxy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JacksonCustomizerReloadTest {
    public static class Invoice { public String oldName = "marker"; @Override public String toString() { return "invoice"; } }
    static class Direct { final ObjectMapper mapper; Direct(ObjectMapper mapper) { this.mapper = mapper; } }
    private static final String AUTO = "org.springframework.boot.autoconfigure.";

    private static AnnotationConfigApplicationContext application() throws Exception {
        // The same test sources also compile against the separate Boot 4 matrix.
        assumeTrue(org.springframework.boot.SpringBootVersion.getVersion().startsWith("3."));
        var context = new AnnotationConfigApplicationContext();
        context.setBeanNameGenerator(org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator.INSTANCE);
        context.register(Class.forName(AUTO+"jackson.JacksonAutoConfiguration"),
                Class.forName(AUTO+"http.HttpMessageConvertersAutoConfiguration"));
        context.refresh();
        var factory = context.getBeanFactory();
        var adapter = new RequestMappingHandlerAdapter();
        var aggregate = context.getBean(Class.forName(AUTO+"http.HttpMessageConverters"));
        @SuppressWarnings("unchecked")
        var converters = (List<org.springframework.http.converter.HttpMessageConverter<?>>)
                aggregate.getClass().getMethod("getConverters").invoke(aggregate);
        adapter.setMessageConverters(new ArrayList<>(converters));
        factory.registerSingleton("adapter", adapter);
        factory.registerSingleton("direct", new Direct(context.getBean(ObjectMapper.class)));
        return context;
    }

    private static void install(ConfigurableApplicationContext context, Consumer<Jackson2ObjectMapperBuilder> action)
            throws Exception {
        Class<?> spi = Class.forName(AUTO+"jackson.Jackson2ObjectMapperBuilderCustomizer");
        Object customizer = Proxy.newProxyInstance(spi.getClassLoader(), new Class<?>[]{spi}, (self, method, args) -> {
            switch (method.getName()) {
                case "customize" -> action.accept((Jackson2ObjectMapperBuilder)args[0]);
                case "hashCode" -> { return System.identityHashCode(self); }
                case "equals" -> { return self == args[0]; }
                case "toString" -> { return "invoice customizer"; }
                default -> throw new UnsupportedOperationException(method.getName());
            }
            return null;
        });
        context.getBeanFactory().registerSingleton("customizer", customizer);
    }

    private static void assertPublished(AnnotationConfigApplicationContext ctx, String json) throws Exception {
        JacksonMapperRefresh.rebuild(ctx.getBeanFactory());
        var mapper = ctx.getBean(ObjectMapper.class);
        assertSame(mapper,ctx.getBean(Direct.class).mapper);
        assertSame(mapper,ctx.getBean(MappingJackson2HttpMessageConverter.class).getObjectMapper());
        assertEquals(json,mapper.writeValueAsString(new Invoice()));
    }

    @Test void addChangeRemoveAndTypeSpecificSerializer() throws Exception {
        try (var ctx=application()) {
            install(ctx,b->b.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
            assertPublished(ctx,"{\"old_name\":\"marker\"}");
            ctx.getDefaultListableBeanFactory().destroySingleton("customizer");
            install(ctx,b->b.modulesToInstall(new SimpleModule().addSerializer(Invoice.class,new ToStringSerializer())));
            assertPublished(ctx,"\"invoice\"");
            ctx.getDefaultListableBeanFactory().destroySingleton("customizer");
            assertPublished(ctx,"{\"oldName\":\"marker\"}");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class NativeMvc implements WebMvcConfigurer {
        private final ApplicationContext context;
        NativeMvc(ApplicationContext context) { this.context = context; }

        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            try {
                var boot = context.getBean(Class.forName(AUTO + "http.HttpMessageConverters"));
                @SuppressWarnings("unchecked")
                var nativeList = (List<HttpMessageConverter<?>>) boot.getClass().getMethod("getConverters").invoke(boot);
                converters.addAll(nativeList);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }

        @Bean InvoiceEndpoint invoiceEndpoint() { return new InvoiceEndpoint(); }
        @Bean Direct direct(ObjectMapper mapper) { return new Direct(mapper); }
    }

    @RestController
    static class InvoiceEndpoint {
        @GetMapping("/invoice") public Invoice invoice() { return new Invoice(); }
    }

    static class AppConverter extends MappingJackson2HttpMessageConverter {
        int mapperReads;
        AppConverter() { super(new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE)); }
        @Override public ObjectMapper getObjectMapper() {
            mapperReads++;
            return super.getObjectMapper();
        }
    }

    abstract static class CustomizeConverters implements WebMvcConfigurer {
        final AppConverter foreign = new AppConverter();
    }
    static class Prepend extends CustomizeConverters {
        @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.add(0, foreign);
        }
    }
    static class Replace extends CustomizeConverters {
        @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
            for (int i = 0; i < converters.size(); i++) {
                if (converters.get(i) instanceof MappingJackson2HttpMessageConverter) {
                    converters.set(i, foreign);
                    return;
                }
            }
            throw new AssertionError("missing primary JSON converter");
        }
    }

    @Test void customizerSaveRefusesPrependedConverter() throws Exception { assertRefused(Prepend.class); }
    @Test void customizerSaveRefusesReplacedConverter() throws Exception { assertRefused(Replace.class); }

    private static void assertRefused(Class<? extends CustomizeConverters> configurer) throws Exception {
        assumeTrue(org.springframework.boot.SpringBootVersion.getVersion().startsWith("3."));
        String subject = "Jackson configuration";
        String reason = "Jackson configuration changed; restart to apply it";
        RestartLedger.resolve(subject, reason);
        try (var ctx = new AnnotationConfigWebApplicationContext()) {
            ctx.setServletContext(new MockServletContext());
            ctx.setBeanNameGenerator(org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator.INSTANCE);
            ctx.register(Class.forName(AUTO+"jackson.JacksonAutoConfiguration"),
                    Class.forName(AUTO+"http.HttpMessageConvertersAutoConfiguration"), NativeMvc.class, configurer);
            ctx.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
            var request = get("/invoice").accept("application/json");
            var baseline = mvc.perform(request).andReturn().getResponse();
            assertEquals(200, baseline.getStatus());
            assertEquals("{\"OldName\":\"marker\"}", baseline.getContentAsString());
            var original = ctx.getBean(ObjectMapper.class);
            var foreign = ctx.getBean(configurer).foreign;
            install(ctx, builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
            int readsBeforeRefresh = foreign.mapperReads;
            assertFalse(JacksonMapperRefresh.refresh(ctx.getBeanFactory()));
            assertEquals(List.of(reason), RestartLedger.reasonsFor(subject));
            assertEquals(readsBeforeRefresh, foreign.mapperReads, "reload must refuse before invoking the app converter");
            assertSame(original, ctx.getBean(ObjectMapper.class));
            assertSame(original, ctx.getBean(Direct.class).mapper);
            var after = mvc.perform(get("/invoice").accept("application/json")).andReturn().getResponse();
            assertEquals(200, after.getStatus());
            assertEquals(baseline.getContentAsString(), after.getContentAsString());
        } finally { RestartLedger.resolve(subject, reason); }
    }
}
