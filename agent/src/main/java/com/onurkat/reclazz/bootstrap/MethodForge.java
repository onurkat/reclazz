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
    private static final class Entry {
        final MethodHandle target;final Executable metadata;volatile RecordHeader.MemberView generic;
        Entry(MethodHandle target,Executable metadata){this.target=target;this.metadata=metadata;}
    }
    private static final class Entries {
        final java.util.concurrent.ConcurrentHashMap<Executable,Entry> normal=new java.util.concurrent.ConcurrentHashMap<>();
        volatile boolean snapshots;
    }
    private static final ClassValue<Entries> entries=new ClassValue<>() {
        @Override protected Entries computeValue(Class<?> owner){return new Entries();}
    };
    private record SnapshotAccessor(Entry entry,InvocationHandler delegate) implements InvocationHandler {
        @Override public Object invoke(Object proxy,Method called,Object[] args)throws Throwable {
            return delegate.invoke(proxy,called,args);
        }
    }
    private static MethodHandle methodRoot,constructorRoot,methodCopy,constructorCopy,methodAccessorGetter,constructorAccessorGetter;
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
            methodRoot=lookup.findVirtual(Method.class,"getRoot",MethodType.methodType(Method.class));
            methodCopy=lookup.findVirtual(Method.class,"copy",MethodType.methodType(Method.class));
            methodAccessorGetter=lookup.findGetter(Method.class,"methodAccessor",accessorType);
            var ctorLookup=MethodHandles.privateLookupIn(Constructor.class,MethodHandles.lookup());
            constructorRoot=ctorLookup.findVirtual(Constructor.class,"getRoot",MethodType.methodType(Constructor.class));
            constructorCopy=ctorLookup.findVirtual(Constructor.class,"copy",MethodType.methodType(Constructor.class));
            constructorAccessorGetter=ctorLookup.findGetter(Constructor.class,"constructorAccessor",Class.forName("jdk.internal.reflect.ConstructorAccessor"));
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
        return createMethod(owner,name,parameters,result,modifiers,target,signature,exceptions,null);
    }
    /** A private prepared root owns its metadata; equal live roots are never re-registered. */
    public static Method snapshotMethod(Class<?> owner,Method metadata,MethodHandle target,String signature) {
        LookupCapture.requireTrusted(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass(),"preparing record method roots");
        MethodType expected=MethodType.methodType(metadata.getReturnType(),metadata.getParameterTypes());
        if(!Modifier.isStatic(metadata.getModifiers()))expected=expected.insertParameterTypes(0,owner);
        if(!expected.equals(target.type()))throw new IllegalArgumentException("Prepared method target type changed");
        RecordSchema.validateRecordMember(owner,metadata,target);
        return createMethod(owner,metadata.getName(),metadata.getParameterTypes(),metadata.getReturnType(),
                metadata.getModifiers(),target,signature,metadata.getExceptionTypes(),metadata);
    }
    /** Owned raw target proof is checked before internally binding the record's live dispatch site. */
    public static Method recordDispatchMethod(Class<?> owner,Method metadata,MethodHandle rawTarget,String key,String signature) {
        LookupCapture.requireTrusted(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass(),"preparing record dispatch roots");
        if(!owner.isRecord())throw new IllegalArgumentException("Record dispatch root requires a record");
        MethodType expected=MethodType.methodType(metadata.getReturnType(),metadata.getParameterTypes());
        if(!Modifier.isStatic(metadata.getModifiers()))expected=expected.insertParameterTypes(0,owner);
        if(!expected.equals(rawTarget.type()))throw new IllegalArgumentException("Record dispatch target type changed");
        RecordSchema.validateRecordMember(owner,metadata,rawTarget);
        MethodHandle target=new RecordMethodTarget(owner,key,rawTarget.asFixedArity()).dispatcher();
        return createMethod(owner,metadata.getName(),metadata.getParameterTypes(),metadata.getReturnType(),
                metadata.getModifiers(),target,signature,metadata.getExceptionTypes(),metadata);
    }
    /** Caller-owned roots use the cohort dispatch without publishing a method site during assembly. */
    public static Method callerDispatchMethod(Class<?> owner,Method metadata,MethodHandle rawTarget,String key,String signature) {
        LookupCapture.requireTrusted(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass(),"preparing record-cohort caller dispatch roots");
        Class<?> carrier=metadata.getDeclaringClass();
        if(owner.isRecord()||carrier!=owner&&!(carrier.isHidden()&&carrier.getClassLoader()==owner.getClassLoader()&&carrier.getNestHost()==owner.getNestHost()))
            throw new IllegalArgumentException("Caller dispatch metadata belongs to another owner");
        MethodType expected=MethodType.methodType(metadata.getReturnType(),metadata.getParameterTypes());
        if(!Modifier.isStatic(metadata.getModifiers()))expected=expected.insertParameterTypes(0,owner);
        if(!expected.equals(rawTarget.type()))throw new IllegalArgumentException("Caller dispatch target type changed");
        RecordSchema.validateTargetOwner(owner,rawTarget);
        MethodHandle target=new RecordMethodTarget(owner,key,rawTarget.asFixedArity()).dispatcher();
        return createMethod(owner,metadata.getName(),metadata.getParameterTypes(),metadata.getReturnType(),metadata.getModifiers(),target,signature,metadata.getExceptionTypes(),metadata);
    }
    private static final class RecordMethodTarget {
        private static final MethodHandle INVOKER;
        static {
            try{INVOKER=MethodHandles.lookup().findVirtual(RecordMethodTarget.class,"invoker",MethodType.methodType(MethodHandle.class));}
            catch(ReflectiveOperationException failure){throw new ExceptionInInitializerError(failure);}
        }
        private final Class<?> owner;private final String key;private final MethodHandle fallback;
        private volatile MethodHandle invocation;
        RecordMethodTarget(Class<?> owner,String key,MethodHandle fallback){this.owner=owner;this.key=java.util.Objects.requireNonNull(key);this.fallback=fallback;}
        MethodHandle dispatcher(){return MethodHandles.foldArguments(MethodHandles.exactInvoker(fallback.type()),INVOKER.bindTo(this));}
        private MethodHandle invoker() {
            MethodHandle found=invocation;if(found!=null)return found;
            synchronized(this) {
                if(invocation==null)invocation=DispatchTable.getOrCreate(owner)
                        .getOrCreateMethodSite(key,new MutableCallSite(fallback)).dynamicInvoker();
                return invocation;
            }
        }
    }
    private static Method createMethod(Class<?> owner,String name,Class<?>[] parameters,Class<?> result,
            int modifiers,MethodHandle target,String signature,Class<?>[] exceptions,Method metadata) {
        if (!available || target==null) return null;
        try {
            Method method=(Method)constructor.invoke(owner,name.intern(),parameters.clone(),result,
                    exceptions.clone(),modifiers,-1,signature,null,null,null);
            InvocationHandler invoke=(proxy,called,args)->{
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
            };
            Object accessor=metadata==null?Proxy.newProxyInstance(null,new Class<?>[]{accessorType},invoke)
                    :snapshotAccessor(owner,target,metadata,accessorType,invoke);
            accessorSetter.invoke(method,accessor);
            if(metadata==null)entries.get(owner).normal.put(method,new Entry(target,null));
            else copyAnnotations(method,metadata);
            return method;
        } catch(Throwable unsupported) { return null; }
    }

    static boolean isForged(Executable method) { return entry(method)!=null; }
    static Executable metadataSource(Executable method){Entry entry=entry(method);return entry==null?null:entry.metadata;}
    static RecordHeader.MemberView genericView(Executable method){Entry entry=entry(method);return entry==null?null:entry.generic;}
    static void bindGenericView(Executable method,RecordHeader.MemberView view){Entry entry=snapshotEntry(method);if(entry==null||entry.generic!=null)throw new IllegalArgumentException("Missing or already bound snapshot root");entry.generic=view;}

    private static Entry entry(Executable method){
        Entries owned=entries.get(method.getDeclaringClass());
        if(owned.snapshots){Entry snapshot=snapshotEntry(method);if(snapshot!=null)return snapshot;}
        return owned.normal.get(method);
    }
    private static Executable root(Executable method)throws Throwable {
        Executable current=method;
        for(;;){Executable parent=(Executable)(current instanceof Method?methodRoot:constructorRoot).invoke(current);
            if(parent==null)return current;current=parent;}
    }
    private static Entry snapshotEntry(Executable method) {
        try {
            Executable root=root(method);
            Object accessor=(root instanceof Method?methodAccessorGetter:constructorAccessorGetter).invoke(root);
            if(accessor!=null&&Proxy.isProxyClass(accessor.getClass())
                    &&Proxy.getInvocationHandler(accessor) instanceof SnapshotAccessor snapshot)return snapshot.entry;
            return null;
        }catch(Throwable failure){throw new IllegalStateException("Cannot resolve prepared member root",failure);}
    }
    static MethodHandle snapshotTarget(Executable method) {
        if(!entries.get(method.getDeclaringClass()).snapshots)return null;
        Entry entry=snapshotEntry(method);return entry==null?null:entry.target;
    }
    static Object snapshotAccessor(Class<?> owner,MethodHandle target,Executable metadata,Class<?> type,InvocationHandler invoke) {
        entries.get(owner).snapshots=true;
        return Proxy.newProxyInstance(null,new Class<?>[]{type},new SnapshotAccessor(new Entry(target,metadata),invoke));
    }
    /** Class's public API copies roots; a component accessor must never expose the shared root. */
    public static Field reflectionCopy(Field field) {
        return MemberForge.reflectionCopy(field);
    }
    public static Method reflectionCopy(Method method) {
        try{return (Method)methodCopy.invoke(root(method));}
        catch(Throwable failure){throw new IllegalStateException("Cannot copy method root",failure);}
    }
    public static Constructor<?> reflectionCopy(Constructor<?> constructor) {
        try{return (Constructor<?>)constructorCopy.invoke(root(constructor));}
        catch(Throwable failure){throw new IllegalStateException("Cannot copy constructor root",failure);}
    }

    public static void metadata(Executable logical,Executable source,MethodHandle target) {
        entries.get(logical.getDeclaringClass()).normal.put(logical,new Entry(target,source));
        copyAnnotations(logical,source);
    }
    static void copyAnnotations(Executable logical,Executable source) {
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
        Entry entry=entry(method);return entry==null || entry.metadata==null?null:ReflectionTypes.wrap(entry.metadata.getAnnotatedReturnType(),entry.metadata,method,entry.generic==null?null:entry.generic::resolve);
    }
    public static AnnotatedType[] annotatedParameters(Executable method) {
        Entry entry=entry(method);return entry==null || entry.metadata==null?null:ReflectionTypes.wrap(entry.metadata.getAnnotatedParameterTypes(),entry.metadata,method,entry.generic==null?null:entry.generic::resolve);
    }
    public static AnnotatedType[] annotatedExceptions(Executable method) {
        Entry entry=entry(method);return entry==null || entry.metadata==null?null:ReflectionTypes.wrap(entry.metadata.getAnnotatedExceptionTypes(),entry.metadata,method,entry.generic==null?null:entry.generic::resolve);
    }
    public static AnnotatedType annotatedReceiver(Executable method) {
        Entry entry=entry(method);return entry==null || entry.metadata==null?null:ReflectionTypes.wrap(entry.metadata.getAnnotatedReceiverType(),entry.metadata,method,entry.generic==null?null:entry.generic::resolve);
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
