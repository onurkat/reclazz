/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Rebuilds the persistence unit after a reload changed an entity's persistent
 * fields, in the one configuration where that is both possible and complete.
 *
 * <p>The mechanism is Spring's own indirection, not a provider proxy. Spring's
 * {@code AbstractEntityManagerFactoryBean} keeps the provider's
 * EntityManagerFactory in its private field {@code nativeEntityManagerFactory}
 * and hands the application a client proxy that resolves that field on every
 * call, so every injected EntityManagerFactory, every {@code @PersistenceContext}
 * EntityManager and every Spring Data repository follows a swap of the field
 * with no bean recreated. The rebuild reflectively invokes the bean's protected
 * {@code createNativeEntityManagerFactory()}, which runs the full provider
 * bootstrap including the {@code hbm2ddl.auto} schema action, swaps the field,
 * and closes the old factory so it does not leak.
 *
 * <p>Measured on Spring Boot 3.3.4 with Hibernate 6.5.3 and H2, on JetBrains
 * Runtime 25 with {@code -XX:+AllowEnhancedClassRedefinition}, adding a field
 * {@code currency} to an entity: the rebuild took 95ms on a request thread,
 * {@code ddl-auto=update} created the CURRENCY column, the new metamodel
 * carried the field, and a persist-flush-clear-find round trip through both an
 * EntityManager and a repository injected before the swap returned the written
 * value. A repository recreate was not needed for that round trip, because the
 * repository resolves its EntityManager through the swapped field at call time.
 *
 * <p>The rebuild runs only when every one of these holds, and otherwise the
 * existing warning stands unchanged:
 *
 * <ul>
 *   <li>the agent was started with {@code jpaRefresh=true}, because closing
 *       every open persistence context is only acceptable when asked for;</li>
 *   <li>the VM has enhanced class redefinition, because on a stock JDK the
 *       loaded class never physically gains the field, so a rebuilt metamodel
 *       still could not see it;</li>
 *   <li>{@code hbm2ddl.auto} is update, create or create-drop, because those
 *       are the settings where the rebuild itself creates the column; at
 *       validate the rebuilt factory would refuse to exist, and at none the
 *       refreshed mapping would point at a column that is not there;</li>
 *   <li>the entity's owning factory bean is an
 *       {@code AbstractEntityManagerFactoryBean}, because the swap is of that
 *       class's private field.</li>
 * </ul>
 */
final class JpaMappingRefresh {

    /**
     * Appended to the warning only when everything else qualifies and the
     * opt-in is the one thing missing, so nobody is sent to a flag that would
     * then decline for a different reason.
     */
    static final String OPT_IN_HINT =
            " Start the agent with jpaRefresh=true to rebuild the persistence unit automatically.";

    private JpaMappingRefresh() {
    }

    /** What the refresh did, and what the warning should carry when it did not run. */
    record Result(boolean refreshed, String appendix) {
        static final Result DECLINED = new Result(false, "");
    }

