/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.ArrayList;

/** Guarded JDK17/21 reflection roots backed by companion handles and real metadata carriers. */
public final class MethodForge {
    private static MethodHandle constructor;
    private static MethodHandle accessorSetter;
    private static MethodHandle nativeParameters;
    private static MethodHandle nativeTypeAnnotations;
    private record Entry(MethodHandle target, Executable metadata) { }
    private static final ClassValue<java.util.concurrent.ConcurrentHashMap<Executable,Entry>> entries=new ClassValue<>() {
        @Override protected java.util.concurrent.ConcurrentHashMap<Executable,Entry> computeValue(Class<?> owner){return new java.util.concurrent.ConcurrentHashMap<>();}
    };
    private static MethodHandle parameterConstructor, realParameters, verifyAccess, specialCallerCheck;
    private static Class<?> accessorType;
    private static VarHandle declaredAnnotations;
    private static volatile boolean available;
    public static Throwable initializationFailure;
    private MethodForge() { }

    /** Called after Instrumentation opens java.lang.reflect to the bootstrap module. */
    public static synchronized boolean initialize() {
        if (available) return true;
        try {
            var lookup=MethodHandles.privateLookupIn(Method.class,MethodHandles.lookup());
            accessorType=Class.forName("jdk.internal.reflect.MethodAccessor");
            constructor=lookup.findConstructor(Method.class,MethodType.methodType(void.class,
                    Class.class,String.class,Class[].class,Class.class,Class[].class,int.class,int.class,
                    String.class,byte[].class,byte[].class,byte[].class));
            accessorSetter=lookup.findSetter(Method.class,"methodAccessor",accessorType);
            declaredAnnotations=MethodHandles.privateLookupIn(Executable.class,MethodHandles.lookup())
                    .findVarHandle(Executable.class,"declaredAnnotations",java.util.Map.class);
            var executable=MethodHandles.privateLookupIn(Executable.class,MethodHandles.lookup());
            nativeParameters=executable.findVirtual(Executable.class,"getParameters0",MethodType.methodType(Parameter[].class));
            nativeTypeAnnotations=executable.findVirtual(Executable.class,"getTypeAnnotationBytes0",MethodType.methodType(byte[].class));
            parameterConstructor=MethodHandles.privateLookupIn(Parameter.class,MethodHandles.lookup()).findConstructor(Parameter.class,
                    MethodType.methodType(void.class,String.class,int.class,Executable.class,int.class));
            realParameters=executable.findVirtual(Executable.class,"hasRealParameterData",MethodType.methodType(boolean.class));
            var special=MethodHandles.Lookup.class.getDeclaredMethod("checkSpecialCaller",Class.class,Class.class);
            special.setAccessible(true);
            specialCallerCheck=MethodHandles.lookup().unreflect(special);
            Class<?> verify=Class.forName("sun.invoke.util.VerifyAccess");
            verifyAccess=MethodHandles.privateLookupIn(verify,MethodHandles.lookup()).findStatic(verify,"isMemberAccessible",
                    MethodType.methodType(boolean.class,Class.class,Class.class,int.class,Class.class,Class.class,int.class));
            if(!MemberForge.initialize())throw new IllegalStateException("Field/constructor accessors unavailable",MemberForge.initializationFailure);
            available=true;
        } catch (Throwable unsupported) { initializationFailure=unsupported;available=false; }
        return available;
    }

    public static boolean isAvailable() { return available; }

    /** Never publish virtual roots unless all native-query guards were installed. */
    public static void disable() { available=false; }

    public static Method forgeMethod(Class<?> owner,String name,Class<?>[] parameters,Class<?> result,
                                     int modifiers,MethodHandle target,byte[] annotations) {
        return forgeMethod(owner,name,parameters,result,modifiers,target,null,new Class<?>[0]);
    }

    public static Method forgeMethod(Class<?> owner,String name,Class<?>[] parameters,Class<?> result,
                                     int modifiers,MethodHandle target,String signature,Class<?>[] exceptions) {
        if (!available || target==null) return null;
        try {
            Method method=(Method)constructor.invoke(owner,name.intern(),parameters.clone(),result,
                    exceptions.clone(),modifiers,-1,signature,null,null,null);
            Object accessor=Proxy.newProxyInstance(null,new Class<?>[]{accessorType},(proxy,called,args)->{
                if (!called.getName().equals("invoke")) {
                    return switch(called.getName()) {
                        case "equals" -> proxy==args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> "ReclazzMethodAccessor";
                    };
                }
                Object receiver=args[0];
                Object[] values=args[1]==null?new Object[0]:(Object[])args[1];
                boolean statik=Modifier.isStatic(modifiers);
                if (!statik) {
                    if(receiver==null)throw new NullPointerException("method receiver");
                    if(!owner.isInstance(receiver))throw new IllegalArgumentException("Invalid method receiver");
                }
                if(values.length!=parameters.length)throw new IllegalArgumentException("Wrong argument count");
                var actual=new ArrayList<Object>();
                if(!statik)actual.add(receiver);
                for(int i=0;i<values.length;i++)actual.add(JacksonBridge.checkedValue(parameters[i],values[i]));
                try {return target.invokeWithArguments(actual);}
                catch(Throwable failure){throw new InvocationTargetException(failure);}
            });
            accessorSetter.invoke(method,accessor);
            entries.get(owner).put(method,new Entry(target,null));
            return method;
        } catch(Throwable unsupported) { return null; }
    }

