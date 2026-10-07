/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Ordinary property edits cannot reconstruct initialization state, ordering or selection policy. */
final class XmlDefinitionMetadataGuard {
    private XmlDefinitionMetadataGuard() { }

    static boolean classify(String name, Object previous, Object candidate, BeanDefinitionDiff diff) {
        if (previous == null) return false; // New definitions retain native Spring creation.
        try {
            Metadata before = metadata(previous), after = metadata(candidate);
            List<String> changed = new ArrayList<>();
            if (!before.dependsOn.equals(after.dependsOn)) changed.add("depends-on");
            if (before.primary != after.primary) changed.add("primary");
            if (before.autowire != after.autowire) changed.add("autowire");
            if (before.candidate != after.candidate) changed.add("autowire-candidate");
            if (before.lazy != after.lazy) changed.add("lazy-init");
            if (changed.isEmpty()) return false;
            diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(name,
                    "definition metadata changed (" + String.join(", ", changed)
                            + "); restart required, bean and definition were not updated"));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(name,
                    "cannot verify definition metadata; restart required: "
                            + SpringReflection.rootCause(failure)));
        }
        return true;
    }

    private record Metadata(List<String> dependsOn, boolean primary, int autowire, boolean candidate, boolean lazy) { }

    private static Metadata metadata(Object definition) throws ReflectiveOperationException {
        String[] dependencies = (String[]) definition.getClass().getMethod("getDependsOn").invoke(definition);
        // XML parser whitespace/comma differences are normalized by Spring. Keep
        // dependency order meaningful, but omitted and empty declarations equivalent.
        List<String> dependsOn = dependencies == null ? List.of() : Arrays.asList(dependencies);
        return new Metadata(dependsOn,
                (Boolean) definition.getClass().getMethod("isPrimary").invoke(definition),
                (Integer) definition.getClass().getMethod("getAutowireMode").invoke(definition),
                (Boolean) definition.getClass().getMethod("isAutowireCandidate").invoke(definition),
                // Spring's parsed flag accounts for default-lazy-init and explicit defaults.
                (Boolean) definition.getClass().getMethod("isLazyInit").invoke(definition));
    }
}
