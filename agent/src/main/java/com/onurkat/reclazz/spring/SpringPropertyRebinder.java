/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.Plural;
import com.onurkat.reclazz.util.Reflect;

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
 * Beyond the post-processor's reach, three more moves: a bean bound through its
 * constructor is destroyed and rebuilt against the updated Environment, a
 * {@code @Value} placeholder field is re-resolved and written directly, and a
 * bean that takes a changed {@code @Value} through its constructor is rebuilt
 * the same way the constructor-bound properties bean is. Scalar field SpEL
 * can use literals and arithmetic/conditional operators over placeholders;
 * application-code access is rejected before live values change. The same subset
 * is supported on scalar constructor parameters of directly constructed singletons.
 */
public final class SpringPropertyRebinder {

    private static final String BINDING_POST_PROCESSOR =
            "org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor";
    private static final String ANNOTATION =
            "org.springframework.boot.context.properties.ConfigurationProperties";

    /** Ours, so a later save replaces it instead of stacking another layer. */
    static final String SOURCE_NAME = "reclazz-reloaded-properties";

    private final List<Object> applicationContexts;

    public SpringPropertyRebinder(List<Object> applicationContexts) {
        this.applicationContexts = applicationContexts;
    }

    /** Validate first; only completed work can accept the immutable file candidate. */
    public PropertyChangeOutcome apply(Map<String, String> changed) {
        return apply(changed, Runnable::run, () -> { });
    }

    public PropertyChangeOutcome apply(Map<String, String> changed,
                                       java.util.function.Consumer<Runnable> boundary,
                                       Runnable accepted) {
        Map<String, String> candidate = Map.copyOf(changed);
        var check = new PropertyChangeCheck().check(applicationContexts, candidate);
        if (!check.passed()) {
            check.findings().forEach(StatusReporter::warn);
            StatusReporter.warn((check.state() == PropertyChangeOutcome.State.REJECTED
                    ? "Rejected" : "Uncheckable") + ": the running configuration is unchanged. "
                    + Plural.of(candidate.size(), "changed key") + " held until the file binds clean.");
            return PropertyChangeOutcome.held(check.state(), check.findings());
        }
        PropertyChangeOutcome[] result = {PropertyChangeOutcome.held(
                PropertyChangeOutcome.State.NOT_RUN, List.of("Property application did not run"))};
        boundary.accept(() -> {
            result[0] = applyLive(candidate);
            if (result[0].state() == PropertyChangeOutcome.State.APPLIED) accepted.run();
        });
        if (result[0].state() == PropertyChangeOutcome.State.PARTIAL) {
            result[0].findings().forEach(StatusReporter::warn);
            StatusReporter.warn("Property change partially applied; live values may differ. "
                    + "The file remains pending for the next save.");
        }
        return result[0];
    }

