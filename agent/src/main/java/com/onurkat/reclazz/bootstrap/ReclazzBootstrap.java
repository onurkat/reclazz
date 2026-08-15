/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.*;

/**
 * Bootstrap methods for invokedynamic call sites injected by the Reclazz transformer.
 * Each watched method/field access is rewritten to an invokedynamic instruction
 * that dispatches through MutableCallSite, allowing atomic re-targeting on reload.
 *
 * BOOTSTRAP CLASS: Must have ZERO dependencies outside java.lang.invoke.* and java.lang.reflect.*.
 */
public final class ReclazzBootstrap {

    /**
     * Bootstrap method for dispatching method calls on watched classes.
     *
     * @param lookup      lookup context from the invokedynamic instruction
     * @param name        method name (e.g., "doSomething")
     * @param type        call site type (includes receiver for instance methods)
     * @param targetClass internal class name owning the method (e.g., "com/example/MyService")
     * @param descHash    hash of the original method descriptor for uniqueness
     * @return a CallSite whose target can be re-pointed on reload
     */
    public static CallSite bootstrapMethod(MethodHandles.Lookup lookup, String name,
                                            MethodType type, String targetClass,
                                            String descHash) throws Throwable {
        String siteKey = name + ":" + descHash;
        Class<?> ownerClass = null;
        MethodHandles.Lookup ownerLookup = null;

        // Initial resolution: find the renamed original method __reclazz$v0$<name>$<hash>
        String renamedMethod = "__reclazz$v0$" + name + "$" + descHash;

        MethodHandle initialTarget;
        try {
            // Instance method bootstrap: type includes receiver as first parameter.
            // The renamed original is an instance method, so use findVirtual with
            // the receiver type dropped from the MethodType.
            ownerClass = Class.forName(targetClass.replace('/', '.'), false,
                    lookup.lookupClass().getClassLoader());
            ownerLookup = MethodHandles.privateLookupIn(ownerClass, lookup);
            initialTarget = ownerLookup.findVirtual(ownerClass, renamedMethod,
                    type.dropParameterTypes(0, 1));
        } catch (Exception e) {
            // Renamed method not found. This happens when the call site targets an
            // inherited method that was never renamed by MethodTrampolineAdapter
            // (e.g. anonymous subclass of HashMap calling put() in its initializer
            // block — javac compiles the call as INVOKEVIRTUAL Subclass.put even
            // though Subclass doesn't declare put). Fall back to the original
            // (un-renamed) method, which virtual dispatch will resolve to the
            // inherited implementation.
            initialTarget = null;
            if (ownerClass != null && ownerLookup != null) {
                try {
                    initialTarget = ownerLookup.findVirtual(ownerClass, name,
                            type.dropParameterTypes(0, 1));
                } catch (Exception ignored) {
                    // Truly not found — fall through to throwing handle below.
                }
            }
            if (initialTarget == null) {
                initialTarget = MethodHandles.throwException(type.returnType(),
                        UnsupportedOperationException.class)
                        .bindTo(new UnsupportedOperationException(
                                "Reclazz: method not found: " + targetClass + "." + name));
                if (type.parameterCount() > 0) {
                    initialTarget = MethodHandles.dropArguments(initialTarget, 0, type.parameterList());
                }
            }
        }

        MutableCallSite callSite = new MutableCallSite(initialTarget);
        DispatchTable.ClassDispatch dispatch = ownerClass != null
                ? DispatchTable.getOrCreate(ownerClass)
                : DispatchTable.getOrCreate(lookup.lookupClass());
        return dispatch.getOrCreateMethodSite(siteKey, callSite);
    }

