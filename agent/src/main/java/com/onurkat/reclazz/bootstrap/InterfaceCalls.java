/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Link a watched interface call with the originating caller's access rights. */
public final class InterfaceCalls {
    private InterfaceCalls() { }

    private static volatile boolean active;
    private static final class Targets {
        java.util.Map<Method, MethodHandle> methods = java.util.Map.of();
    }
    private static final ClassValue<Targets> published = new ClassValue<>() {
        @Override protected Targets computeValue(Class<?> owner) { return new Targets(); }
    };

    /** Share the definition/publication boundary with first-time linkage, per defining interface. */
    public static Object publicationLock(Class<?> owner) {
        LookupCapture.requireTrusted(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass(),
                "preparing interface call targets");
        active = true;
        return published.get(owner);
    }

    public static void publish(Class<?> owner, java.util.Map<Method, MethodHandle> methods) {
        LookupCapture.requireTrusted(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass(),
                "publishing interface call targets");
        var targets = published.get(owner);
        if (!Thread.holdsLock(targets)) throw new IllegalStateException("Interface publication requires its owner lock");
        targets.methods = methods;
    }

    /** JDK Lookup.findStatic entry hook. Null lets the original JVM lookup run. */
    public static MethodHandle logicalStatic(MethodHandles.Lookup caller, Class<?> owner, String name,
                                            MethodType type) throws IllegalAccessException {
        if (!active || owner == null || !owner.isInterface() || type == null || name == null) return null;
        return logical(caller, owner, name, type, true);
    }

    private static MethodHandle logical(MethodHandles.Lookup caller, Class<?> owner, String name,
                                        MethodType type, boolean statik) throws IllegalAccessException {
        if (!active) return null;
        var targets = published.get(owner);
        java.util.Map<Method, MethodHandle> snapshot;
        synchronized (targets) { snapshot = targets.methods; }
        for (var entry : snapshot.entrySet()) {
            Method m = entry.getKey();
            if (m.getDeclaringClass() == owner && m.getName().equals(name)
                    && Modifier.isStatic(m.getModifiers()) == statik
                    && type.equals(MethodType.methodType(m.getReturnType(),m.getParameterTypes()))) {
                caller.accessClass(owner);
                if (!Modifier.isPublic(m.getModifiers())
                        || (caller.lookupModes() & MethodHandles.Lookup.UNCONDITIONAL) == 0) {
                    MethodForge.checkAccess(caller,m,false,false,false);
                }
                return entry.getValue();
            }
        }
        return null;
    }

    public static CallSite reference(MethodHandles.Lookup caller, String samName, MethodType samType,
                                     Class<?> owner, String name, MethodType type, int statik, int alternate,
                                     Object... arguments) throws Throwable {
        MethodType handleType = statik != 0 ? type : type.insertParameterTypes(0, owner);
        MethodHandle logical = logical(caller,owner,name,type,statik != 0);
        boolean physical = false;
        if (logical == null && statik != 0) {
            try {
                logical=caller.findStatic(owner,name,type);
                physical=true;
            } catch (NoSuchMethodException missing) { /* Resolve on invocation if the owner arrives later. */ }
        }
        if (logical == null) logical=link(caller,name,handleType,owner,statik).dynamicInvoker();
        if (alternate==0) {
            MethodType sam=(MethodType)arguments[0], instantiated=(MethodType)arguments[1];
            return physical ? LambdaMetafactory.metafactory(caller,samName,samType,sam,logical,instantiated)
                    : LambdaFactory.metafactory(caller,samName,samType,sam,logical,instantiated);
        }
        Object[] original=new Object[arguments.length+1];
        original[0]=arguments[0];original[1]=logical;
        System.arraycopy(arguments,1,original,2,arguments.length-1);
        return physical ? LambdaMetafactory.altMetafactory(caller,samName,samType,original)
                : LambdaFactory.altMetafactory(caller,samName,samType,original);
    }

    public static CallSite link(MethodHandles.Lookup caller, String name, MethodType siteType,
                                Class<?> owner, int staticFlag) throws ReflectiveOperationException {
        boolean statik = staticFlag != 0;
        MethodType memberType = statik ? siteType : siteType.dropParameterTypes(0,1);
        // Ordinary physical interface statics keep direct native linkage and initialization.
        if (statik) {
            try { return new ConstantCallSite(caller.findStatic(owner,name,memberType)); }
            catch (NoSuchMethodException missing) { /* A separately compiled caller may arrive first. */ }
            catch (IllegalAccessException denied) { throw new IllegalAccessError(denied.getMessage()); }
        }
        var site = new MutableCallSite(siteType);
        MethodHandle resolve = MethodHandles.lookup().findStatic(InterfaceCalls.class,"resolve",
                MethodType.methodType(MethodHandle.class, MutableCallSite.class, MethodHandles.Lookup.class,
                        Class.class,String.class,MethodType.class,boolean.class));
        resolve = MethodHandles.insertArguments(resolve,0,site,caller,owner,name,memberType,statik);
        site.setTarget(MethodHandles.foldArguments(MethodHandles.exactInvoker(siteType),resolve));
        return site;
    }

    private static MethodHandle resolve(MutableCallSite site, MethodHandles.Lookup caller, Class<?> owner,
                                        String name, MethodType type, boolean statik) {
        try {
            MethodHandle target = logical(caller,owner,name,type,statik);
            if (target == null) throw new NoSuchMethodError(owner.getName() + "." + name + type);
            site.setTarget(target.asFixedArity());
            MutableCallSite.syncAll(new MutableCallSite[]{site});
            return target.asFixedArity();
        } catch (IllegalAccessException denied) {
            throw new IllegalAccessError(denied.getMessage());
        }
    }
}
