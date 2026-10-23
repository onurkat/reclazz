/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Real reflection accessors for logical members; no VM offset or constructor slot is dereferenced. */
public final class MemberForge {
    private static MethodHandle fieldConstructor,constructorConstructor,fieldAccessor,overrideFieldAccessor,constructorAccessor;
    private static MethodHandle serializationConstructor;
    private static Constructor<?> virtualIntConstructor;
    private static VarHandle virtualIntTarget;
    private static Object virtualIntForm;
    private static VarHandle fieldAnnotations;
    private static Class<?> fieldAccessorType,constructorAccessorType;
    private static final ClassValue<ConcurrentHashMap<Field,Field>> fields=new ClassValue<>() {
        @Override protected ConcurrentHashMap<Field,Field> computeValue(Class<?> owner){return new ConcurrentHashMap<>();}
    };
    private record Construction(MethodHandle target) { }
    private static final class Frame {
        Object receiver; boolean preSuperWrite;
        Frame(Object receiver){this.receiver=receiver;}
    }
    private static final ThreadLocal<ArrayDeque<Frame>> constructionFrames=ThreadLocal.withInitial(ArrayDeque::new);
    private static final ClassValue<ConcurrentHashMap<String,MethodHandle>> initializers=new ClassValue<>() {
        @Override protected ConcurrentHashMap<String,MethodHandle> computeValue(Class<?> owner){return new ConcurrentHashMap<>();}
    };
    private static java.util.function.BiFunction<Class<?>,String,MethodHandle> initializerProvider;
    public static void initializerProvider(java.util.function.BiFunction<Class<?>,String,MethodHandle> provider) {
        LookupCapture.requireTrusted(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass(),"installing constructor initializers");
        initializerProvider=provider;
    }
    public static void initializer(Class<?> owner,String descriptor,MethodHandle target) {
        LookupCapture.requireTrusted(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass(),"registering constructor initializers");
        initializers.get(owner).put(descriptor,target);
    }
    private static Object construct(Class<?> owner,MethodHandle initializer,Object[] values) throws Throwable {
        if(Modifier.isAbstract(owner.getModifiers()))throw new InstantiationException(owner.getName());
        Frame frame=new Frame(UnsafeAccess.allocateInstance(owner));
        var stack=constructionFrames.get();stack.push(frame);
        try{return initializeReceiver(initializer,frame.receiver,values);}
        finally{stack.pop();if(stack.isEmpty())constructionFrames.remove();}
    }
    private static Object initializeReceiver(MethodHandle target,Object receiver,Object[] values) throws Throwable {
        Object[] args=new Object[values.length+1];args[0]=receiver;System.arraycopy(values,0,args,1,values.length);
        return target.invokeWithArguments(args);
    }
    private static final ClassValue<ConcurrentHashMap<String,MutableCallSite>> constructorSites=new ClassValue<>() {
        @Override protected ConcurrentHashMap<String,MutableCallSite> computeValue(Class<?> owner){return new ConcurrentHashMap<>();}
    };
    private static final ClassValue<ConcurrentHashMap<Constructor<?>,Construction>> constructors=new ClassValue<>() {
        @Override protected ConcurrentHashMap<Constructor<?>,Construction> computeValue(Class<?> owner){return new ConcurrentHashMap<>();}
    };
    private MemberForge() { }