    /**
     * Bootstrap for static method dispatch.
     */
    public static CallSite bootstrapStaticMethod(MethodHandles.Lookup lookup, String name,
                                                   MethodType type, String targetClass,
                                                   String descHash) throws Throwable {
        String siteKey = "static:" + name + ":" + descHash;
        Class<?> ownerClass = null;
        MethodHandles.Lookup ownerLookup = null;

        String renamedMethod = "__reclazz$v0$" + name + "$" + descHash;

        MethodHandle initialTarget;
        try {
            ownerClass = Class.forName(targetClass.replace('/', '.'), false,
                    lookup.lookupClass().getClassLoader());
            ownerLookup = MethodHandles.privateLookupIn(ownerClass, lookup);
            initialTarget = ownerLookup.findStatic(ownerClass, renamedMethod, type);
        } catch (Exception e) {
            // Fall back to the original (un-renamed) static method — see comment
            // in bootstrapMethod for the inherited-method case.
            initialTarget = null;
            if (ownerClass != null && ownerLookup != null) {
                try {
                    initialTarget = ownerLookup.findStatic(ownerClass, name, type);
                } catch (Exception ignored) {
                    // Fall through.
                }
            }
            if (initialTarget == null) {
                initialTarget = MethodHandles.throwException(type.returnType(),
                        UnsupportedOperationException.class)
                        .bindTo(new UnsupportedOperationException(
                                "Reclazz: static method not found: " + targetClass + "." + name));
                if (type.parameterCount() > 0) {
                    initialTarget = MethodHandles.dropArguments(initialTarget, 0, type.parameterList());
                }
            }
        }

        MutableCallSite callSite = new MutableCallSite(initialTarget);
        DispatchTable.ClassDispatch dispatch = ownerClass != null
                ? DispatchTable.getOrCreate(ownerClass)
                : DispatchTable.getOrCreate(lookup.lookupClass());
        return dispatch.getOrCreateMethodSite(siteKey, callSite);
    }

    /**
     * Bootstrap for field get access on watched classes.
     */
    public static CallSite bootstrapFieldGet(MethodHandles.Lookup lookup, String name,
                                              MethodType type, String targetClass) throws Throwable {
        String siteKey = "get:" + name;
        Class<?> ownerClass = null;

        // Initial resolution: direct field access on the original class
        MethodHandle initialTarget;
        try {
            ownerClass = Class.forName(targetClass.replace('/', '.'), false,
                    lookup.lookupClass().getClassLoader());
            MethodHandles.Lookup ownerLookup = MethodHandles.privateLookupIn(ownerClass, lookup);
            initialTarget = ownerLookup.findGetter(ownerClass, name, type.returnType());
        } catch (Exception e) {
            initialTarget = companionGetter(targetClass, name, type.returnType(), type);
        }

        MutableCallSite callSite = new MutableCallSite(initialTarget);
        DispatchTable.ClassDispatch dispatch = ownerClass != null
                ? DispatchTable.getOrCreate(ownerClass)
                : DispatchTable.getOrCreate(lookup.lookupClass());
        return dispatch.getOrCreateFieldGetSite(siteKey, callSite);
    }

    /**
     * Bootstrap for static field get access.
     */
    public static CallSite bootstrapStaticFieldGet(MethodHandles.Lookup lookup, String name,
                                                     MethodType type, String targetClass) throws Throwable {
        String siteKey = "sget:" + name;
        Class<?> ownerClass = null;

        MethodHandle initialTarget;
        try {
            ownerClass = Class.forName(targetClass.replace('/', '.'), false,
                    lookup.lookupClass().getClassLoader());
            MethodHandles.Lookup ownerLookup = MethodHandles.privateLookupIn(ownerClass, lookup);
            initialTarget = ownerLookup.findStaticGetter(ownerClass, name, type.returnType());
        } catch (Exception e) {
            initialTarget = MethodHandles.throwException(type.returnType(),
                    UnsupportedOperationException.class)
                    .bindTo(new UnsupportedOperationException(
                            "Reclazz: static field not found: " + targetClass + "." + name));
            if (type.parameterCount() > 0) {
                initialTarget = MethodHandles.dropArguments(initialTarget, 0, type.parameterList());
            }
        }

        MutableCallSite callSite = new MutableCallSite(initialTarget);
        DispatchTable.ClassDispatch dispatch = ownerClass != null
                ? DispatchTable.getOrCreate(ownerClass)
                : DispatchTable.getOrCreate(lookup.lookupClass());
        return dispatch.getOrCreateFieldGetSite(siteKey, callSite);
    }

    /**
     * Bootstrap for field set access on watched classes.
     */
    public static CallSite bootstrapFieldSet(MethodHandles.Lookup lookup, String name,
                                              MethodType type, String targetClass) throws Throwable {
        String siteKey = "set:" + name;
        Class<?> ownerClass = null;

        MethodHandle initialTarget;
        try {
            ownerClass = Class.forName(targetClass.replace('/', '.'), false,
                    lookup.lookupClass().getClassLoader());
            MethodHandles.Lookup ownerLookup = MethodHandles.privateLookupIn(ownerClass, lookup);
            // type is (Owner, FieldType)void for instance fields
            Class<?> fieldType = type.parameterType(type.parameterCount() - 1);
            initialTarget = ownerLookup.findSetter(ownerClass, name, fieldType);
        } catch (Exception e) {
            Class<?> fieldType = type.parameterType(type.parameterCount() - 1);
            initialTarget = companionSetter(targetClass, name, fieldType, type);
        }

        MutableCallSite callSite = new MutableCallSite(initialTarget);
        DispatchTable.ClassDispatch dispatch = ownerClass != null
                ? DispatchTable.getOrCreate(ownerClass)
                : DispatchTable.getOrCreate(lookup.lookupClass());
        return dispatch.getOrCreateFieldSetSite(siteKey, callSite);
    }

