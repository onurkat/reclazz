/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/**
 * Bootstrap method for the structural reloader's companion-class access
 * fix. Lives on the bootstrap classloader so every companion (which is
 * a hidden class defined on the target's own classloader) can reference
 * it from an {@code invokedynamic} instruction.
 *
 * <h2>The problem</h2>
 * The companion class contains copies of the target class's method bodies
 * as {@code static} methods. When one of those bodies makes a cross-package
 * call to a non-public method on a superclass (e.g.
 * {@code ComposedTypeModel}'s body calls the {@code protected}
 * {@code AbstractItemModel.getPersistenceContext()}), the original Java
 * access check succeeded because {@code ComposedTypeModel} is a
 * legitimate subclass of {@code AbstractItemModel}. The companion is NOT
 * — it extends {@code Object} and is a hidden class in its own runtime
 * package. The same {@code INVOKEVIRTUAL} instruction, when executed
 * from the companion's bytecode, fails with {@code IllegalAccessError}.
 *
 * <h2>The fix</h2>
 * {@link com.onurkat.reclazz.reload.CompanionGenerator CompanionGenerator}
 * rewrites every cross-package invocation in the copied method body to
 * an {@code invokedynamic} instruction whose bootstrap method is
 * {@link #protectedCall}. The bsm:
 *
 * <ol>
 *   <li>Retrieves the target class's {@code __reclazz$lookup} static field
 *       (installed at class-load time by the trampoline transformer). This
 *       is a {@link MethodHandles.Lookup} produced by calling
 *       {@code MethodHandles.lookup()} inside the target class, so it has
 *       full private access including inherited protected members.</li>
 *   <li>Resolves the call via that lookup — {@code findVirtual},
 *       {@code findSpecial}, or {@code findStatic} depending on the
 *       original opcode. Access checks on the resulting
 *       {@link MethodHandle} are performed by Lookup at resolution time,
 *       not at call time, so subsequent invocations skip the check.</li>
 *   <li>Returns a {@link ConstantCallSite} bound to that handle.</li>
 * </ol>
 *
 * <p>Once the call site is bootstrapped the JVM calls the MethodHandle
 * directly on every subsequent invocation — post-JIT this is roughly as
 * fast as a direct virtual call. The only cost is the one-time bsm
 * resolution on first invocation of each rewritten site.
 */
public final class ProtectedCallResolver {

    public static final int KIND_VIRTUAL = 0;
    public static final int KIND_SPECIAL = 1;
    public static final int KIND_STATIC = 2;
    public static final int KIND_INTERFACE = 3;

    private ProtectedCallResolver() {}

    /**
     * Invokedynamic bootstrap method. Called once per rewritten call
     * site, produces a {@link ConstantCallSite} bound to a MethodHandle
     * resolved through the target class's {@code __reclazz$lookup}.
     *
     * @param caller           companion's own lookup (passed by the JVM)
     * @param invocationName   name of the method being called
     * @param invocationType   full invocation descriptor including receiver
     *                         as the first parameter for instance calls
     * @param ownerInternal    JVM-internal name of the class that declares
     *                         the method (may be a superclass of the target)
     * @param targetInternal   JVM-internal name of the class whose
     *                         {@code __reclazz$lookup} we use to resolve
     *                         the handle
     * @param kind             one of {@link #KIND_VIRTUAL},
     *                         {@link #KIND_SPECIAL},
     *                         {@link #KIND_STATIC},
     *                         {@link #KIND_INTERFACE}
     */
    public static CallSite protectedCall(
            MethodHandles.Lookup caller,
            String invocationName,
            MethodType invocationType,
            String ownerInternal,
            String targetInternal,
            int kind) throws Throwable {

        ClassLoader cl = caller.lookupClass().getClassLoader();

        Class<?> target = Class.forName(
                targetInternal.replace('/', '.'), false, cl);

        MethodHandles.Lookup targetLookup = readTargetLookup(target);
        if (targetLookup == null) {
            // Fall back to caller's lookup. If that fails too, the
            // IllegalAccessError propagates through the bsm and surfaces
            // at the call site — exactly where it would have landed
            // without the fix, so we're no worse off.
            targetLookup = caller;
        }

        Class<?> owner = Class.forName(
                ownerInternal.replace('/', '.'), false, cl);

        MethodHandle mh;
        try {
            switch (kind) {
                case KIND_STATIC: {
                    mh = targetLookup.findStatic(owner, invocationName, invocationType);
                    break;
                }
                case KIND_SPECIAL: {
                    // findSpecial takes the "virtual" descriptor (args only,
                    // no receiver) plus a specialCaller that must have the
                    // declaring class as a direct or transitive superclass.
                    // The target class is exactly that.
                    MethodType virtualType = invocationType.dropParameterTypes(0, 1);
                    mh = targetLookup.findSpecial(owner, invocationName, virtualType, target);
                    break;
                }
                case KIND_VIRTUAL:
                case KIND_INTERFACE: {
                    MethodType virtualType = invocationType.dropParameterTypes(0, 1);
                    mh = targetLookup.findVirtual(owner, invocationName, virtualType);
                    break;
                }
                default:
                    throw new UnsupportedOperationException(
                            "Unsupported ProtectedCallResolver kind: " + kind);
            }
        } catch (NoSuchMethodException | NoSuchMethodError notOnTheClass) {
            mh = addedByThisReload(caller, invocationName, invocationType, notOnTheClass);
        }

        return new ConstantCallSite(mh.asType(invocationType));
    }

    /**
     * Resolves a call to a method this reload added.
     *
     * The JVM will not accept a redefinition that adds a member, so an added
     * method exists only in the companion, and the companion is the class
     * running this call: after a structural reload the original method bodies
     * are trampolines and the real code executes here. It is static there,
     * with the receiver as its first parameter, which is exactly the type of
     * this call site.
     *
     * Without this, adding a method and calling it from an existing method of
     * the same class, the ordinary way anyone writes a helper, resolved
     * against the original class, found nothing, and failed the call with a
     * BootstrapMethodError. On a live SAP Commerce server that surfaced as an
     * HTTP 500 from a servlet filter.
     */
    private static MethodHandle addedByThisReload(MethodHandles.Lookup caller,
                                                  String name,
                                                  MethodType invocationType,
                                                  Throwable notOnTheClass) throws Throwable {
        try {
            return caller.findStatic(caller.lookupClass(), name, invocationType);
        } catch (NoSuchMethodException | NoSuchMethodError neitherPlace) {
            // Not an added method either: report what the original lookup
            // said, which is the failure that actually describes the code.
            throw notOnTheClass;
        }
    }

    private static MethodHandles.Lookup readTargetLookup(Class<?> target) {
        try {
            Field lookupField = target.getDeclaredField("__reclazz$lookup");
            lookupField.setAccessible(true);
            return (MethodHandles.Lookup) lookupField.get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
