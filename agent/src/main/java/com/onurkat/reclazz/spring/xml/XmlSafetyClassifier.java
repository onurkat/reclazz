/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Walks every bean definition in a freshly-parsed XML and decides whether each
 * change can be applied live or whether it requires a server restart.
 *
 * Applicability rules (from most to least conservative):
 * <ul>
 *   <li>{@code abstract="true"}, non-singleton scope, {@code factory-bean},
 *       {@code init-method}, {@code destroy-method}, {@code InitializingBean},
 *       {@code DisposableBean}, {@code BeanPostProcessor} →
 *       <b>always unsafe</b></li>
 *   <li>Bean not present in live factory → <b>NEW</b> (safe to add if shape OK)</li>
 *   <li>Bean exists, but {@code class}, {@code parent}, {@code scope}, or
 *       constructor-args differ → <b>unsafe</b> (would need destroy+recreate)</li>
 *   <li>Otherwise, per-property diff of {@code <property>} values — emit
 *       one {@link BeanDefinitionDiff.PropertyChange} per changed property</li>
 * </ul>
 *
 * Stateless: uses {@link SpringReflection} for all factory / BD access so the
 * agent stays free of a compile-time Spring dependency.
 */
final class XmlSafetyClassifier {

    private XmlSafetyClassifier() {}

    static void classify(Object liveFactory, Object tempFactory, Path xmlPath, BeanDefinitionDiff out) {
        String[] newNames = SpringReflection.getBeanDefinitionNames(tempFactory);
        Set<String> newNameSet = new HashSet<>();

        for (String beanName : newNames) {
            newNameSet.add(beanName);
            Object newBd = SpringReflection.getBeanDefinition(tempFactory, beanName);
            if (newBd == null) continue;

            String shapeReason = unsafeShapeReason(newBd);
            if (shapeReason != null) {
                out.unsafe.add(new BeanDefinitionDiff.UnsafeChange(beanName, shapeReason));
                continue;
            }

            boolean exists = SpringReflection.containsBeanDefinition(liveFactory, beanName);
            if (!exists) {
                out.added.add(new BeanDefinitionDiff.NewBean(beanName, newBd));
                continue;
            }

            Object liveBd = SpringReflection.getBeanDefinition(liveFactory, beanName);
            String diffReason = unsafeDiffReason(liveBd, newBd);
            if (diffReason != null) {
                out.unsafe.add(new BeanDefinitionDiff.UnsafeChange(beanName, diffReason));
                continue;
            }

            collectPropertyChanges(beanName, liveBd, newBd, out);
        }

        detectRemovals(liveFactory, xmlPath, newNameSet, out);
    }

    /**
     * Find beans in the live factory whose {@code resourceDescription} points
     * to {@code xmlPath} but which are absent from the newly parsed XML —
     * those beans were removed by the user. Reported as unsafe (report-only);
     * the reloader never calls {@code destroySingleton} for removals.
     *
     * <p>Rationale for report-only: live removal has the same "consumers still
     * hold stale refs" problem as live add, but unlike add, existing
     * {@code @Autowired} consumers calling into a destroyed bean can trip over
     * already-executed {@code destroy-method} side effects (closed connections,
     * stopped threads). The user can just restart to apply the removal — the
     * warning tells them exactly which beans will go away.
     */
    private static void detectRemovals(Object liveFactory, Path xmlPath,
                                        Set<String> newNameSet, BeanDefinitionDiff out) {
        String absPath = xmlPath.toAbsolutePath().toString();
        String fileNameMarker = xmlPath.getFileName().toString() + "]";

        for (String liveName : SpringReflection.getBeanDefinitionNames(liveFactory)) {
            if (newNameSet.contains(liveName)) continue;
            Object liveBd = SpringReflection.getBeanDefinition(liveFactory, liveName);
            if (liveBd == null) continue;

            String res = SpringReflection.getResourceDescription(liveBd);
            if (res == null) continue;

            // Spring formats resourceDescription as e.g. "file [<path>]" or
            // "class path resource [<path>]". Match by absolute path (robust
            // against same-named files in different directories) with a
            // filename-suffix fallback for class-path resources whose
            // recorded path isn't absolute.
            if (!res.contains(absPath) && !res.endsWith(fileNameMarker)) continue;

            out.unsafe.add(new BeanDefinitionDiff.UnsafeChange(
                    liveName, "bean removed from XML — restart to destroy the live instance"));
        }
    }

