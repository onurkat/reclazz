/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Creates synthetic java.lang.reflect.Method and Field objects that represent
 * structurally-added members. These forged objects appear in getDeclaredMethods()
 * results (via ReflectionBridge) and support Method.invoke() by dispatching
 * through the DispatchTable's companion class MethodHandle.
 *
 * Uses sun.misc.Unsafe to allocate uninitialized Method/Field instances and
 * set their private fields. Requires:
 *   --add-opens java.base/java.lang.reflect=ALL-UNNAMED
 *   --add-opens java.base/jdk.internal.reflect=ALL-UNNAMED
 *
 * BOOTSTRAP CLASS: Must have ZERO dependencies outside java.* / sun.* packages.
 */
public final class MethodForge {

    private static final sun.misc.Unsafe UNSAFE;
    // Method field offsets
    private static final long METHOD_CLAZZ_OFFSET;
    private static final long METHOD_NAME_OFFSET;
    private static final long METHOD_RETURN_TYPE_OFFSET;
    private static final long METHOD_PARAM_TYPES_OFFSET;
    private static final long METHOD_MODIFIERS_OFFSET;
    private static final long METHOD_SLOT_OFFSET;
    private static final long METHOD_SIGNATURE_OFFSET;
    private static final long METHOD_ANNOTATIONS_OFFSET;
    private static final long METHOD_ACCESSOR_OFFSET;
    // Field field offsets
    private static final long FIELD_CLAZZ_OFFSET;
    private static final long FIELD_NAME_OFFSET;
    private static final long FIELD_TYPE_OFFSET;
    private static final long FIELD_MODIFIERS_OFFSET;
    private static final long FIELD_SLOT_OFFSET;
    private static final long FIELD_SIGNATURE_OFFSET;

    private static final boolean AVAILABLE;

    static {
        sun.misc.Unsafe u = null;
        long mClazz = -1, mName = -1, mRet = -1, mParams = -1, mMods = -1;
        long mSlot = -1, mSig = -1, mAnnot = -1, mAccessor = -1;
        long fClazz = -1, fName = -1, fType = -1, fMods = -1, fSlot = -1, fSig = -1;
        boolean avail = false;

        try {
            Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            u = (sun.misc.Unsafe) theUnsafe.get(null);

            // Method field offsets
            mClazz = u.objectFieldOffset(Method.class.getDeclaredField("clazz"));
            mName = u.objectFieldOffset(Method.class.getDeclaredField("name"));
            mRet = u.objectFieldOffset(Method.class.getDeclaredField("returnType"));
            mParams = u.objectFieldOffset(Method.class.getDeclaredField("parameterTypes"));
            mMods = u.objectFieldOffset(Method.class.getDeclaredField("modifiers"));
            mSlot = u.objectFieldOffset(Method.class.getDeclaredField("slot"));
            mSig = u.objectFieldOffset(Method.class.getDeclaredField("signature"));
            mAnnot = u.objectFieldOffset(Method.class.getDeclaredField("annotations"));

            // MethodAccessor field — in jdk.internal.reflect package
            mAccessor = u.objectFieldOffset(Method.class.getDeclaredField("methodAccessor"));

            // Field field offsets
            fClazz = u.objectFieldOffset(Field.class.getDeclaredField("clazz"));
            fName = u.objectFieldOffset(Field.class.getDeclaredField("name"));
            fType = u.objectFieldOffset(Field.class.getDeclaredField("type"));
            fMods = u.objectFieldOffset(Field.class.getDeclaredField("modifiers"));
            fSlot = u.objectFieldOffset(Field.class.getDeclaredField("slot"));
            fSig = u.objectFieldOffset(Field.class.getDeclaredField("signature"));

            avail = true;
        } catch (Exception e) {
            // The internal layout of java.lang.reflect.Method changed in JDK
            // 17+: the 'clazz', 'name', 'slot', etc. fields no longer exist
            // as direct declared fields on Method. Reflection patching (which
            // forges synthetic Method/Field objects so reflective scans see
            // structurally-added members) is unavailable on these JVMs. Hot
            // reload itself still works — only the reflection-based view of
            // newly-added methods/fields is degraded. Stay quiet here; the
            // outer agent init logs the degraded mode once with the rest of
            // the capability summary so users see it in context, not as a
            // scary stderr dump on every reload.
        }

        UNSAFE = u;
        METHOD_CLAZZ_OFFSET = mClazz;
        METHOD_NAME_OFFSET = mName;
        METHOD_RETURN_TYPE_OFFSET = mRet;
        METHOD_PARAM_TYPES_OFFSET = mParams;
        METHOD_MODIFIERS_OFFSET = mMods;
        METHOD_SLOT_OFFSET = mSlot;
        METHOD_SIGNATURE_OFFSET = mSig;
        METHOD_ANNOTATIONS_OFFSET = mAnnot;
        METHOD_ACCESSOR_OFFSET = mAccessor;
        FIELD_CLAZZ_OFFSET = fClazz;
        FIELD_NAME_OFFSET = fName;
        FIELD_TYPE_OFFSET = fType;
        FIELD_MODIFIERS_OFFSET = fMods;
        FIELD_SLOT_OFFSET = fSlot;
        FIELD_SIGNATURE_OFFSET = fSig;
        AVAILABLE = avail;
    }

