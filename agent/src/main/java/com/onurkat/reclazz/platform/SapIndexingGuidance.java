/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.ui.StatusReporter;
import java.util.HashSet;
import java.util.Set;

/** Advice about persisted documents, independent of the code reload receipt. */
public final class SapIndexingGuidance {
    private static final Set<String> CONTRACTS = Set.of(
            "de.hybris.platform.solrfacetsearch.provider.FieldValueProvider",
            "de.hybris.platform.solrfacetsearch.provider.ValueResolver",
            "de.hybris.platform.solrfacetsearch.provider.TypeValueResolver");

    private SapIndexingGuidance() { }

    /** Called only after a successful code reload. Never invokes indexing or loads an SDK class. */
    public static void afterReload(Class<?> type) {
        if (!isIndexingType(type)) return;
        StatusReporter.info("SAP indexing code " + type.getName() + " reloaded. "
                + "Reclazz did not reindex stored documents. Validate a subsequent indexing operation "
                + "and explicitly reindex affected documents if needed; affected indexes are not determined.");
    }

    static boolean isIndexingType(Class<?> type) {
        try {
            return matches(type, new HashSet<>());
        } catch (LinkageError | SecurityException unavailable) {
            return false;
        }
    }

    private static boolean matches(Class<?> type, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) return false;
        if (type.isInterface() && CONTRACTS.contains(type.getName())) return true;
        for (Class<?> contract : type.getInterfaces()) {
            if (matches(contract, visited)) return true;
        }
        return matches(type.getSuperclass(), visited);
    }
}
