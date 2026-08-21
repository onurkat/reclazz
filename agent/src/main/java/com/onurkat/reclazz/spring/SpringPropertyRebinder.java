/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.agent.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts changed properties into the running Environment and rebinds the beans
 * that read them.
 *
 * Spring Boot reads a properties file once, at startup, and binds it into
 * objects. Editing the file after that changes nothing at all, which is why
 * changing a timeout or a feature flag has meant a restart even though the
 * value is a field on a bean that is sitting right there.
 *
 * Two steps, both of them things Spring already does to itself. The changed
 * keys go in as a property source ahead of the others, so the Environment
 * answers with the new value. Then every {@code @ConfigurationProperties} bean
 * whose prefix is affected is put back through the binding post-processor, the
 * same one that filled it in at startup.
 *
 * Beyond the post-processor's reach, two more moves: a bean bound through its
 * constructor is destroyed and rebuilt against the updated Environment, and a
 * {@code @Value} placeholder field is re-resolved and written directly. What
 * remains out of reach is said rather than glossed over: a SpEL
 * {@code @Value}, and a {@code @Value} constructor parameter.
 */
public final class SpringPropertyRebinder {

    private static final String BINDING_POST_PROCESSOR =
            "org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor";
    private static final String ANNOTATION =
            "org.springframework.boot.context.properties.ConfigurationProperties";

    /** Ours, so a later save replaces it instead of stacking another layer. */
    private static final String SOURCE_NAME = "reclazz-reloaded-properties";

    private final List<Object> applicationContexts;

    public SpringPropertyRebinder(List<Object> applicationContexts) {
        this.applicationContexts = applicationContexts;
    }

    /** What a property save reached: bound beans, and fields injected directly. */
    public record Applied(List<String> rebound, int valueFields) {
        public boolean tookEffect() {
            return !rebound.isEmpty() || valueFields > 0;
        }
    }

    /**
     * @param changed the keys this save changed, with their new values
     * @return the {@code @ConfigurationProperties} beans that took them, and
     *         how many {@code @Value} fields were re-injected
     */
    public Applied apply(Map<String, String> changed) {
        List<String> rebound = new ArrayList<>();
        int valueFields = 0;
        if (changed.isEmpty()) return new Applied(rebound, 0);

        for (Object context : applicationContexts) {
            try {
                if (!updateEnvironment(context, changed)) continue;
                rebound.addAll(rebind(context, changed));
                valueFields += reinjectValueFields(context, changed);
            } catch (Throwable t) {
                // One context that cannot be reached is not a reason to skip
                // the others: a Spring Boot application has one, a server has
                // dozens and most of them have no Environment at all.
            }
        }
        if (valueFields > 0) {
            StatusReporter.success("Re-injected " + valueFields
                    + " @Value field(s) reading the changed propert"
                    + (changed.size() == 1 ? "y" : "ies"));
        }
        return new Applied(rebound, valueFields);
    }

