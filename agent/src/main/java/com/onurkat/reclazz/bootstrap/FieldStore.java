/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages dynamic field storage for fields added after initial class loading.
 * Each instance of a watched class has an Object[] __reclazz$ext field.
 * This class tracks which index in that array corresponds to which field.
 *
 * BOOTSTRAP CLASS: Must have ZERO dependencies outside java.* packages.
 */
public final class FieldStore {

    /**
     * Per-class field layout: maps (fieldName + descriptor) to array index.
     */
    private static final ConcurrentHashMap<String, FieldLayout> layouts = new ConcurrentHashMap<>();

    /** Cached reflective access to __reclazz$ext fields, keyed by Class. */
    private static final ConcurrentHashMap<Class<?>, java.lang.reflect.Field> extFieldCache = new ConcurrentHashMap<>();

    public static int getIndex(String className, String fieldName, String descriptor) {
        FieldLayout layout = layouts.get(className);
        if (layout == null) return -1;
        return layout.getIndex(fieldName, descriptor);
    }

    public static int registerField(String className, String fieldName, String descriptor) {
        FieldLayout layout = layouts.computeIfAbsent(className, k -> new FieldLayout());
        return layout.register(fieldName, descriptor);
    }

    public static int getFieldCount(String className) {
        FieldLayout layout = layouts.get(className);
        return layout == null ? 0 : layout.size();
    }

    /**
     * Get a field value from an instance's __reclazz$ext array.
     * Called from bootstrap method targets.
     */
    public static Object getField(Object[] extArray, int index) {
        if (extArray == null || index < 0 || index >= extArray.length) return null;
        return extArray[index];
    }

    /**
     * Set a field value in an instance's __reclazz$ext array, resizing if needed.
     * Returns the (possibly new) array.
     */
    public static Object[] setField(Object[] extArray, int index, Object value) {
        if (extArray == null) {
            extArray = new Object[Math.max(8, index + 1)];
        }
        if (index >= extArray.length) {
            Object[] newArray = new Object[Math.max(extArray.length * 2, index + 1)];
            System.arraycopy(extArray, 0, newArray, 0, extArray.length);
            extArray = newArray;
        }
        extArray[index] = value;
        return extArray;
    }

    /**
     * Get a dynamically-added field value from an instance.
     * Called from companion class bytecode for GETFIELD on added fields.
     *
     * @param instance   the object instance (receiver of the field access)
     * @param className  internal class name (e.g., "com/example/MyService")
     * @param fieldName  field name
     * @param desc       field descriptor
     * @return the field value (boxed for primitives), or null if not set
     */
    public static Object getExtField(Object instance, String className,
                                      String fieldName, String desc) {
        int index = getIndex(className, fieldName, desc);
        if (index < 0) {
            // Field not registered yet — register it and return the JVM
            // default for the type (never null for primitives: the caller
            // unboxes, and null would NPE where real field semantics give 0)
            registerField(className, fieldName, desc);
            return defaultValue(desc);
        }
        try {
            java.lang.reflect.Field extField = resolveExtField(instance.getClass());
            if (extField == null) return defaultValue(desc);
            // Synchronize on instance to prevent reading a stale ext array
            // while putExtField is resizing it on another thread
            synchronized (instance) {
                Object[] extArray = (Object[]) extField.get(instance);
                Object value = getField(extArray, index);
                return value != null ? value : defaultValue(desc);
            }
        } catch (Exception e) {
            return defaultValue(desc);
        }
    }

    /**
     * Values for static fields added after startup, keyed by class and field.
     *
     * Instance fields added by a reload live in the {@code __reclazz$ext}
     * array on each object. A static field has no object to hang off, and the
     * companion used to fall through to a plain GETSTATIC against the original
     * class on the grounds that adding a static field was unusual. It is not:
     * a constant, a cache, a feature flag. The field is not in the loaded
     * class's schema, so that access threw NoSuchFieldError and killed the
     * thread, after the reload had already reported success.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> staticValues =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String staticKey(String className, String fieldName, String desc) {
        return className + "#" + fieldName + ":" + desc;
    }

    /**
     * Read a static field added after startup.
     *
     * Returns the JVM default for the descriptor when nothing has been
     * written yet, which is what a real static field of that type would hold
     * before its initialiser ran. Never null for a primitive: the caller
     * unboxes immediately.
     */
    public static Object getStaticExtField(String className, String fieldName, String desc) {
        Object value = staticValues.get(staticKey(className, fieldName, desc));
        return value != null ? value : defaultValue(desc);
    }

