/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reloads {@code *-spring.xml} changes into a running Spring context without
 * restarting the server.
 *
 * <h2>Supported changes</h2>
 * <ul>
 *   <li><b>Property value mutations</b> on existing singleton beans. Applied
 *       in place via reflection setters — the bean is NOT destroyed and
 *       recreated, so BeanPostProcessors don't re-run, autowired consumers
 *       keep holding the same instance, and they see the new value on the
 *       next method call. {@code <ref>}, {@code ${placeholder}}, {@code <list>},
 *       SpEL etc. are resolved through Spring's own
 *       {@code BeanDefinitionValueResolver}.</li>
 *   <li><b>New bean registration</b> for beans whose shape is safe
 *       (singleton, no init-method, no constructor-args, not a
 *       BeanPostProcessor). {@code registerBeanDefinition} + {@code getBean}
 *       — if instantiation fails, the partial state is rolled back.</li>
 * </ul>
 *
 * <h2>Not supported</h2>
 * Bean removal, class changes, constructor-arg changes, init-method beans,
 * BeanPostProcessors, {@code InitializingBean}/{@code DisposableBean}
 * implementers. Any such change is collected into a single warning asking
 * the user to restart — the live context stays untouched.
 *
 * <h2>Caveats surfaced to the user</h2>
 * When a brand-new bean is added live, existing {@code @Autowired} consumers
 * won't see it until they're re-resolved or the server restarts (Spring does
 * not re-inject autowired collections on the fly). The reloader prints a
 * note after every new-bean registration so this isn't a surprise.
 *
 * <h2>Failure isolation</h2>
 * Parse errors leave the live context untouched. Apply errors are caught
 * per bean and reclassified as unsafe so the user sees the specific failure
 * without risking a half-applied context.
 */
public final class SpringXmlReloader {

    private final PlatformContext platformContext;

    public SpringXmlReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    public void reload(Path xmlPath) {
        // Gather all candidate application contexts. On Hybris there are
        // usually multiple (platform tenant context, web storefront contexts,
        // backoffice context, etc.) — we have to target the one that owns
        // THIS XML file's beans, not just the first one captured.
        List<Object> candidates = gatherCandidateContexts();
        if (candidates.isEmpty()) {
            StatusReporter.info("Spring XML changed: " + xmlPath.getFileName()
                    + " — no live ApplicationContext yet, change will apply on next startup");
            return;
        }

        // Use the first context's CL as the Spring classloader hint for
        // parsing. All candidates share the same Spring jar (same webapp
        // CL chain), so any will do.
        ClassLoader springCl = null;
        for (Object ctx : candidates) {
            Object bf = SpringReflection.getBeanFactory(ctx);
            if (bf != null) {
                springCl = bf.getClass().getClassLoader();
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
            StatusReporter.warn("Spring XML: DefaultListableBeanFactory not on classpath — skip");
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
                    + ": no existing context recognised these beans — applying to first available context");
        }

        for (int i = 0; i < owningContexts.size(); i++) {
            Object appContext = owningContexts.get(i);
            Object liveFactory = SpringReflection.getBeanFactory(appContext);
            if (liveFactory == null) continue;
            String ctxLabel = owningContexts.size() == 1 ? ""
                    : " [context " + (i + 1) + "/" + owningContexts.size() + "]";
            applyToSingleContext(xmlPath, tempFactory, appContext, liveFactory, ctxLabel);
        }
    }

