/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/** Owns superseded native factories for as long as their Spring unit is alive. */
final class JpaFactoryRetention {
    private static final int LIMIT = 5;
    private static final Map<Object, Pool> POOLS = new WeakHashMap<>();
    private static final Set<String> STOCK_OBSERVERS = Set.of(
            "org.hibernate.type.spi.TypeConfiguration",
            "org.hibernate.boot.beanvalidation.BeanValidationEventListener",
            "org.hibernate.internal.SessionFactoryImpl$IntegratorObserver",
            "org.hibernate.internal.SessionFactoryImpl$1IntegratorObserver",
            "org.hibernate.bytecode.internal.SessionFactoryObserverForBytecodeEnhancer",
            "org.hibernate.boot.internal.SessionFactoryObserverForNamedQueryValidation",
            "org.hibernate.boot.internal.SessionFactoryObserverForRegistration",
            "org.hibernate.boot.internal.SessionFactoryObserverForSchemaExport",
            "org.hibernate.jpa.boot.internal.ServiceRegistryCloser",
            "org.hibernate.jpa.boot.internal.EntityManagerFactoryBuilderImpl$ServiceRegistryCloser");

    static final class Hold extends Exception {
        Hold(String reason) { super(reason + "; restart to apply the mapping"); }
    }

    static final class Reservation implements AutoCloseable {
        private final Pool pool;
        private boolean published;
        private Reservation(Pool pool) { this.pool = pool; }

        void install(Object bean, Field field, Object fresh) throws IllegalAccessException {
            Object previous = field.get(bean);
            field.set(bean, fresh);
            pool.retained.addFirst(previous);
            published = true;
        }

        String receipt() {
            return " Requests already running finish on the previous persistence unit; new requests use the rebuilt one ("
                    + pool.count.get() + " of " + LIMIT + " retained until context close or restart).";
        }

        @Override public void close() {
            if (!published) pool.count.decrementAndGet();
            pool.lock.unlock();
        }
    }

