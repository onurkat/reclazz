/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.platform;

/** Optional platform registration around one XML singleton change, in its exact owner. */
@FunctionalInterface
public interface XmlBeanLifecycle {
    XmlBeanLifecycle NONE = (context, name, previous) -> (current, applied) -> { };

    /** Called before setters/destruction; previous is null for a new bean. */
    Pending before(Object context, String name, Object previous);

    @FunctionalInterface
    interface Pending {
        /** Called once after all setters or recreation/restoration, including failure. */
        void complete(Object current, boolean applied);
    }
}
