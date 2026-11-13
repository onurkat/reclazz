/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import java.lang.reflect.Field;
import java.util.*;

/** Existing native mapping refs only. Native initialization builds the cache off the live task. */
final class SapHotFolderMappings {
    private static final String ROOT = "de.hybris.platform.acceleratorservices.dataimport.batch.";
    private static final String MAPPING = ROOT + "converter.mapping.impl.DefaultConverterMapping";
    private static final String CONVERTER = ROOT + "converter.impl.DefaultImpexConverter";
    private static final String TASK = ROOT + "task.ImpexTransformerTask";
    private SapHotFolderMappings() { }

    record Edit(BeanDefinitionDiff.PropertyChange change, Object mapping, Object previous, Object requested,
                XmlNativePropertyBinding.Binding binding, String prefix) { }
    record Held(Object target, Object previous) { }
    record Prepared(Object context, Class<?> task, Field cache, List<Edit> edits, List<Held> held) {
        void apply() throws Exception {
            try {
                for (Edit edit : edits) edit.binding.setter().invoke(edit.mapping, edit.requested);
                Object detached = task.getConstructor().newInstance();
                Class<?> contextType = Class.forName("org.springframework.context.ApplicationContext", false, task.getClassLoader());
                task.getMethod("setApplicationContext", contextType).invoke(detached, context);
                task.getMethod("initConvertersMap").invoke(detached);
                Object prepared = cache.get(detached);
                require(prepared instanceof Map<?,?>, "native initialization did not produce a cache");
                for (Held live : held) cache.set(live.target, prepared);
            } catch (Exception failure) {
                for (Edit edit : edits) {
                    try { edit.binding.setter().invoke(edit.mapping, edit.previous); }
                    catch (Exception rollback) { failure.addSuppressed(rollback); }
                }
                for (Held live : held) {
                    try { cache.set(live.target, live.previous); }
                    catch (Exception rollback) { failure.addSuppressed(rollback); }
                }
                throw failure;
            }
        }
    }

    /** Identify native mapping edits even if their runtime shape must be refused. */
    static boolean candidate(Object factory, BeanDefinitionDiff.PropertyChange change) {
        Object bean = SpringReflection.getExistingSingleton(factory, change.beanName);
        for (Class<?> type = bean == null ? null : bean.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals(MAPPING)) return true;
        Object definition = SpringReflection.getBeanDefinition(factory, change.beanName);
        try {
            ClassLoader loader = (ClassLoader) call(factory, "getBeanClassLoader");
            Class<?> api = Class.forName(ROOT + "converter.mapping.ConverterMapping", false, loader);
            if (api.isInstance(bean)) return true;
            String declared = definition == null ? null : (String) call(definition, "getBeanClassName");
            return declared != null && api.isAssignableFrom(Class.forName(declared, false, loader));
        } catch (Exception unavailable) { return false; }
    }