    /** Run classify + apply + report against a single ApplicationContext. */
    private void applyToSingleContext(Path xmlPath, Object tempFactory,
                                       Object appContext, Object liveFactory, String ctxLabel) {
        BeanDefinitionDiff diff = new BeanDefinitionDiff();
        XmlSafetyClassifier.classify(liveFactory, tempFactory, xmlPath, diff);

        if (!diff.hasChanges()) {
            StatusReporter.info("Spring XML " + xmlPath.getFileName() + ctxLabel
                    + " — parsed OK, no live-applicable changes detected");
            return;
        }

        int applied = 0;
        for (BeanDefinitionDiff.PropertyChange change : diff.propertyChanges) {
            try {
                applyPropertyChange(liveFactory, change);
                applied++;
            } catch (Exception e) {
                diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(
                        change.beanName + "." + change.propertyName,
                        "property apply failed: " + SpringReflection.rootCause(e)));
            }
        }
        for (BeanDefinitionDiff.NewBean add : diff.added) {
            try {
                applyNewBean(appContext, liveFactory, add);
                applied++;
            } catch (Exception e) {
                diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(
                        add.beanName,
                        "bean add failed: " + SpringReflection.rootCause(e)));
            }
        }

        reportWithLabel(xmlPath, applied, diff, ctxLabel);
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
                com.onurkat.reclazz.agent.RestartLedger.note(fileName, "bean " + unsafe.beanName + ": " + unsafe.reason);
            }
            StringBuilder warn = new StringBuilder("Restart required for").append(ctxLabel).append(":");
            for (BeanDefinitionDiff.UnsafeChange u : diff.unsafe) {
                warn.append("\n  - ").append(u.beanName).append(" (").append(u.reason).append(")");
            }
            StatusReporter.warn(warn.toString());
        }
    }

    // ─── Property mutation ────────────────────────────────────────────────────

    private void applyPropertyChange(Object liveFactory, BeanDefinitionDiff.PropertyChange change)
            throws Exception {
        // Always mirror the change into the live BD first — this way any
        // future getBean() call (e.g. a lazy bean that hasn't been touched
        // yet, or Spring scope refresh) produces an instance with the new
        // value, even if we don't have a singleton to mutate right now.
        mirrorPropertyToLiveBeanDefinition(liveFactory, change);

        Object singleton = SpringReflection.getExistingSingleton(liveFactory, change.beanName);
        if (singleton == null) {
            // Bean not instantiated yet — nothing live to mutate. The BD
            // update above is enough.
            return;
        }

        Object resolved = SpringReflection.resolveValue(
                liveFactory, change.beanName, change.newBeanDefinition,
                change.propertyName, change.newRawValue);

        Method setter = findSetter(singleton.getClass(), change.propertyName);
        if (setter == null) {
            throw new RuntimeException("no setter for property '" + change.propertyName
                    + "' on " + singleton.getClass().getName());
        }

        setter.invoke(singleton, coerce(resolved, setter.getParameterTypes()[0]));
        StatusReporter.success("Spring property updated: "
                + change.beanName + "." + change.propertyName);
    }

    private void mirrorPropertyToLiveBeanDefinition(Object liveFactory,
                                                     BeanDefinitionDiff.PropertyChange change) {
        try {
            Object liveBd = SpringReflection.getBeanDefinition(liveFactory, change.beanName);
            if (liveBd == null) return;
            Object pvs = SpringReflection.getPropertyValues(liveBd);
            if (pvs == null) return;
            // MutablePropertyValues.add(name, value) replaces if present.
            Method add = pvs.getClass().getMethod("add", String.class, Object.class);
            add.invoke(pvs, change.propertyName, change.newRawValue);
        } catch (Throwable ignored) {
            // Mirroring is best-effort — if the BD doesn't expose a
            // MutablePropertyValues we continue with the live-instance
            // mutation only.
        }
    }

    // ─── New bean registration ────────────────────────────────────────────────

    private void applyNewBean(Object appContext, Object liveFactory,
                               BeanDefinitionDiff.NewBean add) throws Exception {
        SpringReflection.registerBeanDefinition(liveFactory, add.beanName, add.newBeanDefinition);
        try {
            SpringReflection.getBean(appContext, add.beanName);
        } catch (Exception e) {
            // Rollback: any partial singleton + the BD itself.
            SpringReflection.destroySingleton(liveFactory, add.beanName);
            SpringReflection.removeBeanDefinition(liveFactory, add.beanName);
            throw e;
        }
        StatusReporter.success("Spring bean added: " + add.beanName);
        StatusReporter.warn("Note: existing @Autowired consumers won't see '" + add.beanName
                + "' until they're re-resolved or the server is restarted. "
                + "Consumers that use applicationContext.getBeansOfType(...) at runtime will pick it up immediately.");
        com.onurkat.reclazz.agent.RestartLedger.note(add.beanName,
                "was added, and beans injected before it are still holding what they were "
                + "injected with");
    }

    // ─── Reflection helpers for setter lookup + primitive coercion ────────────

    /**
     * Collect every live ApplicationContext we can see — union of the
     * platform-specific lookup (e.g. Hybris Registry / master tenant) and
     * the intercept transformer's holder. On Hybris, multiple contexts
     * exist side by side (platform tenant, web contexts for each storefront,
     * backoffice, …) and only one of them actually owns any given XML file.
     */
    private List<Object> gatherCandidateContexts() {
        List<Object> result = new ArrayList<>();

        // Intercept-transformer holder first — captures every Spring
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
     * already knows at least one of the XML's bean names — each of them is
     * a real "owner" of the XML and needs its own property mutations /
     * bean additions applied.
     */
    private List<Object> pickOwningContexts(List<Object> candidates, String[] beanNames) {
        List<Object> result = new ArrayList<>();
        for (Object ctx : candidates) {
            Object factory = SpringReflection.getBeanFactory(ctx);
            if (factory == null) continue;
            for (String name : beanNames) {
                if (SpringReflection.containsBeanDefinition(factory, name)) {
                    result.add(ctx);
                    break;
                }
            }
        }
        return result;
    }

    private static Method findSetter(Class<?> cls, String propertyName) {
        if (propertyName == null || propertyName.isEmpty()) return null;
        String setterName = "set"
                + Character.toUpperCase(propertyName.charAt(0))
                + propertyName.substring(1);
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            for (Method m : current.getDeclaredMethods()) {
                if (m.getName().equals(setterName) && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    return m;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Object coerce(Object value, Class<?> target) {
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
        // Fall through — setter.invoke will throw IllegalArgumentException
        // with the actual type mismatch if this doesn't work.
        return value;
    }
}
