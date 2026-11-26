/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.ReloadEffects;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.Plural;
import com.onurkat.reclazz.util.Reflect;

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

    enum Result { NOT_APPLICABLE, PUBLISHED, RESTART }

    private static final String CHAIN = "org.springframework.security.web.SecurityFilterChain";

    record ProxyList(Object proxy, Field field, List<?> original) {
        void verify(List<?> expected) throws ReflectiveOperationException {
            Object view = proxy.getClass().getMethod("getFilterChains").invoke(proxy);
            if (!(view instanceof List<?> live) || live.size() != expected.size())
                throw new IllegalStateException("serving list could not be verified");
            for (int i = 0; i < live.size(); i++)
                if (live.get(i) != expected.get(i)) throw new IllegalStateException("serving chain differs");
        }
    }
    record Edit(ProxyList target, List<Object> next) { }
    record Owner(Object context, String[] chains, Map<String, Object> owned,
                 List<Object> originals, List<ProxyList> proxies) { }
    record Pending(List<Owner> owners, boolean restart) { }

    public boolean reloadSecurityConfig(Class<?> type) {
        return publish(type, prepare(type)) != Result.NOT_APPLICABLE;
    }

    /** Capture the actual serving objects before generic bean refresh can replace bean identities. */
    Pending prepare(Class<?> type) {
        boolean mapped = declaresChain(type);
        if (!mapped && !isSecurityConfigClass(type) && !possibleFactory(type)) return null;
        List<Owner> owners = new java.util.ArrayList<>();
        try {
            for (Object context : platformContext.getAllApplicationContexts()) {
                var names = SpringBeans.beanNamesForType(context, CHAIN);
                var configNames = java.util.Set.of(SpringBeans.beanNamesForType(context, type));
                if (configNames.isEmpty()) continue;
                Object factory = SpringBeans.getBeanFactory(context);
                Map<String, Object> owned = new java.util.LinkedHashMap<>();
                for (String name : names) {
                    Object definition = factory.getClass().getMethod("getMergedBeanDefinition", String.class).invoke(factory, name);
                    Object factoryName = definition.getClass().getMethod("getFactoryBeanName").invoke(definition);
                    var method = (java.lang.reflect.Method) definition.getClass().getMethod("getResolvedFactoryMethod").invoke(definition);
                    if ((factoryName != null && configNames.contains(factoryName))
                            || (method != null && method.getDeclaringClass() == type)) {
                        Object existing = SpringBeans.getBean(context, name);
                        if (existing == null) throw new IllegalStateException("chain bean unavailable");
                        owned.put(name, existing);
                    }
                }
                if (owned.isEmpty()) {
                    if (mapped || extendsAdapter(type)) return new Pending(List.of(), true);
                    continue;
                }
                mapped = true;
                List<Object> originals = orderedChainBeans(context, names);
                List<ProxyList> targets = servingLists(context);
                if (targets.isEmpty()) throw new IllegalStateException("serving chain list unavailable");
                for (ProxyList target : targets) replaceSlots(target.original(), originals, originals);
                owners.add(new Owner(context, names, owned, originals, targets));
            }
            if (!owners.isEmpty()) return new Pending(owners, false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            if (mapped) return new Pending(List.of(), true);
        }
        return mapped ? new Pending(List.of(), true) : null;
    }

    /** Collect exactly the chain slots backed by the old beans. Inline chains remain at their original positions. */
    static List<Object> replaceSlots(List<?> before, List<Object> originals, List<Object> replacements) {
        var remaining = java.util.Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        remaining.addAll(originals);
        if (remaining.size() != originals.size() || replacements.size() != originals.size())
            throw new IllegalStateException("ambiguous chain beans");
        List<Object> after = new java.util.ArrayList<>(before);
        int slot = 0;
        for (int i = 0; i < before.size(); i++) {
            Object entry = before.get(i);
            if (remaining.remove(entry)) after.set(i, replacements.get(slot++));
            else if (originals.stream().anyMatch(bean -> bean == entry))
                throw new IllegalStateException("duplicate chain slot");
        }
        if (!remaining.isEmpty()) throw new IllegalStateException("serving proxy does not contain every chain bean");
        return List.copyOf(after);
    }

    Result publish(Class<?> type, Pending pending) {
        if (pending == null) return Result.NOT_APPLICABLE;
        if (pending.restart()) return needsRestart(type);
        List<Edit> edits = new java.util.ArrayList<>();
        try {
            for (Owner owner : pending.owners()) {
                for (var entry : owner.owned().entrySet()) {
                    if (SpringBeans.getBean(owner.context(), entry.getKey()) == entry.getValue())
                        SpringBeanReloader.destroyAndRefreshBean(owner.context(), entry.getKey());
                    Object rebuilt = SpringBeans.getBean(owner.context(), entry.getKey());
                    if (rebuilt == null || rebuilt == entry.getValue()) throw new IllegalStateException("chain was not rebuilt");
                }
                List<Object> current = orderedChainBeans(owner.context(), owner.chains());
                for (ProxyList target : owner.proxies()) {
                    if (target.field().get(target.proxy()) != target.original())
                        throw new IllegalStateException("serving list changed during refresh");
                    edits.add(new Edit(target, replaceSlots(target.original(), owner.originals(), current)));
                }
            }
            publishLists(edits);
            ReloadEffects.note(Plural.of(edits.size(), "security chain list") + " rebuilt");
            StatusReporter.detail("Security filter chain rebuilt and verified.");
            return Result.PUBLISHED;
        } catch (Exception | LinkageError failure) {
            return needsRestart(type);
        }
    }

    static void publishLists(List<Edit> edits) throws ReflectiveOperationException {
        int published = 0;
        try {
            for (Edit edit : edits) {
                ProxyList target = edit.target();
                if (target.field().get(target.proxy()) != target.original())
                    throw new IllegalStateException("serving list changed before publication");
                target.field().set(target.proxy(), edit.next());
                published++;
                target.verify(edit.next());
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            for (int i = published - 1; i >= 0; i--) {
                ProxyList target = edits.get(i).target();
                target.field().set(target.proxy(), target.original());
            }
            throw failure;
        }
    }

    private static Result needsRestart(Class<?> type) {
        String reason = "Security rules changed in " + type.getName() + "; restart to enforce them";
        RestartLedger.note(type.getName(), reason);
        StatusReporter.warn(reason);
        return Result.RESTART;
    }

    private static List<Object> orderedChainBeans(Object context, String[] names) throws ReflectiveOperationException {
        if (names.length == 1) return List.of(SpringBeans.getBean(context, names[0]));
        ClassLoader loader = context.getClass().getClassLoader();
        Class<?> chainType = Class.forName(CHAIN, false, loader);
        Object provider = context.getClass().getMethod("getBeanProvider", Class.class).invoke(context, chainType);
        var api = Class.forName("org.springframework.beans.factory.ObjectProvider", false, loader);
        try (var stream = (java.util.stream.Stream<?>) api.getMethod("orderedStream").invoke(provider)) {
            List<Object> ordered = new java.util.ArrayList<>(stream.toList());
            if (ordered.size() != names.length) throw new IllegalStateException("chain ordering not verified");
            return ordered;
        }
    }

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

    /** Include the proxy already cached by the servlet container, which may outlive its factory bean. */
    private static List<ProxyList> servingLists(Object context) throws ReflectiveOperationException {
        var roots = new java.util.ArrayList<Object>();
        var names = java.util.Set.of(proxyBeanNames(context));
        for (String name : names) {
            Object proxy = SpringBeans.getBean(context, name);
            if (proxy != null) roots.add(proxy);
        }
        Object servlet = null;
        try { servlet = context.getClass().getMethod("getServletContext").invoke(context); }
        catch (NoSuchMethodException nonWeb) { /* directly managed proxy */ }
        if (servlet != null) {
            Object facade = fieldValue(servlet, "context");
            Object standard = facade == null ? null : fieldValue(facade, "context");
            if (standard == null || !(fieldValue(standard, "filterConfigs") instanceof Map<?, ?> configs))
                throw new IllegalStateException("unknown servlet registration");
            boolean registered = false;
            for (Object config : configs.values()) {
                Object filter = fieldValue(config, "filter");
                if (filter == null || !isDelegatingFilterProxy(filter.getClass())) continue;
                Object target = fieldValue(filter, "targetBeanName");
                if (target == null) target = config.getClass().getMethod("getFilterName").invoke(config);
                if (target == null || !names.contains(target)) continue;
                registered = true;
                Object delegate = fieldValue(filter, "delegate");
                if (delegate == null) throw new IllegalStateException("servlet has not resolved its delegate");
                roots.add(delegate);
            }
            if (!registered) throw new IllegalStateException("no matching servlet registration");
        }
        var seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        List<ProxyList> result = new java.util.ArrayList<>();
        for (Object root : roots) {
            boolean found = false;
            for (Object proxy : withNestedProxies(root)) {
                if (!isFilterChainProxy(proxy.getClass())) continue;
                Field field = chainListField(proxy.getClass());
                if (field == null) continue;
                field.setAccessible(true);
                if (!(field.get(proxy) instanceof List<?> list) || list.isEmpty()) continue;
                found = true;
                if (seen.add(proxy)) {
                    var target = new ProxyList(proxy, field, list);
                    target.verify(list);
                    result.add(target);
                }
            }
            if (!found) throw new IllegalStateException("unsupported filter proxy");
        }
        return result;
    }

    private static boolean isDelegatingFilterProxy(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().equals("org.springframework.web.filter.DelegatingFilterProxy")) {
                return true;
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

    /** Exact identity replacement for an XML-owned chain, with rollback of all proxy fields. */
    public static void replaceXmlChain(Object context,Object previous,Object replacement) throws Exception {
        record Edit(Object proxy,Field field,Object before,Object after) { }
        List<Edit> edits=new java.util.ArrayList<>();
        java.util.Set<Object> seen=java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for(String name:proxyBeanNames(context)) {
            Object bean=context.getClass().getMethod("getBean",String.class).invoke(context,name);
            for(Object proxy:withNestedProxies(bean)) {
                if(!seen.add(proxy))continue;
                Field field=chainListField(proxy.getClass());if(field==null)continue;field.setAccessible(true);
                Object raw=field.get(proxy);if(!(raw instanceof List<?> chains))continue;
                List<Object> next=new java.util.ArrayList<>(chains);boolean found=false;
                for(int i=0;i<next.size();i++)if(next.get(i)==previous){next.set(i,replacement);found=true;}
                if(found)edits.add(new Edit(proxy,field,raw,next));
            }
        }
        if(edits.isEmpty())throw new IllegalStateException("owned XML chain is not in a live proxy");
        List<Edit> written=new java.util.ArrayList<>();
        try {
            for(Edit edit:edits) {
                if(edit.field.get(edit.proxy)!=edit.before)throw new IllegalStateException("proxy chain list changed during preparation");
                edit.field.set(edit.proxy,edit.after);written.add(edit);
            }
        } catch(Exception failure) {
            for(int i=written.size()-1;i>=0;i--) {
                Edit edit=written.get(i);edit.field.set(edit.proxy,edit.before);
            }
            throw failure;
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
        return refreshMethodSecurity(reloadedClass, false);
    }

    /** Re-read a saved policy, including removal of the last security annotation. */
    public int refreshMethodSecurity(Class<?> reloadedClass, boolean metadataChanged) {
        if (reloadedClass == null) return 0;

        // The gate is first, not last, and it is what keeps this free. Every
        // reload passes through here, and on a server with dozens of
        // application contexts a bean lookup per context per interceptor type
        // is real work to do on the reload thread for a class that has nothing
        // to do with security. A metadata edit still has to proceed when the
        // last annotation was removed: a caller may retain its startup proxy,
        // whose cached rule is not replaced by recreating the context's bean.
        // The dispatcher's annotation-change flag distinguishes that save
        // from an unrelated body-only edit with no security annotations.
        if (!metadataChanged && !carriesMethodSecurity(reloadedClass)) return 0;

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
            ReloadEffects.note("method security re-read");
            StatusReporter.detail("Method security re-read for " + reloadedClass.getName()
                    + ": @PreAuthorize and friends are enforced as written now.");
        } else if (found) {
            // Not "nothing was cached": an empty cache is refilled above, so
            // reaching here means no cache was found at all on any of the
            // interceptor beans, which is a shape this does not know.
            StatusReporter.warn(reloadedClass.getName() + " carries method-security "
                    + "annotations and none of the interceptors exposed a metadata cache "
                    + "this version knows, so an edited expression may still be enforced "
                    + "as it was. A restart applies it.");
            RestartLedger.note(reloadedClass.getName(),
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
        evictSecuredScanner(target, reloadedClass);
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

    /** Evict the measured native Secured scanner keys before authorities are re-resolved. */
    private static void evictSecuredScanner(Object source, Class<?> owner) {
        if (owner == null || !source.getClass().getName().equals(
                "org.springframework.security.authorization.method.SecuredAuthorizationManager")) return;
        Field scannerField;
        try {
            scannerField = source.getClass().getDeclaredField("scanner");
        } catch (NoSuchFieldException legacy) {
            return; // Measured Security 6 has no annotation-scanner cache.
        }
        try {
            scannerField.setAccessible(true);
            Object scanner = scannerField.get(source);
            String prefix = "org.springframework.security.core.annotation.";
            if (scanner == null || !scanner.getClass().getName().equals(
                    prefix + "ExpressionTemplateSecurityAnnotationScanner"))
                throw new IllegalStateException("unknown Secured scanner");
            Field uniqueField = scanner.getClass().getDeclaredField("unique");
            uniqueField.setAccessible(true);
            Object unique = uniqueField.get(scanner);
            if (unique == null || !unique.getClass().getName().equals(prefix + "UniqueSecurityAnnotationScanner"))
                throw new IllegalStateException("unknown unique Secured scanner");
            Field cacheField = unique.getClass().getDeclaredField("uniqueMethodAnnotationCache");
            if (!Map.class.isAssignableFrom(cacheField.getType()))
                throw new IllegalStateException("unknown Secured method cache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Object, Object> cache = (Map<Object, Object>) cacheField.get(unique);
            Class<?> keyType = Class.forName("org.springframework.core.MethodClassKey", false,
                    source.getClass().getClassLoader());
            Field target = keyType.getDeclaredField("targetClass");
            Field method = keyType.getDeclaredField("method");
            if (target.getType() != Class.class || method.getType() != java.lang.reflect.Method.class)
                throw new IllegalStateException("unknown method cache key");
            target.setAccessible(true);
            method.setAccessible(true);
            for (var entry : cache.entrySet()) {
                Object key = entry.getKey();
                if (key != null && key.getClass() == keyType && target.get(key) == owner
                        && method.get(key) instanceof java.lang.reflect.Method)
                    cache.remove(key, entry.getValue());
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Secured annotation cache could not be refreshed", failure);
        }
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
            java.lang.reflect.Method ask = Reflect.findMethod(source.getClass(), name,
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

    private static boolean beanMethod(java.lang.reflect.Method method) {
        for (var annotation : method.getDeclaredAnnotations())
            if (annotation.annotationType().getName().equals("org.springframework.context.annotation.Bean")) return true;
        return false;
    }

    private static boolean possibleFactory(Class<?> type) {
        try {
            for (Class<?> owner = type; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
                for (var annotation : owner.getDeclaredAnnotations())
                    if (annotation.annotationType().getName().equals("org.springframework.context.annotation.Configuration")) return true;
                for (var method : owner.getDeclaredMethods()) if (beanMethod(method)) return true;
            }
        } catch (LinkageError unavailable) { /* optional method types */ }
        return false;
    }

    static boolean declaresChain(Class<?> type) {
        try {
            Class<?> chain = Class.forName(CHAIN, false, type.getClassLoader());
            for (var method : type.getDeclaredMethods())
                if (chain.isAssignableFrom(method.getReturnType()) && beanMethod(method)) return true;
        } catch (ClassNotFoundException | LinkageError unavailable) { /* security is optional */ }
        return false;
    }

    private static boolean extendsAdapter(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            if (c.getName().equals("org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter")) return true;
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
