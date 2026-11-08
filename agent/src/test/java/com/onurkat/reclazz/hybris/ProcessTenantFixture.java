/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris;

import java.util.*;
import java.nio.file.*;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;
import org.springframework.context.support.GenericApplicationContext;

/** Test-only tenant/session binding; no native tenant or database is started. */
public final class ProcessTenantFixture {
    public static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();
    public static final ThreadLocal<Map<Object, Session>> SESSIONS = ThreadLocal.withInitial(IdentityHashMap::new);
    public static final List<Tenant> tenants = new ArrayList<>();
    public static Tenant attach(GenericApplicationContext context) {
        context.getBeanFactory().setBeanClassLoader(ownerLoader());
        var tenant = new Tenant(context); tenants.add(tenant);
        context.getBeanFactory().registerSingleton("sessionService", new Sessions(tenant));
        return tenant;
    }
    private static ClassLoader isolated;
    private static synchronized ClassLoader ownerLoader() {
        if (isolated != null) return isolated;
        try {
            ClassLoader parent = ProcessTenantFixture.class.getClassLoader();
            Path output = Files.createTempDirectory("example-process-tenant-");
            String[] names = {"de.hybris.platform.core.Registry", "de.hybris.platform.jalo.JaloSession"};
            boolean copied = true;
            for (String name : names) {
                String resource = name.replace('.', '/') + ".class";
                try (var in = parent.getResourceAsStream(resource)) {
                    if (in == null) { copied = false; break; }
                    Path target = output.resolve(resource); Files.createDirectories(target.getParent()); Files.copy(in, target);
                }
            }
            if (!copied) {
                Path sources = Files.isDirectory(Path.of("src/processTestFixtures/java"))
                        ? Path.of("src/processTestFixtures/java") : Path.of("agent/src/processTestFixtures/java");
                String classpath = Path.of(ProcessTenantFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                        + java.io.File.pathSeparator + System.getProperty("java.class.path");
                int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                        classpath, "-d", output.toString(),
                        sources.resolve("de/hybris/platform/core/Registry.java").toString(),
                        sources.resolve("de/hybris/platform/jalo/JaloSession.java").toString());
                if (result != 0) throw new IllegalStateException("synthetic tenant compilation failed");
            }
            isolated = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, parent) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (!name.equals(names[0]) && !name.equals(names[1])) return super.loadClass(name, resolve);
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> found = findLoadedClass(name);
                        if (found == null) found = findClass(name);
                        if (resolve) resolveClass(found); return found;
                    }
                }
            };
            return isolated;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    public static void reset() { tenants.clear(); CURRENT.remove(); SESSIONS.remove(); }
    public static final class Tenant {
        private final Object context;
        public Tenant(Object context) { this.context = context; }
        // Mirrors the inspected package-private native getter.
        Object getApplicationContext() { return context; }
    }
    public static final class Session {
        public final Object tenant;
        public boolean closed;
        public Session(Object tenant) { this.tenant = tenant; }
        public void activate() { SESSIONS.get().put(tenant, this); }
    }
    public static final class Sessions {
        private final Tenant tenant;
        public int created, closed;
        public Sessions(Tenant tenant) { this.tenant = tenant; }
        public boolean hasCurrentSession() { return SESSIONS.get().containsKey(tenant); }
        public Session getCurrentSession() { return SESSIONS.get().get(tenant); }
        public Object getRawSession(Session session) { return session; }
        public Session createNewSession() {
            if (CURRENT.get() != tenant) throw new IllegalStateException("wrong synthetic tenant");
            var session = new Session(tenant); session.activate(); created++; return session;
        }
        public void closeSession(Session session) {
            session.closed = true; closed++; SESSIONS.get().remove(tenant, session);
        }
    }
}