    static Throwable initializationFailure;
    static boolean initialize() {
        try {
            var field=MethodHandles.privateLookupIn(Field.class,MethodHandles.lookup());
            fieldAccessorType=Class.forName("jdk.internal.reflect.FieldAccessor");
            fieldConstructor=field.findConstructor(Field.class,MethodType.methodType(void.class,Class.class,String.class,Class.class,int.class,boolean.class,int.class,String.class,byte[].class));
            fieldAccessor=field.findSetter(Field.class,"fieldAccessor",fieldAccessorType);
            overrideFieldAccessor=field.findSetter(Field.class,"overrideFieldAccessor",fieldAccessorType);
            fieldAnnotations=field.findVarHandle(Field.class,"declaredAnnotations",Map.class);
            var ctor=MethodHandles.privateLookupIn(Constructor.class,MethodHandles.lookup());
            constructorAccessorType=Class.forName("jdk.internal.reflect.ConstructorAccessor");
            constructorConstructor=ctor.findConstructor(Constructor.class,MethodType.methodType(void.class,Class.class,Class[].class,Class[].class,int.class,int.class,String.class,byte[].class,byte[].class));
            constructorAccessor=ctor.findSetter(Constructor.class,"constructorAccessor",constructorAccessorType);
            Class<?> factory=Class.forName("jdk.internal.reflect.ReflectionFactory");
            var reflect=MethodHandles.privateLookupIn(factory,MethodHandles.lookup());
            Object instance=reflect.findStatic(factory,"getReflectionFactory",MethodType.methodType(factory)).invoke();
            serializationConstructor=reflect.findVirtual(factory,"newConstructorForSerialization",MethodType.methodType(Constructor.class,Class.class,Constructor.class)).bindTo(instance);
            return true;
        }catch(Throwable failure){initializationFailure=failure;return false;}
    }

    public static Field field(Class<?> owner,Field metadata) {
        try {
            boolean trustedFinal=Modifier.isFinal(metadata.getModifiers()) && (Modifier.isStatic(metadata.getModifiers()) || owner.isRecord() || owner.isHidden());
            Field root=(Field)fieldConstructor.invoke(owner,metadata.getName().intern(),metadata.getType(),metadata.getModifiers(),trustedFinal,-1,signature(metadata),null);
            fields.get(owner).put(root,metadata);
            for(boolean override:new boolean[]{false,true}) {
                Object accessor=Proxy.newProxyInstance(null,new Class<?>[]{fieldAccessorType},(proxy,called,args)->{
                    if(called.getDeclaringClass()==Object.class)return objectMethod(proxy,called,args,"ReclazzFieldAccessor");
                    Object receiver=args[0];boolean write=called.getName().startsWith("set");
                    if(write){
                        if(Modifier.isFinal(root.getModifiers()) && (!override || trustedFinal))throw new IllegalAccessException("final field: "+root);
                        set(root,receiver,args[1]);return null;
                    }
                    Object value=get(root,receiver);
                    if(called.getName().equals("get"))return value;
                    if(!root.getType().isPrimitive())throw new IllegalArgumentException("Not a primitive field");
                    return JacksonBridge.checkedValue(called.getReturnType(),value);
                });
                (override?overrideFieldAccessor:fieldAccessor).invoke(root,accessor);
            }
            var values=new LinkedHashMap<Class<? extends java.lang.annotation.Annotation>,java.lang.annotation.Annotation>();
            for(var a:metadata.getDeclaredAnnotations())values.put(a.annotationType(),a);
            fieldAnnotations.setVolatile(root,Map.copyOf(values));
            return root;
        }catch(Throwable failure){throw new IllegalStateException("Cannot create field root for "+metadata.getName(),failure);}
    }

    // Signatures are supplied by the classfile publisher, not reconstructed from a Type string.
    private static final ClassValue<ConcurrentHashMap<String,String>> signatures=new ClassValue<>() {
        @Override protected ConcurrentHashMap<String,String> computeValue(Class<?> c){return new ConcurrentHashMap<>();}
    };
    public static void fieldSignature(Class<?> metadata,String name,String signature){if(signature!=null)signatures.get(metadata).put(name,signature);}
    private static String signature(Field field){return signatures.get(field.getDeclaringClass()).get(field.getName());}

