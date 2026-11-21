/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.RecordCodecs;
import com.onurkat.reclazz.spring.SpringRecordCaches;
import java.util.*;

/** Save-scoped repair plan. Holds application references only until that save's finally cleanup. */
public final class RecordNativeRepairs {
    private final List<Class<?>> cohort;
    private final List<JacksonEnumCaches.NamedRecordFlush> mappers;
    private final SpringRecordCaches.Prepared configurations;
    private final List<RecordCodecs.CacheReset> retainedCaches;
    private RecordNativeRepairs(List<Class<?>> cohort, List<JacksonEnumCaches.NamedRecordFlush> mappers,
                                SpringRecordCaches.Prepared configurations, List<RecordCodecs.CacheReset> retainedCaches) {
        this.cohort = cohort; this.mappers = mappers; this.configurations = configurations; this.retainedCaches = retainedCaches;
    }
    public static RecordNativeRepairs prepare(Collection<Class<?>> cohort, List<Object> contexts) throws ReflectiveOperationException {
        List<String> codecFailures = RecordCodecs.preflight();
        if (!codecFailures.isEmpty()) throw new IllegalStateException(String.join("; ", codecFailures));
        return new RecordNativeRepairs(List.copyOf(cohort), JacksonEnumCaches.prepareRecords(contexts),
                SpringRecordCaches.prepare(contexts, cohort), RecordCodecs.prepareCaches());
    }
    public boolean ownsConfiguration(Class<?> type) { return configurations.owns(type); }
    public Map<String,String> apply() {
        Map<String,String> failures = new LinkedHashMap<>(); boolean flushed = true;
        for (var mapper : mappers) {
            try { mapper.action().apply(); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                failures.put(mapper.surface(), com.onurkat.reclazz.ui.Failures.describe(failure)); flushed = false;
            }
        }
        for (var cache : retainedCaches) {
            try { cache.action().apply(); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                failures.put(cache.surface(), com.onurkat.reclazz.ui.Failures.describe(failure)); flushed = false;
            }
        }
        if (flushed) {
            List<String> codecFailures = RecordCodecs.refresh(cohort);
            if (!codecFailures.isEmpty()) failures.put("retained Jackson codecs", String.join("; ", codecFailures));
        } else failures.put("retained Jackson codecs", "mapper flush failed; retained snapshots were not rebuilt from stale mapper caches");
        failures.putAll(configurations.apply());
        return Map.copyOf(failures);
    }
}
