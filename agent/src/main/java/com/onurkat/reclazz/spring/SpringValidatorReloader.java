/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.ApplicationContextHolder;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * Drops the validator's answer to "what does this class constrain".
 *
 * <p>Bean Validation works that out once per class and keeps it, so adding a
 * constraint to a field that was already there changes nothing at all: the
 * class reloads, the annotation is on it, and the request that should now be
 * rejected is still accepted. Measured on Spring Boot 3.3.4 with Hibernate
 * Validator 8, stock JDK 21:
 *
 * <pre>
 *   before        POST /form {"title":""} -> 200
 *   add @NotBlank reload succeeds, "Annotation change ... re-scanning"
 *   after         POST /form {"title":""} -> 200      (and nothing said why)
 * </pre>
 *
 * <p>The cache is {@code beanMetaDataCache} on Hibernate Validator's
 * {@code BeanMetaDataManagerImpl}, and getting to it is the awkward part: the
 * bean is Spring's {@code LocalValidatorFactoryBean}, the managers live in a
 * map on the provider's factory rather than in a field, and a validator built
 * earlier holds its own manager. So the walk descends through fields AND
 * through map values, and its bound is ownership rather than a path: nothing
 * outside {@code org.hibernate.validator} and {@code org.springframework}
 * is read from or cleared. That bound is the same one the method-security
 * refresh uses, and it is there for the same reason: a path is a guess about
 * one version, and this has to survive the next one by declining rather than
 * by wandering.
 *
 * <p>Emptying costs one re-read of the class's constraints on the next
 * validation. It says nothing when it works: a constraint that now applies is
 * the developer's own expectation.
 *
 * <p>Reached from the reload itself rather than from the Spring orchestrator,
 * because that orchestrator runs only for classes that are beans and the class
 * gaining a constraint almost never is: a request body is a plain object. The
 * contexts come from the same holder the Jackson flush uses, for the same
 * reason.
 */
public final class SpringValidatorReloader {

    private static final String VALIDATOR_FACTORY = "jakarta.validation.ValidatorFactory";

    /** The map that holds a class's constraints, under the one name it has. */
    private static final String METADATA_CACHE = "beanMetaDataCache";

    /** Deep enough for bean to factory to managers to manager to cache. */
    private static final int MAX_DEPTH = 5;

    /** How many map values are worth following before this stops being a walk. */
    private static final int MAX_MAP_VALUES = 64;

    private SpringValidatorReloader() {
    }

    /**
     * @return how many constraint caches held something and were emptied
     */
    public static int flush() {
        int cleared = 0;
        for (Object appContext : ApplicationContextHolder.getAllContexts()) {
            for (String name : SpringBeans.beanNamesForType(appContext, VALIDATOR_FACTORY)) {
                Object validator = SpringBeans.getBean(appContext, name);
                if (validator == null) continue;
                cleared += clearConstraintCaches(validator);
            }
        }
        return cleared;
    }

    static int clearConstraintCaches(Object target) {
        return clearConstraintCaches(target, 0,
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    private static int clearConstraintCaches(Object target, int depth,
                                             java.util.Set<Object> visited) {
        if (target == null || depth > MAX_DEPTH) return 0;
        if (!owned(target)) return 0;
        if (!visited.add(target)) return 0;

        int cleared = 0;
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Object value = read(field, target);
                if (value == null) continue;

                if (value instanceof Map<?, ?> map) {
                    if (METADATA_CACHE.equals(field.getName())) {
                        if (!map.isEmpty()) {
                            map.clear();
                            cleared++;
                        }
                        continue;
                    }
                    // The managers are values in a map, not fields, which is
                    // the level a field-only walk would stop one short of.
                    int seen = 0;
                    for (Object entry : map.values()) {
                        if (++seen > MAX_MAP_VALUES) break;
                        cleared += clearConstraintCaches(entry, depth + 1, visited);
                    }
                    continue;
                }
                cleared += clearConstraintCaches(value, depth + 1, visited);
            }
        }
        return cleared;
    }

    /** Only the validator's own objects, and the Spring bean that wraps them. */
    private static boolean owned(Object target) {
        String name = target.getClass().getName();
        return name.startsWith("org.hibernate.validator.")
                || name.startsWith("org.springframework.validation.");
    }

    private static Object read(Field field, Object target) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable notReadable) {
            return null;
        }
    }
}