    private PropertyChangeOutcome applyLive(Map<String, String> changed) {
        List<String> rebound = new ArrayList<>();
        List<String> rebuilt = new ArrayList<>();
        int valueFields = 0;
        List<String> failures = new ArrayList<>();

        for (Object context : applicationContexts) {
            try {
                if (!updateEnvironment(context, changed)) continue;
                rebound.addAll(rebind(context, changed, failures));
                valueFields += reinjectValueFields(context, changed, failures);
                rebuilt.addAll(recreateValueConstructorBeans(context, changed, failures));
            } catch (Throwable t) {
                String finding = context.getClass().getSimpleName() + ": " + Failures.describe(t);
                failures.add(finding);
                RestartLedger.note(context.getClass().getSimpleName(), "property application failed");
            }
        }
        // A pool takes a size or a timeout and cannot take a URL. Asked after
        // the rebind, because the rebind is what makes the properties objects
        // current and the pool's disagreement with them is the whole signal.
        new SpringDataSourceCheck(applicationContexts).report(changed);

        if (valueFields > 0) {
            StatusReporter.success("Re-injected "
                    + Plural.of(valueFields, "@Value field")
                    + " reading the changed propert"
                    + (changed.size() == 1 ? "y" : "ies"));
        }
        if (!rebuilt.isEmpty()) {
            StatusReporter.success("Rebuilt "
                    + Plural.of(rebuilt.size(), "bean")
                    + Plural.word(rebuilt.size(),
                            " that takes a changed @Value through its constructor: ",
                            " that take a changed @Value through their constructor: ")
                    + rebuilt);
        }
        return new PropertyChangeOutcome(failures.isEmpty() ? PropertyChangeOutcome.State.APPLIED
                : PropertyChangeOutcome.State.PARTIAL, rebound, valueFields, rebuilt, failures);
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
     * <p>Field SpEL uses the same restricted evaluator as the precheck; it
     * cannot reach application beans or methods. A direct-placeholder
     * {@code @Value} constructor parameter has no field to write into; it is answered
     * by {@link #recreateValueConstructorBeans}, the same way the
     * constructor-bound properties bean is.
     */
    private static int reinjectValueFields(Object context, Map<String, String> changed,
                                           List<String> failures) throws Exception {
        int injected = 0;
        Object factory = SpringBeans.getBeanFactory(context);
        Object converter = PropertyChangeCheck.call(factory, "getTypeConverter");
        for (ValueTarget target : valueTargets(context, changed)) {
            if (target.field() == null) continue;
            try {
                Object resolved = PropertyChangeCheck.call(factory, "resolveEmbeddedValue", target.expression());
                resolved = PropertyValueExpression.evaluate(context, target, resolved);
                Object converted = PropertyChangeCheck.call(converter, "convertIfNecessary", resolved, target.type());
                target.field().setAccessible(true);
                target.field().set(target.bean(), converted);
                injected++;
            } catch (Throwable failure) {
                failures.add(target.member() + ": " + Failures.describe(failure));
                RestartLedger.note(target.member(), "@Value field could not take the changed property");
            }
        }
        return injected;
    }

    record ValueTarget(Object bean, java.lang.reflect.Field field, Class<?> type,
                       String expression, String member, String unsupportedReason) { }

    /** One sweep defines the targets both checking and live application handle. */
    static List<ValueTarget> valueTargets(Object context, Map<String, String> changed) throws Exception {
        List<ValueTarget> targets = new ArrayList<>();
        Object factory = SpringBeans.getBeanFactory(context);
        @SuppressWarnings("unchecked")
        Class<? extends java.lang.annotation.Annotation> annotation =
                (Class<? extends java.lang.annotation.Annotation>) Class.forName(
                        "org.springframework.beans.factory.annotation.Value", true, context.getClass().getClassLoader());
        Method value = annotation.getMethod("value");
        for (String name : (String[]) PropertyChangeCheck.call(factory, "getSingletonNames")) {
            Object singleton = PropertyChangeCheck.call(factory, "getSingleton", name);
            if (singleton == null) continue;
            Object bean = unwrapAopProxy(singleton);
            Class<?> type = userClass(bean.getClass());
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                    var found = field.getAnnotation(annotation);
                    if (found == null) continue;
                    String expression = (String) value.invoke(found);
                    if (referencesChangedKey(expression, changed))
                        targets.add(new ValueTarget(bean, field, field.getType(), expression, name + "." + field.getName(), null));
                }
            }
            // Recreation re-evaluates every argument, not just the one whose
            // property changed. Validate the unchanged @Value arguments too.
            boolean computedConstructor = hasConstructorExpression(type, annotation, value)
                    && takesChangedValue(type, changed, annotation, value);
            String unsupported = computedConstructor
                    ? constructorExpressionProblem(factory, name, singleton, type) : null;
            for (var constructor : type.getDeclaredConstructors()) {
                var parameters = constructor.getParameters();
                for (int i = 0; i < parameters.length; i++) {
                    var found = parameters[i].getAnnotation(annotation);
                    if (found == null) continue;
                    String expression = (String) value.invoke(found);
                    if (computedConstructor || referencesChangedKey(expression, changed))
                        targets.add(new ValueTarget(bean, null, parameters[i].getType(), expression,
                                name + ".<init>[" + i + "]", unsupported));
                }
            }
        }
        return targets;
    }

