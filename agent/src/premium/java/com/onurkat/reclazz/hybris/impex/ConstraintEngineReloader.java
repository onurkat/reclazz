/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris.impex;

import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import java.util.Date;
import java.util.List;

/** Measures persisted native constraint effects, without parsing ImpEx headers. */
final class ConstraintEngineReloader {
    private static final String SUBJECT = "SAP validation engine";
    private static final String REASON = "Persisted constraints could not refresh the validation engine; reload it manually or restart";
    private static final String SEARCH = "de.hybris.platform.servicelayer.search.";
    private static final String VALIDATION = "de.hybris.platform.validation.services.ValidationService";

    private ConstraintEngineReloader() { }

    private record Root(long count, Long modifiedMillis) { }
    record Snapshot(Object context, ClassLoader loader, Object search, Object validation,
                    List<Root> roots) { }

    static Snapshot before(Object context, ClassLoader loader) {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(loader);
            return capture(context, loader);
        } catch (Exception | LinkageError unavailable) {
            skipped();
            return null;
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    static void after(Snapshot before, Object importResult, String file) {
        if (before == null) return;
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(before.loader);
            if (!Boolean.TRUE.equals(importResult.getClass().getMethod("isFinished").invoke(importResult))) return;
            Snapshot after = capture(before.context, before.loader);
            if (before.search != after.search || before.validation != after.validation) {
                skipped();
                return;
            }
            if (before.roots.equals(after.roots)) return;
            try {
                Class.forName(VALIDATION, false, before.loader).getMethod("reloadValidationEngine")
                        .invoke(before.validation);
                RestartLedger.resolve(SUBJECT, REASON);
                StatusReporter.info("Validation engine reloaded after ImpEx changed constraints: " + file);
            } catch (Exception | LinkageError failed) {
                RestartLedger.note(SUBJECT, REASON);
                StatusReporter.warn("ImpEx imported, but the validation engine could not reload; reload it manually or restart.");
            }
        } catch (Exception | LinkageError unavailable) {
            skipped();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private static void skipped() {
        // Native exceptions can include row data. Do not expose their messages.
        StatusReporter.info("Validation engine refresh skipped: native constraint state could not be inspected.");
    }

    private static Snapshot capture(Object context, ClassLoader loader) throws Exception {
        var bean = context.getClass().getMethod("getBean", String.class);
        Object search = bean.invoke(context, "flexibleSearchService");
        Object validation = bean.invoke(context, "validationService");
        if (search == null || validation == null
                || !Class.forName(VALIDATION, false, loader).isInstance(validation)) {
            throw new IllegalStateException("Native service unavailable");
        }
        return new Snapshot(context, loader, search, validation,
                List.of(root(search, loader, "AbstractConstraint"), root(search, loader, "ConstraintGroup")));
    }

    private static Root root(Object service, ClassLoader loader, String type) throws Exception {
        Class<?> queryType = Class.forName(SEARCH + "FlexibleSearchQuery", false, loader);
        Object query = queryType.getConstructor(String.class)
                .newInstance("SELECT COUNT({pk}),MAX({modifiedtime}) FROM {" + type + "}");
        queryType.getMethod("setDisableSearchRestrictions", boolean.class).invoke(query, true);
        queryType.getMethod("setResultClassList", List.class).invoke(query, List.of(Long.class, Date.class));
        Object result = Class.forName(SEARCH + "FlexibleSearchService", false, loader)
                .getMethod("search", queryType).invoke(service, query);
        Object rows = Class.forName(SEARCH + "SearchResult", false, loader).getMethod("getResult").invoke(result);
        if (!(rows instanceof List<?> values) || values.size() != 1
                || !(values.get(0) instanceof List<?> row) || row.size() != 2
                || !(row.get(0) instanceof Long count) || count < 0
                || !(row.get(1) == null || row.get(1) instanceof Date)
                || ((count == 0) != (row.get(1) == null))) {
            throw new IllegalStateException("Unexpected native aggregate shape");
        }
        return new Root(count, row.get(1) == null ? null : ((Date) row.get(1)).getTime());
    }
}
