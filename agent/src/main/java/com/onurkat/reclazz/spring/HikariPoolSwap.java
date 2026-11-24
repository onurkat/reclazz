/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.util.Reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Points a running HikariDataSource at a changed URL or changed credentials without replacing the bean.
 *
 * <p>The DataSource object keeps its identity, so every consumer that holds it (JdbcTemplate, transaction managers,
 * an EntityManagerFactory) needs no reinjection. Inside it, a new pool is built from the new settings and proven with a
 * real borrow before anything is switched. The switch happens under the monitor HikariDataSource's own lazy
 * {@code getConnection} uses. Connections already borrowed keep working and return to the old pool; the old pool's idle
 * connections are evicted at once, and the old pool is shut down only once nothing is borrowed from it, because
 * {@code HikariPool.shutdown()} aborts in-use connections (read from the 4.x and 5.x bytecode).
 *
 * <p>JMX and metrics registrations carry the pool name, which the old and new pool share. The new pool is therefore built
 * with both turned off and gets them once the old pool, whose shutdown unregisters its own, is gone.
 *
 * <p>Everything is reflective and keyed on Hikari's own names; any member that is missing or refuses makes this return a
 * reason, and nothing is changed. Passwords are never part of a reason or a log line.
 */
final class HikariPoolSwap {
    private HikariPoolSwap() { }

    static final String DATA_SOURCE = "com.zaxxer.hikari.HikariDataSource";
    private static final String CONFIG = "com.zaxxer.hikari.HikariConfig";
    private static final String POOL = "com.zaxxer.hikari.pool.HikariPool";
    /** Longest the new pool's first borrow may take inside the reload. */
    static final long VALIDATE_MILLIS = 10_000;
    /** Longest the old pool waits for borrowed connections before it is shut down anyway. */
    static long drainMillis = TimeUnit.MINUTES.toMillis(30);

    /** The HikariDataSource behind this bean, unwrapping JDBC wrappers; null when it is not one. */
    static Object unwrap(Object bean) {
        if (bean == null) return null;
        if (isA(bean.getClass(), DATA_SOURCE)) return bean;
        try {
            Class<?> type = Class.forName(DATA_SOURCE, false, bean.getClass().getClassLoader());
            if (bean instanceof javax.sql.DataSource wrapper && wrapper.isWrapperFor(type)) return wrapper.unwrap(type);
        } catch (Throwable notHikari) {
            // Not on this loader, or the wrapper will not say.
        }
        return null;
    }