    /**
     * Reasons that disqualify a bean regardless of the live factory state.
     * Applied to both new beans and existing ones — if a user starts setting
     * an init-method on an existing bean, that counts as an unsafe change.
     */
    private static String unsafeShapeReason(Object bd) {
        if (SpringReflection.isAbstract(bd)) {
            return "abstract bean (template — nothing to instantiate)";
        }
        String scope = SpringReflection.getScope(bd);
        if (scope != null && !scope.isEmpty() && !"singleton".equals(scope)) {
            return "scope=" + scope + " (only singleton beans are reloadable)";
        }
        if (isNonEmpty(SpringReflection.getInitMethodName(bd))) {
            return "has init-method (side effects cannot be rolled back)";
        }
        if (isNonEmpty(SpringReflection.getDestroyMethodName(bd))) {
            return "has destroy-method";
        }
        if (isNonEmpty(SpringReflection.getFactoryBeanName(bd))
                || isNonEmpty(SpringReflection.getFactoryMethodName(bd))) {
            return "factory-bean / factory-method construction";
        }
        if (SpringReflection.hasConstructorArgs(bd)) {
            return "has constructor-arg (not reloadable — restart to apply)";
        }
        String className = SpringReflection.getBeanClassName(bd);
        if (SpringReflection.isPostProcessor(className)) {
            return "BeanPostProcessor / BeanFactoryPostProcessor (affects other beans)";
        }
        if (SpringReflection.isInitializingOrDisposable(className)) {
            return "implements InitializingBean / DisposableBean";
        }
        return null;
    }

    /**
     * Reasons that disqualify a change where the bean already exists in the
     * live factory. These would require destroy + recreate, which is exactly
     * what this reloader is designed to avoid.
     */
    private static String unsafeDiffReason(Object liveBd, Object newBd) {
        if (liveBd == null) return null;

        if (!nullSafeEquals(SpringReflection.getBeanClassName(liveBd),
                            SpringReflection.getBeanClassName(newBd))) {
            return "class attribute changed";
        }
        if (!nullSafeEquals(SpringReflection.getParentName(liveBd),
                            SpringReflection.getParentName(newBd))) {
            return "parent changed";
        }
        if (!nullSafeEquals(normalizeScope(SpringReflection.getScope(liveBd)),
                            normalizeScope(SpringReflection.getScope(newBd)))) {
            return "scope changed";
        }
        // Conservatively reject any constructor-arg. Comparing resolved
        // arg values accurately is expensive and mistakes produce broken
        // beans; users can restart instead.
        if (SpringReflection.hasConstructorArgs(newBd) || SpringReflection.hasConstructorArgs(liveBd)) {
            return "constructor-arg present (not reloadable — restart to apply)";
        }
        return null;
    }

    private static void collectPropertyChanges(String beanName, Object liveBd, Object newBd,
                                                BeanDefinitionDiff out) {
        Object[] newPvs = SpringReflection.getPropertyValueArray(SpringReflection.getPropertyValues(newBd));
        Object[] livePvs = SpringReflection.getPropertyValueArray(SpringReflection.getPropertyValues(liveBd));

        Map<String, Object> liveByName = new HashMap<>();
        for (Object pv : livePvs) {
            liveByName.put(SpringReflection.getPropertyName(pv), SpringReflection.getPropertyValue(pv));
        }

        int changed = 0;
        for (Object pv : newPvs) {
            String name = SpringReflection.getPropertyName(pv);
            Object newRaw = SpringReflection.getPropertyValue(pv);
            Object liveRaw = liveByName.get(name);
            if (!rawEquals(liveRaw, newRaw)) {
                out.propertyChanges.add(new BeanDefinitionDiff.PropertyChange(
                        beanName, name, newRaw, newBd));
                changed++;
            }
        }

        if (changed == 0 && newPvs.length < livePvs.length) {
            // Some property was removed from the XML. Leaving the live
            // instance's field as-is is *usually* harmless, but we flag
            // it so the user knows restart would re-apply defaults.
            out.unsafe.add(new BeanDefinitionDiff.UnsafeChange(
                    beanName, "<property> removed — live instance keeps old value until restart"));
        }
    }

    private static boolean isNonEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static String normalizeScope(String scope) {
        return (scope == null || scope.isEmpty()) ? "singleton" : scope;
    }

    private static boolean nullSafeEquals(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * Compare raw {@code PropertyValue} payloads. Spring's representations
     * (e.g. {@code TypedStringValue}, {@code RuntimeBeanReference}) don't
     * override {@code equals}, so we fall back to a {@code toString}-based
     * compare. Lossy, but only produces false-positive "changed" events
     * which result in a harmless re-apply — never a missed change.
     */
    private static boolean rawEquals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return String.valueOf(a).equals(String.valueOf(b));
    }
}
