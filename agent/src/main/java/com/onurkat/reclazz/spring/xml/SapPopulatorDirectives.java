/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies an edited SAP {@code ModifyPopulatorList} directive to its live target list.
 *
 * <p>SAP's {@code ModifyPopulatorListBeanPostProcessor} runs once, when the directive bean
 * is created: it removes {@code remove} from the target converter's populators and appends
 * {@code add}. Changing {@code add} or {@code remove} in XML later has no effect until a
 * restart. For an exact, eagerly created {@code ModifyPopulatorList} whose only edited
 * properties are {@code add}/{@code remove} references, the directive's own effect is
 * reversed (the old {@code add} is taken out, the old {@code remove} is put back) and the
 * new one is applied the same way SAP does. On failure the target list, the directive and
 * the live definition are left exactly as they were.
 *
 * <p>Anything else (a {@code list} target change, subclasses, inherited or lazy
 * directives, {@code ListMergeDirective}) keeps the restart diagnostic.
 */
final class SapPopulatorDirectives {
    private SapPopulatorDirectives() { }

    static final String MODIFY = "de.hybris.platform.converters.impl.ModifyPopulatorList";
    private static final List<String> EDITABLE = List.of("add", "remove");

    /** An accepted edit, applied after new beans of the same save are registered. */
    record Edit(String beanName, Object next) { }

    /** True when only add/remove refs of an exact, eager, created directive changed. */
    static boolean supported(Object factory, String name, Object previous, Object next) {
        try {
            if (previous == null || next == null) return false;
            if (!MODIFY.equals(SpringReflection.getBeanClassName(previous))
                    || !MODIFY.equals(SpringReflection.getBeanClassName(next))) return false;
            if (SpringReflection.getParentName(previous) != null || SpringReflection.getParentName(next) != null) return false;
            if (Boolean.TRUE.equals(call(previous, "isLazyInit")) || Boolean.TRUE.equals(call(next, "isLazyInit"))) return false;
            Object live = SpringReflection.getExistingSingleton(factory, name);
            if (live == null || !MODIFY.equals(live.getClass().getName())) return false;
            for (String property : EDITABLE) {
                Object value = rawValue(next, property);
                if (value != null && !value.getClass().getName().endsWith("RuntimeBeanReference")) return false;
            }
            XmlRawValueComparator comparator = new XmlRawValueComparator(next.getClass().getClassLoader());
            return comparator.verifiedStandardDefinition(withEditable(previous, next))
                    .equals(comparator.verifiedStandardDefinition(next));
        } catch (Exception | LinkageError unverifiable) {
            return false;
        }
    }

    /** Reverses the directive's old effect and applies the new one; all or nothing. */
    static void apply(Object factory, Edit edit) throws Exception {
        Object directive = SpringReflection.getExistingSingleton(factory, edit.beanName());
        if (directive == null) throw new IllegalStateException("directive is not created");
        Object target = call(directive, "getList");
        @SuppressWarnings("unchecked")
        List<Object> populators = (List<Object>) call(target, "getPopulators");
        Object oldAdd = call(directive, "getAdd"), oldRemove = call(directive, "getRemove");
        Object newAdd = resolve(factory, rawValue(edit.next(), "add"));
        Object newRemove = resolve(factory, rawValue(edit.next(), "remove"));
        List<Object> before = new ArrayList<>(populators);
        try {
            if (oldAdd != null) populators.remove(oldAdd);
            if (oldRemove != null && !populators.contains(oldRemove)) populators.add(oldRemove);
            if (newRemove != null) populators.remove(newRemove);
            if (newAdd != null) populators.add(newAdd);
            set(directive, "setAdd", newAdd);
            set(directive, "setRemove", newRemove);
            Object definition = SpringReflection.getBeanDefinition(factory, edit.beanName());
            Object values = call(definition, "getPropertyValues");
            for (String property : EDITABLE) {
                Object value = rawValue(edit.next(), property);
                if (value == null) call(values, "removePropertyValue", property);
                else call(values, "add", property, value);
            }
            call(factory, "clearMetadataCache");
        } catch (Exception | Error failure) {
            populators.clear();
            populators.addAll(before);
            set(directive, "setAdd", oldAdd);
            set(directive, "setRemove", oldRemove);
            throw failure;
        }
    }

    private static Object resolve(Object factory, Object reference) throws Exception {
        if (reference == null) return null;
        return call(factory, "getBean", (String) call(reference, "getBeanName"));
    }

    private static Object withEditable(Object previous, Object next) throws Exception {
        Object copy = call(previous, "cloneBeanDefinition");
        Object values = call(copy, "getPropertyValues");
        for (String property : EDITABLE) {
            Object value = rawValue(next, property);
            if (value == null) call(values, "removePropertyValue", property);
            else call(values, "add", property, value);
        }
        return copy;
    }

    private static Object rawValue(Object definition, String property) throws Exception {
        Object holder = call(call(definition, "getPropertyValues"), "getPropertyValue", property);
        return holder == null ? null : call(holder, "getValue");
    }

    private static void set(Object target, String setter, Object value) throws Exception {
        for (Method method : target.getClass().getMethods())
            if (method.getName().equals(setter) && method.getParameterCount() == 1) { method.invoke(target, value); return; }
        throw new NoSuchMethodException(setter);
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] types = method.getParameterTypes();
            boolean fits = true;
            for (int i = 0; i < args.length && fits; i++) fits = args[i] == null || types[i].isInstance(args[i]);
            if (!fits) continue;
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                throw failure;
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }
}
