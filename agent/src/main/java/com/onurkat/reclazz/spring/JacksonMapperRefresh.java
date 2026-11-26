/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.ReloadEffects;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.util.Reflect;
import java.lang.reflect.Field;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

import static com.onurkat.reclazz.spring.SpringProxyRecreation.call;

/** Rebuilds the Boot mapper through its prototype builder and verifies the serving references. */
final class JacksonMapperRefresh {
    static final String RESTART = "Jackson configuration changed; restart to apply it";
    private static final String JACKSON = "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration$";
    private static final String HTTP = "org.springframework.boot.autoconfigure.http.";
    private static final String JSON = "org.springframework.http.converter.json.";
    private static final String MAPPER = "com.fasterxml.jackson.databind.ObjectMapper";
    private static final String CUSTOMIZER = "org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer";

    // A DispatcherServlet can keep the original Boot converter after Spring recreates its bean.
    private static final Map<Object,WeakReference<Object>> servingByFactory = Collections.synchronizedMap(new WeakHashMap<>());

    static boolean isCustomizer(Class<?> type) {
        if (type.getName().equals(CUSTOMIZER)) return true;
        for (Class<?> parent : type.getInterfaces()) if (isCustomizer(parent)) return true;
        return type.getSuperclass() != null && isCustomizer(type.getSuperclass());
    }

    static boolean isBootJackson(Object factory) {
        if (factory == null) return false;
        try { Class.forName(CUSTOMIZER, false, factory.getClass().getClassLoader()); return true; }
        catch (ClassNotFoundException absent) { return false; }
    }

