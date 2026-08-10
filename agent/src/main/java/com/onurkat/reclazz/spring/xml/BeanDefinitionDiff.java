/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import java.util.ArrayList;
import java.util.List;

/**
 * Classification result produced by {@link XmlSafetyClassifier} when diffing
 * a newly parsed *-spring.xml against the live bean factory.
 *
 * Three outcomes per bean:
 * <ul>
 *   <li>{@link NewBean} — absent in the live factory, safe to register + instantiate</li>
 *   <li>{@link PropertyChange} — exists live, only {@code <property>} value differs,
 *       apply in place via reflection setter (no destroy / recreate — autowired
 *       consumers keep the same instance and see the new value on next call)</li>
 *   <li>{@link UnsafeChange} — requires a server restart (class changed, init-method,
 *       BeanPostProcessor, constructor-arg change, etc.)</li>
 * </ul>
 *
 * Bean removals are intentionally NOT detected — doing so would require the
 * reloader to remember the previous contents of each XML file, which trades
 * a stateless design for a narrow feature with low signal-to-noise.
 */
final class BeanDefinitionDiff {

    final List<NewBean> added = new ArrayList<>();
    final List<PropertyChange> propertyChanges = new ArrayList<>();
    final List<UnsafeChange> unsafe = new ArrayList<>();

    boolean hasChanges() {
        return !added.isEmpty() || !propertyChanges.isEmpty() || !unsafe.isEmpty();
    }

    static final class NewBean {
        final String beanName;
        final Object newBeanDefinition;

        NewBean(String beanName, Object newBeanDefinition) {
            this.beanName = beanName;
            this.newBeanDefinition = newBeanDefinition;
        }
    }

    static final class PropertyChange {
        final String beanName;
        final String propertyName;
        /** Raw (unresolved) value — may be a {@code TypedStringValue}, {@code RuntimeBeanReference}, etc. */
        final Object newRawValue;
        /** Needed by {@code BeanDefinitionValueResolver} to resolve the raw value in context. */
        final Object newBeanDefinition;

        PropertyChange(String beanName, String propertyName, Object newRawValue, Object newBeanDefinition) {
            this.beanName = beanName;
            this.propertyName = propertyName;
            this.newRawValue = newRawValue;
            this.newBeanDefinition = newBeanDefinition;
        }
    }

    static final class UnsafeChange {
        final String beanName;
        final String reason;

        UnsafeChange(String beanName, String reason) {
            this.beanName = beanName;
            this.reason = reason;
        }
    }
}
