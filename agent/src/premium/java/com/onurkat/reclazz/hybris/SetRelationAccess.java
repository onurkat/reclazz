/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Verify newly installed SET relation endpoints before announcing that application saves can use them. */
final class SetRelationAccess {
    private static final String TYPES = "de.hybris.platform.jalo.type.";
    private final Map<Object, Set<String>> pending = new WeakHashMap<>();

    static Map<String, Object> relations(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> manager = Class.forName(TYPES + "TypeManager", false, loader);
        Class<?> relation = Class.forName(TYPES + "RelationType", false, loader);
        Object tm = manager.getMethod("getInstance").invoke(null);
        var types = (Collection<?>) manager.getMethod("getAllComposedTypes").invoke(tm);
        var result = new LinkedHashMap<String, Object>();
        Method code = relation.getMethod("getCode");
        for (Object type : types) {
            if (relation.isInstance(type)) result.put((String) code.invoke(type), type);
        }
        return result;
    }

    synchronized Set<String> verify(Object tenant, ClassLoader loader, Set<String> before) throws ReflectiveOperationException {
        var current = relations(loader);
        var check = pending.computeIfAbsent(tenant, ignored -> new LinkedHashSet<>());
        check.retainAll(current.keySet());
        for (String code : current.keySet()) {
            if (!before.contains(code)) check.add(code);
        }
        for (var it = check.iterator(); it.hasNext();) {
            Object relation = current.get(it.next());
            if (Boolean.TRUE.equals(relation.getClass().getMethod("isOneToMany").invoke(relation))
                    || endpointsReady(tenant, loader, relation)) it.remove();
        }
        if (check.isEmpty()) pending.remove(tenant);
        return Set.copyOf(check);
    }

    private static boolean endpointsReady(Object tenant, ClassLoader loader, Object relation) throws ReflectiveOperationException {
        Class<?> collection = Class.forName(TYPES + "CollectionType", false, loader);
        int set = collection.getField("SET").getInt(null);
        for (String method : new String[]{"getSourceAttributeDescriptor", "getTargetAttributeDescriptor"}) {
            Object descriptor = relation.getClass().getMethod(method).invoke(relation);
            if (descriptor == null) continue;
            Object type = descriptor.getClass().getMethod("getAttributeType").invoke(descriptor);
            if (!collection.isInstance(type) || (int) collection.getMethod("getTypeOfCollection").invoke(type) != set) continue;
            if (!hasReflectiveAccessor(tenant, loader, descriptor)) return false;
        }
        return true;
    }

    private static boolean hasReflectiveAccessor(Object tenant, ClassLoader loader, Object descriptor) throws ReflectiveOperationException {
        Object owner = descriptor.getClass().getMethod("getEnclosingType").invoke(descriptor);
        Class<?> jalo = (Class<?>) owner.getClass().getMethod("getJaloClass").invoke(owner);
        String qualifier = (String) descriptor.getClass().getMethod("getQualifier").invoke(descriptor);
        Class<?> item = Class.forName("de.hybris.platform.jalo.Item", false, loader);
        Class<?> tenantType = Class.forName("de.hybris.platform.core.Tenant", false, loader);
        Class<?> descriptorType = Class.forName(TYPES + "AttributeDescriptor", false, loader);
        Class<?> reflection = Class.forName(TYPES + "ReflectionAttributeAccess", false, loader);
        // Follow the native Item.getAccessorFor order, including an already cached generic accessor.
        // Resolving from descriptor and class also works before a new item type has its first row.
        Method registered = item.getDeclaredMethod("getRegisteredClassAccessesorFor", tenantType, Class.class, String.class);
        Method byDescriptor = item.getDeclaredMethod("getRegisteredNonClassAccessesorFor", tenantType, descriptorType);
        registered.setAccessible(true);
        byDescriptor.setAccessible(true);
        Object access = registered.invoke(null, tenant, jalo, qualifier);
        if (access == null) access = byDescriptor.invoke(null, tenant, descriptor);
        if (access == null) access = reflection.getMethod("createReflectionAccess", tenantType, Class.class, descriptorType)
                .invoke(null, tenant, jalo, descriptor);
        return reflection.isInstance(access);
    }
}