    /**
     * Rebuild every singleton that takes a changed {@code @Value} through its
     * constructor.
     *
     * <p>A constructor parameter has no field to write the new value into: by
     * the time the bean exists the value has already been passed in, and
     * whatever the constructor did with it (stored it, derived something from
     * it, handed it to a client it built) is not something a field write can
     * undo. That is the same shape as a constructor-bound
     * {@code @ConfigurationProperties} bean, and it gets the same answer:
     * the instance is not mutated, it is replaced. Destroying and re-creating
     * the singleton runs the constructor Spring ran at startup, this time
     * against an Environment that already holds the new value, and the
     * stale-reference sweep re-points the holders of the old instance.
     *
     * <p>Beans the post-processor already rebound are skipped: a
     * {@code @ConfigurationProperties} class is {@link #rebind}'s to handle,
     * and rebuilding it here as well would throw away the instance that path
     * just filled in.
     */
    private static List<String> recreateValueConstructorBeans(Object context,
                                                              Map<String, String> changed, List<String> failures) {
        List<String> rebuilt = new ArrayList<>();
        try {
            Object beanFactory = SpringBeans.getBeanFactory(context);
            Method getSingletonNames = Reflect.findMethod(beanFactory.getClass(), "getSingletonNames");
            Method getSingleton = Reflect.findMethod(beanFactory.getClass(), "getSingleton", String.class);
            if (getSingletonNames == null || getSingleton == null) return rebuilt;

            ClassLoader loader = context.getClass().getClassLoader();
            @SuppressWarnings("unchecked")
            Class<? extends java.lang.annotation.Annotation> valueAnnotation =
                    (Class<? extends java.lang.annotation.Annotation>) Class.forName(
                            "org.springframework.beans.factory.annotation.Value", true, loader);
            Method valueMember = valueAnnotation.getMethod("value");

            Class<? extends java.lang.annotation.Annotation> propertiesAnnotation = null;
            try {
                @SuppressWarnings("unchecked")
                Class<? extends java.lang.annotation.Annotation> found =
                        (Class<? extends java.lang.annotation.Annotation>) Class.forName(
                                ANNOTATION, true, loader);
                propertiesAnnotation = found;
            } catch (Throwable notSpringBoot) {
                // No @ConfigurationProperties on this loader, so no bean here
                // can be one, and nothing needs skipping.
            }

            for (String name : (String[]) getSingletonNames.invoke(beanFactory)) {
                Object bean;
                try {
                    bean = getSingleton.invoke(beanFactory, name);
                } catch (Throwable notNow) {
                    continue;
                }
                if (bean == null) continue;

                Class<?> type = userClass(unwrapAopProxy(bean).getClass());
                if (propertiesAnnotation != null && type.getAnnotation(propertiesAnnotation) != null) {
                    continue;
                }
                if (!takesChangedValue(type, changed, valueAnnotation, valueMember)) continue;

                if (rebuildSingleton(context, name, type)) rebuilt.add(name);
                else failures.add(name + ": @Value constructor bean could not be rebuilt");
            }
        } catch (Throwable notSpringShaped) {
            failures.add(context.getClass().getSimpleName() + ": " + Failures.describe(notSpringShaped));
        }
        return rebuilt;
    }

