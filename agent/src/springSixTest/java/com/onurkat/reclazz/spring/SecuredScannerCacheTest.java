/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodClassKey;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.authorization.method.SecuredAuthorizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SecuredScannerCacheTest {
    public static class Owner { @Secured("ROLE_OWNER") public void work() {} }
    public static class Other { @Secured("ROLE_CONTROL") public void control() {} }

    @Test void ownerScannerIsEvictedBeforeRefillAndOtherOwnersStayIdentical() throws Exception {
        var manager = new SecuredAuthorizationManager();
        Field scannerField;
        try { scannerField = manager.getClass().getDeclaredField("scanner"); }
        catch (NoSuchFieldException legacy) { assumeTrue(false, "Security6 has no scanner cache"); return; }
        scannerField.setAccessible(true);
        Object scanner = scannerField.get(manager);
        Object unique = field(scanner, "unique");
        @SuppressWarnings("unchecked")
        Map<Object,Object> cache = (Map<Object,Object>)field(unique, "uniqueMethodAnnotationCache");
        Method resolve = manager.getClass().getDeclaredMethod("resolveAuthorities", Method.class, Class.class);
        resolve.setAccessible(true);
        Method work = Owner.class.getDeclaredMethod("work"), control = Other.class.getDeclaredMethod("control");
        Object ownerKey = new MethodClassKey(work, Owner.class), otherKey = new MethodClassKey(control, Other.class);
        try {
            assertEquals(Set.of("ROLE_OWNER"), resolve.invoke(manager, work, Owner.class));
            assertEquals(Set.of("ROLE_CONTROL"), resolve.invoke(manager, control, Other.class));
            Object otherAnnotation = cache.get(otherKey);
            assertNotNull(otherAnnotation);
            // A stale scanner answer must not be used to refill the outer authority cache.
            cache.put(ownerKey, otherAnnotation);
            assertEquals(Set.of("ROLE_CONTROL"), resolve.invoke(manager, work, Owner.class));
            assertTrue(SpringSecurityReloader.clearMethodSecurityCaches(manager, 0, Owner.class) > 0);
            @SuppressWarnings("unchecked")
            Map<Object,Object> authorities = (Map<Object,Object>)field(manager, "cachedAuthorities");
            assertEquals(Set.of("ROLE_OWNER"), authorities.get(ownerKey), "Refill must follow scoped scanner eviction");
            assertEquals(Set.of("ROLE_OWNER"), resolve.invoke(manager, work, Owner.class));
            assertSame(otherAnnotation, cache.get(otherKey), "Unrelated scanner entry must not be cleared or recreated");
            assertEquals(Set.of("ROLE_CONTROL"), resolve.invoke(manager, control, Other.class));
            assertSame(otherAnnotation, cache.get(otherKey));
        } finally {
            cache.remove(ownerKey);
            cache.remove(otherKey);
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
