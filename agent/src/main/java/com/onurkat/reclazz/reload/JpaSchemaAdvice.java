/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.platform.ApplicationContextHolder;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * What a developer actually has to do about a field the persistence mapping did
 * not pick up.
 *
 * <p>The first version of that message told everyone the same thing: restart,
 * and add the column. That is right for one configuration and wrong for the two
 * others, and being confidently wrong about the next step is worse than being
 * vague.
 *
 * <ul>
 *   <li>With {@code hbm2ddl.auto} at update, create or create-drop, which is
 *       the usual development setting, a restart is the whole fix: Hibernate
 *       adds the column itself. Telling that developer to write DDL sends them
 *       to do work the framework was going to do.</li>
 *   <li>At validate, a restart does not fix anything, it <em>breaks the
 *       application</em>: Hibernate compares the mapping against the database
 *       at startup and refuses to start when a mapped column is missing. That
 *       developer needs to know the column has to exist <em>before</em> the
 *       restart, and today they would find out by watching the server fail to
 *       come up.</li>
 *   <li>At none, or unset, the restart is safe and the column is theirs.</li>
 * </ul>
 *
 * <p>Read from the running EntityManagerFactory through the JPA API alone, so
 * it works the same on Hibernate and on anything else that implements the
 * specification, and it declines quietly when it cannot tell rather than
 * guessing.
 */
final class JpaSchemaAdvice {

    private JpaSchemaAdvice() {
    }

    /**
     * @return the sentence to append to the warning, or the neutral one when
     *         the configuration cannot be read
     */
    static String forEntity(Class<?> entityClass) {
        return adviceFor(ddlAutoSetting(entityClass));
    }

    /**
     * The sentence for a schema setting, separated from finding it so the
     * wording can be held by a test without a running persistence unit.
     *
     * @param setting the configured value, or null when it could not be read.
     *                Spring Boot does not publish the property at all when it
     *                is none, and an application that sets nothing has nothing
     *                to publish either; both mean the same instruction.
     */
    static String adviceFor(String setting) {
        if (setting == null) {
            return " Restart, and add the column, to pick this up.";
        }

        return switch (setting) {
            case "update", "create", "create-drop", "create-only" ->
                    " A restart is enough here: this application runs with hbm2ddl.auto="
                            + setting + ", so Hibernate adds the column itself on the way up.";
            case "validate" ->
                    " Careful: this application runs with hbm2ddl.auto=validate, so it will"
                            + " refuse to start until that column exists. Add the column first,"
                            + " then restart.";
            default ->
                    " Restart, and add the column: this application runs with hbm2ddl.auto="
                            + setting + ", so nothing creates it for you.";
        };
    }

    /**
     * The schema setting of the persistence unit this entity belongs to.
     *
     * <p>An application can have more than one, so the entity's own unit is the
     * one that matters: asking any factory at random would give the right
     * answer only by luck. A factory that does not know this entity is skipped.
     *
     * <p>Package-visible because {@link JpaMappingRefresh} gates on the same
     * setting: rebuilding the unit only helps when the schema action will
     * create the column during the rebuild.
     */
    static String ddlAutoSetting(Class<?> entityClass) {
        for (Object context : ApplicationContextHolder.getAllContexts()) {
            for (Object factory : entityManagerFactories(context)) {
                if (!managesEntity(factory, entityClass)) continue;
                String setting = settingOf(factory);
                if (setting != null) return setting;
            }
        }
        return null;
    }

    private static java.util.List<Object> entityManagerFactories(Object context) {
        java.util.List<Object> found = new java.util.ArrayList<>();
        try {
            Class<?> emfType = Class.forName("jakarta.persistence.EntityManagerFactory",
                    false, context.getClass().getClassLoader());
            found.addAll(beansOfType(context, emfType));
        } catch (Throwable ignored) {
            // Jakarta absent; the javax-era name is the other possibility.
        }
        if (found.isEmpty()) {
            try {
                Class<?> emfType = Class.forName("javax.persistence.EntityManagerFactory",
                        false, context.getClass().getClassLoader());
                found.addAll(beansOfType(context, emfType));
            } catch (Throwable ignored) {
                // No JPA here at all, which is a fine answer.
            }
        }
        return found;
    }

    private static java.util.Collection<Object> beansOfType(Object context, Class<?> type) {
        try {
            Method getBeansOfType = context.getClass().getMethod("getBeansOfType", Class.class);
            Object beans = getBeansOfType.invoke(context, type);
            if (beans instanceof Map<?, ?> map) {
                return new java.util.ArrayList<>(map.values());
            }
        } catch (Throwable ignored) {
            // A context that will not answer is a context with nothing to say.
        }
        return java.util.List.of();
    }

    /** Whether this persistence unit is the one that maps the entity. */
    static boolean managesEntity(Object factory, Class<?> entityClass) {
        try {
            Object metamodel = factory.getClass().getMethod("getMetamodel").invoke(factory);
            Method entity = metamodel.getClass().getMethod("entity", Class.class);
            entity.setAccessible(true);
            return entity.invoke(metamodel, entityClass) != null;
        } catch (Throwable t) {
            // IllegalArgumentException from entity() means "not mine", which is
            // the answer; anything else is unreadable and treated the same way.
            return false;
        }
    }

    static String settingOf(Object factory) {
        try {
            Object properties = factory.getClass().getMethod("getProperties").invoke(factory);
            if (!(properties instanceof Map<?, ?> map)) return null;
            for (String key : new String[]{
                    "hibernate.hbm2ddl.auto",
                    "jakarta.persistence.schema-generation.database.action",
                    "javax.persistence.schema-generation.database.action"}) {
                Object value = map.get(key);
                if (value != null && !value.toString().isBlank()) {
                    return value.toString().trim().toLowerCase(java.util.Locale.ROOT);
                }
            }
        } catch (Throwable ignored) {
            // Reading configuration must never be the thing that breaks a reload.
        }
        return null;
    }
}
