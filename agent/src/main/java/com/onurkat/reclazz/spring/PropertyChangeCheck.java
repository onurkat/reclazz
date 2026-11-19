/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.Failures;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;

/** Runs the application's Boot binding policy against detached targets and sources. */
public final class PropertyChangeCheck {
    private static final String PROPERTIES = "org.springframework.boot.context.properties.";
    private static final String BOUND = PROPERTIES + "BoundConfigurationProperties";
    private static final String PLACEHOLDERS =
            "org.springframework.context.support.PropertySourcesPlaceholderConfigurer";

    public record Result(PropertyChangeOutcome.State state, List<String> findings, ValueSelection selection) {
        public Result { findings = List.copyOf(findings); }
        public boolean passed() { return findings.isEmpty(); }
    }

    public Result check(List<Object> contexts, Map<String, String> changed) {
        return check(contexts, changed, null);
    }

    Result check(List<Object> contexts, Map<String, String> changed, Object preparedEnvironment) {
        return check(contexts, changed, preparedEnvironment, Set.of(), new HashMap<>());
    }

    Result check(List<Object> contexts, Map<String, String> changed, Object preparedEnvironment,
                 Set<String> removed, Map<String, Object> resets) {
        return check(contexts, changed, preparedEnvironment, removed, resets, null);
    }

    Result check(List<Object> contexts, Map<String, String> changed, Object preparedEnvironment,
                 Set<String> removed, Map<String, Object> resets, Object beforeEnvironment) {
        ValueSelection.Builder selections = new ValueSelection.Builder();
        List<String> rejected = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (Object context : contexts) {
            String location = context.getClass().getName();
            ScratchBinder binder = null;
            try {
                // Non-Spring contexts have no Environment and are not binding targets.
                if (Arrays.stream(context.getClass().getMethods())
                        .noneMatch(m -> m.getName().equals("getEnvironment") && m.getParameterCount() == 0)) continue;
                Object liveEnvironment = call(context, "getEnvironment");
                if (liveEnvironment == null) continue;
                ClassLoader loader = context.getClass().getClassLoader();
                Object environment = preparedEnvironment != null ? preparedEnvironment
                        : candidateEnvironment(loader, liveEnvironment, changed);
                selections.context(context, changed);
                checkValues(context, environment, changed, rejected, unavailable, selections, beforeEnvironment);
                Class<?> annotation;
                try { annotation = Class.forName(PROPERTIES + "ConfigurationProperties", false, loader); }
                catch (ClassNotFoundException noBoot) { continue; }
                @SuppressWarnings("unchecked")
                Map<String, Object> beans = (Map<String, Object>) call(context, "getBeansWithAnnotation", annotation);
                for (var entry : beans.entrySet()) {
                    if (!SpringPropertyRebinder.affects(changed.keySet(),
                            SpringPropertyRebinder.prefixOf(entry.getValue(), annotation))) continue;
                    try {
                        if (binder == null) binder = scratchBinder(context, environment, loader);
                        Object detached = checkBean(context, entry.getKey(), entry.getValue(), binder, loader);
                        if (detached != null && SpringPropertyRebinder.affects(removed,
                                SpringPropertyRebinder.prefixOf(entry.getValue(), annotation))) {
                            Class<?> beanUtils = Class.forName("org.springframework.beans.BeanUtils", true, loader);
                            for (Object descriptor : (Object[]) beanUtils.getMethod("getPropertyDescriptors", Class.class)
                                    .invoke(null, detached.getClass()))
                                if (!call(descriptor, "getName").equals("class")
                                        && (call(descriptor, "getReadMethod") == null || call(descriptor, "getWriteMethod") == null))
                                    throw new IllegalStateException("Removal requires readable and writable properties: " + call(descriptor, "getName"));
                            resets.put(entry.getKey(), detached);
                        }
                    } catch (Throwable failure) {
                        Throwable cause = unwrap(failure);
                        String finding = location + "/" + entry.getKey() + ": " + Failures.describe(cause);
                        if (isBindingFailure(cause)) rejected.add(finding);
                        else unavailable.add(finding);
                    }
                }
            } catch (Throwable failure) {
                unavailable.add(location + ": " + Failures.describe(unwrap(failure)));
            } finally {
                if (binder != null) {
                    try { binder.close(); }
                    catch (Throwable failure) {
                        unavailable.add(location + ": scratch validator cleanup failed: " + Failures.describe(failure));
                    }
                }
            }
        }
        List<String> findings = new ArrayList<>(rejected);
        findings.addAll(unavailable);
        return new Result(!unavailable.isEmpty() ? PropertyChangeOutcome.State.UNCHECKABLE
                : !rejected.isEmpty() ? PropertyChangeOutcome.State.REJECTED
                : PropertyChangeOutcome.State.APPLIED, findings, selections.build());
    }

