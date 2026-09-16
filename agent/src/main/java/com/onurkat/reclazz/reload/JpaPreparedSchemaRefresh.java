/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/** Hibernate validation against an already prepared schema, without selecting a DDL action. */
final class JpaPreparedSchemaRefresh {
    private static final String PENDING_REASON = "new entity prepared-schema mapping is not installed; check validation or restart";
    private static final String FACTORY = "org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean";
    private static final Map<Class<?>, Pending> PENDING = Collections.synchronizedMap(new WeakHashMap<>());
    private record Pending(byte[] metadata, List<String> physical, WeakReference<Object> factory) { }
    private JpaPreparedSchemaRefresh() { }

    static void apply(String name, Class<?> entity, Object bean, Field nativeField, byte[] bytecode) {
        List<String> managed = null;
        boolean added = false, installed = false;
        try {
            String problem = policyProblem(bean);
            if (problem != null) { refused(name, problem); return; }
            Object info = bean.getClass().getMethod("getPersistenceUnitInfo").invoke(bean);
            @SuppressWarnings("unchecked") List<String> names = (List<String>) info.getClass()
                    .getMethod("getManagedClassNames").invoke(info);
            managed = names;
            if (bytecode != null) PENDING.put(entity, new Pending(metadata(bytecode), physicalMetadata(entity), new WeakReference<>(bean)));
            if (!managed.contains(name)) { managed.add(name); added = true; }
            var create = bean.getClass().getDeclaredMethod("createNativeEntityManagerFactory");
            create.setAccessible(true);
            Object fresh = create.invoke(bean);
            installed = install(entity, bean, nativeField, fresh);
            if (!installed) throw new IllegalStateException("validated factory does not map " + name);
            mapped(name, entity);
            StatusReporter.success(name + ": rebuilt persistence unit '"
                    + bean.getClass().getMethod("getPersistenceUnitName").invoke(bean)
                    + "' after validating the prepared schema; no schema action was changed."
                    + " The previous native factory was retired.");
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null) root = root.getCause();
            StatusReporter.warn("New entity " + name + " validation failed; the previous factory is retained: "
                    + root + ". Prepare the schema and compile a body-only edit with the original mapping to retry.");
            RestartLedger.note(name, PENDING_REASON);
        } finally {
            if (!installed && added) managed.remove(name);
        }
    }

    /** Check the candidate before touching the live field. Failed candidates must not leak. */
    static boolean install(Class<?> entity, Object bean, Field nativeField, Object fresh) throws Exception {
        boolean swapped = false;
        try {
            if (!JpaSchemaAdvice.managesEntity(fresh, entity)) return false;
            Object old = nativeField.get(bean);
            nativeField.set(bean, fresh);
            swapped = true;
            if (old != null) {
                try { old.getClass().getMethod("close").invoke(old); }
                catch (Throwable closeFailure) {
                    StatusReporter.warn("Prepared entity mapping installed, but retiring the previous factory failed: "
                            + closeFailure);
                }
            }
            return true;
        } finally {
            if (!swapped && fresh != null) fresh.getClass().getMethod("close").invoke(fresh);
        }
    }

    static String policyProblem(Object bean) throws Exception {
        // Custom factory/provider callbacks can alter bootstrap semantics or write schema.
        if (!FACTORY.equals(bean.getClass().getName())) return "custom persistence factory is outside prepared-schema support";
        Object provider = bean.getClass().getMethod("getPersistenceProvider").invoke(bean);
        if (provider == null || !Set.of("org.hibernate.jpa.HibernatePersistenceProvider",
                "org.springframework.orm.jpa.vendor.SpringHibernateJpaPersistenceProvider").contains(provider.getClass().getName()))
            return "prepared-schema refresh requires the standard Hibernate provider";
        Object info = bean.getClass().getMethod("getPersistenceUnitInfo").invoke(bean);
        Map<?, ?> unit = (Map<?, ?>) info.getClass().getMethod("getProperties").invoke(info);
        Map<?, ?> overrides = (Map<?, ?>) bean.getClass().getMethod("getJpaPropertyMap").invoke(bean);
        Object nativeFactory = bean.getClass().getMethod("getNativeEntityManagerFactory").invoke(bean);
        Map<?, ?> current = (Map<?, ?>) nativeFactory.getClass().getMethod("getProperties").invoke(nativeFactory);
        boolean configured = false;
        for (Map<?, ?> values : List.of(unit, overrides, current)) {
            Object action = values.get("hibernate.hbm2ddl.auto");
            if (action != null && !"validate".equals(action.toString().trim()))
                return "schema action changed from validate";
            if (values != current && action != null) configured = true;
            for (var entry : values.entrySet()) {
                if (entry.getValue() == null) continue;
                String key = String.valueOf(entry.getKey());
                if (key.startsWith("hibernate.hbm2ddl.auto.")
                        || key.equals("hibernate.schema_management_tool")
                        || key.equals("hibernate.hbm2ddl.schema_filter_provider")
                        || key.startsWith("jakarta.persistence.schema-generation.database.action")
                        || key.startsWith("javax.persistence.schema-generation.database.action")
                        || key.startsWith("jakarta.persistence.schema-generation.scripts.action")
                        || key.startsWith("javax.persistence.schema-generation.scripts.action"))
                    return "conflicting or custom schema setting " + key;
            }
        }
        return configured ? null : "no explicit validate action in the factory or persistence unit";
    }

    static void retry(String name, byte[] bytecode) {
        List<Map.Entry<Class<?>, Pending>> candidates = new ArrayList<>();
        synchronized (PENDING) { PENDING.forEach((type, pending) -> candidates.add(Map.entry(type, pending))); }
        for (var entry : candidates) {
            Class<?> type = entry.getKey(); Pending pending = entry.getValue();
            if (!type.getName().equals(name)) continue;
            try {
                Object bean = pending.factory.get();
                if (bean == null || bean != JpaMappingRefresh.soleFactoryBean()) { PENDING.remove(type); continue; }
                if (!Arrays.equals(pending.metadata, metadata(bytecode))) {
                    refused(name, "pending entity metadata changed after class loading; restart is required");
                    continue;
                }
                if (!pending.physical.equals(physicalMetadata(type))) {
                    refused(name, "loaded entity metadata has not returned to the original mapping; restart is required");
                    continue;
                }
                JpaMappingRefresh.applyForNewEntity(name, type, bytecode);
            } catch (Throwable failure) { refused(name, "pending mapping could not be verified: " + failure); }
        }
    }

    private static List<String> physicalMetadata(Class<?> type) {
        // A successful companion dispatch update need not mean native redefinition
        // succeeded. Hibernate reads reflection, so verify that side independently.
        List<String> result = new ArrayList<>();
        result.add(type.toGenericString() + annotations(type));
        for (var field : type.getDeclaredFields()) {
            if (!InjectedNames.isInjected(field.getName())) result.add(field.toGenericString() + annotations(field));
        }
        for (var method : type.getDeclaredMethods()) {
            if (!InjectedNames.isInjected(method.getName())) result.add(method.toGenericString() + annotations(method));
        }
        for (var constructor : type.getDeclaredConstructors()) result.add(constructor.toGenericString() + annotations(constructor));
        Collections.sort(result);
        return result;
    }
    private static String annotations(java.lang.reflect.AnnotatedElement element) {
        return Arrays.stream(element.getDeclaredAnnotations()).map(Object::toString).sorted()
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static byte[] metadata(byte[] bytes) {
        // Stable declaration/annotation fingerprint. Bodies and debug details may change;
        // physical fields, access mode and mapping annotations must match the loaded candidate.
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(writer, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }
    static void mapped(String name, Class<?> entity) {
        PENDING.remove(entity);
        RestartLedger.resolve(name, PENDING_REASON);
    }
    private static void refused(String name, String reason) {
        StatusReporter.warn("New entity " + name + " was not mapped: " + reason + ".");
        RestartLedger.note(name, PENDING_REASON);
    }
}
