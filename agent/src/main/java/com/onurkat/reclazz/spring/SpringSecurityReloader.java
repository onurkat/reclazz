/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Rebuilds the Spring Security filter chain after a security configuration
 * class reloads, so the edited rules are what requests are checked against.
 *
 * <p>The rules live in {@code SecurityFilterChain} beans, built once at
 * startup by the {@code @Bean} methods of the security configuration and then
 * captured in the live {@code FilterChainProxy}, which is what the servlet
 * container actually calls. Reloading the configuration class changes the
 * {@code @Bean} method's code but nothing re-runs it, so the running proxy
 * keeps enforcing the old rules while the source shows the new ones: the worst
 * kind of stale, because nothing fails.
 *
 * <p>So when a security configuration class reloads, each
 * {@code SecurityFilterChain} bean is destroyed and rebuilt, which re-runs the
 * {@code @Bean} method with its reloaded body against a fresh
 * {@code HttpSecurity}, and the new chain is swapped into the live
 * {@code FilterChainProxy} in place: the proxy instance itself is kept,
 * because the container's {@code DelegatingFilterProxy} holds it and would not
 * notice a replacement. Chains in the proxy that are not beans (the ones
 * {@code WebSecurity.ignoring()} builds inline) are left exactly where they
 * are.
 *
 * <p>What this does not cover, and says so: method security. The interceptors
 * behind {@code @PreAuthorize} and friends keep their own metadata and are not
 * rebuilt here; a change to those still needs a restart, and the log names
 * that case instead of folding it into a rebuilt-chain success line.
 *
 * <p>All reflective, no Spring Security dependency; without it on the
 * classpath nothing here runs. A rebuild that fails part-way leaves the old
 * chains serving: the bean may be gone from the factory, but the proxy still
 * holds the old instance, so the application keeps answering with the rules it
 * had, and the log says the new ones are not live.
 */
public class SpringSecurityReloader {

    private final PlatformContext platformContext;

    public SpringSecurityReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    /**
     * Rebuild the filter chains if the reloaded class is security
     * configuration.
     */
    public boolean reloadSecurityConfig(Class<?> reloadedClass) {
        if (!isSecurityConfigClass(reloadedClass)) return false;

        boolean handled = false;
        // Security beans may live in any context (typically a web context).
        for (Object appContext : platformContext.getAllApplicationContexts()) {
            handled |= reloadSecurityConfigIn(appContext, reloadedClass);
        }
        return handled;
    }