    static Object candidateEnvironment(ClassLoader loader, Object live,
                                               Map<String, String> changed) throws Exception {
        Object environment = Class.forName("org.springframework.core.env.StandardEnvironment", true, loader)
                .getConstructor().newInstance();
        Object sources = call(environment, "getPropertySources");
        List<String> defaults = new ArrayList<>();
        for (Object source : (Iterable<?>) sources) defaults.add((String) call(source, "getName"));
        for (String name : defaults) call(sources, "remove", name);
        Map<String, Object> overlay = new LinkedHashMap<>();
        for (Object source : (Iterable<?>) call(live, "getPropertySources")) {
            String name = (String) call(source, "getName");
            // Boot's attached source points back into the LIVE Environment.
            if (name.equals("configurationProperties")) continue;
            if (name.equals(SpringPropertyRebinder.SOURCE_NAME)) {
                if (call(source, "getSource") instanceof Map<?, ?> prior)
                    prior.forEach((key, value) -> overlay.put(String.valueOf(key), value));
            }
            call(sources, "addLast", source);
        }
        overlay.putAll(changed);
        Object first = Class.forName("org.springframework.core.env.MapPropertySource", true, loader)
                .getConstructor(String.class, Map.class).newInstance("reclazz-candidate", overlay);
        call(sources, "addFirst", first);
        if (bootResolver(loader, live)) attachBoot(loader, environment);
        return environment;
    }

    /** A failed overlay may already be live. Trace retries against the last
     * successfully accepted map, just as the owned-file route does. */
    static Object acceptedEnvironment(ClassLoader loader, Object live) throws Exception {
        Object before = candidateEnvironment(loader, live, Map.of());
        Object liveSource = call(call(live, "getPropertySources"), "get", SpringPropertyRebinder.SOURCE_NAME);
        if (liveSource != null && call(liveSource, "getSource") instanceof SpringPropertyRebinder.OverlayValues values
                && !values.accepted.equals(values)) {
            Object sources = call(before, "getPropertySources");
            Class<?> sourceType = Class.forName("org.springframework.core.env.MapPropertySource", true, loader);
            for (String name : List.of(SpringPropertyRebinder.SOURCE_NAME, "reclazz-candidate"))
                call(sources, "replace", name, sourceType.getConstructor(String.class, Map.class).newInstance(name, values.accepted));
        }
        return before;
    }

    static Object environmentResolver(ClassLoader loader, Object environment) throws Exception {
        Class<?> type = Class.forName("org.springframework.core.env.AbstractEnvironment", false, loader);
        Method getter = type.getDeclaredMethod("getPropertyResolver");
        getter.setAccessible(true);
        return getter.invoke(environment);
    }

    static boolean isBootResolver(Object resolver) {
        return resolver != null && resolver.getClass().getName()
                .equals(PROPERTIES + "source.ConfigurationPropertySourcesPropertyResolver");
    }

    static boolean bootResolver(ClassLoader loader, Object environment) throws Exception {
        if (!isBootResolver(environmentResolver(loader, environment))) return false;
        try {
            Class<?> factory = Class.forName(PROPERTIES + "source.ConfigurationPropertySources", false, loader);
            Method attach = factory.getMethod("attach", Class.forName("org.springframework.core.env.Environment", false, loader));
            Method create = factory.getMethod("createPropertyResolver",
                    Class.forName("org.springframework.core.env.MutablePropertySources", false, loader));
            return Modifier.isPublic(factory.getModifiers())
                    && Modifier.isStatic(attach.getModifiers()) && attach.getReturnType() == void.class
                    && Modifier.isStatic(create.getModifiers()) && create.getReturnType()
                            == Class.forName("org.springframework.core.env.ConfigurablePropertyResolver", false, loader);
        } catch (ClassNotFoundException | NoSuchMethodException absent) {
            // Older Boot APIs keep the existing raw placeholder path.
            return false;
        }
    }

