/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.config;

import java.nio.file.Path;
import java.util.*;
import java.lang.ref.WeakReference;

/** Derived admission evidence travels with its Runtime revision, never in a second publication. */
public record WatchInventory(Set<String> names, Set<Path> roots, Set<String> backofficeNames,
                             Set<Path> backofficeRoots, Map<String,List<WeakReference<Class<?>>>> rawDefinitions) {
    public WatchInventory {
        names=Set.copyOf(names); roots=Set.copyOf(roots); backofficeNames=Set.copyOf(backofficeNames);
        backofficeRoots=Set.copyOf(backofficeRoots);
        var copy=new HashMap<String,List<WeakReference<Class<?>>>>();
        rawDefinitions.forEach((name,definitions)->copy.put(name,List.copyOf(definitions)));
        rawDefinitions=Map.copyOf(copy);
    }
}
