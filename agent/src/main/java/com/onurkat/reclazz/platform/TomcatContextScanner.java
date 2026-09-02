/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Enumeration;

/**
 * Discovers Spring ApplicationContexts of web applications that are
 * ALREADY RUNNING when the agent is attached to a live JVM.
 *
 * SpringContextInterceptTransformer only captures contexts refreshed
 * AFTER the agent loads. In attach mode every web context has long been
 * refreshed, so without this scan the Spring reloaders would only see
 * the global context (via the Hybris Registry fallback) and web-layer
 * reloads (MVC re-scan, web-scoped beans, caches) would silently no-op.
 *
 * Strategy: walk Tomcat's container tree via reflection —
 * Bootstrap.daemon → Catalina → Server → Services → Engine → Hosts →
 * StandardContexts → ServletContext attributes. Spring stores its
 * contexts as well-known ServletContext attributes.
 *
 * All reflection — no Tomcat or Spring compile dependency. Graceful
 * no-op when Tomcat isn't present (standalone Spring Boot uses the
 * intercept transformer path instead).
 */
public final class TomcatContextScanner {

    /** Spring's root web application context attribute. */
    private static final String ROOT_CONTEXT_ATTR =
            "org.springframework.web.context.WebApplicationContext.ROOT";

    /** Prefix for per-DispatcherServlet context attributes. */
    private static final String SERVLET_CONTEXT_ATTR_PREFIX =
            "org.springframework.web.servlet.FrameworkServlet.CONTEXT.";

    private TomcatContextScanner() {}

    /**
     * Scan the running Tomcat (if any) and register every live Spring
     * web context into {@link ApplicationContextHolder}.
     *
     * @return number of contexts registered
     */
    public static int scanAndRegister() {
        int registered = 0;
        try {
            Object server = findCatalinaServer();
            if (server == null) return 0;

            Object[] services = (Object[]) invoke(server, "findServices");
            if (services == null) return 0;

            for (Object service : services) {
                Object engine = invoke(service, "getContainer");
                if (engine == null) continue;
                for (Object host : children(engine)) {
                    for (Object context : children(host)) {
                        registered += registerSpringContexts(context);
                    }
                }
            }
        } catch (Throwable t) {
            StatusReporter.warn("Tomcat context scan failed: " + com.onurkat.reclazz.ui.Failures.describe(t));
        }
        return registered;
    }

    /** Bootstrap.daemon → catalinaDaemon → getServer() */
    private static Object findCatalinaServer() {
        try {
            Class<?> bootstrap = Class.forName("org.apache.catalina.startup.Bootstrap");
            Field daemonField = bootstrap.getDeclaredField("daemon");
            daemonField.setAccessible(true);
            Object daemon = daemonField.get(null);
            if (daemon == null) return null;

            Field catalinaField = bootstrap.getDeclaredField("catalinaDaemon");
            catalinaField.setAccessible(true);
            Object catalina = catalinaField.get(daemon);
            if (catalina == null) return null;

            return invoke(catalina, "getServer");
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            // Not a standalone Tomcat (embedded servers take the
            // intercept-transformer path) — nothing to scan.
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Object[] children(Object container) {
        try {
            Object[] result = (Object[]) invoke(container, "findChildren");
            return result != null ? result : new Object[0];
        } catch (Exception e) {
            return new Object[0];
        }
    }

    /** Register Spring contexts found in one webapp's ServletContext. */
    private static int registerSpringContexts(Object standardContext) {
        int registered = 0;
        try {
            Object servletContext = invoke(standardContext, "getServletContext");
            if (servletContext == null) return 0;

            Enumeration<?> names = (Enumeration<?>) invoke(servletContext, "getAttributeNames");
            if (names == null) return 0;

            Method getAttribute = servletContext.getClass().getMethod("getAttribute", String.class);
            getAttribute.setAccessible(true);

            while (names.hasMoreElements()) {
                String name = String.valueOf(names.nextElement());
                if (!name.equals(ROOT_CONTEXT_ATTR)
                        && !name.startsWith(SERVLET_CONTEXT_ATTR_PREFIX)) {
                    continue;
                }
                Object candidate = getAttribute.invoke(servletContext, name);
                // A failed web context stores its startup EXCEPTION under
                // the ROOT attribute — only register real contexts.
                if (isApplicationContext(candidate)) {
                    ApplicationContextHolder.register(candidate);
                    registered++;
                }
            }
        } catch (Exception ignored) {}
        return registered;
    }

    /** Classloader-agnostic instanceof ApplicationContext check. */
    private static boolean isApplicationContext(Object candidate) {
        if (candidate == null) return false;
        for (Class<?> c = candidate.getClass(); c != null; c = c.getSuperclass()) {
            if (implementsApplicationContext(c)) return true;
        }
        return false;
    }

    private static boolean implementsApplicationContext(Class<?> type) {
        for (Class<?> iface : type.getInterfaces()) {
            if (iface.getName().equals("org.springframework.context.ApplicationContext")
                    || implementsApplicationContext(iface)) {
                return true;
            }
        }
        return false;
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        Method m = target.getClass().getMethod(methodName);
        m.setAccessible(true);
        return m.invoke(target);
    }
}