    static void attachBoot(ClassLoader loader, Object environment) throws Exception {
        Class.forName(PROPERTIES + "source.ConfigurationPropertySources", true, loader)
                .getMethod("attach", Class.forName("org.springframework.core.env.Environment", false, loader))
                .invoke(null, environment);
    }

    private static void checkValues(Object context, Object environment, Map<String, String> changed,
                                    List<String> rejected, List<String> unavailable, ValueSelection.Builder selections, Object beforeEnvironment) throws Exception {
        Object converter = call(SpringBeans.getBeanFactory(context), "getTypeConverter");
        var trace = new PropertyValueDependencies(context, changed, environment, beforeEnvironment);
        for (var target : SpringPropertyRebinder.valueTargets(context, expression -> {
            boolean selected = trace.test(expression);
            if (selected) selections.expression(context, expression);
            return selected;
        })) {
            try {
                Object resolved = call(environment, "resolveRequiredPlaceholders", target.expression());
                resolved = PropertyValueExpression.evaluate(context, target, resolved);
                call(converter, "convertIfNecessary", resolved, target.type());
            } catch (Throwable failure) {
                List<String> findings = unwrap(failure) instanceof PropertyValueExpression.Unsupported ? unavailable : rejected;
                findings.add(context.getClass().getSimpleName() + "/" + target.member()
                        + ": " + Failures.describe(unwrap(failure)));
            }
        }
    }