    static boolean isForged(Executable method) { return entry(method)!=null; }
    private static Entry entry(Executable method){return entries.get(method.getDeclaringClass()).get(method);}

    public static void metadata(Executable logical,Executable source,MethodHandle target) {
        entries.get(logical.getDeclaringClass()).put(logical,new Entry(target,source));
        var map=new java.util.LinkedHashMap<Class<? extends java.lang.annotation.Annotation>,java.lang.annotation.Annotation>();
        for(var value:source.getDeclaredAnnotations())map.put(value.annotationType(),value);
        declaredAnnotations.setVolatile(logical,java.util.Map.copyOf(map));
    }

    /** Native parameter queries require a VM slot. Create Parameters attached to the logical copy. */
    public static Parameter[] parameters(Executable method) {
        Entry entry=entry(method);
        try {
            if(entry==null)return (Parameter[])nativeParameters.invoke(method);
            if(entry.metadata==null || !(boolean)realParameters.invoke(entry.metadata))return null;
            Parameter[] original=entry.metadata.getParameters(), result=new Parameter[original.length];
            for(int i=0;i<result.length;i++)result[i]=(Parameter)parameterConstructor.invoke(original[i].isNamePresent()?original[i].getName():null,original[i].getModifiers(),method,i);
            return result;
        }catch(RuntimeException | Error failure){throw failure;}
        catch(Throwable failure){throw new IllegalStateException(failure);}
    }

    public static byte[] typeAnnotations(Executable method) {
        if(isForged(method))return null; // Public annotated-type queries below use the carrier's pool.
        try {return (byte[])nativeTypeAnnotations.invoke(method);}
        catch(RuntimeException | Error failure){throw failure;}
        catch(Throwable failure){throw new IllegalStateException(failure);}
    }

    public static boolean hasMetadata(Executable method){Entry value=entry(method);return value!=null && value.metadata!=null;}

    public static java.lang.annotation.Annotation[][] parameterAnnotations(Executable method) {
        Entry entry=entry(method);
        return entry==null?null:entry.metadata==null?new java.lang.annotation.Annotation[method.getParameterCount()][0]:entry.metadata.getParameterAnnotations();
    }
    public static AnnotatedType annotatedReturn(Executable method) {
        Entry entry=entry(method);return entry==null || entry.metadata==null?null:ReflectionTypes.wrap(entry.metadata.getAnnotatedReturnType(),entry.metadata,method);
    }
    public static AnnotatedType[] annotatedParameters(Executable method) {
        Entry entry=entry(method);return entry==null || entry.metadata==null?null:ReflectionTypes.wrap(entry.metadata.getAnnotatedParameterTypes(),entry.metadata,method);
    }
    public static AnnotatedType[] annotatedExceptions(Executable method) {
        Entry entry=entry(method);return entry==null || entry.metadata==null?null:ReflectionTypes.wrap(entry.metadata.getAnnotatedExceptionTypes(),entry.metadata,method);
    }
    public static AnnotatedType annotatedReceiver(Executable method) {
        Entry entry=entry(method);return entry==null || entry.metadata==null?null:ReflectionTypes.wrap(entry.metadata.getAnnotatedReceiverType(),entry.metadata,method);
    }

    /** Apply the same JDK member/mode/module check without resolving an invented VM slot. */
    @SuppressWarnings("deprecation")
    static void checkAccess(MethodHandles.Lookup lookup,Member member,boolean override,boolean constructor,boolean setter) throws IllegalAccessException {
        int mods=member.getModifiers();
        if(setter && Modifier.isFinal(mods) && (!override || Modifier.isStatic(mods) || member.getDeclaringClass().isRecord() || member.getDeclaringClass().isHidden()))
            throw new IllegalAccessException("final field: "+member);
        if(override)return;
        if(constructor && Modifier.isProtected(mods))mods^=Modifier.PROTECTED;
        int requested=mods & (Modifier.PUBLIC|Modifier.PRIVATE|Modifier.PROTECTED);
        if(requested==0)requested=MethodHandles.Lookup.PACKAGE;
        int modes=lookup.lookupModes();
        Class<?> owner=member.getDeclaringClass();
        boolean samePackage=samePackage(owner,lookup.lookupClass());
        boolean mode=(requested & modes)!=0 || (requested & Modifier.PROTECTED)!=0 && (modes & MethodHandles.Lookup.PACKAGE)!=0 && samePackage;
        try {
            if(!mode || !(boolean)verifyAccess.invoke(owner,owner,mods,lookup.lookupClass(),lookup.previousLookupClass(),modes))
                throw new IllegalAccessException("Member is not accessible to "+lookup+": "+member);
        }catch(IllegalAccessException failure){throw failure;}
        catch(Throwable failure){throw new IllegalStateException(failure);}
    }