    static boolean isA(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) if (c.getName().equals(name)) return true;
        return false;
    }

    static String jdbcUrl(Object hikari) { return (String) call(hikari, "getJdbcUrl"); }
    static String username(Object hikari) { return (String) call(hikari, "getUsername"); }
    static String password(Object hikari) { return (String) call(hikari, "getPassword"); }
    static String driverClassName(Object hikari) { return (String) call(hikari, "getDriverClassName"); }

    /** What happened; {@code refused} is null when the pool now uses the new settings. */
    record Outcome(String refused, int stillBorrowed, boolean started) {
        static Outcome refuse(String why) { return new Outcome(why, 0, false); }
    }

    /**
     * Switches {@code hikari} to the given settings. {@code null} keeps a setting as it is.
     *
     * <p>The new pool is built from a copy of the configuration, so the old pool, which reads its credentials from
     * {@code hikari} for every connection it opens, never sees the new values while it still serves: a refused change
     * leaves it exactly as it was. Only after the switch do {@code hikari}'s own settings take the new values, with the
     * old pool's minimum idle set to 0 so it opens nothing more while it drains.
     */
    static Outcome swap(Object hikari, String url, String username, String password) {
        try {
            if (hikari.getClass().getName().contains("$$"))
                return Outcome.refuse("the DataSource bean is a class proxy, whose own fields are not the pool's");
            Class<?> dataSourceType = declaring(hikari.getClass(), DATA_SOURCE);
            Class<?> configType = declaring(hikari.getClass(), CONFIG);
            if (dataSourceType == null || configType == null) return Outcome.refuse("not a HikariDataSource");
            Field sealed = field(configType, "sealed");
            Field pool = field(dataSourceType, "pool");
            Field fastPath = field(dataSourceType, "fastPathPool");
            Field registerMbeans = field(configType, "isRegisterMbeans");
            Field trackerFactory = field(configType, "metricsTrackerFactory");
            Field metricRegistry = field(configType, "metricRegistry");
            Field healthChecks = field(configType, "healthCheckRegistry");
            if (fastPath.get(hikari) != null)
                return Outcome.refuse("the pool was started from a HikariConfig in its constructor, so its pool is fixed");
            if (Boolean.TRUE.equals(call(hikari, "isClosed"))) return Outcome.refuse("the pool is closed");
            if (call(hikari, "getDataSource") != null || call(hikari, "getDataSourceClassName") != null
                    || call(hikari, "getDataSourceJNDI") != null)
                return Outcome.refuse("the pool takes its connections from a DataSource, not from a JDBC URL");
            if (healthChecks.get(hikari) != null)
                return Outcome.refuse("the pool registers health checks under its name, which a second pool cannot share");

            Object old;
            Object fresh;
            synchronized (hikari) {
                old = pool.get(hikari);
                if (old == null) {
                    // Never used yet: the first getConnection builds the pool from these settings.
                    String oldUrl = jdbcUrl(hikari), oldUser = username(hikari), oldPassword = password(hikari);
                    boolean wasSealed = sealed.getBoolean(hikari);
                    sealed.setBoolean(hikari, false);
                    try { apply(hikari, url, username, password); }
                    catch (Throwable refusedSetting) {
                        restore(hikari, oldUrl, oldUser, oldPassword);
                        return Outcome.refuse("the pool did not accept the new settings");
                    } finally { sealed.setBoolean(hikari, wasSealed); }
                    return new Outcome(null, 0, false);
                }
                Object copy = configType.getConstructor().newInstance();
                configType.getMethod("copyStateTo", configType).invoke(hikari, copy);
                try { apply(copy, url, username, password); }
                catch (Throwable refusedSetting) { return Outcome.refuse("the pool did not accept the new settings"); }
                boolean mbeans = registerMbeans.getBoolean(copy);
                Object factory = trackerFactory.get(copy), registry = metricRegistry.get(copy);
                // The old pool still holds the JMX and metrics names; the new one gets them once the old one is gone.
                registerMbeans.setBoolean(copy, false);
                trackerFactory.set(copy, null);
                metricRegistry.set(copy, null);
                // No fail-fast connect in the constructor: the bounded borrow below is the one proof, inside VALIDATE_MILLIS.
                call(copy, "setInitializationFailTimeout", -1L);
                try {
                    fresh = newPool(hikari, copy);
                } catch (Throwable unreachable) {
                    fresh = null;
                }
                if (fresh == null || !borrows(fresh)) {
                    if (fresh != null) shutdownQuietly(fresh);
                    return Outcome.refuse("a connection with the new settings could not be opened");
                }
                // The new pool keeps its own copy; hikari's getters now report what it uses.
                // The retiring pool opens nothing more while it drains: its own configuration (hikari itself for the
                // first pool, an earlier copy after that) gets minimum idle 0 until it is shut down.
                Object oldConfig = poolConfig(old);
                int minimumIdle = (Integer) call(oldConfig, "getMinimumIdle");
                boolean wasSealed = sealed.getBoolean(hikari);
                sealed.setBoolean(hikari, false);
                try { apply(hikari, url, username, password); }
                catch (Throwable ignored) { /* the copy took them; only the getters lag */ }
                finally { sealed.setBoolean(hikari, wasSealed); }
                call(oldConfig, "setMinimumIdle", 0);
                pool.set(hikari, fresh);
                if (Boolean.TRUE.equals(call(hikari, "isClosed"))) {
                    // close() ran concurrently (it does not take this monitor) and shut the old pool; nobody would close this one.
                    shutdownQuietly(fresh);
                    return Outcome.refuse("the pool was closed while switching");
                }
                retire(old, fresh, hikari, pool, oldConfig, minimumIdle, copy, registerMbeans, mbeans, factory, registry);
            }
            int borrowed = (Integer) poolMethod(old, "getActiveConnections").invoke(old);
            return new Outcome(null, borrowed, true);
        } catch (Throwable notHikariShaped) {
            return Outcome.refuse("the pool's internals are not the HikariCP 4/5 ones this was written against");
        }
    }

    private static void apply(Object config, String url, String username, String password) {
        if (url != null) call(config, "setJdbcUrl", url);
        if (username != null) call(config, "setUsername", username);
        if (password != null) call(config, "setPassword", password);
    }

    private static Object newPool(Object hikari, Object config) throws Exception {
        Class<?> poolType = Class.forName(POOL, true, hikari.getClass().getClassLoader());
        Class<?> configType = Class.forName(CONFIG, false, hikari.getClass().getClassLoader());
        Constructor<?> constructor = poolType.getConstructor(configType);
        return constructor.newInstance(config);
    }

    private static boolean borrows(Object pool) {
        try (java.sql.Connection connection = (java.sql.Connection) poolMethod(pool, "getConnection", long.class)
                .invoke(pool, VALIDATE_MILLIS)) {
            return connection != null;
        } catch (Throwable failed) {
            return false;
        }
    }

    /** Old pools of each DataSource still draining; the last one to finish hands the monitoring names to the live pool. */
    private static final java.util.Map<Object, java.util.concurrent.atomic.AtomicInteger> DRAINING =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Grace before the first "nothing borrowed" reading: a caller that read the old pool just before the switch borrows in it. */
    static long graceMillis = 1_000;

    /** Evicts the old pool's idle connections now and shuts it down once nothing has been borrowed from it for a while. */
    private static void retire(Object old, Object fresh, Object hikari, Field pool, Object oldConfig, int minimumIdle, Object copy,
                               Field registerMbeans, boolean mbeans, Object factory, Object registry) {
        try { poolMethod(old, "softEvictConnections").invoke(old); } catch (Throwable ignored) { }
        synchronized (hikari) { DRAINING.computeIfAbsent(hikari, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet(); }
        Thread drain = new Thread(() -> {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(drainMillis);
            try {
                Thread.sleep(graceMillis);
                Method active = poolMethod(old, "getActiveConnections");
                int quiet = 0;
                while (quiet < 3 && System.nanoTime() < deadline) {
                    quiet = (Integer) active.invoke(old) == 0 ? quiet + 1 : 0;
                    if (quiet < 3) Thread.sleep(200);
                }
            } catch (Throwable stop) {
                // Shut down below either way.
            }
            shutdownQuietly(old);
            synchronized (hikari) {
                // hikari's own getters report the user's minimum idle again (a copy's value dies with its pool).
                try { if (oldConfig == hikari) call(hikari, "setMinimumIdle", minimumIdle); } catch (Throwable ignored) { }
                // JMX and metrics names are shared by every pool of this DataSource: only once the last draining pool is
                // gone is the name free, so whichever retirement finishes last registers the pool that is live then.
                if (DRAINING.get(hikari).decrementAndGet() > 0) return;
                try {
                    Object live = pool.get(hikari);
                    if (live == null || Boolean.TRUE.equals(call(hikari, "isClosed"))) return;
                    if (factory != null) poolMethod(live, "setMetricsTrackerFactory", Class.forName(
                            "com.zaxxer.hikari.metrics.MetricsTrackerFactory", false, live.getClass().getClassLoader()))
                            .invoke(live, factory);
                    else if (registry != null) poolMethod(live, "setMetricRegistry", Object.class).invoke(live, registry);
                    if (mbeans) {
                        // The live pool's own configuration is a copy built with JMX off.
                        registerMbeans.setBoolean(poolConfig(live), true);
                        Method handle = Reflect.findMethod(live.getClass(), "handleMBeans", live.getClass(), boolean.class);
                        if (handle != null) { handle.setAccessible(true); handle.invoke(live, live, true); }
                    }
                } catch (Throwable notRegistered) {
                    // The pool works without them; only the monitoring entry is missing.
                }
            }
        }, "reclazz-hikari-retire");
        drain.setDaemon(true);
        drain.start();
    }

    private static Object poolConfig(Object pool) throws Exception {
        for (Class<?> c = pool.getClass(); c != null; c = c.getSuperclass()) {
            try { Field config = c.getDeclaredField("config"); config.setAccessible(true); return config.get(pool); }
            catch (NoSuchFieldException next) { /* up */ }
        }
        throw new NoSuchFieldException("config");
    }

    private static void shutdownQuietly(Object pool) {
        try { poolMethod(pool, "shutdown").invoke(pool); } catch (Throwable ignored) { }
    }

    private static void restore(Object hikari, String url, String user, String password) {
        try { call(hikari, "setJdbcUrl", url); } catch (Throwable ignored) { }
        try { call(hikari, "setUsername", user); } catch (Throwable ignored) { }
        try { call(hikari, "setPassword", password); } catch (Throwable ignored) { }
    }

    private static Method poolMethod(Object pool, String name, Class<?>... parameters) throws NoSuchMethodException {
        return pool.getClass().getMethod(name, parameters);
    }

    private static Class<?> declaring(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) if (c.getName().equals(name)) return c;
        return null;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object call(Object target, String name, Object... arguments) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            if (arguments.length == 1 && !fits(method.getParameterTypes()[0], arguments[0])) continue;
            try {
                return method.invoke(target, arguments);
            } catch (java.lang.reflect.InvocationTargetException failed) {
                throw new IllegalStateException(failed.getCause() == null ? "failed" : failed.getCause().getClass().getSimpleName());
            } catch (IllegalAccessException denied) {
                throw new IllegalStateException(denied);
            }
        }
        return null;
    }

    private static boolean fits(Class<?> parameter, Object argument) {
        if (argument instanceof String) return parameter == String.class;
        if (argument instanceof Integer) return parameter == int.class;
        if (argument instanceof Long) return parameter == long.class;
        return !parameter.isPrimitive() && (argument == null || parameter.isInstance(argument));
    }

    static boolean same(String a, String b) { return Objects.equals(a, b); }
}
