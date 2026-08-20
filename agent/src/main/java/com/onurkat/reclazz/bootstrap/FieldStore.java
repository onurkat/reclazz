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

    /**
     * Cached reflective access to the {@code __reclazz$ext} field of a class.
     *
     * On the Class rather than in a map keyed by it: a map entry would keep
     * every class that ever carried an added field, and its classloader, alive
     * for the life of the JVM. A ClassValue is stored on the class and dies
     * with it.
     *
     * ABSENT stands for "this class has no such field", which is not the same
     * as "not looked up yet" and must not be cached as a permanent answer: the
     * field arrives when a later reload adds one.
     */
    private static final Object ABSENT = new Object();

    private static final ClassValue<Object> extFieldCache = new ClassValue<>() {
        @Override
        protected Object computeValue(Class<?> type) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField("__reclazz$ext");
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException notYet) {
                return ABSENT;
            }
        }
    };

    /**
     * One spelling of a class name, because the callers do not agree on one.
     *
     * A companion's bytecode carries the internal name it was generated from,
     * {@code demo/GreetService}, and a call site set up by the bootstrap
     * carries the binary name, {@code demo.GreetService}. Both mean the same
     * class, and while they were separate keys they meant separate storage: a
     * field added by a reload and assigned in a constructor was written under
     * one and read back under the other, so it read as never set.
     */
    private static String layoutKey(String className) {
        return className.replace('/', '.');
    }

    public static int getIndex(String className, String fieldName, String descriptor) {
        FieldLayout layout = layouts.get(layoutKey(className));
        if (layout == null) return -1;
        return layout.getIndex(fieldName, descriptor);
    }

    public static int registerField(String className, String fieldName, String descriptor) {
        FieldLayout layout = layouts.computeIfAbsent(layoutKey(className), k -> new FieldLayout());
        return layout.register(fieldName, descriptor);
    }

    public static int getFieldCount(String className) {
        FieldLayout layout = layouts.get(layoutKey(className));
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
     * Values for static fields added after startup, per owning class.
     *
     * Instance fields added by a reload live in the {@code __reclazz$ext}
     * array on each object. A static field has no object to hang off, and the
     * companion used to fall through to a plain GETSTATIC against the original
     * class on the grounds that adding a static field was unusual. It is not:
     * a constant, a cache, a feature flag. The field is not in the loaded
     * class's schema, so that access threw NoSuchFieldError and killed the
     * thread, after the reload had already reported success.
     *
     * <p>Keyed on the owning {@code Class} through a {@link ClassValue}, for
     * the same reason {@link #extFieldCache} is: a global map keyed by class
     * name would hold both the entry and, through the stored value (an
     * appended enum constant is an <em>instance</em> of the class), the class
     * and its whole classloader alive for the life of the JVM. On a webapp
     * loader that Tomcat discards on redeploy that is a leak of the entire app.
     * A {@code ClassValue} lives on the class and dies with it, so the storage
     * is collected exactly when the class it belongs to is. The inner map is
     * keyed by {@code fieldName + ":" + desc}: the class is already the outer
     * key, so the name no longer has to carry it, and the two spellings a class
     * name arrives in ({@code demo/Foo} vs {@code demo.Foo}) can no longer
     * split one field's storage in two, because the {@code Class} is one
     * identity.
     */
    private static final ClassValue<ConcurrentHashMap<String, Object>> staticValuesByClass =
            new ClassValue<>() {
                @Override
                protected ConcurrentHashMap<String, Object> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<>();
                }
            };

    /**
     * Static field keys written at least once for a class, whatever the value.
     *
     * A null write removes the entry from the value map, so presence there
     * cannot answer "has this field ever been set". The initialiser needs that
     * answer and nothing else does. Held per-class in a {@link ClassValue} for
     * the same collect-with-the-class reason as {@link #staticValuesByClass}.
     */
    private static final ClassValue<java.util.Set<String>> staticsWrittenByClass =
            new ClassValue<>() {
                @Override
                protected java.util.Set<String> computeValue(Class<?> type) {
                    return ConcurrentHashMap.newKeySet();
                }
            };

    private static String staticKey(String fieldName, String desc) {
        return fieldName + ":" + desc;
    }

    /**
     * Read a static field added after startup.
     *
     * Returns the JVM default for the descriptor when nothing has been
     * written yet, which is what a real static field of that type would hold
     * before its initialiser ran. Never null for a primitive: the caller
     * unboxes immediately. A null owner (never emitted by the companion, which
     * loads the class constant) falls back to the default rather than throwing.
     */
    public static Object getStaticExtField(Class<?> owner, String fieldName, String desc) {
        if (owner == null) return defaultValue(desc);
        Object value = staticValuesByClass.get(owner).get(staticKey(fieldName, desc));
        return value != null ? value : defaultValue(desc);
    }

    /** Write a static field added after startup. */
    public static void putStaticExtField(Class<?> owner, String fieldName, String desc, Object value) {
        if (owner == null) return;
        String key = staticKey(fieldName, desc);
        staticsWrittenByClass.get(owner).add(key);
        ConcurrentHashMap<String, Object> values = staticValuesByClass.get(owner);
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
    }

    /**
     * Give an added static field its initial value, once and only once.
     *
     * <p>The JVM cannot add a field to a loaded class, so a field added by a
     * reload never enters the loaded class's schema. Every later reload
     * therefore diffs it as added all over again, and an initialiser that ran
     * each time would wipe the field on every unrelated edit: a cache emptied
     * because somebody changed a method body two files away. So the first
     * write wins, and later reloads leave the field where the application put
     * it.
     *
     * @return true when this call is what set the field
     */
    public static boolean initialiseStaticOnce(Class<?> owner, String fieldName,
                                                String desc, Object value) {
        if (owner == null) return false;
        String key = staticKey(fieldName, desc);
        if (!staticsWrittenByClass.get(owner).add(key)) return false;
        if (value != null) staticValuesByClass.get(owner).put(key, value);
        return true;
    }

    /** Whether an added static field has been written or initialised yet. */
    public static boolean isStaticInitialised(Class<?> owner, String fieldName, String desc) {
        if (owner == null) return false;
        return staticsWrittenByClass.get(owner).contains(staticKey(fieldName, desc));
    }

    /**
     * Same as {@link #putStaticExtField} with the value first, which is the
     * order the value already sits in on the operand stack at a PUTSTATIC.
     * Emitting a swap for a possibly-wide value is fiddlier than an overload.
     */
    public static void putStaticExtFieldSwapped(Object value, Class<?> owner,
                                                 String fieldName, String desc) {
        putStaticExtField(owner, fieldName, desc, value);
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
     * Resolve and cache the {@code __reclazz$ext} Field while reflection can
     * still see it.
     *
     * <p>Root-level reflection filtering (ReflectionRootFilter in the agent)
     * hides the field from {@code getDeclaredField} for everyone, this class
     * included, and the first added-field access on a class usually happens
     * after its first structural reload, which is exactly when the filter goes
     * on. A {@code Field} captured before registration keeps working after it
     * (measured on SapMachine 21 and JBR 25), so the filter calls this before
     * registering and the cache carries the access from then on.
     */
    public static void captureExtField(Class<?> clazz) {
        resolveExtField(clazz);
    }

    /**
     * Resolve the __reclazz$ext field for a class, with caching.
     * Does NOT cache misses — the field may be added later by retransformation.
     */
    private static java.lang.reflect.Field resolveExtField(Class<?> clazz) {
        Object cached = extFieldCache.get(clazz);
        if (cached instanceof java.lang.reflect.Field field) return field;

        // The class had no such field when it was first asked about, and a
        // reload may have added one since. Ask again rather than keeping the
        // old answer.
        extFieldCache.remove(clazz);
        Object fresh = extFieldCache.get(clazz);
        return (fresh instanceof java.lang.reflect.Field field) ? field : null;
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