    private boolean reloadSecurityConfigIn(Object appContext, Class<?> reloadedClass) {
        try {
            String[] filterChainNames = SpringBeans.beanNamesForType(appContext,
                    "org.springframework.security.web.SecurityFilterChain");
            if (filterChainNames != null && filterChainNames.length > 0) {
                return rebuildFilterChains(appContext, reloadedClass, filterChainNames);
            }

            // Also try with WebSecurityConfigurerAdapter (legacy)
            String[] configurerNames = SpringBeans.beanNamesForType(appContext,
                    "org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter");
            if (configurerNames != null && configurerNames.length > 0) {
                StatusReporter.warn("Legacy WebSecurityConfigurerAdapter changed. " +
                        "Restart required for security changes.");
                com.onurkat.reclazz.agent.RestartLedger.note(reloadedClass.getName(),
                        "a WebSecurityConfigurerAdapter change, which predates rebuildable chains");
                return true;
            }

        } catch (Exception e) {
            StatusReporter.warn("Spring Security reload check failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Destroy and re-create each chain bean, then swap the current bean chains
     * over the stale ones inside the live proxy. Both halves have to land for
     * the reload to be real, and each failure mode is reported as itself.
     *
     * <p>Staleness is decided against the container, not against a
     * before-and-after pair: by the time this runs, the bean-refresh cascade
     * has usually already rebuilt the chain beans once (the chain depends on
     * the configuration bean that was refreshed), so an instance captured
     * "before" here was never the one the proxy holds. The proxy's list is
     * instead compared with what the container currently serves: an element
     * that is a current bean stays, an element with no filters is an
     * {@code ignoring()} chain and stays, and the rest are the startup-era
     * chains, replaced in order by the rebuilt beans.
     */
    private boolean rebuildFilterChains(Object appContext, Class<?> reloadedClass,
                                        String[] chainNames) {
        // 1. Re-create the chain beans. The @Bean method re-runs here with the
        // reloaded configuration code; when the cascade already did this, the
        // second run is one more build of the same current code.
        List<Object> current;
        try {
            for (String name : chainNames) {
                SpringBeanReloader.destroyAndRefreshBean(appContext, name);
            }
            current = orderedChainBeans(appContext, chainNames);
        } catch (Throwable t) {
            // The old chains are still what the proxy serves, so the failure
            // is a stale ruleset, not an open door. Say exactly that.
            StatusReporter.warn("Security filter chain rebuild failed ("
                    + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage())
                    + "). The previous security rules are still enforced; "
                    + "a restart applies the new ones.");
            com.onurkat.reclazz.agent.RestartLedger.note(reloadedClass.getName(),
                    "a security configuration change whose chain rebuild failed");
            return true;
        }
        if (current.isEmpty()) {
            return false;                     // nothing was actually rebuilt here
        }

        // 2. Rebuild the proxy bean too, so the factory's answer is a proxy
        // built from the current chains rather than the startup ones. The
        // proxy's factory method runs on WebSecurityConfiguration's builder,
        // which throws AlreadyBuiltException the second time (measured), so
        // that configuration is rebuilt first: a fresh instance gets a fresh
        // builder and the current chain beans autowired into it.
        String[] webSecurityConfigs = SpringBeans.beanNamesForType(appContext,
                "org.springframework.security.config.annotation.web.configuration.WebSecurityConfiguration");
        if (webSecurityConfigs != null) {
            for (String name : webSecurityConfigs) {
                try {
                    SpringBeanReloader.destroyAndRefreshBean(appContext, name);
                } catch (Throwable keepOld) {
                    // Without a fresh builder the proxy recreation below fails
                    // and is reported there.
                }
            }
        }
        for (String proxyName : proxyBeanNames(appContext)) {
            try {
                SpringBeanReloader.destroyAndRefreshBean(appContext, proxyName);
            } catch (Throwable keepOld) {
                // The factory keeps the proxy it had; the steps below still
                // try to make the servlet layer consult it afresh.
            }
        }

        // 3. The servlet container holds its own reference: Boot registers a
        // DelegatingFilterProxy whose delegate field caches the proxy instance
        // from startup, unreachable through any bean. Reset that cache so the
        // next request resolves the rebuilt bean; where the container is not
        // Tomcat, fall back to swapping chains inside whatever proxy the
        // factory still serves.
        int swapped = resetServletDelegates(appContext);
        swapped += swapIntoLiveProxies(appContext, current);

        if (swapped > 0) {
            StatusReporter.success("Security filter chain rebuilt: " + swapped
                    + " chain(s) now enforce the reloaded configuration.");
            if (usesMethodSecurity(reloadedClass)) {
                StatusReporter.warn("Method security (@PreAuthorize and friends) keeps its "
                        + "own metadata and is not rebuilt; those changes still need a restart.");
                com.onurkat.reclazz.agent.RestartLedger.note(reloadedClass.getName(),
                        "method-security metadata this rebuild does not reach");
            }
            return true;
        }

        // Rebuilt beans but swapped nothing: either no live proxy was found,
        // or its list could not be safely matched to the current beans. The
        // running rules are the old ones either way; say so.
        StatusReporter.warn("Security filter chain beans were rebuilt but the live "
                + "FilterChainProxy did not take them. "
                + "A full restart may be needed for security changes to take effect.");
        com.onurkat.reclazz.agent.RestartLedger.note(reloadedClass.getName(),
                "a security configuration change the filter chain cannot rebuild");
        return true;
    }

    /**
     * The current chain beans in the container's own order:
     * {@code getBeanProvider(...).orderedStream()} is what Boot itself sorts
     * the chains with at startup, and the by-name fallback keeps definition
     * order, which is the same thing for the single-chain majority.
     */
    private static List<Object> orderedChainBeans(Object appContext, String[] chainNames)
            throws Exception {
        try {
            Class<?> chainType = Class.forName("org.springframework.security.web.SecurityFilterChain",
                    false, appContext.getClass().getClassLoader());
            Object provider = appContext.getClass().getMethod("getBeanProvider", Class.class)
                    .invoke(appContext, chainType);
            java.util.stream.Stream<?> stream = (java.util.stream.Stream<?>) provider.getClass()
                    .getMethod("orderedStream").invoke(provider);
            List<Object> ordered = new java.util.ArrayList<>(stream.toList());
            if (!ordered.isEmpty()) return ordered;
        } catch (Throwable fallBackBelow) {
            // orderedStream is Spring 5.1+; anything older answers by name.
        }
        java.lang.reflect.Method getBean = appContext.getClass().getMethod("getBean", String.class);
        List<Object> byName = new java.util.ArrayList<>();
        for (String name : chainNames) {
            byName.add(getBean.invoke(appContext, name));
        }
        return byName;
    }

    /**
     * Replace old chain instances with new ones inside each live proxy's
     * {@code filterChains} list, keeping the proxy and every non-bean chain
     * (the {@code ignoring()} ones) untouched. Falls back to writing a fresh
     * list into the field when the captured list refuses {@code set}.
     *
     * <p>The proxy bean is not always the proxy that holds the chains: Spring
     * Boot 3.3's {@code WebMvcSecurityConfiguration$CompositeFilterChainProxy}
     * extends FilterChainProxy but keeps its inherited chain list null and
     * carries the real FilterChainProxy in a field of its own (measured: the
     * bean's own list held nothing to swap while the nested proxy held the old
     * chains). So each proxy's fields are searched one level deep for nested
     * FilterChainProxy instances, and every proxy found is swapped.
     */
    /** The names the live proxy answers to, with Boot's default as fallback. */
    private static String[] proxyBeanNames(Object appContext) {
        String[] proxyNames = SpringBeans.beanNamesForType(appContext,
                "org.springframework.security.web.FilterChainProxy");
        if (proxyNames == null || proxyNames.length == 0) {
            // Boot registers the proxy under this name with type Filter, so a
            // type lookup can miss it while the bean is right there.
            proxyNames = new String[] { "springSecurityFilterChain" };
        }
        return proxyNames;
    }

    /**
     * Drop the servlet layer's cached delegate so the next request resolves
     * the rebuilt proxy bean.
     *
     * <p>Boot wires security into the container as a
     * {@code DelegatingFilterProxy} created inside the registration bean's
     * {@code onStartup} and handed straight to the servlet context: no bean
     * holds it, and once its {@code delegate} field caches the startup proxy,
     * every bean rebuild in the world changes nothing the container consults.
     * On Tomcat (which is what SAP Commerce and default Boot run) the filter
     * is reachable through the servlet context's internals:
     * facade &rarr; ApplicationContext &rarr; StandardContext &rarr;
     * {@code filterConfigs} &rarr; each config's {@code filter}. Every
     * DelegatingFilterProxy targeting one of the proxy bean names gets its
     * delegate cleared, which is the class's own lazy-init state, re-filled
     * from the factory on the next request. A different container makes this
     * a quiet zero and the chain-list swap below is the fallback.
     */
    private int resetServletDelegates(Object appContext) {
        java.util.Set<String> names = java.util.Set.of(proxyBeanNames(appContext));
        int reset = 0;
        try {
            Object servletContext = appContext.getClass()
                    .getMethod("getServletContext").invoke(appContext);
            if (servletContext == null) return 0;

            // Tomcat: ApplicationContextFacade.context -> ApplicationContext.context -> StandardContext
            Object inner = fieldValue(servletContext, "context");
            Object standardContext = inner == null ? null : fieldValue(inner, "context");
            Object filterConfigs = standardContext == null
                    ? null : fieldValue(standardContext, "filterConfigs");
            if (!(filterConfigs instanceof java.util.Map<?, ?> configs)) return 0;

            for (Object config : configs.values()) {
                Object filter = fieldValue(config, "filter");
                if (filter == null || !isDelegatingFilterProxy(filter.getClass())) continue;
                Object target = fieldValue(filter, "targetBeanName");
                if (target != null && !names.contains(String.valueOf(target))) continue;
                if (clearDelegate(filter)) reset++;
            }
        } catch (Throwable notTomcatShaped) {
            return reset;
        }
        return reset;
    }

    private static boolean isDelegatingFilterProxy(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().equals("org.springframework.web.filter.DelegatingFilterProxy")) {
                return true;
            }
        }
        return false;
    }