    static Prepared prepare(Object context, Object factory, List<BeanDefinitionDiff.PropertyChange> changes,
                            boolean admitted) throws Exception {
        require(admitted, "hot-folder mapping requires an observed SAP XML owner");
        ClassLoader loader = (ClassLoader) call(factory, "getBeanClassLoader");
        Class<?> mapping = Class.forName(MAPPING, false, loader);
        Class<?> converter = Class.forName(CONVERTER, false, loader);
        Class<?> task = Class.forName(TASK, false, loader);
        Class<?> mappingApi = Class.forName(ROOT + "converter.mapping.ConverterMapping", false, loader);
        Class<?> converterApi = Class.forName(ROOT + "converter.ImpexConverter", false, loader);
        // Native init calls getBeansOfType. Prove every local mapping is already
        // materialized and plain before permitting that call, including lazy definitions.
        String[] mappingNames = (String[]) factory.getClass().getMethod("getBeanNamesForType", Class.class, boolean.class, boolean.class)
                .invoke(factory, mappingApi, true, false);
        for (String name : mappingNames) {
            Object existing = SpringReflection.getExistingSingleton(factory, name);
            require(existing != null && existing.getClass() == mapping, "lazy/custom/proxy hot-folder mapping requires restart");
            plainDefinition(factory, name, mapping);
        }
        List<Edit> edits = new ArrayList<>();
        Set<Object> seenMappings = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var change : changes) {
            Object existing = SpringReflection.getExistingSingleton(factory, change.beanName);
            require(existing != null && existing.getClass() == mapping && seenMappings.add(existing),
                    "only one converter-ref edit per existing native mapping is supported");
            plainDefinition(factory, change.beanName, mapping);
            require(change.propertyName.equals("converter"), "hot-folder mapping prefix/policy edit requires restart");
            Object previous = mapping.getMethod("getConverter").invoke(existing);
            Object requested = reference(factory, change.newRawValue, converter);
            require(previous != null && previous.getClass() == converter, "custom prior hot-folder converter requires restart");
            // The old reference must also be a direct existing singleton, not a transient/inner bean.
            Object oldRaw = raw(SpringReflection.getBeanDefinition(factory, change.beanName), "converter");
            require(reference(factory, oldRaw, converter) == previous, "live converter differs from captured definition");
            String prefix = (String) mapping.getMethod("getMapping").invoke(existing);
            Object prefixRaw = raw(SpringReflection.getBeanDefinition(factory, change.beanName), "mapping");
            require(prefix != null && !prefix.isEmpty() && prefix.equals(text(prefixRaw)), "unverified mapping prefix");
            var binding = XmlNativePropertyBinding.prepare(factory, existing, "converter", change.newRawValue);
            require(binding.setter().getDeclaringClass() == mapping && binding.setter().getParameterTypes()[0] == converterApi,
                    "unverified native converter setter");
            edits.add(new Edit(change, existing, previous, binding.convert(requested), binding, prefix));
        }
        Field cache = task.getDeclaredField("converterMap"); cache.setAccessible(true);
        Field owner = task.getDeclaredField("applicationContext"); owner.setAccessible(true);
        require(cache.getType() == Map.class, "unverified native converter cache field");
        task.getConstructor(); task.getMethod("initConvertersMap");
        Class<?> contextApi = Class.forName("org.springframework.context.ApplicationContext", false, loader);
        task.getMethod("setApplicationContext", contextApi);
        Class<?> advised = Class.forName("org.springframework.aop.framework.Advised", false, loader);
        Class<?> sourceType = Class.forName("org.springframework.aop.target.SingletonTargetSource", false, loader);
        List<Held> held = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Edit> collected = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : (String[]) call(factory, "getSingletonNames")) {
            Object exposed = SpringReflection.getExistingSingleton(factory, name);
            Object target = exposed;
            if (advised.isInstance(exposed)) {
                Object source = advised.getMethod("getTargetSource").invoke(exposed);
                Class<?> targetClass = (Class<?>) call(source, "getTargetClass");
                if (targetClass == null || !task.isAssignableFrom(targetClass)) continue;
                require(source.getClass() == sourceType && Boolean.TRUE.equals(call(source, "isStatic")),
                        "dynamic hot-folder target requires restart");
                target = call(source, "getTarget");
            }
            if (!task.isInstance(target)) continue;
            require(target.getClass() == task && owner.get(target) == context, "custom/wrong-owner hot-folder task requires restart");
            Object current = cache.get(target);
            require(current instanceof Map<?,?>, "native hot-folder task has no initialized cache");
            boolean uses = false;
            for (Edit edit : edits) {
                Object converters = ((Map<?,?>) current).get(edit.prefix);
                if (converters instanceof List<?> list && list.stream().anyMatch(value -> value == edit.previous)) {
                    collected.add(edit); uses = true;
                }
            }
            if (uses && seen.add(target)) held.add(new Held(target, current));
        }
        require(collected.size() == edits.size() && !held.isEmpty(), "no verified native collector for hot-folder mapping");
        return new Prepared(context, task, cache, edits, held);
    }

    private static Object reference(Object factory, Object raw, Class<?> expected) throws Exception {
        Class<?> reference = Class.forName("org.springframework.beans.factory.config.RuntimeBeanReference", false, factory.getClass().getClassLoader());
        require(raw != null && raw.getClass() == reference && !Boolean.TRUE.equals(call(raw, "isToParent")), "direct local converter ref required");
        String name = (String) call(raw, "getBeanName");
        name = (String) factory.getClass().getMethod("canonicalName", String.class).invoke(factory, name);
        Object value = SpringReflection.getExistingSingleton(factory, name);
        require(value != null && value.getClass() == expected, "existing plain native converter singleton required");
        plainDefinition(factory, name, expected);
        return value;
    }
    private static void plainDefinition(Object factory, String name, Class<?> expected) throws Exception {
        Object definition = SpringReflection.getBeanDefinition(factory, name);
        require(definition != null && expected.getName().equals(call(definition, "getBeanClassName"))
                && Boolean.TRUE.equals(call(definition, "isSingleton")) && !Boolean.TRUE.equals(call(definition, "isLazyInit"))
                && call(definition, "getFactoryBeanName") == null && call(definition, "getFactoryMethodName") == null
                && call(definition, "getParentName") == null, "direct non-lazy native definition required");
    }
    private static Object raw(Object definition, String property) {
        for (Object value : SpringReflection.getPropertyValueArray(SpringReflection.getPropertyValues(definition)))
            if (property.equals(SpringReflection.getPropertyName(value))) return SpringReflection.getPropertyValue(value);
        return null;
    }
    private static String text(Object raw) throws Exception {
        if (raw instanceof String string) return string;
        if (raw != null && raw.getClass().getName().equals("org.springframework.beans.factory.config.TypedStringValue"))
            return (String) call(raw, "getValue");
        return null;
    }
    private static Object call(Object receiver, String method) throws Exception {
        return receiver.getClass().getMethod(method).invoke(receiver);
    }
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }
}
