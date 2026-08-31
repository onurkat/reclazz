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
import java.util.Map;

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
 * <p>Method security is the other half, and it is not reached by rebuilding
 * chains: {@code @PreAuthorize} and friends are enforced by interceptors that
 * resolve the annotation on a method once and keep the answer, keyed by method
 * and class. Redefinition changes what the annotation says without changing
 * either key, so the interceptor keeps enforcing the expression the method used
 * to carry. {@link #refreshMethodSecurity} clears those answers, the same move
 * {@code SpringOperationSourceReloader} makes for {@code @Transactional} and
 * {@code @Cacheable}, and it runs for every reloaded class rather than only for
 * security configurations: the common edit is a {@code @PreAuthorize} on a
 * service, which is not a security configuration class at all and used to reach
 * nothing here.
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
                // Deliberately not rebuilt, and worth saying why rather than
                // leaving it looking like an oversight. An adapter does not
                // produce a chain bean to destroy and re-create: WebSecurity
                // builds the chains from the adapter internally, once, and the
                // only way in is to take that builder apart. Security is the
                // one area where a half-applied change is worse than none, and
                // this path could not be measured against anything: the API
                // was removed in Spring Security 6, so there is no version of
                // it left to be sure against. Refusing to guess here keeps the
                // rules that are running the rules that were reviewed.
                //
                // What the old sentence got wrong was the scope. Only the
                // filter chain waits for a restart; method security on this
                // generation is re-read like any other, by refreshMethodSecurity.
                StatusReporter.warn("Security rules changed in a WebSecurityConfigurerAdapter, "
                        + "which builds its chains inside WebSecurity rather than as beans "
                        + "Reclazz can rebuild, so the URL rules keep enforcing what they "
                        + "enforced until a restart. Method security (@PreAuthorize and "
                        + "friends) is re-read and is live. Moving to a SecurityFilterChain "
                        + "bean, which is what Spring Security 6 requires anyway, is what "
                        + "makes the URL rules reloadable too.");
                com.onurkat.reclazz.agent.RestartLedger.note(reloadedClass.getName(),
                        "URL rules in a WebSecurityConfigurerAdapter, which has no chain bean "
                                + "to rebuild");
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
            // Method security is not part of the chain and is not rebuilt
            // here. It is refreshed for every reloaded class, before this
            // runs, by refreshMethodSecurity; saying anything about it here
            // would either repeat that or contradict it.
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

    /**
     * The beans that hold a resolved-once answer for {@code @PreAuthorize} and
     * friends, across the two generations of the API.
     *
     * <p>Spring Security 5 asks a {@code MethodSecurityMetadataSource} and
     * caches the attributes on it. Spring Security 6 replaced that with
     * authorization-manager interceptors, one bean per annotation, each
     * holding a registry that caches the parsed expression by method and
     * class. Both are looked up by name so that neither generation being
     * absent is an error.
     *
     * <p>{@code AuthorizationAdvisor} is in the list because of what a live
     * Boot 3.3 / Security 6.3 run showed: asking for the concrete interceptor
     * type answers nothing. The bean registered under
     * {@code preAuthorizeAuthorizationMethodInterceptor} is a
     * {@code DeferringMethodInterceptor} holding a {@code SingletonSupplier}
     * of the real one, so the concrete type never matches and the whole
     * refresh silently did nothing. {@code AuthorizationAdvisor} is the
     * interface that wrapper does implement, and asking for it returned
     * exactly the five method-security interceptors and nothing else. The
     * concrete types stay for 6.0 to 6.2, where the bean is the interceptor
     * itself.
     */
    private static final String[] METHOD_SECURITY_BEAN_TYPES = {
            "org.springframework.security.access.method.MethodSecurityMetadataSource",
            "org.springframework.security.authorization.method.AuthorizationAdvisor",
            "org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor",
            "org.springframework.security.authorization.method.AuthorizationManagerAfterMethodInterceptor",
    };

    /** The names the generations give the map, most specific first. */
    private static final String[] METHOD_SECURITY_CACHE_FIELDS = {
            "cachedAttributes", "attributeCache", "cachedManagers", "cachedAuthorities",
    };

    /**
     * How each generation is asked to resolve a method again.
     *
     * <p>Clearing is half the job. The other half was measured on a live Boot
     * 3.3 server: after the clear, the very next call filled the map back up
     * with the OLD expression. The re-resolution reads the annotation off the
     * {@code Method} object the AOP invocation carries, which was captured
     * when the proxy was built, and a {@code Method} keeps its annotation
     * parse for the life of the object; redefinition does not invalidate it.
     * A freshly taken {@code Method} for the same method reads the new value,
     * asserted in the same run.
     *
     * <p>So the map is refilled here from fresh {@code Method} objects. The
     * key compares methods by equality rather than identity, so the entry a
     * fresh one writes is the entry the stale one finds.
     */
    private static final String[] METHOD_SECURITY_ASK_METHODS = {
            "getAttribute",    // Spring Security 6 expression registries
            "getAttributes",   // Spring Security 5 metadata sources
    };

    /** Deep enough for the longest real chain, and no deeper. */
    private static final int METHOD_SECURITY_MAX_DEPTH = 5;

    /** Only Spring Security's own objects are ever read from or cleared. */
    private static final String SECURITY_PACKAGE = "org.springframework.security.";

    /**
     * Re-read {@code @PreAuthorize} and friends for a reloaded class.
     *
     * <p>Called for every reload, not only for security configurations,
     * because the edit that needs this is a changed expression on a service
     * method. An application without Spring Security pays one cached map
     * lookup per context for that, and one that has it pays a bean-name
     * lookup Spring answers from its own type cache.
     *
     * <p>Clearing is not gated on the class still carrying the annotation.
     * A save that <em>removes</em> a {@code @PreAuthorize} leaves the
     * interceptor holding the expression it used to have, which is the one
     * outcome worse than a stale rule: a method the developer just opened
     * would keep being refused.
     *
     * <p>Clearing is the whole move: both generations repopulate on the next
     * call, from annotations that are current by then, and both cache the
     * absence of an annotation too, so a method that just gained a
     * {@code @PreAuthorize} is exactly as stale as one that changed it. The
     * annotation parse itself is already fresh here, because
     * {@code SpringOperationSourceReloader} clears Spring's own annotation
     * caches earlier in the same reload.
     *
     * @return how many caches held an answer and were cleared
     */
    public int refreshMethodSecurity(Class<?> reloadedClass) {
        if (reloadedClass == null) return 0;

        // The gate is first, not last, and it is what keeps this free. Every
        // reload passes through here, and on a server with dozens of
        // application contexts a bean lookup per context per interceptor type
        // is real work to do on the reload thread for a class that has nothing
        // to do with security. A class that does not carry the annotations is
        // not one whose rules just changed.
        //
        // A save that REMOVES the last annotation is the case this gate does
        // not cover, and it does not need to: measured on Boot 3.3, removing
        // a @PreAuthorize opens the method on the next request anyway, because
        // the bean refresh builds a new proxy and its pointcut is evaluated
        // against Method objects taken fresh from the reloaded class.
        if (!carriesMethodSecurity(reloadedClass)) return 0;

        int cleared = 0;
        boolean found = false;
        for (Object appContext : platformContext.getAllApplicationContexts()) {
            for (Class<?> type : methodSecurityTypes(appContext)) {
                String[] names = SpringBeans.beanNamesForType(appContext, type);
                if (names.length == 0) continue;
                found = true;
                for (String name : names) {
                    try {
                        cleared += clearMethodSecurityCaches(
                                SpringBeans.getBean(appContext, name), 0, reloadedClass);
                    } catch (Throwable oneBean) {
                        // A bean that cannot be read keeps its cache; the
                        // stale window stays what it was, and the count below
                        // reports only what really changed.
                    }
                }
            }
        }

        if (cleared > 0) {
            StatusReporter.success("Method security re-read for " + reloadedClass.getName()
                    + ": @PreAuthorize and friends are enforced as written now.");
        } else if (found) {
            // Not "nothing was cached": an empty cache is refilled above, so
            // reaching here means no cache was found at all on any of the
            // interceptor beans, which is a shape this does not know.
            StatusReporter.warn(reloadedClass.getName() + " carries method-security "
                    + "annotations and none of the interceptors exposed a metadata cache "
                    + "this version knows, so an edited expression may still be enforced "
                    + "as it was. A restart applies it.");
            com.onurkat.reclazz.agent.RestartLedger.note(reloadedClass.getName(),
                    "method-security metadata that could not be re-read");
        }
        return cleared;
    }

    /**
     * The method-security types this loader can resolve, looked up once.
     *
     * <p>Resolving them is a {@code Class.forName} per name, and the miss is
     * the expensive half: a class loader with a large class path re-scans it
     * on every failed load, and an application without Spring Security would
     * pay five of those per reload, per context, for an answer that cannot
     * change. So the answer is kept per loader, weakly, and a context whose
     * loader is gone takes its entry with it.
     */
    private static final java.util.Map<ClassLoader, List<Class<?>>> TYPES_BY_LOADER =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static List<Class<?>> methodSecurityTypes(Object appContext) {
        ClassLoader loader = appContext.getClass().getClassLoader();
        if (loader == null) return List.of();
        return TYPES_BY_LOADER.computeIfAbsent(loader, resolveWith -> {
            List<Class<?>> types = new java.util.ArrayList<>();
            for (String name : METHOD_SECURITY_BEAN_TYPES) {
                try {
                    types.add(Class.forName(name, false, resolveWith));
                } catch (Throwable notThisApplication) {
                    // No Spring Security of that generation here, which is
                    // most applications for at least one of the two.
                }
            }
            return List.copyOf(types);
        });
    }

    static int clearMethodSecurityCaches(Object target, int depth) {
        return clearMethodSecurityCaches(target, depth, null);
    }

    static int clearMethodSecurityCaches(Object target, int depth, Class<?> reloadedClass) {
        return clearMethodSecurityCaches(target, depth, reloadedClass,
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    /**
     * Clear the cache on this object, then on everything of Spring Security's
     * that it holds.
     *
     * <p>This was a walk down three named fields until a live run met the
     * shape those names assume is not the only one: on Boot 3.3 the chain is
     * {@code DeferringMethodInterceptor} to {@code SingletonSupplier} to the
     * interceptor to its manager to its registry, and the supplier is not a
     * field name anybody should have to know. So the walk follows every field
     * instead, and the bound that keeps it honest is ownership rather than a
     * name list: nothing outside {@code org.springframework.security} is read
     * from or cleared, which is a stronger promise than three field names
     * were, because it holds whatever the next version renames.
     *
     * <p>A {@code Supplier} is unwrapped rather than followed, since that is
     * how the configuration defers construction. The depth cap, the identity
     * set and the package check together mean a cycle, a deep graph and
     * somebody else's object all end the same way, immediately.
     */
    private static int clearMethodSecurityCaches(Object target, int depth,
                                                 Class<?> reloadedClass,
                                                 java.util.Set<Object> visited) {
        if (target == null || depth > METHOD_SECURITY_MAX_DEPTH) return 0;
        if (!target.getClass().getName().startsWith(SECURITY_PACKAGE)) return 0;
        if (!visited.add(target)) return 0;

        // Clearing and refilling are one move, and the refill runs whether or
        // not there was anything to clear. An empty cache is not a safe state:
        // the first call after the reload resolves through the Method the AOP
        // invocation carries, which is the stale one, so an untouched cache
        // fills itself with the old expression exactly like a cleared one
        // did. Filling it here first is what makes the answer current before
        // anybody asks.
        boolean hasCache = cacheField(target) != null;
        int cleared = 0;
        if (hasCache) {
            boolean emptied = SpringOperationSourceReloader.clearMetadataCache(
                    target, METHOD_SECURITY_CACHE_FIELDS);
            if (refill(target, reloadedClass) > 0 || emptied) cleared = 1;
        }

        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                Object value = read(field, target);
                if (value instanceof java.util.function.Supplier<?> deferred) {
                    value = supplied(deferred);
                }
                if (value == null) continue;
                cleared += clearMethodSecurityCaches(value, depth + 1, reloadedClass, visited);
            }
        }
        return cleared;
    }

    /**
     * Ask this source about every method of the reloaded class, with
     * {@code Method} objects taken fresh from it.
     *
     * <p>Two shapes, because Spring Security has two. An expression registry
     * has a {@code getAttribute(Method, Class)} that fills its own map, so
     * asking it is the whole refill. {@code @Secured} and the JSR-250
     * annotations instead resolve inside a call that needs a live
     * {@code MethodInvocation}, which there is none of here; what they do have
     * is a {@code resolve...(Method, Class)} that computes without caching, so
     * the answer is computed and put into the map that was just cleared, under
     * the key the framework itself would have used.
     *
     * <p>A resolve that answers null is skipped rather than stored: null is
     * how these maps say "not cached", and one of them replaces it with a
     * sentinel that is not ours to construct.
     */
    private static int refill(Object source, Class<?> reloadedClass) {
        if (reloadedClass == null) return 0;

        for (String name : METHOD_SECURITY_ASK_METHODS) {
            java.lang.reflect.Method ask = SpringBeans.findMethod(source.getClass(), name,
                    java.lang.reflect.Method.class, Class.class);
            if (ask == null) continue;
            int answered = 0;
            for (java.lang.reflect.Method method : reloadedClass.getDeclaredMethods()) {
                if (method.isSynthetic()) continue;
                try {
                    ask.invoke(source, method, reloadedClass);
                    answered++;
                } catch (Throwable oneMethod) {
                    // A method the source will not answer for stays
                    // unresolved, exactly as it was before this existed.
                }
            }
            return answered;
        }

        return refillByResolving(source, reloadedClass);
    }

    /** The shape that computes without caching: compute, then store. */
    private static int refillByResolving(Object source, Class<?> reloadedClass) {
        java.lang.reflect.Method resolve = null;
        for (Class<?> c = source.getClass(); c != null && c != Object.class && resolve == null;
                c = c.getSuperclass()) {
            for (java.lang.reflect.Method candidate : c.getDeclaredMethods()) {
                if (!candidate.getName().startsWith("resolve")) continue;
                Class<?>[] parameters = candidate.getParameterTypes();
                if (parameters.length != 2) continue;
                if (parameters[0] != java.lang.reflect.Method.class || parameters[1] != Class.class) {
                    continue;
                }
                candidate.setAccessible(true);
                resolve = candidate;
                break;
            }
        }
        if (resolve == null) return 0;

        Field mapField = cacheField(source);
        if (mapField == null) return 0;

        int stored = 0;
        try {
            @SuppressWarnings("unchecked")
            Map<Object, Object> cache = (Map<Object, Object>) mapField.get(source);
            if (cache == null) return 0;

            java.lang.reflect.Constructor<?> key = Class.forName(
                    "org.springframework.core.MethodClassKey", false,
                    source.getClass().getClassLoader())
                    .getConstructor(java.lang.reflect.Method.class, Class.class);

            for (java.lang.reflect.Method method : reloadedClass.getDeclaredMethods()) {
                if (method.isSynthetic()) continue;
                try {
                    Object resolved = resolve.invoke(source, method, reloadedClass);
                    if (resolved == null) continue;
                    cache.put(key.newInstance(method, reloadedClass), resolved);
                    stored++;
                } catch (Throwable oneMethod) {
                    // As above: unresolved is where it already was.
                }
            }
        } catch (Throwable notThisShape) {
            // A source whose map or key type moved keeps an empty cache and
            // resolves again on its own, which is what happened before this.
        }
        return stored;
    }

    /** The map this object was cleared through, for the refill to write into. */
    private static Field cacheField(Object source) {
        for (Class<?> c = source.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (String name : METHOD_SECURITY_CACHE_FIELDS) {
                try {
                    Field field = c.getDeclaredField(name);
                    if (!Map.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException keepLooking) {
                    // the next name, then the next class up
                }
            }
        }
        return null;
    }

    private static Object read(Field field, Object target) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable notReadable) {
            return null;
        }
    }

    /**
     * What a deferred interceptor is holding, or null.
     *
     * <p>The supplier reached here is the configuration's own, and by the time
     * a reload happens it has long since been resolved: asking is a field read
     * behind a memo. A supplier that would rather not answer is not one this
     * has anything to say about.
     */
    private static Object supplied(java.util.function.Supplier<?> deferred) {
        try {
            return deferred.get();
        } catch (Throwable notReady) {
            return null;
        }
    }

    /**
     * Whether this class is one a method-security edit would be about: it
     * enables method security, or one of its methods carries an annotation
     * method security enforces.
     */
    static boolean carriesMethodSecurity(Class<?> clazz) {
        for (var annotation : clazz.getAnnotations()) {
            String name = annotation.annotationType().getName();
            if (name.contains("EnableGlobalMethodSecurity") || name.contains("EnableMethodSecurity")) {
                return true;
            }
        }
        try {
            for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
                for (var annotation : method.getAnnotations()) {
                    if (isMethodSecurityAnnotation(annotation.annotationType().getName())) {
                        return true;
                    }
                }
            }
        } catch (Throwable notReadable) {
            return false;
        }
        return false;
    }

    /**
     * Spring Security's own four, plus the JSR-250 three it also enforces.
     *
     * <p>By simple name, the way {@code isConstructorBound} matches its own:
     * the JSR-250 annotations exist under both {@code javax} and
     * {@code jakarta}, and matching the full name would have to list every
     * package either framework has used or will use. What a false positive
     * costs here is one log line about a cache that really was cleared, which
     * is why the looser match is the safer one.
     */
    static boolean isMethodSecurityAnnotation(String name) {
        String simple = name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
        return simple.equals("PreAuthorize") || simple.equals("PostAuthorize")
                || simple.equals("PreFilter") || simple.equals("PostFilter")
                || simple.equals("Secured") || simple.equals("RolesAllowed")
                || simple.equals("DenyAll") || simple.equals("PermitAll");
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
