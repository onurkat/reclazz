/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.SpringXmlAliases;
import java.nio.file.Path;
import java.util.*;

/** Reports unapplied declaration changes only; does not retarget or reinject live consumers. */
final class XmlAliasDiagnostics {
    private XmlAliasDiagnostics() { }

    static void classify(Object context, Object factory, Object parsed, Path path, BeanDefinitionDiff diff) {
        Map<String, String> next = SpringXmlAliases.snapshot(parsed, path);
        if (next == null) {
            diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange("XML aliases",
                    "alias metadata unavailable; cannot verify changes, restart required"));
            return;
        }
        Map<String, String> old = SpringXmlAliases.snapshot(factory, path);
        if (old == null) old = SpringXmlAliases.snapshot(context, path);
        if (old == null) {
            // Never assign every live alias of a bean to this file: other files may own them.
            if (!next.isEmpty()) diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange("XML aliases",
                    "alias ownership baseline unavailable for " + next.keySet()
                            + "; changes are unverified and not applied, restart required"));
            return;
        }
        Set<String> names = new TreeSet<>(old.keySet()); names.addAll(next.keySet());
        for (String name : names) {
            if (Objects.equals(old.get(name), next.get(name))) continue;
            String action = !old.containsKey(name) ? "added" : !next.containsKey(name) ? "removed" : "retargeted";
            diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange("alias '" + name + "'",
                    "alias declaration " + action + "; live aliases were not changed, restart required"));
        }
    }
}
