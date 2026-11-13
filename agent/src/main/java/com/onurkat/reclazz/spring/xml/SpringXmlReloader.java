/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.platform.PlatformTenant;
import com.onurkat.reclazz.platform.SpringXmlResources;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.util.Reflect;

/**
 * Reloads {@code *-spring.xml} changes into a running Spring context without
 * restarting the server.
 *
 * <h2>Supported changes</h2>
 * <ul>
 *   <li><b>Property value mutations</b> on existing singleton beans. Applied
 *       in place via reflection setters, the bean is NOT destroyed and
 *       recreated, so BeanPostProcessors don't re-run, autowired consumers
 *       keep holding the same instance, and they see the new value on the
 *       next method call. {@code <ref>}, {@code ${placeholder}}, {@code <list>},
 *       SpEL etc. are resolved through Spring's own
 *       {@code BeanDefinitionValueResolver}.</li>
 *   <li><b>New bean registration</b> for beans whose shape is safe
 *       (ordinary singleton, no constructor-args or infrastructure role).
 *       Native creation runs InitializingBean or a declared init-method. {@code registerBeanDefinition} + {@code getBean}
 *      , if instantiation fails, the partial state is rolled back.</li>
 *   <li>Selected existing constructor/factory/lifecycle edits use native
 *       recreation and direct holder repair through {@link XmlBeanRecreator}.</li>
 * </ul>
 *
 * <h2>Not supported</h2>
 * Bean removal, class changes, unsupported constructor/factory/lifecycle shapes,
 * BeanPostProcessors and {@code DisposableBean} implementers. Existing
 * {@code InitializingBean} property edits retain their lifecycle guard. Any such change is collected into a single warning asking
 * the user to restart, the live context stays untouched.
 *
 * <h2>Caveats surfaced to the user</h2>
 * When a brand-new bean is added live, existing {@code @Autowired} consumers
 * won't see it until they're re-resolved or the server restarts (Spring does
 * not re-inject autowired collections on the fly). The reloader prints a
 * note after every new-bean registration so this isn't a surprise.
 *
 * <h2>Failure isolation</h2>
 * Parse errors leave the live context untouched. Apply errors are caught
 * per property and reported as unsafe; other supported changes can still apply.
 * Failed updates to instantiated beans retain their previous raw definition
 * value. Application setter side effects are not rolled back.
 */
public final class SpringXmlReloader {

    private final PlatformContext platformContext;
    private final com.onurkat.reclazz.platform.XmlBeanLifecycle beanLifecycle;
    private final java.util.function.Consumer<java.util.Set<Path>> processRegistrations;

    public SpringXmlReloader(PlatformContext platformContext) {
        this(platformContext, paths -> { });
    }

    /** Publication remains behind the caller's optional-module admission. */
    public SpringXmlReloader(PlatformContext platformContext,
                            java.util.function.Consumer<java.util.Set<Path>> processRegistrations) {
        this(platformContext, processRegistrations, com.onurkat.reclazz.platform.XmlBeanLifecycle.NONE);
    }

    public SpringXmlReloader(PlatformContext platformContext,
                            java.util.function.Consumer<java.util.Set<Path>> processRegistrations,
                            com.onurkat.reclazz.platform.XmlBeanLifecycle beanLifecycle) {
        this.platformContext = platformContext;
        this.processRegistrations = java.util.Objects.requireNonNull(processRegistrations);
        this.beanLifecycle = java.util.Objects.requireNonNull(beanLifecycle);
    }

    /** Native platform values for exact XML scalar placeholders; no definition mutation. */
    public void reapplyPlaceholderProperties(java.util.Map<String,String> values) {
        XmlPlaceholderProperties.apply(java.util.Map.copyOf(values), gatherCandidateContexts());
    }