    /** Null the {@code delegate} field declared on DelegatingFilterProxy itself. */
    private static boolean clearDelegate(Object filterProxy) {
        for (Class<?> c = filterProxy.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (!c.getName().equals("org.springframework.web.filter.DelegatingFilterProxy")) continue;
            try {
                Field delegate = c.getDeclaredField("delegate");
                delegate.setAccessible(true);
                if (delegate.get(filterProxy) == null) return false;   // nothing cached yet
                delegate.set(filterProxy, null);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    /** A named instance field's value, searched up the class hierarchy. */
    private static Object fieldValue(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException next) {
                // keep walking up
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private int swapIntoLiveProxies(Object appContext, List<Object> current) {
        String[] proxyNames = proxyBeanNames(appContext);

        int swapped = 0;
        for (String proxyName : proxyNames) {
            Object proxy;
            try {
                proxy = appContext.getClass().getMethod("getBean", String.class)
                        .invoke(appContext, proxyName);
            } catch (Throwable missing) {
                continue;
            }
            if (proxy == null || !isFilterChainProxy(proxy.getClass())) {
                continue;
            }
            for (Object each : withNestedProxies(proxy)) {
                swapped += swapChains(each, current);
            }
        }
        return swapped;
    }

    /**
     * The proxy itself plus every FilterChainProxy held directly in one of its
     * fields, in encounter order and without duplicates.
     */
    private static List<Object> withNestedProxies(Object proxy) {
        java.util.ArrayList<Object> proxies = new java.util.ArrayList<>();
        proxies.add(proxy);
        for (Class<?> c = proxy.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(proxy);
                    if (value != null && value != proxy
                            && isFilterChainProxy(value.getClass())
                            && !proxies.contains(value)) {
                        proxies.add(value);
                    }
                } catch (Throwable ignored) {
                    // A field this JVM will not open is a field without our proxy.
                }
            }
        }
        return proxies;
    }

    /**
     * Replace the stale entries of one proxy's chain list with the current
     * beans, in order.
     *
     * <p>An element that is identical to a current bean is already right and
     * stays. An element with no filters is a chain {@code WebSecurity.ignoring()}
     * built inline, never a bean, and stays where it is. Everything else is a
     * chain from an earlier era of the container, and there must be exactly as
     * many of those as there are current beans not yet in the list: the two
     * line up in order or nothing is touched, because guessing which rules go
     * where is the one thing a security swap must never do.
     */
    @SuppressWarnings("unchecked")
    static int swapChains(Object proxy, List<Object> current) {
        Field field = chainListField(proxy.getClass());
        if (field == null) return 0;
        try {
            field.setAccessible(true);
            List<Object> chains = (List<Object>) field.get(proxy);
            if (chains == null || chains.isEmpty()) return 0;

            java.util.Set<Object> currentSet =
                    java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            currentSet.addAll(current);

            List<Integer> staleIndexes = new java.util.ArrayList<>();
            List<Object> missing = new java.util.ArrayList<>();
            for (Object bean : current) {
                boolean present = false;
                for (Object chain : chains) {
                    if (chain == bean) { present = true; break; }
                }
                if (!present) missing.add(bean);
            }
            for (int i = 0; i < chains.size(); i++) {
                Object chain = chains.get(i);
                if (currentSet.contains(chain)) continue;   // already the current bean
                if (!hasFilters(chain)) continue;           // ignoring() chain, not ours
                staleIndexes.add(i);
            }
            if (missing.isEmpty()) {
                return 0;                     // the list already serves the current beans
            }
            if (staleIndexes.size() != missing.size()) {
                // Guessing which rules go where is the one thing a security
                // swap must never do; the mismatch is reported, not resolved.
                StatusReporter.warn("Filter chain swap declined: " + staleIndexes.size()
                        + " stale chain(s) in the live proxy but " + missing.size()
                        + " rebuilt bean(s) to place. The old rules keep serving.");
                return 0;
            }

            int swapped = 0;
            try {
                for (int i = 0; i < staleIndexes.size(); i++) {
                    chains.set(staleIndexes.get(i), missing.get(i));
                    swapped++;
                }
            } catch (UnsupportedOperationException immutable) {
                // The proxy captured an unmodifiable list; replace the list
                // itself, same contents, current beans where the stale were.
                List<Object> fresh = new java.util.ArrayList<>(chains);
                for (int i = 0; i < staleIndexes.size(); i++) {
                    fresh.set(staleIndexes.get(i), missing.get(i));
                    swapped++;
                }
                field.set(proxy, fresh);
            }
            return swapped;
        } catch (Throwable t) {
            // The list or its field refused the write; the old rules keep
            // serving and the caller's warn says so. Name the refusal here.
            StatusReporter.warn("Filter chain swap failed: " + t);
            return 0;
        }
    }

    /** Whether the chain carries filters; an {@code ignoring()} chain has none. */
    private static boolean hasFilters(Object chain) {
        try {
            Object filters = chain.getClass().getMethod("getFilters").invoke(chain);
            return filters instanceof List<?> list && !list.isEmpty();
        } catch (Throwable unknownShape) {
            // A chain whose filters cannot be read is treated as a real one:
            // the count check above still guards against a wrong pairing.
            return true;
        }
    }

    /** FilterChainProxy or a subclass of it, matched by name up the hierarchy. */
    private static boolean isFilterChainProxy(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().equals("org.springframework.security.web.FilterChainProxy")) {
                return true;
            }
        }
        return false;
    }

