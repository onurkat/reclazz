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
 * What cannot be done this way is said rather than glossed over. A bean bound
 * through its constructor has no field to rebind, and a {@code @Value} field
 * was injected once and copied; both are recorded as needing a restart.
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

    /**
     * @param changed the keys this save changed, with their new values
     * @return the {@code @ConfigurationProperties} beans that took them
     */
    public List<String> apply(Map<String, String> changed) {
        List<String> rebound = new ArrayList<>();
        if (changed.isEmpty()) return rebound;

        for (Object context : applicationContexts) {
            try {
                if (!updateEnvironment(context, changed)) continue;
                rebound.addAll(rebind(context, changed));
            } catch (Throwable t) {
                // One context that cannot be reached is not a reason to skip
                // the others: a Spring Boot application has one, a server has
                // dozens and most of them have no Environment at all.
            }
        }
        return rebound;
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