    /**
     * Write the new value into every singleton field whose {@code @Value}
     * placeholder reads a changed key.
     *
     * <p>Spring resolves {@code @Value} once, at injection time, and nothing
     * re-reads it: the Environment could hold the new value forever and the
     * field would keep the old one. The fields are found by sweeping the live
     * singletons, matching the placeholder text against the changed keys, and
     * resolving through the bean factory's own embedded-value resolver, so the
     * default syntax and nesting behave exactly as they did at startup.
     *
     * <p>What is left alone, on purpose: a SpEL expression ({@code #{...}}),
     * because re-evaluating arbitrary expressions is running application code
     * at a moment it did not choose; and a {@code @Value} constructor
     * parameter, which has no field to write into, the same immutability the
     * constructor-bound properties path answers by rebuilding the bean.
     */
    private static int reinjectValueFields(Object context, Map<String, String> changed) {
        int injected = 0;
        try {
            Object beanFactory = SpringBeans.getBeanFactory(context);
            Method resolveEmbedded = SpringBeans.findMethod(beanFactory.getClass(),
                    "resolveEmbeddedValue", String.class);
            Method getTypeConverter = findMethod(beanFactory.getClass(), "getTypeConverter");
            if (resolveEmbedded == null || getTypeConverter == null) return 0;

            ClassLoader loader = context.getClass().getClassLoader();
            @SuppressWarnings("unchecked")
            Class<? extends java.lang.annotation.Annotation> valueAnnotation =
                    (Class<? extends java.lang.annotation.Annotation>) Class.forName(
                            "org.springframework.beans.factory.annotation.Value", true, loader);
            Method valueMember = valueAnnotation.getMethod("value");

            Method getSingletonNames = SpringBeans.findMethod(beanFactory.getClass(), "getSingletonNames");
            Method getSingleton = SpringBeans.findMethod(beanFactory.getClass(), "getSingleton", String.class);
            if (getSingletonNames == null || getSingleton == null) return 0;

            Object typeConverter = getTypeConverter.invoke(beanFactory);
            Method convert = SpringBeans.findMethod(typeConverter.getClass(),
                    "convertIfNecessary", Object.class, Class.class);
            if (convert != null) convert.setAccessible(true);

            for (String name : (String[]) getSingletonNames.invoke(beanFactory)) {
                Object bean;
                try {
                    bean = getSingleton.invoke(beanFactory, name);
                } catch (Throwable notNow) {
                    continue;
                }
                if (bean == null) continue;
                Object target = unwrapAopProxy(bean);
                injected += reinjectInto(target, changed, valueAnnotation, valueMember,
                        beanFactory, resolveEmbedded, typeConverter, convert);
            }
        } catch (Throwable notSpringShaped) {
            return injected;
        }
        return injected;
    }