    private static ScratchBinder scratchBinder(Object live, Object environment, ClassLoader loader) throws Exception {
        Class<?> sources = Class.forName(PROPERTIES + "source.ConfigurationPropertySources", true, loader);
        sources.getMethod("attach", Class.forName("org.springframework.core.env.Environment", true, loader))
                .invoke(null, environment);
        Class<?> applicationContext = Class.forName("org.springframework.context.ApplicationContext", true, loader);
        // Preserve ConfigurableApplicationContext so Boot copies property editors too.
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        for (Class<?> type = live.getClass(); type != null; type = type.getSuperclass())
            for (Class<?> face : type.getInterfaces())
                if (Modifier.isPublic(face.getModifiers())) interfaces.add(face);
        interfaces.add(applicationContext);
        Object emptyFactory = Class.forName("org.springframework.beans.factory.support.DefaultListableBeanFactory",
                true, loader).getConstructor().newInstance();
        Object proxy = Proxy.newProxyInstance(loader, interfaces.toArray(Class<?>[]::new), (ignored, method, args) -> {
            if (method.getName().equals("getEnvironment") && method.getParameterCount() == 0) return environment;
            if (hiddenLookup(args)) {
                // The empty factory supplies correct absent-bean semantics for providers,
                // containsBeanDefinition, getBeansOfType and all getBean overloads.
                return call(emptyFactory, method.getName(), args);
            }
            try { return method.invoke(live, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        Class<?> type = Class.forName(PROPERTIES + "ConfigurationPropertiesBinder", true, loader);
        Constructor<?> constructor = type.getDeclaredConstructor(applicationContext);
        constructor.setAccessible(true);
        return new ScratchBinder(constructor.newInstance(proxy), live, loader);
    }

    private static boolean hiddenLookup(Object[] args) {
        if (args == null || args.length == 0) return false;
        Object key = args[0];
        String name = key instanceof Class<?> type ? type.getName() : String.valueOf(key);
        return name.equals(BOUND) || name.equals(PLACEHOLDERS);
    }

    private static Object checkBean(Object context, String name, Object live, ScratchBinder binder,
                                  ClassLoader loader) throws Exception {
        Class<?> metadata = Class.forName(PROPERTIES + "ConfigurationPropertiesBean", true, loader);
        Class<?> appContext = Class.forName("org.springframework.context.ApplicationContext", true, loader);
        Object bean = metadata.getMethod("get", appContext, Object.class, String.class).invoke(null, context, live, name);
        if (bean == null) throw new IllegalStateException("No binding metadata for " + name);
        Object original = call(bean, "asBindTarget");
        Object type = call(original, "getType");
        Class<?> javaType = (Class<?>) call(type, "resolve");
        Class<?> bindable = Class.forName(PROPERTIES + "bind.Bindable", true, loader);
        // Retain generic type and merged annotations, never the live supplier.
        Object target = bindable.getMethod("of", type.getClass()).invoke(null, type);
        target = call(target, "withAnnotations", (Object) (Annotation[]) call(original, "getAnnotations"));
        Object bindMethod;
        boolean boot3;
        try {
            bindMethod = call(original, "getBindMethod");
            boot3 = true;
            target = call(target, "withBindMethod", bindMethod);
        } catch (NoSuchMethodException boot2) {
            bindMethod = call(bean, "getBindMethod");
            boot3 = false;
        }
        boolean valueObject = bindMethod.toString().equals("VALUE_OBJECT");
        Object instance = null;
        if (!valueObject) {
            Constructor<?> constructor = javaType.getDeclaredConstructor();
            constructor.setAccessible(true);
            instance = constructor.newInstance();
            target = call(target, "withExistingValue", instance);
        }
        Constructor<?> constructor = boot3
                ? metadata.getDeclaredConstructor(String.class, Object.class, bindable)
                : metadata.getDeclaredConstructor(String.class, Object.class,
                        Class.forName(PROPERTIES + "ConfigurationProperties", true, loader), bindable);
        constructor.setAccessible(true);
        Object detached = boot3 ? constructor.newInstance(name, instance, target)
                : constructor.newInstance(name, instance, call(bean, "getAnnotation"), target);
        binder.prepare(target, javaType);
        Method bind = binder.nativeBinder.getClass().getDeclaredMethod(valueObject ? "bindOrCreate" : "bind", metadata);
        bind.setAccessible(true);
        bind.invoke(binder.nativeBinder, detached);
        return instance;
    }

    /** Per-save ownership; live context validators are borrowed and never closed here. */
    private static final class ScratchBinder {
        final Object nativeBinder;
        final Object context;
        final ClassLoader loader;
        final Field cached;
        final Field customField;
        final Object originalCustom;
        final boolean jsr303Present;
        final List<Object> owned = new ArrayList<>();

        ScratchBinder(Object binder, Object context, ClassLoader loader) throws Exception {
            this.nativeBinder = binder; this.context = context; this.loader = loader;
            Field old;
            try { old = binder.getClass().getDeclaredField("jsr303Validator"); }
            catch (NoSuchFieldException absent) { old = null; }
            cached = old;
            if (cached != null) {
                customField = null; originalCustom = null; jsr303Present = false;
                return;
            }
            Class<?> validator = Class.forName("org.springframework.validation.Validator", false, loader);
            Class<?> bindable = Class.forName(PROPERTIES + "bind.Bindable", false, loader);
            if (!binder.getClass().getName().equals(PROPERTIES + "ConfigurationPropertiesBinder")
                    || !perBindValidatorShape(binder.getClass(), validator, bindable))
                throw new IllegalStateException("unknown Spring Boot validator lifecycle");
            customField = binder.getClass().getDeclaredField("configurationPropertiesValidator");
            customField.setAccessible(true); originalCustom = customField.get(binder);
            Field present = binder.getClass().getDeclaredField("jsr303Present");
            present.setAccessible(true); jsr303Present = present.getBoolean(binder);
            // Only this detached binder changes. It must not create unowned per-bind factories.
            present.setBoolean(binder, false);
            if (jsr303Present) {
                Class<?> fallback = Class.forName(PROPERTIES + "ConfigurationPropertiesJsr303Validator", false, loader);
                try {
                    fallback.getDeclaredConstructor(Class.forName("org.springframework.context.ApplicationContext", false, loader), Class.class);
                    Field delegate = fallback.getDeclaredField("delegate");
                    delegate.getType().getMethod("close");
                } catch (ReflectiveOperationException | LinkageError unknown) {
                    throw new IllegalStateException("unknown Spring Boot validator lifecycle", unknown);
                }
            }
        }

        void prepare(Object target, Class<?> type) throws Exception {
            if (cached != null) return;
            List<Object> validators = new ArrayList<>();
            if (originalCustom != null) validators.add(originalCustom);
            Class<?> validated = Class.forName("org.springframework.validation.annotation.Validated", false, loader);
            if (jsr303Present && call(target, "getAnnotation", validated) != null) {
                Class<?> fallback = Class.forName(PROPERTIES + "ConfigurationPropertiesJsr303Validator", true, loader);
                Method present = fallback.getDeclaredMethod("isJsr303Present",
                        Class.forName("org.springframework.context.ApplicationContext", false, loader));
                present.setAccessible(true);
                if (!Boolean.TRUE.equals(present.invoke(null, context)))
                    throw new IllegalStateException("unknown Spring Boot validator lifecycle: JSR303 availability changed");
                Constructor<?> constructor = fallback.getDeclaredConstructor(
                        Class.forName("org.springframework.context.ApplicationContext", false, loader), Class.class);
                constructor.setAccessible(true);
                Object validator = constructor.newInstance(context, type);
                owned.add(validator);
                validators.add(validator);
            }
            if (validators.isEmpty()) { customField.set(nativeBinder, null); return; }
            Class<?> validatorType = Class.forName("org.springframework.validation.Validator", false, loader);
            // Boot calls supports then validate immediately with the resolved bindable type.
            // Keep that type, including nested bindables, and preserve supports/validate order.
            Class<?>[] currentType = new Class<?>[1];
            Object composite = Proxy.newProxyInstance(loader, new Class<?>[]{validatorType}, (proxy, method, args) -> {
                if (method.getName().equals("supports")) { currentType[0] = (Class<?>) args[0]; return true; }
                if (method.getName().equals("validate")) {
                    Class<?> selected = currentType[0];
                    for (Object validator : validators)
                        if (Boolean.TRUE.equals(call(validator, "supports", selected)))
                            call(validator, "validate", args);
                    return null;
                }
                if (method.getName().equals("toString")) return "Reclazz scratch validators";
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
                throw new UnsupportedOperationException(method.toString());
            });
            customField.set(nativeBinder, composite);
        }

        void close() throws Exception {
            if (cached != null) { closeScratchValidator(nativeBinder); return; }
            Exception failure = null;
            for (Object validator : owned) {
                try { closeValidator(validator); }
                catch (Exception problem) {
                    if (failure == null) failure = problem; else failure.addSuppressed(problem);
                }
            }
            if (failure != null) throw failure;
        }
    }

    static boolean perBindValidatorShape(Class<?> binder, Class<?> validator, Class<?> bindable) {
        try {
            Field custom = binder.getDeclaredField("configurationPropertiesValidator");
            Field present = binder.getDeclaredField("jsr303Present");
            if (custom.getType() != validator || !Modifier.isFinal(custom.getModifiers())
                    || present.getType() != boolean.class || !Modifier.isFinal(present.getModifiers())) return false;
            for (Field field : binder.getDeclaredFields())
                if (!field.equals(custom)
                        && (validator.isAssignableFrom(field.getType()) || validatorFactoryType(field.getType()))) return false;
            Method fallback = binder.getDeclaredMethod("getJsr303Validator", Class.class);
            Method validators = binder.getDeclaredMethod("getValidators", bindable);
            return Modifier.isPrivate(fallback.getModifiers()) && fallback.getReturnType() == validator
                    && Modifier.isPrivate(validators.getModifiers()) && validators.getReturnType() == List.class;
        } catch (ReflectiveOperationException | LinkageError unknown) { return false; }
    }

    private static boolean validatorFactoryType(Class<?> type) {
        if (type.getName().equals("jakarta.validation.ValidatorFactory")) return true;
        for (Class<?> face : type.getInterfaces()) if (validatorFactoryType(face)) return true;
        return type.getSuperclass() != null && validatorFactoryType(type.getSuperclass());
    }

    /** Boot's private fallback owns a validator factory; a per-save instance must close it. */
    private static void closeScratchValidator(Object binder) throws Exception {
        Field field = binder.getClass().getDeclaredField("jsr303Validator");
        field.setAccessible(true);
        Object validator = field.get(binder);
        if (validator == null) return;
        closeValidator(validator);
    }

    private static void closeValidator(Object validator) throws Exception {
        Field delegateField = validator.getClass().getDeclaredField("delegate");
        delegateField.setAccessible(true);
        call(delegateField.get(validator), "close");
    }

    private static boolean isBindingFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause.getClass().getName().equals(PROPERTIES + "bind.BindException")) return true;
        return false;
    }

    static Throwable unwrap(Throwable failure) {
        while (failure instanceof InvocationTargetException && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    /** Public Spring APIs, invoked without linking Spring into the agent. */
    static Object call(Object target, String name, Object... args) throws Exception {
        if (args == null) args = new Object[0];
        methods: for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                Class<?> type = types[i] == boolean.class ? Boolean.class : types[i];
                if (args[i] != null && !type.isInstance(args[i])) continue methods;
            }
            method.setAccessible(true);
            return method.invoke(target, args);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }
}