    /**
     * Whether any constructor of this class takes a {@code @Value} reading one
     * of the changed keys.
     *
     * <p>Direct-placeholder selection retains its conservative sweep of every
     * constructor. A bean declaring constructor SpEL must additionally pass
     * constructorExpressionProblem in the precheck: a single constructor and
     * Spring's cached creation policy must agree before any live mutation.
     */
    static boolean takesChangedValue(Class<?> type, Map<String, String> changed,
                                     Class<? extends java.lang.annotation.Annotation> valueAnnotation,
                                     Method valueMember) {
        for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (java.lang.annotation.Annotation[] parameter : constructor.getParameterAnnotations()) {
                for (java.lang.annotation.Annotation annotation : parameter) {
                    if (!valueAnnotation.isInstance(annotation)) continue;
                    try {
                        String expression = String.valueOf(valueMember.invoke(annotation));
                        if (referencesChangedKey(expression, changed)) return true;
                    } catch (Throwable oneParameter) {
                        // A parameter whose annotation cannot be read is not a
                        // reason to rebuild the bean.
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasConstructorExpression(Class<?> type,
            Class<? extends java.lang.annotation.Annotation> annotation, Method value) throws Exception {
        for (var constructor : type.getDeclaredConstructors()) {
            for (var parameter : constructor.getParameters()) {
                var found = parameter.getAnnotation(annotation);
                if (found != null && PropertyValueExpression.isExpression((String) value.invoke(found))) return true;
            }
        }
        return false;
    }

    /** Prove Spring will re-resolve this constructor, rather than call a factory or supplier. */
    private static String constructorExpressionProblem(Object factory, String name, Object singleton, Class<?> type) {
        try {
            if (singleton.getClass() != type)
                return "computed constructor parameters require an unproxied bean";
            if (type.getDeclaredConstructors().length != 1)
                return "computed constructor parameters require exactly one constructor";
            for (var annotation : type.getAnnotations()) {
                if (annotation.annotationType().getName().equals(ANNOTATION))
                    return "@ConfigurationProperties uses its own constructor binding path";
            }
            if (!Boolean.TRUE.equals(PropertyChangeCheck.call(factory, "containsBeanDefinition", name)))
                return "computed constructor parameters require a bean definition, not a manual singleton";
            Object definition = PropertyChangeCheck.call(factory, "getMergedBeanDefinition", name);
            if (!Boolean.TRUE.equals(PropertyChangeCheck.call(definition, "isSingleton")))
                return "computed constructor parameters require singleton scope";
            if (PropertyChangeCheck.call(definition, "getFactoryMethodName") != null
                    || PropertyChangeCheck.call(definition, "getFactoryBeanName") != null)
                return "computed constructor parameters do not support factory methods";
            if (PropertyChangeCheck.call(definition, "getInstanceSupplier") != null)
                return "computed constructor parameters do not support instance suppliers";
            if (!type.getName().equals(PropertyChangeCheck.call(definition, "getBeanClassName")))
                return "bean definition does not directly construct the live class";
            if (Boolean.TRUE.equals(PropertyChangeCheck.call(definition, "hasConstructorArgumentValues"))
                    || Boolean.TRUE.equals(PropertyChangeCheck.call(definition, "hasMethodOverrides")))
                return "explicit constructor arguments or method overrides are unsupported";
            // Spring's constructor cache is read only. A prepared argument is
            // resolved again by ConstructorResolver; a resolved value is reused.
            var constructor = type.getDeclaredConstructors()[0];
            if (!constructor.equals(Reflect.readField(definition, "resolvedConstructorOrFactoryMethod"))
                    || Reflect.readField(definition, "resolvedConstructorArguments") != null
                    || !(Reflect.readField(definition, "preparedConstructorArguments") instanceof Object[] prepared)
                    || prepared.length != constructor.getParameterCount())
                return "Spring's re-resolvable constructor arguments could not be verified";
            return null;
        } catch (Throwable failure) {
            return "constructor creation policy could not be verified: " + Failures.describe(failure);
        }
    }

    /** Replace the singleton and re-point what held it, or say why it stayed. */
    private static boolean rebuildSingleton(Object context, String beanName, Class<?> type) {
        try {
            Object[] pair = SpringBeanReloader.destroyAndRefreshBean(context, beanName);
            if (pair == null || pair[1] == null) {
                RestartLedger.note(beanName,
                        "a @Value constructor parameter that could not be rebuilt in place");
                StatusReporter.warn(beanName + " takes a changed @Value through its constructor "
                        + "and could not be rebuilt in place. A restart is what applies it.");
                return false;
            }
            if (pair[0] != null && pair[0] != pair[1]) {
                java.util.IdentityHashMap<Object, Object> replaced = new java.util.IdentityHashMap<>();
                replaced.put(pair[0], pair[1]);
                SpringBeanReloader.healStaleReferences(java.util.List.of(context), replaced);
            }
            return true;
        } catch (Throwable t) {
            RestartLedger.note(beanName,
                    "a @Value constructor parameter that could not be rebuilt in place");
            StatusReporter.warn("Rebuilding " + beanName + " (" + type.getSimpleName()
                    + ") failed (" + (Failures.describe(t) == null ? t.getClass().getSimpleName() : Failures.describe(t))
                    + "); the value it was given at startup is the one it keeps. "
                    + "A restart is what applies the new one.");
            return false;
        }
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
                Method getTargetSource = Reflect.findMethod(current.getClass(), "getTargetSource");
                if (getTargetSource == null || !current.getClass().getName().contains("$")) {
                    return current;
                }
                Object targetSource = getTargetSource.invoke(current);
                if (targetSource == null) return current;
                Method getTarget = Reflect.findMethod(targetSource.getClass(), "getTarget");
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
        Method getEnvironment = Reflect.findMethod(context.getClass(), "getEnvironment");
        if (getEnvironment == null) return false;
        Object environment = getEnvironment.invoke(context);
        if (environment == null) return false;

        Method getPropertySources = Reflect.findMethod(environment.getClass(), "getPropertySources");
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

    private List<String> rebind(Object context, Map<String, String> changed, List<String> failures) throws Exception {
        List<String> rebound = new ArrayList<>();

        ClassLoader loader = context.getClass().getClassLoader();
        Class<?> annotation;
        try { annotation = Class.forName(ANNOTATION, true, loader); }
        catch (ClassNotFoundException noBoot) { return rebound; }

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
            failures.add("ConfigurationProperties binding post-processor unavailable: " + Failures.describe(notBoot));
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
                } else failures.add(bean.getKey() + ": constructor-bound bean could not be rebuilt");
                continue;
            }

            try {
                rebindMethod.invoke(postProcessor, bean.getValue(), bean.getKey());
                rebound.add(bean.getKey());
            } catch (Throwable t) {
                failures.add(bean.getKey() + ": " + Failures.describe(t));
                RestartLedger.note(bean.getKey(),
                        "properties under \"" + prefix + "\" that could not be rebound");
                StatusReporter.warn("Could not rebind " + bean.getKey() + ": " + Failures.describe(t));
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
                        ? "; re-pointed " + Plural.of(healed, "reference") + " to it" : ""));
            }
            return true;
        } catch (Throwable t) {
            RestartLedger.note(beanName,
                    "properties under \"" + prefix + "\" that only a constructor can take");
            StatusReporter.warn("Rebuilding " + beanName + " failed ("
                    + (Failures.describe(t) == null ? t.getClass().getSimpleName() : Failures.describe(t))
                    + "); the values it already holds cannot be replaced. "
                    + "A restart is what applies them.");
            return false;
        }
    }

    /** The prefix the bean asked for, or "" when it binds the root. */
    static String prefixOf(Object bean, Class<?> annotation) {
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

    private static Object invokeIfPresent(Object target, String method, String argument) {
        try {
            return target.getClass().getMethod(method, String.class).invoke(target, argument);
        } catch (Throwable notThere) {
            return null;
        }
    }
}