    private static int reinjectInto(Object bean, Map<String, String> changed,
                                    Class<? extends java.lang.annotation.Annotation> valueAnnotation,
                                    Method valueMember, Object beanFactory,
                                    Method resolveEmbedded, Object typeConverter,
                                    Method convert) {
        int injected = 0;
        for (Class<?> c = userClass(bean.getClass()); c != null && c != Object.class;
                c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                try {
                    java.lang.annotation.Annotation value = field.getAnnotation(valueAnnotation);
                    if (value == null) continue;
                    String expression = String.valueOf(valueMember.invoke(value));
                    if (!referencesChangedKey(expression, changed)) continue;
                    if (expression.contains("#{")) continue;   // SpEL: stated policy above

                    Object resolved = resolveEmbedded.invoke(beanFactory, expression);
                    Object converted = convert != null
                            ? convert.invoke(typeConverter, resolved, field.getType())
                            : resolved;
                    field.setAccessible(true);
                    field.set(bean, converted);
                    injected++;
                } catch (Throwable oneField) {
                    // A field that cannot take the value keeps the one it has;
                    // the count reports only what really changed.
                }
            }
        }
        return injected;
    }

    /** Whether the placeholder text reads any of the changed keys. */
    static boolean referencesChangedKey(String expression, Map<String, String> changed) {
        if (expression == null || !expression.contains("${")) return false;
        for (String key : changed.keySet()) {
            if (expression.contains("${" + key + "}")
                    || expression.contains("${" + key + ":")) {
                return true;
            }
        }
        return false;
    }

    /** The bean behind an AOP proxy, or the bean itself when there is none. */
    private static Object unwrapAopProxy(Object bean) {
        try {
            Object current = bean;
            for (int depth = 0; depth < 5; depth++) {
                Method getTargetSource = findMethod(current.getClass(), "getTargetSource");
                if (getTargetSource == null || !current.getClass().getName().contains("$")) {
                    return current;
                }
                Object targetSource = getTargetSource.invoke(current);
                if (targetSource == null) return current;
                Method getTarget = findMethod(targetSource.getClass(), "getTarget");
                if (getTarget == null) return current;
                Object target = getTarget.invoke(targetSource);
                if (target == null || target == current) return current;
                current = target;
            }
            return current;
        } catch (Throwable notAProxy) {
            return bean;
        }
    }

    /**
     * Adds the changed keys ahead of every other source.
     *
     * Editing the source the value came from would be closer to what the
     * developer wrote, but there is no reliable way back to it: a key can come
     * from a file, an environment variable or a command line, and Boot names
     * those sources differently across versions. Overriding is honest about
     * what happened and is undone by the restart that reloads the file anyway.
     */
    boolean updateEnvironment(Object context, Map<String, String> changed) throws Exception {
        Method getEnvironment = findMethod(context.getClass(), "getEnvironment");
        if (getEnvironment == null) return false;
        Object environment = getEnvironment.invoke(context);
        if (environment == null) return false;

        Method getPropertySources = findMethod(environment.getClass(), "getPropertySources");
        if (getPropertySources == null) return false;
        Object sources = getPropertySources.invoke(environment);

        Map<String, Object> values = new LinkedHashMap<>();
        Object existing = invokeIfPresent(sources, "get", SOURCE_NAME);
        if (existing != null) {
            Object source = existing.getClass().getMethod("getSource").invoke(existing);
            if (source instanceof Map<?, ?> previous) {
                previous.forEach((key, value) -> values.put(String.valueOf(key), value));
            }
        }
        values.putAll(changed);

        Class<?> mapSource = Class.forName("org.springframework.core.env.MapPropertySource",
                true, environment.getClass().getClassLoader());
        Object replacement = mapSource.getConstructor(String.class, Map.class)
                .newInstance(SOURCE_NAME, values);

        Class<?> propertySource = Class.forName("org.springframework.core.env.PropertySource",
                true, environment.getClass().getClassLoader());
        if (existing != null) {
            sources.getClass().getMethod("replace", String.class, propertySource)
                    .invoke(sources, SOURCE_NAME, replacement);
        } else {
            sources.getClass().getMethod("addFirst", propertySource).invoke(sources, replacement);
        }
        return true;
    }

    private List<String> rebind(Object context, Map<String, String> changed) throws Exception {
        List<String> rebound = new ArrayList<>();

        ClassLoader loader = context.getClass().getClassLoader();
        Class<?> annotation = Class.forName(ANNOTATION, true, loader);

        @SuppressWarnings("unchecked")
        Map<String, Object> beans = (Map<String, Object>) context.getClass()
                .getMethod("getBeansWithAnnotation", Class.class)
                .invoke(context, annotation);
        if (beans.isEmpty()) return rebound;

        Object postProcessor;
        try {
            postProcessor = context.getClass().getMethod("getBean", String.class)
                    .invoke(context, BINDING_POST_PROCESSOR);
        } catch (Throwable notBoot) {
            return rebound;
        }
        Method rebindMethod = postProcessor.getClass()
                .getMethod("postProcessBeforeInitialization", Object.class, String.class);

        for (Map.Entry<String, Object> bean : beans.entrySet()) {
            String prefix = prefixOf(bean.getValue(), annotation);
            if (!affects(changed.keySet(), prefix)) continue;

            // Asked to rebind one of these, the post-processor returns without
            // complaint and without doing anything, so the check has to happen
            // here. There is nothing to write a new value into, but there is a
            // bean definition to build a new instance from: the Environment
            // already holds the changed keys at this point, so destroying and
            // re-creating the singleton binds the new values through the same
            // constructor path startup used, and the stale-reference sweep
            // re-points the fields that held the old instance.
            if (isConstructorBound(bean.getValue())) {
                if (recreateConstructorBound(context, bean.getKey(), prefix)) {
                    rebound.add(bean.getKey());
                }
                continue;
            }

            try {
                rebindMethod.invoke(postProcessor, bean.getValue(), bean.getKey());
                rebound.add(bean.getKey());
            } catch (Throwable t) {
                RestartLedger.note(bean.getKey(),
                        "properties under \"" + prefix + "\" that could not be rebound");
                StatusReporter.warn("Could not rebind " + bean.getKey() + ": " + t.getMessage());
            }
        }
        return rebound;
    }

    /**
     * Whether the bean was filled in through its constructor, which leaves
     * nothing to write a new value into.
     *
     * A record is the common shape, and an explicitly annotated constructor
     * the other. Both are asking for immutability, and immutability is kept:
     * the instance is never mutated, it is replaced, the way every other
     * immutable value gets a new state.
     */
    static boolean isConstructorBound(Object bean) {
        Class<?> type = userClass(bean.getClass());
        if (type.isRecord()) return true;

        for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (java.lang.annotation.Annotation annotation : constructor.getAnnotations()) {
                // By simple name: Spring Boot has moved this annotation
                // between packages across versions, and both spellings mean
                // the same thing here.
                if (annotation.annotationType().getSimpleName().equals("ConstructorBinding")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Replace a constructor-bound properties bean with one built from the
     * updated Environment, and re-point every field that held the old one.
     *
     * <p>The failure mode is the state this path used to be the only answer
     * for: the old instance keeps serving and the log says a restart applies
     * the change. What can no longer happen silently is the middle ground,
     * where the bean is rebuilt but its holders keep the old object; the sweep
     * that heals class-reload refreshes runs here too, and holders it cannot
     * reach (a value copied out of the bean into a local or a derived field)
     * were never reachable by any rebind either.
     */
    private static boolean recreateConstructorBound(Object context, String beanName, String prefix) {
        try {
            Object[] pair = SpringBeanReloader.destroyAndRefreshBean(context, beanName);
            if (pair == null || pair[1] == null) {
                // Not a singleton, or the factory would not rebuild it.
                RestartLedger.note(beanName,
                        "properties under \"" + prefix + "\" that only a constructor can take");
                StatusReporter.warn(beanName + " takes its properties through its constructor "
                        + "and could not be rebuilt in place. A restart is what applies them.");
                return false;
            }
            if (pair[0] != null && pair[0] != pair[1]) {
                java.util.IdentityHashMap<Object, Object> replaced = new java.util.IdentityHashMap<>();
                replaced.put(pair[0], pair[1]);
                int healed = SpringBeanReloader.healStaleReferences(
                        java.util.List.of(context), replaced);
                StatusReporter.success(beanName + " rebuilt through its constructor with the "
                        + "new values" + (healed > 0
                        ? "; re-pointed " + healed + " reference(s) to it" : ""));
            }
            return true;
        } catch (Throwable t) {
            RestartLedger.note(beanName,
                    "properties under \"" + prefix + "\" that only a constructor can take");
            StatusReporter.warn("Rebuilding " + beanName + " failed ("
                    + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage())
                    + "); the values it already holds cannot be replaced. "
                    + "A restart is what applies them.");
            return false;
        }
    }

    /** The prefix the bean asked for, or "" when it binds the root. */
    private static String prefixOf(Object bean, Class<?> annotation) {
        try {
            Class<?> type = userClass(bean.getClass());
            Object found = type.getAnnotation(annotation.asSubclass(java.lang.annotation.Annotation.class));
            if (found == null) return "";

            // Through the annotation interface, not the proxy's own class:
            // what answers here is a generated implementation whose methods
            // are only reachable that way.
            Object prefix = annotation.getMethod("prefix").invoke(found);
            if (prefix != null && !String.valueOf(prefix).isEmpty()) return String.valueOf(prefix);
            Object value = annotation.getMethod("value").invoke(found);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * A proxied bean's own class carries no annotations; the class it was
     * generated from does.
     */
    private static Class<?> userClass(Class<?> type) {
        return type.getName().contains("$$") && type.getSuperclass() != null
                ? type.getSuperclass()
                : type;
    }

    /** A bean binding the root prefix is affected by anything. */
    static boolean affects(java.util.Set<String> changedKeys, String prefix) {
        if (prefix == null || prefix.isEmpty()) return true;
        for (String key : changedKeys) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }

    private static Method findMethod(Class<?> type, String name) {
        try {
            Method method = type.getMethod(name);
            method.setAccessible(true);
            return method;
        } catch (Throwable notThere) {
            return null;
        }
    }

    private static Object invokeIfPresent(Object target, String method, String argument) {
        try {
            return target.getClass().getMethod(method, String.class).invoke(target, argument);
        } catch (Throwable notThere) {
            return null;
        }
    }
}
