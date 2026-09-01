/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bean Validation resolves a class's constraints once and keeps them, so
 * adding {@code @NotBlank} to a field that was already there changed nothing:
 * measured on Boot 3.3 with Hibernate Validator 8, the request that should now
 * be rejected was still accepted, and nothing said why. Measured again after
 * this: 400, and 200 once the constraint is taken back off.
 *
 * <p>Reaching the cache is the awkward part, and the reason these tests exist.
 * The managers are values in a map rather than fields, so a walk that follows
 * only fields stops one level short of the thing it is looking for. What is
 * held here is that level, the depth and cycle bounds, and the ownership rule
 * that keeps the walk out of objects that are not the validator's.
 */
class SpringValidatorReloaderTest {

    @Test
    void theCacheBehindAMapValueIsReached() {
        var manager = new org.hibernate.validator.internal.ValidatorShapes.MetaDataManager();
        manager.cache().put(String.class, "stale constraints");
        var factory = new org.hibernate.validator.internal.ValidatorShapes.Factory(manager);

        assertEquals(1, SpringValidatorReloader.clearConstraintCaches(factory),
                "the managers are values in a map, which is where a field-only walk stops");
        assertTrue(manager.cache().isEmpty());
    }

    @Test
    void everyManagerIsReached() {
        var first = new org.hibernate.validator.internal.ValidatorShapes.MetaDataManager();
        var second = new org.hibernate.validator.internal.ValidatorShapes.MetaDataManager();
        first.cache().put(String.class, "stale");
        second.cache().put(Integer.class, "stale");

        assertEquals(2, SpringValidatorReloader.clearConstraintCaches(
                new org.hibernate.validator.internal.ValidatorShapes.Factory(first, second)));
        assertTrue(first.cache().isEmpty());
        assertTrue(second.cache().isEmpty());
    }

    /** An empty cache is not work done, and counting it would say it was. */
    @Test
    void anEmptyCacheIsNotCounted() {
        assertEquals(0, SpringValidatorReloader.clearConstraintCaches(
                new org.hibernate.validator.internal.ValidatorShapes.Factory(
                        new org.hibernate.validator.internal.ValidatorShapes.MetaDataManager())));
    }

    @Test
    void aCycleEndsTheWalkRatherThanTheJvm() {
        var one = org.hibernate.validator.internal.ValidatorShapes.Cycle.pair();
        one.cache().put(String.class, "stale");

        assertEquals(1, SpringValidatorReloader.clearConstraintCaches(one));
        assertTrue(one.cache().isEmpty());
    }

    /**
     * The bound is ownership: an object that is not the validator's is never
     * read from, whatever it holds and whatever it calls its fields.
     */
    @Test
    void nothingOutsideTheValidatorIsTouched() {
        NotTheValidator theirs = new NotTheValidator();
        theirs.beanMetaDataCache.put(String.class, "not ours");

        assertEquals(0, SpringValidatorReloader.clearConstraintCaches(theirs));
        assertFalse(theirs.beanMetaDataCache.isEmpty());
    }

    /** Same field name, same shape, different owner. */
    static class NotTheValidator {
        final Map<Class<?>, Object> beanMetaDataCache = new HashMap<>();
    }
}