    @SuppressWarnings("deprecation")
    public static MethodHandle unreflect(MethodHandles.Lookup lookup,Method method,Class<?> specialCaller) throws IllegalAccessException {
        Entry entry=entry(method);if(entry==null)return null;
        boolean special=specialCaller!=null;
        if(special) {
            try{specialCallerCheck.invoke(lookup,specialCaller,method.getDeclaringClass());}
            catch(IllegalAccessException failure){throw failure;}
            catch(Throwable failure){throw new IllegalStateException(failure);}
            if(Modifier.isStatic(method.getModifiers()) || !method.getDeclaringClass().isAssignableFrom(specialCaller))throw new IllegalAccessException("Invalid invokespecial receiver");
        }
        MethodHandles.Lookup checked=special?lookup.in(specialCaller):lookup;
        checkAccess(checked,method,!special && method.isAccessible(),false,false);
        MethodHandle result=entry.target;
        if(!special && !Modifier.isStatic(method.getModifiers()) && !Modifier.isPrivate(method.getModifiers())) {
            try {
                result=MethodHandles.lookup().findStatic(MethodForge.class,"invokeVirtual",
                        MethodType.methodType(Object.class,Method.class,Object.class,Object[].class)).bindTo(method)
                        .asCollector(Object[].class,method.getParameterCount()).asType(entry.target.type());
            }catch(NoSuchMethodException failure){throw new IllegalStateException(failure);}
        }
        if(special)result=result.asType(result.type().changeParameterType(0,specialCaller));
        else if(!method.isAccessible() && Modifier.isProtected(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())
                && !samePackage(method.getDeclaringClass(),lookup.lookupClass()))
            result=result.asType(result.type().changeParameterType(0,lookup.lookupClass()));
        return method.isVarArgs()?result.asVarargsCollector(method.getParameterTypes()[method.getParameterCount()-1]):result;
    }

    public static MethodHandle unreflectSpecial(MethodHandles.Lookup lookup,Method method,Class<?> caller) throws IllegalAccessException {
        return unreflect(lookup,method,java.util.Objects.requireNonNull(caller));
    }

    private static Object invokeVirtual(Method method,Object receiver,Object[] values) throws Throwable {
        java.util.Objects.requireNonNull(receiver);
        Class<?> owner=method.getDeclaringClass();
        if(!owner.isInstance(receiver))throw new ClassCastException("Wrong method receiver");
        for(Class<?> type=receiver.getClass();!Modifier.isFinal(method.getModifiers()) && type!=null && type!=owner;type=type.getSuperclass()) {
            int visibility=method.getModifiers() & (Modifier.PUBLIC|Modifier.PROTECTED|Modifier.PRIVATE);
            if(visibility==0 && !samePackage(owner,type))continue;
            Method override=null;
            for(Method candidate:type.getDeclaredMethods())if(candidate.getName().equals(method.getName())
                    && java.util.Arrays.equals(candidate.getParameterTypes(),method.getParameterTypes())
                    && !Modifier.isStatic(candidate.getModifiers()) && !Modifier.isPrivate(candidate.getModifiers())){override=candidate;break;}
            if(override!=null) {
                Entry next=entry(override);
                if(next!=null){var args=new ArrayList<Object>();args.add(receiver);java.util.Collections.addAll(args,values);return next.target.invokeWithArguments(args);}
                override.setAccessible(true);
                try{return override.invoke(receiver,values);}catch(InvocationTargetException failure){throw failure.getCause();}
            }
        }
        Entry entry=entry(method);var args=new ArrayList<Object>();args.add(receiver);java.util.Collections.addAll(args,values);
        return entry.target.invokeWithArguments(args);
    }

    /** Annotation instances are parsed against the metadata carrier's constant pool, never the owner's. */
    public static void annotations(Method method,java.lang.annotation.Annotation[] values) {
        var map=new java.util.LinkedHashMap<Class<? extends java.lang.annotation.Annotation>,java.lang.annotation.Annotation>();
        for(var value:values)map.put(value.annotationType(),value);
        declaredAnnotations.setVolatile(method,java.util.Map.copyOf(map));
    }

    static boolean samePackage(Class<?> left,Class<?> right) {
        return left.getModule()==right.getModule() && left.getPackageName().equals(right.getPackageName());
    }
}