    /** The proxy's chain list, found by shape so a renamed field only degrades. */
    private static Field chainListField(Class<?> proxyClass) {
        for (Class<?> c = proxyClass; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field byName = c.getDeclaredField("filterChains");
                if (List.class.isAssignableFrom(byName.getType())) return byName;
            } catch (NoSuchFieldException ignored) {
                // fall through to the shape scan below
            }
            for (Field field : c.getDeclaredFields()) {
                if (List.class.isAssignableFrom(field.getType())
                        && field.getGenericType().getTypeName().contains("SecurityFilterChain")) {
                    return field;
                }
            }
        }
        return null;
    }

    private static boolean usesMethodSecurity(Class<?> clazz) {
        for (var annotation : clazz.getAnnotations()) {
            String name = annotation.annotationType().getName();
            if (name.contains("EnableGlobalMethodSecurity") || name.contains("EnableMethodSecurity")) {
                return true;
            }
        }
        return false;
    }

    private boolean isSecurityConfigClass(Class<?> clazz) {
        try {
            // Check annotations
            for (var annotation : clazz.getAnnotations()) {
                String name = annotation.annotationType().getName();
                if (name.contains("EnableWebSecurity") ||
                        name.contains("EnableGlobalMethodSecurity") ||
                        name.contains("EnableMethodSecurity")) {
                    return true;
                }
            }
            // Check interfaces/superclass for SecurityConfigurer
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                for (Class<?> iface : current.getInterfaces()) {
                    if (iface.getName().contains("SecurityConfigurer") ||
                            iface.getName().contains("SecurityFilterChain")) {
                        return true;
                    }
                }
                if (current.getName().contains("WebSecurityConfigurerAdapter")) {
                    return true;
                }
                current = current.getSuperclass();
            }
        } catch (Exception ignored) {}
        return false;
    }
}
