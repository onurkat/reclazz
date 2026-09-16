/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import java.util.*;

/** Eligibility only. Spring still merges the original saved annotations and aliases. */
final class ComposedCacheAnnotations {
    private static final Set<String> CACHE = Set.of("Lorg/springframework/cache/annotation/Cacheable;",
            "Lorg/springframework/cache/annotation/CachePut;", "Lorg/springframework/cache/annotation/CacheEvict;",
            "Lorg/springframework/cache/annotation/Caching;");
    private static final Set<String> JAVA_METADATA = Set.of(
            "Ljava/lang/annotation/Target;", "Ljava/lang/annotation/Retention;",
            "Ljava/lang/annotation/Documented;", "Ljava/lang/annotation/Inherited;", "Ljava/lang/Deprecated;");
    private record Shape(boolean cache, boolean safe) { }
    private final ClassLoader loader;
    private final Map<String, Shape> shapes = new HashMap<>();

    ComposedCacheAnnotations(ClassLoader loader) { this.loader = loader; }

    boolean has(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream().anyMatch(a -> shape(a.desc).cache());
    }
    boolean supported(String descriptor) {
        var shape = shape(descriptor);
        return shape.cache() && shape.safe();
    }
    boolean composed(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream().anyMatch(a -> !CACHE.contains(a.desc) && shape(a.desc).cache());
    }
    private Shape shape(String descriptor) {
        return shapes.computeIfAbsent(descriptor, d -> inspect(d, new HashSet<>()));
    }
    private Shape inspect(String descriptor, Set<String> path) {
        if (CACHE.contains(descriptor)) return new Shape(true, true);
        if (JAVA_METADATA.contains(descriptor)) return new Shape(false, true);
        if (path.size() >= 64 || !path.add(descriptor)) return new Shape(false, false);
        boolean cache = false, safe = true;
        try {
            Class<?> type = Class.forName(Type.getType(descriptor).getClassName(), false, loader);
            if (!type.isAnnotation()) return new Shape(false, false);
            for (var annotation : type.getDeclaredAnnotations()) {
                var child = inspect(Type.getDescriptor(annotation.annotationType()), path);
                cache |= child.cache();
                safe &= child.safe();
            }
            // A custom marker without cache semantics is not silently allowed
            // beside a cache annotation: another framework may own that marker.
            return new Shape(cache, cache && safe);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError | java.lang.annotation.AnnotationFormatError unreadable) {
            return new Shape(cache, false);
        } finally { path.remove(descriptor); }
    }
}
