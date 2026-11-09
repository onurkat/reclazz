/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.platform;

import java.util.Set;

/** Optional owner-local singleton identities whose dependency edges survive a bean refresh. */
@FunctionalInterface
public interface PreservedSingletons {
    PreservedSingletons NONE = owner -> Set.of();
    Set<String> names(Object owner) throws Exception;
}
