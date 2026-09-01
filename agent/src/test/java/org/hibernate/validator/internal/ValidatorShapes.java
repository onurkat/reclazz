/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hibernate.validator.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hibernate Validator's own shapes, under its own package, because the package
 * is what the constraint refresh trusts.
 *
 * <p>The walk reads and clears nothing outside {@code org.hibernate.validator}
 * and {@code org.springframework.validation}, so a fake built anywhere else
 * would be rejected before it proved anything. The shape here is the one taken
 * off a live Boot 3.3 / Hibernate Validator 8 run: the managers are values in a
 * map rather than fields, which is the level a field-only walk stops one short
 * of. Hibernate Validator is deliberately not a test dependency, so there is no
 * name to collide with.
 */
public final class ValidatorShapes {

    private ValidatorShapes() {
    }

    /** BeanMetaDataManagerImpl: the class-to-constraints cache lives here. */
    public static class MetaDataManager {
        private final Map<Class<?>, Object> beanMetaDataCache = new ConcurrentHashMap<>();

        public Map<Class<?>, Object> cache() {
            return beanMetaDataCache;
        }
    }

    /** ValidatorFactoryImpl: the managers are values in a map. */
    public static class Factory {
        private final Map<Object, MetaDataManager> beanMetaDataManagers = new HashMap<>();

        public Factory(MetaDataManager... managers) {
            for (int i = 0; i < managers.length; i++) {
                beanMetaDataManagers.put("key" + i, managers[i]);
            }
        }
    }

    /** Two objects holding each other: the walk must not follow them forever. */
    public static class Cycle {
        private Cycle other;
        private final Map<Class<?>, Object> beanMetaDataCache = new HashMap<>();

        public static Cycle pair() {
            Cycle one = new Cycle();
            Cycle two = new Cycle();
            one.other = two;
            two.other = one;
            return one;
        }

        public Map<Class<?>, Object> cache() {
            return beanMetaDataCache;
        }
    }
}