    public static Constructor<?> constructor(Class<?> owner,Constructor<?> metadata,int modifiers,String signature,MethodHandle target) {
        try {
            Constructor<?> root=(Constructor<?>)constructorConstructor.invoke(owner,metadata.getParameterTypes(),metadata.getExceptionTypes(),modifiers,-1,signature,null,null);
            var site=constructorSites.get(owner).computeIfAbsent(target.type().toMethodDescriptorString(),key->new MutableCallSite(target));
            site.setTarget(target);MutableCallSite.syncAll(new MutableCallSite[]{site});
            MethodHandle raw=site.dynamicInvoker();
            MethodHandle current=MethodHandles.lookup().findStatic(MemberForge.class,"construct",
                    MethodType.methodType(Object.class,Class.class,MethodHandle.class,Object[].class));
            current=MethodHandles.insertArguments(current,0,owner,raw).asCollector(Object[].class,target.type().parameterCount()-1)
                    .asType(target.type().dropParameterTypes(0,1));
            final MethodHandle invocation=current;
            Object accessor=Proxy.newProxyInstance(null,new Class<?>[]{constructorAccessorType},(proxy,called,args)->{
                if(!called.getName().equals("newInstance"))return objectMethod(proxy,called,args,"ReclazzConstructorAccessor");
                Object[] values=args[0]==null?new Object[0]:(Object[])args[0];
                if(values.length!=root.getParameterCount())throw new IllegalArgumentException("Wrong argument count");
                Class<?>[] types=root.getParameterTypes();Object[] checked=new Object[values.length];
                for(int i=0;i<checked.length;i++)checked[i]=JacksonBridge.checkedValue(types[i],values[i]);
                if(Modifier.isAbstract(owner.getModifiers()))throw new InstantiationException(owner.getName());
                try{return invocation.invokeWithArguments(checked);}
                catch(Throwable failure){throw new InvocationTargetException(failure);}
            });
            constructorAccessor.invoke(root,accessor);
            constructors.get(owner).put(root,new Construction(current));
            MethodForge.metadata(root,metadata,current);
            return root;
        }catch(Throwable failure){throw new IllegalStateException("Cannot create constructor root for "+owner.getName(),failure);}
    }

    private static Object objectMethod(Object proxy,Method called,Object[] args,String label) {
        return switch(called.getName()){case "equals"->proxy==args[0];case "hashCode"->System.identityHashCode(proxy);default->label;};
    }
    private static void receiver(Field field,Object receiver) {
        if(!Modifier.isStatic(field.getModifiers())) {
            Objects.requireNonNull(receiver);
            if(!field.getDeclaringClass().isInstance(receiver))throw new IllegalArgumentException("Invalid field receiver");
        }
    }
    private static Object get(Field field,Object receiver) {
        receiver(field,receiver);Class<?> owner=field.getDeclaringClass();String descriptor=field.getType().descriptorString();
        return Modifier.isStatic(field.getModifiers())?FieldStore.getStaticExtField(owner,field.getName(),descriptor):FieldStore.getExtField(receiver,owner.getName(),field.getName(),descriptor);
    }
    private static void set(Field field,Object receiver,Object value) {
        receiver(field,receiver);Object checked=JacksonBridge.checkedValue(field.getType(),value);
        Class<?> owner=field.getDeclaringClass();String descriptor=field.getType().descriptorString();
        if(Modifier.isStatic(field.getModifiers()))FieldStore.putStaticExtField(owner,field.getName(),descriptor,checked);
        else FieldStore.putExtField(receiver,checked,owner.getName(),field.getName(),descriptor);
    }
    public static AnnotatedType annotatedField(Field field) {
        Field metadata=fields.get(field.getDeclaringClass()).get(field);
        return metadata==null?null:ReflectionTypes.wrap(metadata.getAnnotatedType(),metadata.getDeclaringClass(),field.getDeclaringClass());
    }
    public static boolean isField(Field field){return fields.get(field.getDeclaringClass()).containsKey(field);}