    /** Entry for filenames recognized by captured resource identity, never by bean names. */
    public void reloadLoaded(Path xmlPath) {
        List<Object> owners = SpringXmlResources.owners(xmlPath, gatherCandidateContexts());
        if (owners.isEmpty()) {
            StatusReporter.info("Spring XML " + xmlPath.getFileName() + ", no active owner; nothing applied");
            return;
        }
        List<OwnerChange> changes = new ArrayList<>();
        for (Object context : owners) {
            Object factory = SpringReflection.getBeanFactory(context);
            if (factory == null) continue;
            try {
                ClassLoader loader = (ClassLoader) factory.getClass().getMethod("getBeanClassLoader").invoke(factory);
                Object temporary = SpringReflection.newTempBeanFactory(loader);
                Object environment = context.getClass().getMethod("getEnvironment").invoke(context);
                // Parsed the way the context parsed it, or SAP's lazy web beans
                // read as a lazy-init edit. SAP's reader reads the tenant's config.
                Class<?> documentReader = SpringXmlResources.documentReader(xmlPath, context);
                if (documentReader != null) PlatformTenant.ensureActive(loader);
                if (temporary != null && SpringReflection.loadXml(temporary, xmlPath.toFile(), loader, environment,
                        documentReader))
                    changes.add(new OwnerChange(temporary, context, factory,
                            owners.size() == 1 ? "" : " [context " + (owners.indexOf(context) + 1) + "/" + owners.size() + "]", true,
                            documentReader == null ? null : loader));
            } catch (ReflectiveOperationException failure) {
                StatusReporter.warn("Spring XML: application bean classloader unavailable");
            }
        }
        applyToOwners(xmlPath, changes, owners);
        // Every owner and added singleton is visible before evaluating sibling names.
        processRegistrations.accept(com.onurkat.reclazz.platform.SapProcessDefinitions.registeredFrom(xmlPath));
    }

    public void reload(Path xmlPath) {
        if (!SpringXmlResources.owners(xmlPath, gatherCandidateContexts()).isEmpty()) {
            reloadLoaded(xmlPath);
            return;
        }
        // Gather all candidate application contexts. On Hybris there are
        // usually multiple (platform tenant context, web storefront contexts,
        // backoffice context, etc.), we have to target the one that owns
        // THIS XML file's beans, not just the first one captured.
        List<Object> candidates = gatherCandidateContexts();
        if (candidates.isEmpty()) {
            StatusReporter.info("Spring XML changed: " + xmlPath.getFileName()
                    + ", no live ApplicationContext yet, change will apply on next startup");
            return;
        }

        // Use the application's bean loader; Spring itself may live in a
        // parent that cannot see application classes. The shared parse path
        // requires candidate contexts using the same Spring classes.
        ClassLoader springCl = null;
        for (Object ctx : candidates) {
            Object bf = SpringReflection.getBeanFactory(ctx);
            if (bf != null) {
                try { springCl = (ClassLoader) bf.getClass().getMethod("getBeanClassLoader").invoke(bf); }
                catch (ReflectiveOperationException failure) {
                    StatusReporter.warn("Spring XML: application bean classloader unavailable");
                    return;
                }
                break;
            }
        }
        if (springCl == null) {
            StatusReporter.warn("Spring XML: no candidate context exposed a bean factory");
            return;
        }

        // Sandbox parse. If this fails, we haven't touched any live context.
        Object tempFactory = SpringReflection.newTempBeanFactory(springCl);
        if (tempFactory == null) {
            StatusReporter.warn("Spring XML: DefaultListableBeanFactory not on classpath, skip");
            return;
        }
        if (!SpringReflection.loadXml(tempFactory, xmlPath.toFile(), springCl)) {
            // Parse error is already reported by SpringReflection.loadXml.
            return;
        }

        String[] newNames = SpringReflection.getBeanDefinitionNames(tempFactory);

        // In Hybris-style deployments the same XML can be loaded into
        // multiple ApplicationContexts (platform tenant + storefront web
        // context + backoffice web context). Each has its OWN instance of
        // every bean defined in the XML. Updating just one context leaves
        // the others stale, so we apply to every context that owns any of
        // the XML's beans.
        List<Object> owningContexts = pickOwningContexts(candidates, newNames);
        if (owningContexts.isEmpty()) {
            // None of the captured contexts has ANY of the XML's beans.
            // Either the XML is brand new (add-only) or the real owning
            // context wasn't captured. Fall back to the first candidate.
            owningContexts = List.of(candidates.get(0));
            StatusReporter.warn("Spring XML " + xmlPath.getFileName()
                    + ": no existing context recognised these beans, applying to first available context");
        }

        List<OwnerChange> changes = new ArrayList<>();
        for (int i = 0; i < owningContexts.size(); i++) {
            Object appContext = owningContexts.get(i);
            Object liveFactory = SpringReflection.getBeanFactory(appContext);
            if (liveFactory == null) continue;
            String ctxLabel = owningContexts.size() == 1 ? ""
                    : " [context " + (i + 1) + "/" + owningContexts.size() + "]";
            changes.add(new OwnerChange(tempFactory, appContext, liveFactory, ctxLabel, false, null));
        }
        applyToOwners(xmlPath, changes, owningContexts);
    }