    /**
     * Rebuild if every gate holds, and otherwise say nothing beyond what the
     * existing warning already says, except the opt-in hint in the single case
     * where the flag is genuinely all that is missing.
     */
    static Result apply(String className, Class<?> entityClass, JpaEntityChange.Change change) {
        try {
            if (!vmQualifies()) return Result.DECLINED;

            String ddlAuto = JpaSchemaAdvice.ddlAutoSetting(entityClass);
            if (!ddlAutoQualifies(ddlAuto)) return Result.DECLINED;

            Object factoryBean = owningFactoryBean(entityClass);
            if (factoryBean == null) return Result.DECLINED;

            if (!optedIn()) return new Result(false, OPT_IN_HINT);

            return rebuild(className, entityClass, change, factoryBean, ddlAuto);
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) root = root.getCause();
            // The warning that follows is still true: the mapping has the old
            // shape. The failed attempt is added so the developer does not
            // wonder why the promised rebuild did not happen.
            return new Result(false, " The automatic rebuild failed: " + root + ".");
        }
    }

    /**
     * Whether the schema action will create the column during the rebuild.
     *
     * <p>These three are the settings where a fresh bootstrap writes DDL. At
     * validate the fresh factory would fail its own startup against the
     * missing column, and at none or unset the refreshed mapping would map a
     * column that does not exist, which turns the next query into an error.
     */
    static boolean ddlAutoQualifies(String setting) {
        return setting != null && switch (setting) {
            case "update", "create", "create-drop" -> true;
            default -> false;
        };
    }

    private static boolean vmQualifies() {
        var probe = com.onurkat.reclazz.agent.ReclazzAgent.getProbeResult();
        return probe != null && probe.hasEnhancedRedefinition();
    }

    private static boolean optedIn() {
        var config = com.onurkat.reclazz.agent.ReclazzAgent.getConfig();
        return config != null && config.isJpaRefresh();
    }

    /**
     * The AbstractEntityManagerFactoryBean whose current native factory maps
     * this entity, or null when there is none, which includes every non-Spring
     * and non-JPA application.
     */
    private static Object owningFactoryBean(Class<?> entityClass) {
        for (Object context : ApplicationContextHolder.getAllContexts()) {
            try {
                Class<?> beanType = Class.forName(
                        "org.springframework.orm.jpa.AbstractEntityManagerFactoryBean",
                        false, context.getClass().getClassLoader());
                Method getBeansOfType = context.getClass().getMethod("getBeansOfType", Class.class);
                Object beans = getBeansOfType.invoke(context, beanType);
                if (!(beans instanceof Map<?, ?> map)) continue;
                for (Object bean : map.values()) {
                    Object nativeFactory = nativeFactoryField(bean).get(bean);
                    if (nativeFactory != null
                            && JpaSchemaAdvice.managesEntity(nativeFactory, entityClass)) {
                        return bean;
                    }
                }
            } catch (Throwable ignored) {
                // A context without Spring ORM has nothing to rebuild.
            }
        }
        return null;
    }

    private static Result rebuild(String className, Class<?> entityClass,
                                  JpaEntityChange.Change change, Object factoryBean,
                                  String ddlAuto) throws Exception {
        Field nativeField = nativeFactoryField(factoryBean);
        Class<?> declaring = nativeField.getDeclaringClass();
        Method create = declaring.getDeclaredMethod("createNativeEntityManagerFactory");
        create.setAccessible(true);

        long start = System.nanoTime();
        Object fresh = create.invoke(factoryBean);
        long wallMs = (System.nanoTime() - start) / 1_000_000;

        Object old = nativeField.get(factoryBean);
        nativeField.set(factoryBean, fresh);
        if (old != null) {
            old.getClass().getMethod("close").invoke(old);
        }

        if (!JpaSchemaAdvice.managesEntity(fresh, entityClass)) {
            // The swap already happened and is not undone: the fresh factory is
            // the one the schema action ran for, and putting the closed one
            // back would be worse. Saying so is what is left.
            return new Result(false, " The automatic rebuild ran in " + wallMs
                    + "ms but the new metamodel does not carry " + entityClass.getName()
                    + ", so the warning above still stands.");
        }

        String unit = unitName(factoryBean);
        StringBuilder msg = new StringBuilder(className).append(' ').append(change.describe())
                .append(": rebuilt persistence unit '").append(unit).append("' in ")
                .append(wallMs).append("ms and the new mapping carries it.");
        if (!change.added().isEmpty()) {
            msg.append(" The column was created by hbm2ddl.auto=").append(ddlAuto)
                    .append(" during the rebuild.");
        }
        msg.append(" Open persistence contexts from before the rebuild are closed.");
        StatusReporter.success(msg.toString());
        return new Result(true, "");
    }

    private static Field nativeFactoryField(Object factoryBean) throws NoSuchFieldException {
        for (Class<?> c = factoryBean.getClass(); c != null; c = c.getSuperclass()) {
            if ("org.springframework.orm.jpa.AbstractEntityManagerFactoryBean".equals(c.getName())) {
                Field field = c.getDeclaredField("nativeEntityManagerFactory");
                field.setAccessible(true);
                return field;
            }
        }
        throw new NoSuchFieldException(
                "not an AbstractEntityManagerFactoryBean: " + factoryBean.getClass().getName());
    }

    private static String unitName(Object factoryBean) {
        try {
            Object name = factoryBean.getClass().getMethod("getPersistenceUnitName")
                    .invoke(factoryBean);
            if (name != null && !name.toString().isBlank()) return name.toString();
        } catch (Throwable ignored) {
            // The name decorates the log line; the rebuild does not need it.
        }
        return "default";
    }
}