    /** Write a static field added after startup. */
    public static void putStaticExtField(String className, String fieldName, String desc, Object value) {
        String key = staticKey(className, fieldName, desc);
        if (value == null) {
            staticValues.remove(key);
        } else {
            staticValues.put(key, value);
        }
    }

    /**
     * Same as {@link #putStaticExtField} with the value first, which is the
     * order the value already sits in on the operand stack at a PUTSTATIC.
     * Emitting a swap for a possibly-wide value is fiddlier than an overload.
     */
    public static void putStaticExtFieldSwapped(Object value, String className,
                                                 String fieldName, String desc) {
        putStaticExtField(className, fieldName, desc, value);
    }

    /** JVM default value for a field descriptor (boxed for primitives). */
    private static Object defaultValue(String desc) {
        if (desc == null || desc.isEmpty()) return null;
        switch (desc.charAt(0)) {
            case 'I': return 0;
            case 'J': return 0L;
            case 'S': return (short) 0;
            case 'B': return (byte) 0;
            case 'C': return (char) 0;
            case 'Z': return Boolean.FALSE;
            case 'F': return 0f;
            case 'D': return 0d;
            default:  return null; // reference or array type
        }
    }

    /**
     * Set a dynamically-added field value on an instance.
     * Called from companion class bytecode for PUTFIELD on added fields.
     * Synchronized on the instance to prevent race conditions during ext array resize.
     *
     * @param instance   the object instance (receiver of the field access)
     * @param boxedValue the value to set (already boxed if primitive)
     * @param className  internal class name
     * @param fieldName  field name
     * @param desc       field descriptor
     */
    public static void putExtField(Object instance, Object boxedValue,
                                    String className, String fieldName, String desc) {
        int index = registerField(className, fieldName, desc);
        try {
            java.lang.reflect.Field extField = resolveExtField(instance.getClass());
            if (extField == null) return;
            // Synchronize on instance to prevent race on ext array resize (#8)
            synchronized (instance) {
                Object[] extArray = (Object[]) extField.get(instance);
                Object[] newArray = setField(extArray, index, boxedValue);
                if (newArray != extArray) {
                    extField.set(instance, newArray);
                }
            }
        } catch (Exception e) {
            // Silently fail — field store not available for this instance
        }
    }

    /**
     * Resolve the __reclazz$ext field for a class, with caching.
     * Does NOT cache misses — the field may be added later by retransformation.
     */
    private static java.lang.reflect.Field resolveExtField(Class<?> clazz) {
        java.lang.reflect.Field cached = extFieldCache.get(clazz);
        if (cached != null) return cached;
        try {
            java.lang.reflect.Field f = clazz.getDeclaredField("__reclazz$ext");
            f.setAccessible(true);
            extFieldCache.put(clazz, f);
            return f;
        } catch (NoSuchFieldException e) {
            // Class doesn't have __reclazz$ext yet — don't cache, it may be added later
            return null;
        }
    }

    static final class FieldLayout {
        private final ConcurrentHashMap<String, Integer> indices = new ConcurrentHashMap<>();
        private volatile int nextIndex = 0;

        int getIndex(String fieldName, String descriptor) {
            Integer idx = indices.get(fieldName + ":" + descriptor);
            return idx == null ? -1 : idx;
        }

        synchronized int register(String fieldName, String descriptor) {
            String key = fieldName + ":" + descriptor;
            Integer existing = indices.get(key);
            if (existing != null) return existing;
            int idx = nextIndex++;
            indices.put(key, idx);
            return idx;
        }

        int size() { return nextIndex; }
    }
}