    static Reservation reserve(Object bean, Field nativeField) throws Exception {
        Pool pool = poolFor(bean);
        pool.lock.lock();
        try {
            if (pool.closed) throw new Hold("the persistence unit is closing");
            if (pool.count.get() >= LIMIT) throw new Hold(LIMIT + " persistence rebuilds are retained");
            Object old = nativeField.get(bean);
            String reason = policy(bean, old);
            if (reason != null) throw new Hold(reason);
            pool.count.incrementAndGet();
            return new Reservation(pool);
        } catch (Throwable failure) {
            pool.lock.unlock();
            if (failure instanceof Hold hold) throw hold;
            if (failure instanceof Error error) throw error;
            throw new Hold("could not verify the persistence unit's configuration (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private static synchronized Pool poolFor(Object bean) throws Exception {
        Pool existing = POOLS.get(bean);
        if (existing != null) return existing;
        Field owner = field(bean.getClass(), "beanFactory");
        Object registry = owner.get(bean);
        if (registry == null) throw new Hold("the owning Spring bean factory is unavailable");
        Class<?> disposable = Class.forName("org.springframework.beans.factory.DisposableBean", false, registry.getClass().getClassLoader());
        Pool pool = new Pool();
        Object listener = Proxy.newProxyInstance(disposable.getClassLoader(), new Class<?>[]{disposable}, (self, method, args) -> {
            return switch (method.getName()) {
                case "destroy" -> { pool.close(); yield null; }
                case "equals" -> self == args[0];
                case "hashCode" -> System.identityHashCode(self);
                default -> "Reclazz persistence unit cleanup";
            };
        });
        Runtime.getRuntime().addShutdownHook(pool.hook);
        try {
            registry.getClass().getMethod("registerDisposableBean", String.class, disposable).invoke(registry,
                    "__reclazz$retainedPersistenceFactories." + java.util.UUID.randomUUID(), listener);
        } catch (ReflectiveOperationException failure) {
            Runtime.getRuntime().removeShutdownHook(pool.hook);
            throw new Hold("could not register cleanup on the owning Spring bean factory");
        }
        POOLS.put(bean, pool);
        return pool;
    }

    private static final class Pool {
        final ReentrantLock lock = new ReentrantLock();
        final AtomicInteger count = new AtomicInteger();
        final ArrayDeque<Object> retained = new ArrayDeque<>();
        final Thread hook = new Thread(this::close, "Reclazz-PersistenceCleanup");
        boolean closed;

        void close() {
            lock.lock();
            try {
                if (closed) return;
                closed = true;
                while (!retained.isEmpty()) {
                    Object old = retained.removeFirst();
                    try { old.getClass().getMethod("close").invoke(old); }
                    catch (ReflectiveOperationException | RuntimeException failure) {
                        StatusReporter.warn("Closing a retained persistence factory failed: " + failure);
                    }
                }
                count.set(0);
                if (Thread.currentThread() != hook) {
                    try { Runtime.getRuntime().removeShutdownHook(hook); }
                    catch (IllegalStateException shutdownInProgress) { /* The registered hook will see closed. */ }
                }
            } finally { lock.unlock(); }
        }
    }

    private static String policy(Object bean, Object old) throws Exception {
        if (old == null || !old.getClass().getName().equals("org.hibernate.internal.SessionFactoryImpl"))
            return "unknown persistence provider";
        ClassLoader loader = old.getClass().getClassLoader();
        String version = (String) Class.forName("org.hibernate.Version", false, loader).getMethod("getVersionString").invoke(null);
        if (!version.startsWith("5.6.") && !version.startsWith("6.") && !version.startsWith("7.")) return "unverified Hibernate version " + version;
        Object unit = bean.getClass().getMethod("getPersistenceUnitInfo").invoke(bean);
        for (Object source : new Object[]{old.getClass().getMethod("getProperties").invoke(old),
                bean.getClass().getMethod("getJpaPropertyMap").invoke(bean),
                unit == null ? Map.of() : unit.getClass().getMethod("getProperties").invoke(unit)}) {
            if (!(source instanceof Map<?, ?> settings)) return "unreadable persistence settings";
            String reason = settingProblem(settings);
            if (reason != null) return reason;
        }
        Object cache = old.getClass().getMethod("getCache").invoke(old);
        Class<?> caches = Class.forName("org.hibernate.cache.spi.CacheImplementor", false, loader);
        Object region = caches.getMethod("getRegionFactory").invoke(cache);
        if (!region.getClass().getName().equals("org.hibernate.cache.internal.NoCachingRegionFactory"))
            return "the persistence unit uses cache regions that may be shared with the replacement";
        Object chain = field(old.getClass(), version.startsWith("7.") ? "observerChain" : "observer").get(old);
        if (!chain.getClass().getName().equals("org.hibernate.internal.SessionFactoryObserverChain"))
            return "unrecognised Hibernate observer chain";
        Object observers = field(chain.getClass(), "observers").get(chain);
        if (observers instanceof Collection<?> entries) {
            for (Object observer : entries) {
                if (!STOCK_OBSERVERS.contains(observer.getClass().getName())) return "an unrecognised Hibernate factory observer is registered ("
                        + observer.getClass().getName() + ")";
            }
        } else if (observers != null) return "unreadable Hibernate factory observers";
        return null;
    }

    static String settingProblem(Map<?, ?> settings) {
        for (var entry : settings.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String value = String.valueOf(entry.getValue()).trim();
            if (value.isEmpty()) continue;
            if (key.equals("hibernate.session_factory_name") || key.equals("hibernate.session_factory.jndi")
                    || key.equals("hibernate.session_factory_name_is_jndi") && Boolean.parseBoolean(value) || key.startsWith("hibernate.jndi."))
                return key + " binds a shared persistence factory name";
            if ((key.equals("hibernate.jmx.enabled") || key.equals("hibernate.cache.use_query_cache")) && Boolean.parseBoolean(value))
                return key + "=true uses shared services";
            if (key.equals("hibernate.cache.region.factory_class")) {
                Object configured = entry.getValue();
                String region = configured instanceof Class<?> type ? type.getName()
                        : configured instanceof String text ? text.trim() : configured.getClass().getName();
                if (!region.equals("none") && !region.equals("org.hibernate.cache.internal.NoCachingRegionFactory"))
                    return key + "=" + region + " may share cache regions with the replacement";
            }
            if (Set.of("hibernate.stats.factory", "hibernate.session_factory_observer", "hibernate.service_contributor").contains(key))
                return key + " configures an unverified persistence service";
            if (key.startsWith("hibernate.hbm2ddl.auto") || key.equals("spring.jpa.hibernate.ddl-auto")
                    || key.endsWith("persistence.schema-generation.database.action")) {
                String action = value.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
                if (!Set.of("none", "update", "validate").contains(action))
                    return key + "=" + value + " can change existing tables while requests are active";
            }
        }
        return null;
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException inherited) { /* Walk to the declaration. */ }
        }
        throw new NoSuchFieldException(name);
    }

    private JpaFactoryRetention() {}
}