    @SuppressWarnings("deprecation")
    public static MethodHandle unreflectField(MethodHandles.Lookup lookup,Field field,boolean setter) throws IllegalAccessException {
        if(!isField(field))return null;
        MethodForge.checkAccess(lookup,field,field.isAccessible(),false,setter);
        try {
            MethodHandle result=MethodHandles.lookup().findStatic(MemberForge.class,setter?"set":"get",setter?
                    MethodType.methodType(void.class,Field.class,Object.class,Object.class):MethodType.methodType(Object.class,Field.class,Object.class)).bindTo(field);
            if(Modifier.isStatic(field.getModifiers()))result=MethodHandles.insertArguments(result,0,(Object)null);
            MethodType type=setter?MethodType.methodType(void.class,Modifier.isStatic(field.getModifiers())?new Class<?>[]{field.getType()}:new Class<?>[]{field.getDeclaringClass(),field.getType()}):
                    MethodType.methodType(field.getType(),Modifier.isStatic(field.getModifiers())?new Class<?>[0]:new Class<?>[]{field.getDeclaringClass()});
            if(!field.isAccessible() && Modifier.isProtected(field.getModifiers()) && !Modifier.isStatic(field.getModifiers())
                    && !MethodForge.samePackage(field.getDeclaringClass(),lookup.lookupClass()))type=type.changeParameterType(0,lookup.lookupClass());
            return result.asType(type);
        }catch(NoSuchMethodException failure){throw new IllegalStateException(failure);}
    }
    public static void installIntVarHandle() throws ReflectiveOperationException {
        LookupCapture.requireTrusted(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass(),"installing a VarHandle implementation");
        Class<?> formClass=Class.forName("java.lang.invoke.VarForm");
        var formConstructor=formClass.getDeclaredConstructor(Class.class,Class.class,Class.class,Class[].class);formConstructor.setAccessible(true);
        virtualIntForm=formConstructor.newInstance(VirtualIntAccess.class,Object.class,int.class,new Class<?>[0]);
        Class<?> directClass=Class.forName("java.lang.invoke.VarHandleInts$FieldInstanceReadOnly");
        var directConstructor=directClass.getDeclaredConstructor(Class.class,long.class,formClass,boolean.class);directConstructor.setAccessible(true);
        // The direct target supplies supported-mode entries only. No VM field offset is ever used.
        virtualIntTarget=(VarHandle)directConstructor.newInstance(Object.class,0L,virtualIntForm,false);
        Class<?> indirect=Class.forName("java.lang.invoke.IndirectVarHandle");
        virtualIntConstructor=indirect.getDeclaredConstructor(VarHandle.class,Class.class,Class[].class,java.util.function.BiFunction.class,formClass,boolean.class);
        virtualIntConstructor.setAccessible(true);
    }
    public static VarHandle unreflectVarHandle(MethodHandles.Lookup lookup,Field field) throws IllegalAccessException {
        if(!isField(field))return null;
        MethodForge.checkAccess(lookup,field,false,false,false);
        if(field.getType()!=int.class || Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers()))
            throw new IllegalAccessException("Virtual VarHandle currently requires a mutable int instance field");
        Class<?> coordinate=field.getDeclaringClass();
        if(Modifier.isProtected(field.getModifiers()) && !MethodForge.samePackage(coordinate,lookup.lookupClass()))coordinate=lookup.lookupClass();
        Class<?> receiverType=coordinate;
        java.util.function.BiFunction<VarHandle.AccessMode,MethodHandle,MethodHandle> factory=(mode,ignored)->VirtualIntAccess.handle(field,receiverType,mode);
        try{return (VarHandle)virtualIntConstructor.newInstance(virtualIntTarget,int.class,new Class<?>[]{coordinate},factory,virtualIntForm,false);}
        catch(Throwable failure){throw new IllegalStateException("Cannot create virtual int VarHandle",failure);}
    }
    @SuppressWarnings("deprecation")
    public static MethodHandle unreflectConstructor(MethodHandles.Lookup lookup,Constructor<?> constructor) throws IllegalAccessException {
        Construction entry=constructors.get(constructor.getDeclaringClass()).get(constructor);
        if(entry==null)return null;
        MethodForge.checkAccess(lookup,constructor,constructor.isAccessible(),true,false);
        MethodHandle result=entry.target;
        return constructor.isVarArgs()?result.asVarargsCollector(constructor.getParameterTypes()[constructor.getParameterCount()-1]):result;
    }

    public static MethodHandle findConstructor(MethodHandles.Lookup lookup,Class<?> owner,MethodType type) throws ReflectiveOperationException {
        if(type.returnType()!=void.class)throw new NoSuchMethodException("Constructor type must return void");
        Constructor<?> constructor;
        try{constructor=owner.getDeclaredConstructor(type.parameterArray());}catch(NoSuchMethodException absent){return null;}
        Construction logical=constructors.get(owner).get(constructor);
        if(logical==null)return null;
        MethodForge.checkAccess(lookup,constructor,false,true,false);
        MethodHandle result=logical.target;
        return constructor.isVarArgs()?result.asVarargsCollector(constructor.getParameterTypes()[constructor.getParameterCount()-1]):result;
    }
    public static void ensureInitialized(Class<?> owner) throws ClassNotFoundException {
        Class.forName(owner.getName(),true,owner.getClassLoader());
    }
    public static Object invokeConstructor(MethodHandles.Lookup lookup,Class<?> owner,String descriptor,Object[] values) throws Throwable {
        MethodType type=MethodType.fromMethodDescriptorString(descriptor,owner.getClassLoader());
        MethodHandle target=findConstructor(lookup,owner,type);
        if(target==null)target=lookup.findConstructor(owner,type);
        return target.asFixedArity().invokeWithArguments(values);
    }
    /** Delegation runs constructor bodies on the allocation belonging to the outermost logical constructor. */
    public static Object delegate(Class<?> owner,Class<?> declaring,String descriptor,Object[] values) throws Throwable {
        Frame frame=constructionFrames.get().peek();
        if(frame==null)throw new IllegalStateException("Missing logical construction frame");
        if(declaring==Object.class && descriptor.equals("()V"))return frame.receiver;
        MethodHandle target=initializers.get(declaring).get(descriptor);
        if(target==null && initializerProvider!=null) {
            target=initializerProvider.apply(declaring,descriptor);
            if(target!=null)initializers.get(declaring).putIfAbsent(descriptor,target);
        }
        if(target!=null)return initializeReceiver(target,frame.receiver,values);
        if(frame.preSuperWrite)throw new UnsupportedOperationException("Cannot preserve pre-super writes through an uninstrumented superclass: "+declaring.getName());
        Class<?> allocation=frame.receiver.getClass();
        Constructor<?> ctor=declaring.getDeclaredConstructor(MethodType.fromMethodDescriptorString(descriptor,owner.getClassLoader()).parameterArray());
        if(allocation!=declaring)ctor=(Constructor<?>)serializationConstructor.invoke(allocation,ctor);
        ctor.setAccessible(true);
        try{frame.receiver=ctor.newInstance(values);return frame.receiver;}
        catch(InvocationTargetException failure){throw failure.getCause();}
    }
    public static void beforeSuperField(Object receiver,Object value,Class<?> owner,String name) throws ReflectiveOperationException {
        Frame frame=constructionFrames.get().peek();
        if(frame==null || frame.receiver!=receiver)throw new IllegalStateException("Wrong pre-super receiver");
        frame.preSuperWrite=true;constructorField(receiver,value,owner,name);
    }

    /** Final instance writes in a constructor body cannot remain putfield instructions in a static companion. */
    public static void constructorField(Object receiver,Object value,Class<?> owner,String name) throws ReflectiveOperationException {
        Field field=owner.getDeclaredField(name);field.setAccessible(true);field.set(receiver,value);
    }
    public static void finishConstruction(){UnsafeAccess.storeFence();}
}
