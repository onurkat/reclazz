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
 *
 * <p>Every public method here has no Java caller and none of them is dead. They
 * are named in the {@code Handle} the transformer writes into an
 * invokedynamic instruction, so the only thing that calls them is bytecode this
 * agent generated, and every tool that looks for callers will report them as
 * unused. Deleting one produces a class that verifies, links, and then throws
 * on the first call to a reloaded method.
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
        String siteKey = InjectedNames.siteKey(name, descHash);
        Class<?> ownerClass = null;
        MethodHandles.Lookup ownerLookup = null;

        // Initial resolution: find the renamed original method __reclazz$v0$<name>$<hash>
        String renamedMethod = InjectedNames.renamed(name, descHash);

        MethodHandle initialTarget;
        MethodHandle publicCall = null;
        try {
            // Instance method bootstrap: type includes receiver as first parameter.
            // The renamed original is an instance method, so use findVirtual with
            // the receiver type dropped from the MethodType.
            ownerClass = Class.forName(targetClass.replace('/', '.'), false,
                    lookup.lookupClass().getClassLoader());
            ownerLookup = MethodHandles.privateLookupIn(ownerClass, lookup);
            MethodType virtualType = type.dropParameterTypes(0, 1);
            MethodHandle renamed = ownerLookup
                    .findVirtual(ownerClass, renamedMethod, virtualType).asType(type);

            // Calls made from inside the class keep the direct route. That is
            // self-invocation, which never went through a proxy in Spring
            // either, and it is the route a proxy re-enters when its
            // interception calls super: sending that back through the public
            // method would call the interception again, forever.
            if (lookup.lookupClass() != ownerClass) {
                publicCall = publicCall(ownerLookup, ownerClass, name, virtualType, type);
            }
            initialTarget = (publicCall == null)
                    ? renamed
                    : guardWithOverride(ownerClass, name, publicCall, renamed);
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
        if (publicCall != null) {
            // A reload re-points this site at the new implementation, and doing
            // that plainly would walk past an override again, so the dispatch
            // table keeps what it needs to rebuild the guard.
            dispatch.registerOverrideGuard(siteKey, ownerClass, name, publicCall);
        }
        return dispatch.getOrCreateMethodSite(siteKey, callSite);
    }

    /**
     * The public method behind the renamed copy, or null when there is none to
     * override.
     */
    private static MethodHandle publicCall(MethodHandles.Lookup ownerLookup,
                                           Class<?> ownerClass,
                                           String name,
                                           MethodType virtualType,
                                           MethodType type) {
        try {
            return ownerLookup.findVirtual(ownerClass, name, virtualType).asType(type);
        } catch (Exception noPublicMethod) {
            return null;
        }
    }

    /**
     * Sends the call to the receiver's own implementation when its class
     * overrides the method, and straight to the reloadable copy otherwise.
     *
     * The renamed copy is what makes a reload swappable, and it lives on the
     * class being reloaded, so invoking it on a receiver whose class overrides
     * the public method runs the wrong body: the override never gets a say.
     *
     * Spring makes that the normal case rather than a corner one. A bean with
     * {@code @Transactional}, {@code @Cacheable}, {@code @Async} or an aspect
     * is a CGLIB subclass wrapping the target, and the interception is exactly
     * such an override. Measured on a Spring Boot application before this
     * existed: a {@code @Cacheable} method ran on every call with the agent
     * attached and once in total without it, and an around-advice never ran.
     *
     * When the override hands the call to its target, the receiver is the
     * target's own class, which takes the direct route, so nothing loops.
     */
    public static MethodHandle guardWithOverride(Class<?> ownerClass,
                                                 String publicName,
                                                 MethodHandle publicCall,
                                                 MethodHandle direct) {
        MethodType type = publicCall.type();
        MethodType virtualType = type.dropParameterTypes(0, 1);

        MethodHandle test = OVERRIDE_TEST
                .bindTo(new OverrideCheck(ownerClass, publicName, virtualType))
                .asType(MethodType.methodType(boolean.class, type.parameterType(0)));

        return MethodHandles.guardWithTest(test, publicCall, direct.asType(type));
    }

    private static final MethodHandle OVERRIDE_TEST;

    static {
        try {
            OVERRIDE_TEST = MethodHandles.lookup().findVirtual(OverrideCheck.class, "overrides",
                    MethodType.methodType(boolean.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Whether a receiver's class overrides the method. Cached per class: the
     * answer cannot change for a given class, and the question is asked on
     * every call.
     */
    private static final class OverrideCheck extends ClassValue<Boolean> {
        private final Class<?> ownerClass;
        private final String methodName;
        private final Class<?>[] parameterTypes;

        OverrideCheck(Class<?> ownerClass, String methodName, MethodType virtualType) {
            this.ownerClass = ownerClass;
            this.methodName = methodName;
            this.parameterTypes = virtualType.parameterArray();
        }

        boolean overrides(Object receiver) {
            return receiver != null && get(receiver.getClass());
        }

        @Override
        protected Boolean computeValue(Class<?> receiverClass) {
            if (receiverClass == ownerClass) return Boolean.FALSE;
            try {
                return receiverClass.getMethod(methodName, parameterTypes)
                        .getDeclaringClass() != ownerClass;
            } catch (Throwable notVisible) {
                return Boolean.FALSE;
            }
        }
    }

    /**
     * Bootstrap for static method dispatch.
     */
    public static CallSite bootstrapStaticMethod(MethodHandles.Lookup lookup, String name,
                                                   MethodType type, String targetClass,
                                                   String descHash) throws Throwable {
        String siteKey = InjectedNames.staticSiteKey(name, descHash);
        Class<?> ownerClass = null;
        MethodHandles.Lookup ownerLookup = null;

        String renamedMethod = InjectedNames.renamed(name, descHash);

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

}
