/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.Failures;

import java.lang.annotation.Annotation;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Saved arguments for a definition created and still owned by the added-bean path. */
final class SpringAddedFactoryValues {
    private static final String ATTRIBUTE = SpringAddedFactoryValues.class.getName();
    private SpringAddedFactoryValues() { }

    private record Metadata(WeakReference<Object> factory, WeakReference<Object> definition,
                            Class<?> owner, String ownerName, String methodName, boolean full,
                            Method parameters, Supplier<?> supplier) { }

    static void attach(Object factory, Object definition, Class<?> owner, String ownerName,
                       String methodName, boolean full, Method parameters, Supplier<?> supplier) throws Exception {
        if (parameters == null) throw new IllegalStateException("added factory parameter metadata is missing");
        PropertyChangeCheck.call(definition, "setAttribute", ATTRIBUTE,
                new Metadata(new WeakReference<>(factory), new WeakReference<>(definition),
                        owner, ownerName, methodName, full, parameters, supplier));
    }

    static SpringFactoryValues.Values inspect(Object factory, Object definition, String name, Object singleton,
            Class<? extends Annotation> annotation, Method value, PropertyValueDependencies affected) throws Exception {
        Object attribute = PropertyChangeCheck.call(definition, "getAttribute", ATTRIBUTE);
        if (!(attribute instanceof Metadata metadata)) return null;
        List<SpringPropertyRebinder.ValueTarget> targets = new ArrayList<>();
        boolean selected = false;
        var parameters = metadata.parameters().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            var found = parameters[i].getAnnotation(annotation);
            if (found == null) continue;
            String expression = (String) value.invoke(found);
            selected |= affected.test(expression);
            targets.add(new SpringPropertyRebinder.ValueTarget(singleton, null, parameters[i].getType(),
                    expression, name + "." + metadata.methodName() + "[" + i + "]", null));
        }
        // A supplier owns creation even when none of its inputs changed. Never
        // mistake annotations on a constructor called inside its body for inputs.
        if (!selected) return new SpringFactoryValues.Values(List.of());
        String problem = policy(factory, definition, name, singleton, metadata, annotation.getClassLoader());
        return SpringFactoryValues.checked(targets, problem);
    }

    /** Only inspect existing objects and definition metadata, never invoke a supplier. */
    private static String policy(Object factory, Object definition, String name, Object singleton,
                                 Metadata metadata, ClassLoader loader) {
        try {
            Object original = PropertyChangeCheck.call(factory, "getBeanDefinition", name);
            if (metadata.factory().get() != factory || metadata.definition().get() != original
                    || PropertyChangeCheck.call(original, "getAttribute", ATTRIBUTE) != metadata)
                return "added factory definition ownership changed";
            for (Object candidate : List.of(original, definition)) {
                if (PropertyChangeCheck.call(candidate, "getInstanceSupplier") != metadata.supplier())
                    return "added factory instance supplier changed";
                if (!Boolean.TRUE.equals(PropertyChangeCheck.call(candidate, "isSingleton")))
                    return "added factory values require singleton scope";
                if (PropertyChangeCheck.call(candidate, "getBeanClass") != metadata.parameters().getReturnType()
                        || PropertyChangeCheck.call(candidate, "getFactoryMethodName") != null
                        || PropertyChangeCheck.call(candidate, "getFactoryBeanName") != null
                        || Boolean.TRUE.equals(PropertyChangeCheck.call(candidate, "hasConstructorArgumentValues"))
                        || Boolean.TRUE.equals(PropertyChangeCheck.call(candidate, "hasMethodOverrides")))
                    return "added factory creation metadata changed";
            }
            if (SpringFactoryValues.isProxy(singleton, loader))
                return "added factory values require an unproxied product";
            if (!metadata.parameters().getReturnType().isInstance(singleton))
                return "added factory return type does not describe the live product";
            if (SpringFactoryValues.hasProperties(singleton.getClass().getAnnotations()))
                return "factory @ConfigurationProperties uses its own binding path";
            Object owner = PropertyChangeCheck.call(factory, "getSingleton", metadata.ownerName());
            if (owner == null || !Boolean.TRUE.equals(PropertyChangeCheck.call(factory, "isSingleton", metadata.ownerName()))
                    || !SpringConfigurationCalls.matches(owner, metadata.owner(), factory, metadata.full()))
                return "added factory owner is no longer a supported singleton";
            return SpringFactoryValues.expressionPolicy(factory);
        } catch (Throwable failure) {
            return "added factory creation policy could not be verified: " + Failures.describe(failure);
        }
    }
}
