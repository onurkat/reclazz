/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import java.util.*;

/** Eligibility only. Spring still merges the original saved annotations and aliases. */
final class ComposedTransactionAnnotations {
    private static final String TX = "Lorg/springframework/transaction/annotation/Transactional;";
    private static final Set<String> JAVA_METADATA = Set.of(
            "Ljava/lang/annotation/Target;", "Ljava/lang/annotation/Retention;",
            "Ljava/lang/annotation/Documented;", "Ljava/lang/annotation/Inherited;", "Ljava/lang/Deprecated;");
    private record Shape(boolean transaction, boolean safe) { }
    private final ClassLoader loader;
    private final Map<String, Shape> shapes = new HashMap<>();

    ComposedTransactionAnnotations(ClassLoader loader) { this.loader = loader; }

    boolean has(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream().anyMatch(a -> shape(a.desc).transaction());
    }
    boolean supported(String descriptor) {
        var shape = shape(descriptor);
        return shape.transaction() && shape.safe();
    }
    boolean composed(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream().anyMatch(a -> !TX.equals(a.desc) && shape(a.desc).transaction());
    }
    private Shape shape(String descriptor) {
        return shapes.computeIfAbsent(descriptor, d -> inspect(d, new HashSet<>()));
    }
    private Shape inspect(String descriptor, Set<String> path) {
        if (TX.equals(descriptor)) return new Shape(true, true);
        if (JAVA_METADATA.contains(descriptor)) return new Shape(false, true);
        if (path.size() >= 64 || !path.add(descriptor)) return new Shape(false, false);
        boolean transaction = false, safe = true;
        try {
            Class<?> type = Class.forName(Type.getType(descriptor).getClassName(), false, loader);
            if (!type.isAnnotation()) return new Shape(false, false);
            for (var annotation : type.getDeclaredAnnotations()) {
                var child = inspect(Type.getDescriptor(annotation.annotationType()), path);
                transaction |= child.transaction();
                safe &= child.safe();
            }
            // A custom marker without transaction semantics is not silently allowed
            // beside a transaction annotation: another framework may own that marker.
            return new Shape(transaction, transaction && safe);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError | java.lang.annotation.AnnotationFormatError unreadable) {
            return new Shape(transaction, false);
        } finally { path.remove(descriptor); }
    }
}