    /**
     * Whether MethodForge is operational (Unsafe available + field offsets resolved).
     */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * Forge a synthetic Method object that appears to belong to the given class.
     * When invoke() is called, it dispatches through the provided MethodHandle
     * (which points to the companion class implementation).
     *
     * @param declaringClass the class the method should appear to belong to
     * @param name           the method name
     * @param parameterTypes parameter types
     * @param returnType     return type
     * @param modifiers      access modifiers (e.g., Modifier.PUBLIC)
     * @param dispatchMH     MethodHandle to the companion class method (for invoke support)
     * @param annotations    raw annotation bytes (may be null)
     * @return a forged Method object, or null if forge is unavailable
     */
    public static Method forgeMethod(Class<?> declaringClass, String name,
                                      Class<?>[] parameterTypes, Class<?> returnType,
                                      int modifiers, MethodHandle dispatchMH,
                                      byte[] annotations) {
        if (!AVAILABLE) return null;

        try {
            // Allocate an uninitialized Method object
            Method m = (Method) UNSAFE.allocateInstance(Method.class);

            // Set core fields
            UNSAFE.putObject(m, METHOD_CLAZZ_OFFSET, declaringClass);
            UNSAFE.putObject(m, METHOD_NAME_OFFSET, name);
            UNSAFE.putObject(m, METHOD_RETURN_TYPE_OFFSET, returnType);
            UNSAFE.putObject(m, METHOD_PARAM_TYPES_OFFSET, parameterTypes.clone());
            UNSAFE.putInt(m, METHOD_MODIFIERS_OFFSET, modifiers);
            UNSAFE.putInt(m, METHOD_SLOT_OFFSET, Integer.MAX_VALUE); // synthetic slot — avoids -1 clash
            UNSAFE.putObject(m, METHOD_SIGNATURE_OFFSET, null);

            if (annotations != null) {
                UNSAFE.putObject(m, METHOD_ANNOTATIONS_OFFSET, annotations);
            }

            // Set up MethodAccessor for invoke() support via dynamic proxy
            if (dispatchMH != null) {
                Object accessor = createMethodAccessor(dispatchMH);
                if (accessor != null) {
                    UNSAFE.putObject(m, METHOD_ACCESSOR_OFFSET, accessor);
                }
            }

            return m;
        } catch (Exception e) {
            System.err.println("[Reclazz] Failed to forge Method " + name + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Forge a synthetic Field object that appears to belong to the given class.
     * The field's get/set operations are backed by FieldStore.
     *
     * @param declaringClass the class the field should appear to belong to
     * @param name           the field name
     * @param type           the field type
     * @param modifiers      access modifiers
     * @return a forged Field object, or null if forge is unavailable
     */
    public static Field forgeField(Class<?> declaringClass, String name,
                                    Class<?> type, int modifiers) {
        if (!AVAILABLE) return null;

        try {
            Field f = (Field) UNSAFE.allocateInstance(Field.class);

            UNSAFE.putObject(f, FIELD_CLAZZ_OFFSET, declaringClass);
            UNSAFE.putObject(f, FIELD_NAME_OFFSET, name);
            UNSAFE.putObject(f, FIELD_TYPE_OFFSET, type);
            UNSAFE.putInt(f, FIELD_MODIFIERS_OFFSET, modifiers);
            UNSAFE.putInt(f, FIELD_SLOT_OFFSET, Integer.MAX_VALUE);
            UNSAFE.putObject(f, FIELD_SIGNATURE_OFFSET, null);

            return f;
        } catch (Exception e) {
            System.err.println("[Reclazz] Failed to forge Field " + name + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Create a MethodAccessor proxy that dispatches Method.invoke() calls
     * through a MethodHandle pointing to the companion class implementation.
     *
     * Uses jdk.internal.reflect.MethodAccessor interface via dynamic proxy.
     */
    private static Object createMethodAccessor(MethodHandle dispatchMH) {
        try {
            // Load the MethodAccessor interface from the bootstrap classloader
            Class<?> methodAccessorClass = Class.forName("jdk.internal.reflect.MethodAccessor");

            // Create a proxy that implements MethodAccessor and dispatches via the MethodHandle
            return Proxy.newProxyInstance(
                    null, // bootstrap classloader
                    new Class<?>[]{methodAccessorClass},
                    new MethodHandleAccessorHandler(dispatchMH)
            );
        } catch (Exception e) {
            // If MethodAccessor is not accessible, invoke() won't work but
            // reflection discovery (getDeclaredMethods) still will
            return null;
        }
    }

    /**
     * InvocationHandler that dispatches MethodAccessor.invoke() through a MethodHandle.
     */
    private static final class MethodHandleAccessorHandler implements InvocationHandler {
        private final MethodHandle dispatchMH;

        MethodHandleAccessorHandler(MethodHandle mh) {
            this.dispatchMH = mh;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("invoke".equals(method.getName())) {
                // MethodAccessor.invoke(Object obj, Object[] args)
                Object receiver = args[0];
                Object[] methodArgs = (Object[]) args[1];

                if (methodArgs == null || methodArgs.length == 0) {
                    // Instance method: receiver is first arg to companion static method
                    return dispatchMH.invoke(receiver);
                } else {
                    // Build args array: [receiver, arg0, arg1, ...]
                    Object[] fullArgs = new Object[methodArgs.length + 1];
                    fullArgs[0] = receiver;
                    System.arraycopy(methodArgs, 0, fullArgs, 1, methodArgs.length);
                    return dispatchMH.invokeWithArguments(fullArgs);
                }
            }
            // Other methods (equals, hashCode, toString) — default behavior
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) return proxy == args[0];
            if ("toString".equals(method.getName())) return "ReclazzMethodAccessor";
            return null;
        }
    }
}