    /**
     * Bootstrap for static field set access.
     */
    public static CallSite bootstrapStaticFieldSet(MethodHandles.Lookup lookup, String name,
                                                     MethodType type, String targetClass) throws Throwable {
        String siteKey = "sset:" + name;
        Class<?> ownerClass = null;

        MethodHandle initialTarget;
        try {
            ownerClass = Class.forName(targetClass.replace('/', '.'), false,
                    lookup.lookupClass().getClassLoader());
            MethodHandles.Lookup ownerLookup = MethodHandles.privateLookupIn(ownerClass, lookup);
            Class<?> fieldType = type.parameterType(type.parameterCount() - 1);
            initialTarget = ownerLookup.findStaticSetter(ownerClass, name, fieldType);
        } catch (Exception e) {
            initialTarget = MethodHandles.empty(type);
        }

        MutableCallSite callSite = new MutableCallSite(initialTarget);
        DispatchTable.ClassDispatch dispatch = ownerClass != null
                ? DispatchTable.getOrCreate(ownerClass)
                : DispatchTable.getOrCreate(lookup.lookupClass());
        return dispatch.getOrCreateFieldSetSite(siteKey, callSite);
    }

    /**
     * Where a call site goes when the field is not in the loaded class.
     *
     * A field added by a reload cannot be added to the loaded class, so it
     * lives in the companion store. Resolution against the real class fails,
     * and the failure used to become {@code MethodHandles.empty} for writes:
     * a call site that accepted the value and dropped it. The constructor of a
     * class that had gained a field therefore ran, assigned, and lost the
     * assignment, so an object created after the reload came back with the
     * field still null. Reads got an exception-throwing handle instead, which
     * at least said something, but neither is what the code meant.
     *
     * Both now go to the same store the companion reads and writes.
     */
    private static String fieldDescriptor(Class<?> type) {
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == double.class) return "D";
        if (type == float.class) return "F";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == void.class) return "V";
        if (type.isArray()) return type.getName().replace('.', '/');
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static MethodHandle companionGetter(String className, String name,
                                                 Class<?> fieldType, MethodType type) throws Throwable {
        MethodHandle get = MethodHandles.lookup().findStatic(
                com.onurkat.reclazz.bootstrap.FieldStore.class, "getExtField",
                MethodType.methodType(Object.class, Object.class, String.class,
                        String.class, String.class));
        return MethodHandles.insertArguments(get, 1,
                className.replace('/', '.'), name, fieldDescriptor(fieldType)).asType(type);
    }

    private static MethodHandle companionSetter(String className, String name,
                                                 Class<?> fieldType, MethodType type) throws Throwable {
        MethodHandle put = MethodHandles.lookup().findStatic(
                com.onurkat.reclazz.bootstrap.FieldStore.class, "putExtField",
                MethodType.methodType(void.class, Object.class, Object.class,
                        String.class, String.class, String.class));
        return MethodHandles.insertArguments(put, 2,
                className.replace('/', '.'), name, fieldDescriptor(fieldType)).asType(type);
    }

    private static MethodHandle companionStaticGetter(String className, String name,
                                                       Class<?> fieldType, MethodType type) throws Throwable {
        MethodHandle get = MethodHandles.lookup().findStatic(
                com.onurkat.reclazz.bootstrap.FieldStore.class, "getStaticExtField",
                MethodType.methodType(Object.class, String.class, String.class, String.class));
        return MethodHandles.insertArguments(get, 0,
                className.replace('/', '.'), name, fieldDescriptor(fieldType)).asType(type);
    }

    private static MethodHandle companionStaticSetter(String className, String name,
                                                       Class<?> fieldType, MethodType type) throws Throwable {
        MethodHandle put = MethodHandles.lookup().findStatic(
                com.onurkat.reclazz.bootstrap.FieldStore.class, "putStaticExtFieldSwapped",
                MethodType.methodType(void.class, Object.class, String.class,
                        String.class, String.class));
        return MethodHandles.insertArguments(put, 1,
                className.replace('/', '.'), name, fieldDescriptor(fieldType)).asType(type);
    }
}