    private static final class OwnerChange {
        final Object temporary, context, factory;
        final String label;
        final boolean observed;
        final ClassLoader tenantLoader;
        final BeanDefinitionDiff diff = new BeanDefinitionDiff();
        final java.util.Set<Object> changedMappings = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Map<String, SapXmlEventListeners.Admission> listeners = java.util.Map.of();
        int applied;
        SapHotFolderMappings.Prepared hotFolder;
        SpringIntegrationEndpoints.Outcome endpoints = SpringIntegrationEndpoints.Outcome.NONE;
        boolean securityHandled;
        OwnerChange(Object temporary, Object context, Object factory, String label,
                    boolean observed, ClassLoader tenantLoader) {
            this.temporary = temporary; this.context = context; this.factory = factory;
            this.label = label; this.observed = observed; this.tenantLoader = tenantLoader;
        }
        void activate() { if (tenantLoader != null) PlatformTenant.ensureActive(tenantLoader); }
    }

    private void applyToOwners(Path xmlPath, List<OwnerChange> changes, List<Object> owners) {
        // Integration endpoints are swapped first, before the raw baseline is captured,
        // so the generic classifier compares against the swapped definitions and sees
        // only what is left.
        for (var change : changes) {
            change.activate();
            change.endpoints = SpringIntegrationEndpoints.apply(change.context, change.factory, change.temporary,
                    xmlPath, change.label);
            change.applied += change.endpoints.swapped();
            change.diff.unsafe.addAll(change.endpoints.failures());
        }
        PropertySave save = new PropertySave(owners);
        for(var change:changes) {
            change.activate();
            var security=SpringSecurityXmlChains.apply(change.context,change.factory,change.temporary,xmlPath);
            change.securityHandled=security.handled();change.applied+=security.applied();
            if(security.failure()!=null)change.diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange("security:http",security.failure()));
        }
        // Capture every participating raw definition before any owner's setters run.
        for (var change : changes) if(!change.securityHandled) save.capture(change.factory, change.temporary);
        for (var change : changes) {
            change.activate();
            if (change.observed) com.onurkat.reclazz.platform.SapProcessDefinitions.reconcile(xmlPath, change.context,
                    new java.util.HashSet<>(java.util.Arrays.asList(SpringReflection.getBeanDefinitionNames(change.temporary))));
            if(change.securityHandled)continue;
            if (change.observed) {
                try {
                    if (platformContext.getPlatformId() == PlatformContext.Platform.HYBRIS)
                        change.listeners = SapXmlEventListeners.inspect(change.context, change.factory, change.temporary, xmlPath);
                } catch (RuntimeException unavailable) {
                    // An unavailable optional platform identity denies only the SAP allowance.
                    // Ordinary XML classification must still run for generic adapters.
                }
            }
            XmlSafetyClassifier.classify(change.factory, change.temporary, xmlPath, change.diff, save.rawProperties, change.listeners);
            XmlAliasDiagnostics.classify(change.context, change.factory, change.temporary, xmlPath, change.diff,
                    change.endpoints.derivedAliases());
        }
        boolean hybris;
        try { hybris = platformContext.getPlatformId() == PlatformContext.Platform.HYBRIS; }
        catch (RuntimeException unavailable) { hybris = false; }
        for (var owner : changes) {
            var selected = owner.diff.propertyChanges.stream()
                    .filter(edit -> SapHotFolderMappings.candidate(owner.factory, edit)).toList();
            if (selected.isEmpty()) continue;
            try {
                owner.hotFolder = SapHotFolderMappings.prepare(owner.context, owner.factory, selected,
                        hybris && owner.observed);
            } catch (Exception failure) {
                owner.diff.propertyChanges.removeAll(selected);
                for (var edit : selected) owner.diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(edit.beanName,
                        "hot-folder mapping requires restart: " + SpringReflection.rootCause(failure)));
            }
        }
        boolean mappingMutation = changes.stream().anyMatch(owner -> owner.diff.propertyChanges.stream().anyMatch(change ->
                SapFieldSetRefresher.isMapping(SpringReflection.getExistingSingleton(owner.factory, change.beanName))));
        if (mappingMutation) com.onurkat.reclazz.bootstrap.CacheDependencyLedger.beginMutation();
        try {
            save.publish(changes);
            try {
                for (var change : changes) {
                    change.activate();
                    if (change.hotFolder != null) {
                        var edits = change.hotFolder.edits();
                        try {
                            for (var edit : edits) save.requirePublished(change.factory, edit.change());
                            change.hotFolder.apply();
                            for (var edit : edits) {
                                save.succeeded(change.factory, edit.change());
                                change.applied++;
                            }
                        } catch (Exception failure) {
                            for (var edit : edits) change.diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(edit.change().beanName,
                                    "hot-folder native refresh failed: " + SpringReflection.rootCause(failure)));
                        }
                        // Metadata participation was captured by publish; the generic
                        // setter path must not repeat this owner-local transaction.
                        change.diff.propertyChanges.removeAll(edits.stream().map(SapHotFolderMappings.Edit::change).toList());
                    }
                    change.applied += applyProperties(change.context, change.factory, change.diff, save, change.changedMappings);
                }
            } finally {
                save.settle(changes);
            }
            // OCC rebuilds from raw definitions, which must already contain every accepted edit.
            for (var change : changes) {
                change.activate();
                refreshMappings(change.context, change.changedMappings, change.diff);
            }
        } finally {
            if (mappingMutation) com.onurkat.reclazz.bootstrap.CacheDependencyLedger.endMutation();
        }
        // New consumers may create changed lazy beans: publish their metadata first.
        for (var change : changes) {
            change.activate();
            for (BeanDefinitionDiff.NewBean add : change.diff.added) {
                try {
                    var listener = change.listeners.get(add.beanName);
                    if (listener != null && listener.definition() == add.newBeanDefinition)
                        SapXmlEventListeners.create(add.beanName, listener, beanLifecycle);
                    else applyNewBean(change.context, change.factory, add);
                    if (change.observed) com.onurkat.reclazz.platform.SapProcessDefinitions.registered(xmlPath, change.context, add.beanName);
                    change.applied++;
                } catch (Exception e) {
                    change.diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(add.beanName,
                            "bean add failed: " + SpringReflection.rootCause(e)));
                }
            }
            if (!change.diff.hasChanges() && change.applied == 0) StatusReporter.info("Spring XML " + xmlPath.getFileName() + change.label
                    + ", parsed OK, no live-applicable changes detected");
            else reportWithLabel(xmlPath, change.applied, change.diff, change.label);
        }
    }

    /** Owner-local resolution and lifecycle after shared raw metadata publication. */
    private int applyProperties(Object appContext, Object liveFactory, BeanDefinitionDiff diff, PropertySave save,
                                java.util.Set<Object> changedMappings) {
        int applied = 0;
        try {
            applied += XmlBeanRecreator.apply(appContext, liveFactory, diff.recreated, beanLifecycle);
        } catch (Exception failure) {
            for (var replacement : diff.recreated)
                diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(replacement.name(),
                        "bean recreation failed: " + com.onurkat.reclazz.ui.Failures.describe(failure)));
        }
        // Pair the lifecycle once per bean, not once per property. Native registries
        // must release the old registration before either the ref or type changes.
        java.util.Map<String, List<BeanDefinitionDiff.PropertyChange>> byBean = new java.util.LinkedHashMap<>();
        for (var change : diff.propertyChanges)
            byBean.computeIfAbsent(change.beanName, ignored -> new ArrayList<>()).add(change);
        java.util.Map<String, java.util.Set<String>> dependencies = new java.util.LinkedHashMap<>();
        java.util.Set<String> failed = new java.util.LinkedHashSet<>();
        for (var entry : byBean.entrySet()) {
            var refs = new java.util.LinkedHashSet<String>();
            dependencies.put(entry.getKey(), refs);
            try {
                for (var change : entry.getValue()) collectReferences(liveFactory, change.newRawValue, refs,
                        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 0, new int[]{4096});
                refs.retainAll(byBean.keySet());
            } catch (Exception failure) {
                failed.add(entry.getKey());
                diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(entry.getKey(),
                        "property dependency inspection failed: " + SpringReflection.rootCause(failure)));
            }
        }
        for (String name : dependencyOrder(dependencies)) {
            if (failed.contains(name)) continue;
            String prerequisite = dependencies.get(name).stream().filter(failed::contains).findFirst().orElse(null);
            if (prerequisite != null) {
                failed.add(name);
                diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(name,
                        "deferred: depends on " + prerequisite + ", which failed"));
                continue;
            }
            Object previous = SpringReflection.getExistingSingleton(liveFactory, name);
            var pending = beanLifecycle.before(appContext, name, previous);
            boolean successful = true;
            try {
                for (var change : byBean.get(name)) {
                    try {
                        applyPropertyChange(liveFactory, change, save);
                        Object singleton = SpringReflection.getExistingSingleton(liveFactory, change.beanName);
                        if (SapFieldSetRefresher.isMapping(singleton)) changedMappings.add(singleton);
                        applied++;
                    } catch (Exception e) {
                        successful = false;
                        diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(
                                change.beanName + "." + change.propertyName,
                                "property apply failed: " + SpringReflection.rootCause(e)));
                    }
                }
            } finally {
                if (!successful) failed.add(name);
                pending.complete(SpringReflection.getExistingSingleton(liveFactory, name), successful);
            }
        }
        return applied;
    }

    /** Inspect only declared raw refs/containers, never an application getter or bean. */
    private static void collectReferences(Object factory, Object raw, java.util.Set<String> refs,
                                          java.util.Set<Object> seen, int depth, int[] remaining) throws Exception {
        if (raw == null || !seen.add(raw)) return;
        if (depth > 32 || --remaining[0] < 0) throw new IllegalArgumentException("property dependency inspection bound exceeded");
        if (raw.getClass().getName().equals("org.springframework.beans.factory.config.RuntimeBeanReference")) {
            if ((Boolean) raw.getClass().getMethod("isToParent").invoke(raw)) return;
            String name = (String) raw.getClass().getMethod("getBeanName").invoke(raw);
            refs.add((String) factory.getClass().getMethod("canonicalName", String.class).invoke(factory, name));
        } else if (raw instanceof java.util.Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                collectReferences(factory, entry.getKey(), refs, seen, depth + 1, remaining);
                collectReferences(factory, entry.getValue(), refs, seen, depth + 1, remaining);
            }
        } else if (raw instanceof java.util.Collection<?> items) {
            for (Object item : items) collectReferences(factory, item, refs, seen, depth + 1, remaining);
        }
    }

    private static List<String> dependencyOrder(java.util.Map<String, java.util.Set<String>> dependencies) {
        var remaining = new java.util.LinkedHashSet<>(dependencies.keySet());
        List<String> ordered = new ArrayList<>();
        while (!remaining.isEmpty()) {
            String ready = remaining.stream().filter(name -> java.util.Collections.disjoint(dependencies.get(name), remaining))
                    .findFirst().orElse(null);
            if (ready != null) { ordered.add(ready); remaining.remove(ready); continue; }
            // Every remaining node has a prerequisite: following them reaches
            // a cycle. Find its strongly connected members, retaining XML order.
            String seed = remaining.iterator().next();
            var path = new java.util.HashSet<String>();
            while (path.add(seed)) seed = dependencies.get(seed).stream().filter(remaining::contains).findFirst().orElseThrow();
            var reachable = reachable(seed, dependencies, remaining);
            List<String> cycle = new ArrayList<>();
            for (String name : remaining)
                if (reachable.contains(name) && reachable(name, dependencies, remaining).contains(seed)) cycle.add(name);
            String reason = "edited beans reference each other; restart to be sure: " + String.join(", ", cycle);
            StatusReporter.warn(reason);
            RestartLedger.note(String.join(", ", cycle), reason);
            ordered.addAll(cycle); remaining.removeAll(cycle);
        }
        return ordered;
    }

    private static java.util.Set<String> reachable(String start, java.util.Map<String, java.util.Set<String>> graph,
                                                  java.util.Set<String> remaining) {
        var visited = new java.util.HashSet<String>();
        var pending = new java.util.ArrayDeque<String>(); pending.add(start);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (remaining.contains(name) && visited.add(name)) pending.addAll(graph.get(name));
        }
        return visited;
    }

    private void refreshMappings(Object appContext, java.util.Set<Object> changedMappings, BeanDefinitionDiff diff) {
        for (String failure : SapFieldSetRefresher.refresh(appContext, changedMappings))
            diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange("OCC field mappings", failure));
    }

    private void reportWithLabel(Path xmlPath, int applied, BeanDefinitionDiff diff, String ctxLabel) {
        String fileName = xmlPath.getFileName().toString();
        StringBuilder summary = new StringBuilder()
                .append("Spring XML ").append(fileName).append(ctxLabel).append(" reloaded: ")
                .append(applied).append(" change").append(applied == 1 ? "" : "s").append(" applied");
        if (!diff.unsafe.isEmpty()) {
            summary.append(", ").append(diff.unsafe.size()).append(" require restart");
        }
        StatusReporter.info(summary.toString());

        if (!diff.unsafe.isEmpty()) {
            for (BeanDefinitionDiff.UnsafeChange unsafe : diff.unsafe) {
                RestartLedger.note(fileName, "bean " + unsafe.beanName + ": " + unsafe.reason);
            }
            StringBuilder warn = new StringBuilder("Restart required for").append(ctxLabel).append(":");
            for (BeanDefinitionDiff.UnsafeChange u : diff.unsafe) {
                warn.append("\n  - ").append(u.beanName).append(" (").append(u.reason).append(")");
            }
            StatusReporter.warn(warn.toString());
        }
    }

    // ─── Property mutation ────────────────────────────────────────────────────

    private void applyPropertyChange(Object liveFactory, BeanDefinitionDiff.PropertyChange change, PropertySave save)
            throws Exception {
        save.requirePublished(liveFactory, change);
        Object singleton = SpringReflection.getExistingSingleton(liveFactory, change.beanName);
        if (singleton == null) {
            // Publication already invalidated every owner's merged cache.
            // Do not create an otherwise unused lazy singleton.
            save.succeeded(liveFactory, change);
            return;
        }

        XmlNativePropertyBinding.Binding binding = XmlNativePropertyBinding.prepare(
                liveFactory, singleton, change.propertyName, change.newRawValue);
        Object resolved = SpringReflection.resolveValue(
                liveFactory, change.beanName, change.newBeanDefinition,
                change.propertyName, change.newRawValue);
        binding.setter().invoke(singleton, binding.convert(resolved));
        // Settle restores failed raw edits; application setter side effects
        // and native instances created during resolution are not rolled back.
        save.succeeded(liveFactory, change);
        StatusReporter.success("Spring property updated: "
                + change.beanName + "." + change.propertyName);
    }

    private final class PropertySave {
        final java.util.Map<Object, Object[]> rawProperties = new java.util.IdentityHashMap<>();
        final java.util.Map<Object, java.util.Set<Object>> definitionOwners = new java.util.IdentityHashMap<>();
        final java.util.Map<Object, java.util.Map<Object, java.util.Set<String>>> definitionNames = new java.util.IdentityHashMap<>();
        final java.util.Map<Object, java.util.Map<Object, java.util.Set<String>>> earlyNames = new java.util.IdentityHashMap<>();
        final java.util.Map<Object, java.util.Map<String, Mirror>> mirrors = new java.util.IdentityHashMap<>();
        final List<Object> factories = new ArrayList<>();
        PropertySave(List<Object> owners) {
            for (Object owner : owners) {
                Object factory = SpringReflection.getBeanFactory(owner);
                if (factory != null) factories.add(factory);
            }
        }
        void capture(Object factory, Object temporary) {
            for (String name : SpringReflection.getBeanDefinitionNames(temporary)) {
                Object definition = SpringReflection.getBeanDefinition(factory, name);
                if (definition == null) continue;
                rawProperties.computeIfAbsent(definition, ignored -> SpringReflection.getPropertyValueArray(
                        SpringReflection.getPropertyValues(definition)));
                var users = definitionOwners.computeIfAbsent(definition, ignored ->
                        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
                // Include owners whose parse failed: their shared metadata must not be advanced.
                for (Object candidate : factories) {
                    if (SpringReflection.getBeanDefinition(candidate, name) != definition) continue;
                    users.add(candidate);
                    definitionNames.computeIfAbsent(definition, ignored -> new java.util.IdentityHashMap<>())
                            .computeIfAbsent(candidate, ignored -> new java.util.LinkedHashSet<>()).add(name);
                }
            }
        }
        void publish(List<OwnerChange> changes) {
            // Gather participants before publishing any value. Shared metadata
            // cannot advance past a missing/unsafe/parse-failed owner.
            for (var owner : changes) for (var change : owner.diff.propertyChanges) {
                Object definition = SpringReflection.getBeanDefinition(owner.factory, change.beanName);
                var mirror = mirrors.computeIfAbsent(definition, ignored -> new java.util.LinkedHashMap<>())
                        .computeIfAbsent(change.propertyName, ignored -> new Mirror(definition, change));
                mirror.plannedOwners.add(owner.factory);
            }
            for (var entry : mirrors.entrySet()) for (Mirror mirror : entry.getValue().values()) {
                try {
                    var users = definitionOwners.get(entry.getKey());
                    if (users == null || !mirror.plannedOwners.containsAll(users))
                        throw new IllegalStateException("shared definition has an owner without an applicable property change");
                    for (Object previous : rawProperties.get(entry.getKey())) {
                        if (!mirror.change.propertyName.equals(SpringReflection.getPropertyName(previous))) continue;
                        mirror.previousPresent = true;
                        mirror.previousRaw = SpringReflection.getPropertyValue(previous);
                    }
                    setRaw(mirror, true);
                    mirror.published = true;
                    var early = earlyNames.computeIfAbsent(entry.getKey(), ignored -> new java.util.IdentityHashMap<>());
                    for (var owner : definitionNames.get(entry.getKey()).entrySet()) for (String name : owner.getValue()) {
                        if (SpringReflection.getExistingSingleton(owner.getKey(), name) != null) continue;
                        early.computeIfAbsent(owner.getKey(), ignored -> new java.util.LinkedHashSet<>()).add(name);
                        invalidate(owner.getKey(), name);
                    }
                } catch (Exception failure) {
                    mirror.failure = SpringReflection.rootCause(failure);
                    if (mirror.published) {
                        try { restore(mirror); }
                        catch (Exception rollback) { mirror.failure += "; raw rollback failed: " + SpringReflection.rootCause(rollback); }
                    }
                }
            }
        }
        void requirePublished(Object factory, BeanDefinitionDiff.PropertyChange change) {
            Object definition = SpringReflection.getBeanDefinition(factory, change.beanName);
            Mirror mirror = mirrors.get(definition).get(change.propertyName);
            if (mirror.failure != null) throw new IllegalStateException("property metadata publication failed: " + mirror.failure);
        }
        void succeeded(Object factory, BeanDefinitionDiff.PropertyChange change) {
            Object definition = SpringReflection.getBeanDefinition(factory, change.beanName);
            if (definition == null) return;
            // Preserve initialized merged metadata, as ordinary property edits
            // always have. Absent lazy owners were invalidated before resolution.
            mirrors.get(definition).get(change.propertyName).appliedOwners.add(factory);
        }
        void settle(List<OwnerChange> changes) {
            for (var entry : mirrors.entrySet()) for (Mirror mirror : entry.getValue().values()) {
                var users = definitionOwners.get(entry.getKey());
                if (!mirror.published || (users != null && mirror.appliedOwners.containsAll(users))) continue;
                try { restore(mirror); }
                catch (Exception failure) {
                    for (var owner : changes) if (users != null && users.contains(owner.factory))
                        owner.diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(mirror.change.beanName,
                                "raw property rollback failed: " + SpringReflection.rootCause(failure)));
                }
            }
        }
        void restore(Mirror mirror) throws Exception {
            setRaw(mirror, false);
            var early = earlyNames.get(mirror.definition);
            if (early != null) for (var owner : early.entrySet())
                for (String name : owner.getValue()) invalidate(owner.getKey(), name);
            mirror.published = false;
        }
        void invalidate(Object factory, String name) throws Exception {
            Method clear = Reflect.findMethod(factory.getClass(), "clearMergedBeanDefinition", String.class);
            if (clear == null) throw new IllegalStateException("merged-definition cache invalidation unavailable");
            clear.invoke(factory, name);
        }
        void setRaw(Mirror mirror, boolean requested) throws Exception {
            Object values = SpringReflection.getPropertyValues(mirror.definition);
            if (values == null) throw new IllegalStateException("mutable property metadata unavailable");
            if (requested || mirror.previousPresent)
                values.getClass().getMethod("add", String.class, Object.class)
                        .invoke(values, mirror.change.propertyName, requested ? mirror.change.newRawValue : mirror.previousRaw);
            else values.getClass().getMethod("removePropertyValue", String.class).invoke(values, mirror.change.propertyName);
        }
    }
    private static final class Mirror {
        final Object definition;
        final BeanDefinitionDiff.PropertyChange change;
        final java.util.Set<Object> plannedOwners = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final java.util.Set<Object> appliedOwners = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Object previousRaw;
        boolean previousPresent, published;
        String failure;
        Mirror(Object definition, BeanDefinitionDiff.PropertyChange change) {
            this.definition = definition; this.change = change;
        }
    }

    // ─── New bean registration ────────────────────────────────────────────────

    private void applyNewBean(Object appContext, Object liveFactory,
                               BeanDefinitionDiff.NewBean add) throws Exception {
        SpringReflection.registerBeanDefinition(liveFactory, add.beanName, add.newBeanDefinition);
        var pending = beanLifecycle.before(appContext, add.beanName, null);
        Object created = null;
        try {
            created = SpringReflection.getBean(appContext, add.beanName);
        } catch (Exception e) {
            // Rollback: any partial singleton + the BD itself.
            SpringReflection.destroySingleton(liveFactory, add.beanName);
            SpringReflection.removeBeanDefinition(liveFactory, add.beanName);
            throw e;
        } finally {
            pending.complete(created, created != null);
        }
        StatusReporter.success("Spring bean added: " + add.beanName);
        StatusReporter.warn("Note: existing @Autowired consumers won't see '" + add.beanName
                + "' until they're re-resolved or the server is restarted. "
                + "Consumers that use applicationContext.getBeansOfType(...) at runtime will pick it up immediately.");
        RestartLedger.note(add.beanName,
                "was added, and beans injected before it are still holding what they were "
                + "injected with");
    }

    // ─── Reflection helpers for setter lookup + primitive coercion ────────────

    /**
     * Collect every live ApplicationContext we can see, union of the
     * platform-specific lookup (e.g. Hybris Registry / master tenant) and
     * the intercept transformer's holder. On Hybris, multiple contexts
     * exist side by side (platform tenant, web contexts for each storefront,
     * backoffice, …) and only one of them actually owns any given XML file.
     */
    private List<Object> gatherCandidateContexts() {
        List<Object> result = new ArrayList<>();

        // Intercept-transformer holder first, captures every Spring
        // context that completes refresh() in this JVM, regardless of
        // Hybris tenant state.
        for (Object ctx : ApplicationContextHolder.getAllContexts()) {
            if (!result.contains(ctx)) result.add(ctx);
        }

        // Platform-specific: on Hybris this is Registry.getMasterTenant().getApplicationContext()
        Object platformCtx = platformContext.getApplicationContext();
        if (platformCtx != null && !result.contains(platformCtx)) {
            result.add(platformCtx);
        }
        return result;
    }

    /**
     * Walk the candidate contexts and return every one whose bean factory
     * already knows at least one of the XML's bean names, each of them is
     * a real "owner" of the XML and needs its own property mutations /
     * bean additions applied.
     */
    private List<Object> pickOwningContexts(List<Object> candidates, String[] beanNames) {
        List<Object> result = new ArrayList<>();
        for (Object ctx : candidates) {
            Object factory = SpringReflection.getBeanFactory(ctx);
            if (factory == null) continue;
            for (String name : beanNames) {
                // annotation-config and component-scan register Spring's own
                // processors under these names in every context that uses them.
                if (name.startsWith("org.springframework.")) continue;
                if (SpringReflection.containsBeanDefinition(factory, name)) {
                    result.add(ctx);
                    break;
                }
            }
        }
        return result;
    }

    static Object coerce(Object value, Class<?> target) {
        if (value == null) return null;
        if (target.isInstance(value)) return value;
        String s = value.toString();
        if (target == String.class) return s;
        if (target == int.class || target == Integer.class) return Integer.valueOf(s);
        if (target == long.class || target == Long.class) return Long.valueOf(s);
        if (target == boolean.class || target == Boolean.class) return Boolean.valueOf(s);
        if (target == double.class || target == Double.class) return Double.valueOf(s);
        if (target == float.class || target == Float.class) return Float.valueOf(s);
        if (target == short.class || target == Short.class) return Short.valueOf(s);
        if (target == byte.class || target == Byte.class) return Byte.valueOf(s);
        // Fall through, setter.invoke will throw IllegalArgumentException
        // with the actual type mismatch if this doesn't work.
        return value;
    }
}
