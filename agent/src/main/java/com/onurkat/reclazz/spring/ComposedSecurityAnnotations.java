/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import java.util.*;

/** Eligibility and required policies only; native Spring evaluates the saved annotations. */
final class ComposedSecurityAnnotations {
    private static final String PRE = "Lorg/springframework/security/access/prepost/PreAuthorize;";
    private static final String POST = "Lorg/springframework/security/access/prepost/PostAuthorize;";
    private static final Set<String> JAVA_METADATA = Set.of(
            "Ljava/lang/annotation/Target;", "Ljava/lang/annotation/Retention;",
            "Ljava/lang/annotation/Documented;", "Ljava/lang/annotation/Inherited;", "Ljava/lang/Deprecated;");
    private record Shape(int policies, boolean safe) { }
    private final ClassLoader loader;
    private final Map<String, Shape> shapes = new HashMap<>();

    ComposedSecurityAnnotations(ClassLoader loader) { this.loader = loader; }

    boolean supported(String descriptor) {
        var shape = shape(descriptor);
        return shape.policies() != 0 && shape.safe();
    }
    int policies(Class<?> annotation) { return shape(Type.getDescriptor(annotation)).policies(); }
    boolean duplicatePolicies(List<AnnotationNode> annotations) {
        int seen = 0;
        if (annotations != null) for (var annotation : annotations) {
            int flags = shape(annotation.desc).policies();
            if ((seen & flags) != 0) return true;
            seen |= flags;
        }
        return false;
    }
    private Shape shape(String descriptor) {
        return shapes.computeIfAbsent(descriptor, d -> inspect(d, new HashSet<>()));
    }
    private Shape inspect(String descriptor, Set<String> path) {
        if (PRE.equals(descriptor)) return new Shape(1, true);
        if (POST.equals(descriptor)) return new Shape(2, true);
        if (JAVA_METADATA.contains(descriptor)) return new Shape(0, true);
        if (path.size() >= 64 || !path.add(descriptor)) return new Shape(0, false);
        int policies = 0;
        boolean safe = true;
        try {
            Class<?> type = Class.forName(Type.getType(descriptor).getClassName(), false, loader);
            if (!type.isAnnotation()) return new Shape(0, false);
            // Marker policies have fixed expressions. Alias/template parameters are
            // version-sensitive and must not be silently treated as fixed defaults.
            safe = type.getDeclaredMethods().length == 0;
            for (var annotation : type.getDeclaredAnnotations()) {
                var child = inspect(Type.getDescriptor(annotation.annotationType()), path);
                safe &= child.safe() && (policies & child.policies()) == 0;
                policies |= child.policies();
            }
            return new Shape(policies, policies != 0 && safe);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError | java.lang.annotation.AnnotationFormatError unreadable) {
            return new Shape(policies, false);
        } finally { path.remove(descriptor); }
    }
}