    /** A native @Bean edit can recreate the mapper and converter while DispatcherServlet still serves the old pair. */
    static NativeCapture captureNative(Class<?> configuration, byte[] raw, java.util.function.Supplier<? extends Iterable<?>> contexts) throws Exception {
        Publication before = null;
        for (Object context : contexts.get()) {
            Object factory = SpringBeans.getBeanFactory(context);
            if (!isBootJackson(factory)) continue;
            String[] configs = names(factory, configuration);
            if (configs.length == 0) continue;
            Class<?> spi = type(factory, CUSTOMIZER);
            boolean nativeOwner = false;
            for (String name : names(factory, spi)) {
                if (!Boolean.TRUE.equals(call(factory, "containsBeanDefinition", name))) continue;
                Object definition = call(factory, "getBeanDefinition", name);
                if (!java.util.Arrays.asList(configs).contains(call(definition, "getFactoryBeanName"))) continue;
                String methodName = (String)call(definition, "getFactoryMethodName");
                if (methodName == null) continue;
                // A removed native @Bean can still have a registered definition and dependents.
                // Stop before Spring destroys these singletons or calls the tombstoned method.
                require(raw != null);
                ClassNode saved = new ClassNode();
                new ClassReader(raw).accept(saved, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                require(saved.methods.stream().anyMatch(m -> m.name.equals(methodName)
                        && m.visibleAnnotations != null
                        && m.visibleAnnotations.stream().anyMatch(a -> a.desc.equals("Lorg/springframework/context/annotation/Bean;"))));
                nativeOwner = true;
            }
            if (!nativeOwner) continue;
            require(configs.length == 1 && before == null);
            before = inspect(factory);
        }
        return before == null ? null : new NativeCapture(before);
    }

    record NativeCapture(Publication before) {
        void finish() {
            try {
                Publication after = inspect(before.factory, before);
                // The configuration refresh can be held by another Spring owner. A new builder
                // using its old customizer singletons cannot establish cold equivalence.
                require(after.old != before.old);
                Object candidate = after.old;
                List<Reference> targets = before.references();
                targets.addAll(after.references());
                switchReferences(targets, before.old, candidate);
                servingByFactory.put(before.factory, new WeakReference<>(after.serving));
                ReloadEffects.note("Jackson configuration applied to the MVC ObjectMapper");
            } catch (Exception | LinkageError failure) { restart(); }
        }
    }

    static void restart() {
        RestartLedger.note("Jackson configuration", RESTART);
        StatusReporter.warn(RESTART);
    }

    static boolean refresh(Object factory) {
        try {
            rebuild(factory);
            ReloadEffects.note("Jackson configuration applied to the MVC ObjectMapper");
            return true;
        } catch (Exception | LinkageError failure) {
            restart();
            return false;
        }
    }

    static void rebuild(Object factory) throws Exception {
        Publication publication = inspect(factory);
        publication.publish(publication.buildMapper());
    }

    private static Class<?> type(Object factory, String name) throws ClassNotFoundException {
        return Class.forName(name, false, factory.getClass().getClassLoader());
    }

    private static String[] names(Object factory, Class<?> type) throws Exception {
        return (String[])factory.getClass().getMethod("getBeanNamesForType", Class.class, boolean.class, boolean.class)
                .invoke(factory, type, true, false);
    }

    private static String unique(Object factory, Class<?> type) throws Exception {
        var names = names(factory, type);
        require(names.length == 1);
        return names[0];
    }

    private static void owner(Object factory, String name, String config, String method) throws Exception {
        Object definition = call(factory, "getBeanDefinition", name);
        require(config.equals(call(definition, "getFactoryBeanName")) && method.equals(call(definition, "getFactoryMethodName")));
    }

    interface Reference {
        Object read() throws Exception;
        void replace(Object old, Object candidate) throws Exception;
    }

    record Slot(Object bean, Field field) implements Reference {
        public Object read() throws IllegalAccessException { return field.get(bean); }
        public void replace(Object old, Object candidate) throws IllegalAccessException {
            require(read() == old);
            field.set(bean, candidate);
        }
    }

    private record Converter(Object instance) implements Reference {
        public Object read() throws Exception { return call(instance, "getObjectMapper"); }
        public void replace(Object old, Object candidate) throws Exception {
            require(read() == old);
            call(instance, "setObjectMapper", candidate);
        }
    }

    private record Singleton(Object factory, String name, ConcurrentMap<String,Object> instances) implements Reference {
        public Object read() throws Exception { return call(factory, "getBean", name); }
        public void replace(Object old, Object candidate) { require(instances.replace(name, old, candidate)); }
    }

    static void switchReferences(List<Reference> targets, Object old, Object candidate) throws Exception {
        require(candidate != null && candidate != old && candidate.getClass() == old.getClass());
        for (Reference target : targets) {
            Object value = target.read();
            require(value == old || value == candidate);
        }
        List<Reference> attempted = new ArrayList<>();
        try {
            for (Reference target : targets) {
                if (target.read() == candidate) continue;
                attempted.add(target); // A setter can throw after assigning.
                target.replace(old, candidate);
            }
            for (Reference target : targets) require(target.read() == candidate);
        } catch (Exception | LinkageError failure) {
            for (int i = attempted.size()-1; i >= 0; i--) {
                Reference target = attempted.get(i);
                try { if (target.read() == candidate) target.replace(candidate, old); }
                catch (Exception | LinkageError rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
    }

    private static Publication inspect(Object factory) throws Exception {
        return inspect(factory, null);
    }

    private static Publication inspect(Object factory, Publication previous) throws Exception {
        String mapperName = unique(factory, type(factory, MAPPER));
        owner(factory, mapperName, JACKSON + "JacksonObjectMapperConfiguration", "jacksonObjectMapper");
        String builderName = unique(factory, type(factory, JSON + "Jackson2ObjectMapperBuilder"));
        owner(factory, builderName, JACKSON + "JacksonObjectMapperBuilderConfiguration", "jacksonObjectMapperBuilder");
        require(Boolean.TRUE.equals(call(factory, "isPrototype", builderName)));
        Class<?> converterType = type(factory, JSON + "MappingJackson2HttpMessageConverter");
        String converterName = unique(factory, converterType);
        owner(factory, converterName, HTTP + "JacksonHttpMessageConvertersConfiguration$MappingJackson2HttpMessageConverterConfiguration",
                "mappingJackson2HttpMessageConverter");
        Object primary = call(factory, previous == null ? "getSingleton" : "getBean", converterName);
        Object old = call(factory, "getSingleton", mapperName);
        require(old != null && primary != null);
        Object primaryMapper = call(primary, "getObjectMapper");
        require(primaryMapper == old || (previous != null && primary == previous.converter && primaryMapper == previous.old));
        Class<?> aggregateType = type(factory, HTTP + "HttpMessageConverters");
        String aggregateName = unique(factory, aggregateType);
        owner(factory, aggregateName, HTTP + "HttpMessageConvertersAutoConfiguration", "messageConverters");
        Object aggregate = call(factory, "getSingleton", aggregateName);
        require(aggregate != null && aggregate.getClass() == aggregateType);
        Object adapter = call(factory, "getSingleton", unique(factory, type(factory,
                "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter")));
        require(adapter != null);
        List<?> defaults = jsonConverters(call(aggregate, "getConverters"), converterType);
        List<?> serving = jsonConverters(call(adapter, "getMessageConverters"), converterType);
        require(!defaults.isEmpty() && defaults.size() == serving.size());
        WeakReference<Object> known = servingByFactory.get(factory);
        Object verified = previous != null ? previous.serving : known == null ? null : known.get();
        require(defaults.get(0) == primary || defaults.get(0) == verified);
        require(serving.get(0) == primary || serving.get(0) == verified);
        if (previous == null) require(call(serving.get(0), "getObjectMapper") == old);
        for (int i = 1; i < defaults.size(); i++) require(defaults.get(i) == serving.get(i));
        // Boot may keep Spring MVC's own fallback after the primary converter.
        require(defaults.size() == 1 || (defaults.size() == 2 && defaults.get(1).getClass() == converterType
                && call(defaults.get(1), "getObjectMapper") != old));
        Object registry = Reflect.readField(factory, "singletonObjects");
        require(registry instanceof ConcurrentMap<?, ?>);
        List<Slot> slots = new ArrayList<>();
        for (String name : (String[]) call(factory, "getSingletonNames")) {
            Object bean = call(factory, "getSingleton", name);
            if (bean == null || bean == primary || bean == old) continue;
            for (Field field : SpringBeanReloader.candidateFields(bean.getClass())) {
                if (field.getType().isInstance(old) && field.get(bean) == old) slots.add(new Slot(bean, field));
            }
        }
        return new Publication(factory, builderName, mapperName, (ConcurrentMap<String,Object>)registry,
                primary, serving.get(0), old, slots);
    }

    private static List<?> jsonConverters(Object source, Class<?> type) {
        return ((List<?>)source).stream().filter(type::isInstance).toList();
    }

    record Publication(Object factory, String builderName, String name, ConcurrentMap<String,Object> registry,
                               Object converter, Object serving, Object old, List<Slot> slots) {
        Object buildMapper() throws Exception {
            Object builder = call(factory, "getBean", builderName);
            builder.getClass().getMethod("createXmlMapper", boolean.class).invoke(builder, false);
            return call(builder, "build");
        }

        List<Reference> references() {
            List<Reference> targets = new ArrayList<>(slots);
            targets.add(new Singleton(factory, name, registry));
            targets.add(new Converter(converter));
            if (serving != converter) targets.add(new Converter(serving));
            return targets;
        }

        void publish(Object candidate) throws Exception { switchReferences(references(), old, candidate); }
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException(RESTART);
    }
}
