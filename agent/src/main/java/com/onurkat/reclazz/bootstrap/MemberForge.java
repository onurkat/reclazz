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
    private static MethodHandle fieldRoot,fieldCopy,fieldAccessorGetter;
    private static MethodHandle serializationConstructor;
    private static Constructor<?> virtualIntConstructor;
    private static VarHandle virtualIntTarget;
    private static Object virtualIntForm;
    private static VarHandle fieldAnnotations;
    private static Class<?> fieldAccessorType,constructorAccessorType;
    private static final class Fields {
        final ConcurrentHashMap<Field,Field> normal=new ConcurrentHashMap<>();
        volatile boolean snapshots;
    }
    private static final ClassValue<Fields> fields=new ClassValue<>() {
        @Override protected Fields computeValue(Class<?> owner){return new Fields();}
    };
    private static final class FieldSnapshot implements InvocationHandler {
        final Field metadata;final InvocationHandler delegate;volatile RecordHeader.MemberView generic;
        FieldSnapshot(Field metadata,InvocationHandler delegate){this.metadata=metadata;this.delegate=delegate;}
        @Override public Object invoke(Object proxy,Method called,Object[] args)throws Throwable {return delegate.invoke(proxy,called,args);}
    }
    private record Construction(MethodHandle target,MethodHandle initializer) {
        Construction(MethodHandle target){this(target,null);}
    }
    private static final class Frame {
        Object receiver; boolean preSuperWrite;
        final RecordSchema.State recordState;final RecordCaller.View callerState;final Map<Class<?>,RecordCaller.View> callerStates;
        Frame(Object receiver,RecordSchema.State recordState,RecordCaller.Construction callerContext){this.receiver=receiver;this.recordState=recordState;this.callerState=callerContext==null?null:callerContext.own();this.callerStates=callerContext==null?Map.of():callerContext.views();}
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
        RecordCaller.ordinaryReplacement(owner);
        initializers.get(owner).put(descriptor,target);
    }
    private static Object construct(Class<?> owner,MethodHandle initializer,Object[] values) throws Throwable {
        if(Modifier.isAbstract(owner.getModifiers()))throw new InstantiationException(owner.getName());
        RecordSchema.State recordState=owner.isRecord()?RecordSchema.virtualState(owner):null;
        if(recordState!=null) {
            String descriptor=initializer.type().dropParameterTypes(0,1).changeReturnType(void.class).toMethodDescriptorString();
            initializer=recordState.membersInternal().initializerTargets().get(descriptor);
            if(initializer==null)throw new NoSuchMethodError("Retired logical record constructor: "+owner.getName()+descriptor);
        }
        RecordCaller.Construction callerContext=recordState==null?RecordCaller.capture(owner):null;
        RecordCaller.View callerState=callerContext==null?null:callerContext.own();
        if(callerState!=null) {
            String descriptor=initializer.type().dropParameterTypes(0,1).changeReturnType(void.class).toMethodDescriptorString();
            initializer=callerState.initializers.get(descriptor);
            if(initializer==null)throw new NoSuchMethodError("Retired record-cohort caller constructor: "+owner.getName()+descriptor);
        }
        return constructSnapshot(owner,initializer,values,recordState,callerContext);
    }
    private static Object constructSnapshot(Class<?> owner,MethodHandle initializer,Object[] values,RecordSchema.State recordState)throws Throwable {
        return constructSnapshot(owner,initializer,values,recordState,null);
    }
    private static Object constructSnapshot(Class<?> owner,MethodHandle initializer,Object[] values,RecordSchema.State recordState,RecordCaller.Construction callerContext)throws Throwable {
        Frame frame=new Frame(UnsafeAccess.allocateInstance(owner),recordState,callerContext);
        var stack=constructionFrames.get();stack.push(frame);
        try{return initializeReceiver(initializer,frame.receiver,values);}
        finally{stack.pop();if(stack.isEmpty())constructionFrames.remove();}
    }
    private static Object initializeReceiver(MethodHandle target,Object receiver,Object[] values) throws Throwable {
        Object[] args=new Object[values.length+1];args[0]=receiver;System.arraycopy(values,0,args,1,values.length);
        return target.invokeWithArguments(args);
    }
    private static final StackWalker constructorCaller=StackWalker.getInstance(Set.of(
            StackWalker.Option.RETAIN_CLASS_REFERENCE,StackWalker.Option.SHOW_REFLECT_FRAMES));
    /** Called after the JDK's original Constructor access check, before its accessor executes. */
    public static Object newRecordInstance(Constructor<?> constructor,Object[] args)throws InstantiationException,InvocationTargetException {
        Class<?> owner=constructor.getDeclaringClass();RecordSchema.State state=RecordSchema.virtualState(owner);
        RecordCaller.Construction callerContext=state==null?RecordCaller.capture(owner):null;
        if(state==null&&callerContext==null)return null;
        Class<?> caller=constructorCaller.walk(frames->frames.skip(1).map(StackWalker.StackFrame::getDeclaringClass).findFirst().orElse(null));
        if(caller!=Constructor.class)throw new SecurityException("Record construction requires the JDK Constructor access check");
        Object[] values=args==null?new Object[0]:args;Class<?>[] types=constructor.getParameterTypes();
        if(values.length!=types.length)throw new IllegalArgumentException("Wrong argument count");
        Object[] checked=new Object[types.length];
        for(int i=0;i<checked.length;i++)checked[i]=JacksonBridge.checkedValue(types[i],values[i]);
        String descriptor=MethodType.methodType(void.class,types).toMethodDescriptorString();
        try {
            Object value;
            if(state!=null)value=state.constructorFactory(owner,descriptor).invokeWithArguments(checked);
            else {
                MethodHandle initializer=callerContext.own().initializers.get(descriptor);
                if(initializer==null)throw new NoSuchMethodError("Retired record-cohort caller constructor: "+descriptor);
                value=constructSnapshot(owner,initializer,checked,null,callerContext);
            }
            if(value==null)throw new IllegalStateException("Logical record constructor returned null");
            return value;
        }catch(Throwable failure){throw new InvocationTargetException(failure);}
    }
    static MethodHandle recordConstructorFactory(Class<?> owner,String descriptor,RecordSchema.State state) {
        MethodType signature=MethodType.fromMethodDescriptorString(descriptor,owner.getClassLoader()).changeReturnType(owner);
        MethodHandle initializer=state.membersInternal().initializerTargets().get(descriptor);
        try {
            if(initializer==null) {
                // Cache an exception factory, not a Throwable whose VM backtrace could retain
                // the disposable caller that first invoked this retired descriptor.
                var error=MethodHandles.publicLookup().findConstructor(NoSuchMethodError.class,MethodType.methodType(void.class,String.class))
                        .bindTo("Retired logical record constructor: "+owner.getName()+descriptor);
                return MethodHandles.dropArguments(MethodHandles.foldArguments(MethodHandles.throwException(owner,NoSuchMethodError.class),error),0,signature.parameterList());
            }
            MethodHandle factory=MethodHandles.lookup().findStatic(MemberForge.class,"constructSnapshot",
                    MethodType.methodType(Object.class,Class.class,MethodHandle.class,Object[].class,RecordSchema.State.class));
            factory=MethodHandles.insertArguments(factory,0,owner,initializer);
            factory=MethodHandles.insertArguments(factory,1,state);
            return factory.asCollector(Object[].class,signature.parameterCount()).asType(signature);
        }catch(ReflectiveOperationException unavailable){throw new IllegalStateException(unavailable);}
    }
    private static final ThreadLocal<Boolean> nativeRecordConstructor=ThreadLocal.withInitial(()->false);
    static boolean preparedRecord(Class<?> owner) {return owner.isRecord()&&preparedConstructorOwner(owner);}
    private static boolean preparedConstructorOwner(Class<?> owner) {
        if(LookupCapture.recordTargetLookup(owner)!=null)return true;
        for(Field field:ReflectionBridge.physicalDeclaredFields(owner))if(field.getName().equals(InjectedNames.LOOKUP_FIELD)
                &&field.getType()==MethodHandles.Lookup.class&&Modifier.isStatic(field.getModifiers())&&field.isSynthetic())return true;
        return false;
    }
    private static final class RecordConstructorTarget {
        private static final MethodHandle SELECT;
        static {
            try{SELECT=MethodHandles.lookup().findVirtual(RecordConstructorTarget.class,"select",MethodType.methodType(MethodHandle.class));}
            catch(ReflectiveOperationException failure){throw new ExceptionInInitializerError(failure);}
        }
        private final Class<?> owner;private final String descriptor;private final MethodHandle before,callerFactory;
        RecordConstructorTarget(Class<?> owner,String descriptor,MethodHandle before){
            this.owner=owner;this.descriptor=descriptor;this.before=before;
            try {
                var factory=MethodHandles.lookup().findStatic(MemberForge.class,"constructCallerReference",MethodType.methodType(Object.class,Class.class,String.class,MethodHandle.class,Object[].class));
                callerFactory=MethodHandles.insertArguments(factory,0,owner,descriptor,before).asCollector(Object[].class,before.type().parameterCount()).asType(before.type());
            }catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
        }
        MethodHandle dispatcher() {
            var target=MethodHandles.foldArguments(MethodHandles.exactInvoker(before.type()),SELECT.bindTo(this));
            return before.isVarargsCollector()?target.asVarargsCollector(before.type().parameterType(before.type().parameterCount()-1)):target;
        }
        private MethodHandle select() {
            RecordSchema.State state=RecordSchema.virtualState(owner);
            if(state!=null)return state.constructorFactory(owner,descriptor);
            return RecordCaller.current(owner)==null?before:callerFactory;
        }
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
            fieldRoot=field.findVirtual(Field.class,"getRoot",MethodType.methodType(Field.class));
            fieldCopy=field.findVirtual(Field.class,"copy",MethodType.methodType(Field.class));
            fieldAccessorGetter=field.findGetter(Field.class,"fieldAccessor",fieldAccessorType);
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
        if(owner!=null&&owner.isRecord()) {
            LookupCapture.requireTrusted(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass(),"preparing record field roots");
            RecordSchema.validateCarrier(owner,metadata.getDeclaringClass());
        }
        return field(owner,metadata,false);
    }
    /** Preparing an equal-signature field must not replace a live root's annotation carrier. */
    public static Field snapshotField(Class<?> owner,Field metadata) {
        LookupCapture.requireTrusted(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass(),"preparing record field roots");
        if(!MethodForge.isAvailable())throw new IllegalStateException("Field roots unavailable");
        RecordSchema.validateCarrier(owner,metadata.getDeclaringClass());
        return field(owner,metadata,true);
    }
    private static Field field(Class<?> owner,Field metadata,boolean snapshot) {
        try {
            boolean trustedFinal=Modifier.isFinal(metadata.getModifiers()) && (Modifier.isStatic(metadata.getModifiers()) || owner.isRecord() || owner.isHidden());
            Field root=(Field)fieldConstructor.invoke(owner,metadata.getName().intern(),metadata.getType(),metadata.getModifiers(),trustedFinal,-1,signature(metadata),null);
            if(snapshot)fields.get(owner).snapshots=true;else fields.get(owner).normal.put(root,metadata);
            for(boolean override:new boolean[]{false,true}) {
                InvocationHandler invoke=(proxy,called,args)->{
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
                };
                Object accessor=Proxy.newProxyInstance(null,new Class<?>[]{fieldAccessorType},snapshot?new FieldSnapshot(metadata,invoke):invoke);
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
        return constructor(owner,metadata,modifiers,signature,target,false);
    }
    /** Snapshot roots do not mutate global initializer tables or an existing constructor site. */
    public static Constructor<?> snapshotConstructor(Class<?> owner,Constructor<?> metadata,int modifiers,String signature,MethodHandle target) {
        LookupCapture.requireTrusted(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass(),"preparing record constructor roots");
        if(!MethodForge.isAvailable())throw new IllegalStateException("Constructor roots unavailable");
        MethodType expected=MethodType.methodType(owner,metadata.getParameterTypes()).insertParameterTypes(0,owner);
        if(!expected.equals(target.type()))throw new IllegalArgumentException("Prepared initializer target type changed");
        RecordSchema.validateRecordMember(owner,metadata,target);
        return constructor(owner,metadata,modifiers,signature,target,true);
    }
    private static Constructor<?> constructor(Class<?> owner,Constructor<?> metadata,int modifiers,String signature,MethodHandle target,boolean snapshot) {
        try {
            Constructor<?> root=(Constructor<?>)constructorConstructor.invoke(owner,metadata.getParameterTypes(),metadata.getExceptionTypes(),modifiers,-1,signature,null,null);
            MethodHandle raw=target;
            if(!snapshot) {
                var site=constructorSites.get(owner).computeIfAbsent(target.type().toMethodDescriptorString(),key->new MutableCallSite(target));
                site.setTarget(target);MutableCallSite.syncAll(new MutableCallSite[]{site});
                raw=site.dynamicInvoker();
            }
            MethodHandle current;
            if(snapshot&&!owner.isRecord()) {
                String descriptor=target.type().dropParameterTypes(0,1).changeReturnType(void.class).toMethodDescriptorString();
                MethodHandle physical=null;
                var lookup=LookupCapture.recordTargetLookup(owner);
                if(lookup==null)lookup=MethodHandles.privateLookupIn(owner,MethodHandles.lookup());
                try{physical=nativeConstructorForReference(lookup,owner,MethodType.fromMethodDescriptorString(descriptor,owner.getClassLoader()));}
                catch(NoSuchMethodException absent){/* A constructor added only in the logical view. */}
                current=MethodHandles.lookup().findStatic(MemberForge.class,"constructCallerSnapshot",
                        MethodType.methodType(Object.class,Class.class,String.class,MethodHandle.class,MethodHandle.class,Object.class,Object[].class));
                current=MethodHandles.insertArguments(current,0,owner,descriptor,raw,physical,RecordCaller.ordinaryGeneration(owner));
            }else {
                current=MethodHandles.lookup().findStatic(MemberForge.class,"construct",
                        MethodType.methodType(Object.class,Class.class,MethodHandle.class,Object[].class));
                current=MethodHandles.insertArguments(current,0,owner,raw);
            }
            current=current.asCollector(Object[].class,target.type().parameterCount()-1)
                    .asType(target.type().dropParameterTypes(0,1));
            final MethodHandle invocation=current;
            InvocationHandler invoke=(proxy,called,args)->{
                if(!called.getName().equals("newInstance"))return objectMethod(proxy,called,args,"ReclazzConstructorAccessor");
                Object[] values=args[0]==null?new Object[0]:(Object[])args[0];
                if(values.length!=root.getParameterCount())throw new IllegalArgumentException("Wrong argument count");
                Class<?>[] types=root.getParameterTypes();Object[] checked=new Object[values.length];
                for(int i=0;i<checked.length;i++)checked[i]=JacksonBridge.checkedValue(types[i],values[i]);
                if(Modifier.isAbstract(owner.getModifiers()))throw new InstantiationException(owner.getName());
                try{return invocation.invokeWithArguments(checked);}
                catch(Throwable failure){throw new InvocationTargetException(failure);}
            };
            Object accessor=snapshot?MethodForge.snapshotAccessor(owner,current,metadata,constructorAccessorType,invoke)
                    :Proxy.newProxyInstance(null,new Class<?>[]{constructorAccessorType},invoke);
            constructorAccessor.invoke(root,accessor);
            if(snapshot)MethodForge.copyAnnotations(root,metadata);
            else {
                constructors.get(owner).put(root,new Construction(current,raw));
                MethodForge.metadata(root,metadata,current);
            }
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
        receiver(field,receiver);Class<?> owner=field.getDeclaringClass();
        if(owner.isRecord()||fieldSnapshot(field)!=null)return RecordStorage.get(field,receiver);
        String descriptor=field.getType().descriptorString();
        return Modifier.isStatic(field.getModifiers())?FieldStore.getStaticExtField(owner,field.getName(),descriptor):FieldStore.getExtField(receiver,owner.getName(),field.getName(),descriptor);
    }
    private static void set(Field field,Object receiver,Object value) {
        receiver(field,receiver);Object checked=JacksonBridge.checkedValue(field.getType(),value);
        Class<?> owner=field.getDeclaringClass();String descriptor=field.getType().descriptorString();
        if(fieldSnapshot(field)!=null&&!owner.isRecord()){RecordStorage.set(field,receiver,checked);return;}
        if(Modifier.isStatic(field.getModifiers()))FieldStore.putStaticExtField(owner,field.getName(),descriptor,checked);
        else FieldStore.putExtField(receiver,checked,owner.getName(),field.getName(),descriptor);
    }
    public static AnnotatedType annotatedField(Field field) {
        Field metadata=fieldMetadata(field);
        RecordHeader.MemberView generic=genericView(field);
        return metadata==null?null:ReflectionTypes.wrap(metadata.getAnnotatedType(),metadata.getDeclaringClass(),field.getDeclaringClass(),generic==null?null:generic::resolve);
    }
    public static boolean isField(Field field){return fieldMetadata(field)!=null;}
    private static FieldSnapshot fieldSnapshot(Field field) {
        if(!fields.get(field.getDeclaringClass()).snapshots)return null;
        try {
            Field root=field,next;while((next=(Field)fieldRoot.invoke(root))!=null)root=next;
            Object accessor=fieldAccessorGetter.invoke(root);
            return accessor!=null&&Proxy.isProxyClass(accessor.getClass())&&Proxy.getInvocationHandler(accessor) instanceof FieldSnapshot snapshot?snapshot:null;
        }catch(Throwable failure){throw new IllegalStateException(failure);}
    }
    static Field metadataSource(Field field){return fieldMetadata(field);}
    static RecordHeader.MemberView genericView(Field field){FieldSnapshot snapshot=fieldSnapshot(field);return snapshot==null?null:snapshot.generic;}
    static void bindGenericView(Field field,RecordHeader.MemberView view){FieldSnapshot snapshot=fieldSnapshot(field);if(snapshot==null||snapshot.generic!=null)throw new IllegalArgumentException("Missing or already bound snapshot field");snapshot.generic=view;}

    private static Field fieldMetadata(Field field) {
        Fields owned=fields.get(field.getDeclaringClass());
        if(owned.snapshots)try {
            Field root=field;Field next;
            while((next=(Field)fieldRoot.invoke(root))!=null)root=next;
            Object accessor=fieldAccessorGetter.invoke(root);
            if(accessor!=null&&Proxy.isProxyClass(accessor.getClass())) {
                var handler=Proxy.getInvocationHandler(accessor);
                if(handler instanceof FieldSnapshot snapshot)return snapshot.metadata;
            }
        }catch(Throwable failure){throw new IllegalStateException("Cannot read prepared field metadata",failure);}
        return owned.normal.get(field);
    }
    static Field reflectionCopy(Field field) {
        try {
            Field root=field;Field next;
            while((next=(Field)fieldRoot.invoke(root))!=null)root=next;
            return (Field)fieldCopy.invoke(root);
        }catch(Throwable failure){throw new IllegalStateException("Cannot copy field root",failure);}
    }

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
        Class<?> owner=constructor.getDeclaringClass();
        if(nativeRecordConstructor.get()||referenceNativeOwner.get()==owner)return null;
        MethodHandle snapshot=MethodForge.snapshotTarget(constructor);
        Construction entry=snapshot==null?constructors.get(owner).get(constructor):new Construction(snapshot);
        if(entry==null) {
            if(!preparedConstructorOwner(owner))return null;
            MethodHandle nativeTarget;
            nativeRecordConstructor.set(true);
            try{nativeTarget=lookup.unreflectConstructor(constructor);}finally{nativeRecordConstructor.remove();}
            String descriptor=MethodType.methodType(void.class,constructor.getParameterTypes()).toMethodDescriptorString();
            return new RecordConstructorTarget(owner,descriptor,nativeTarget).dispatcher();
        }
        MethodForge.checkAccess(lookup,constructor,constructor.isAccessible(),true,false);
        MethodHandle result=entry.target;
        return constructor.isVarArgs()?result.asVarargsCollector(constructor.getParameterTypes()[constructor.getParameterCount()-1]):result;
    }

    public static MethodHandle findConstructor(MethodHandles.Lookup lookup,Class<?> owner,MethodType type) throws ReflectiveOperationException {
        if(type.returnType()!=void.class)throw new NoSuchMethodException("Constructor type must return void");
        if(nativeRecordConstructor.get()||referenceNativeOwner.get()==owner)return null;
        Constructor<?> constructor;
        try{constructor=owner.getDeclaredConstructor(type.parameterArray());}catch(NoSuchMethodException absent){
            if(RecordSchema.virtualState(owner)!=null||RecordCaller.current(owner)!=null)throw absent;
            return null;
        }
        MethodHandle snapshot=MethodForge.snapshotTarget(constructor);
        Construction logical=snapshot==null?constructors.get(owner).get(constructor):new Construction(snapshot);
        if(logical==null) {
            if(!preparedConstructorOwner(owner))return null;
            MethodHandle nativeTarget;
            nativeRecordConstructor.set(true);
            try{nativeTarget=lookup.findConstructor(owner,type);}finally{nativeRecordConstructor.remove();}
            return new RecordConstructorTarget(owner,type.toMethodDescriptorString(),nativeTarget).dispatcher();
        }
        MethodForge.checkAccess(lookup,constructor,false,true,false);
        MethodHandle result=logical.target;
        return constructor.isVarArgs()?result.asVarargsCollector(constructor.getParameterTypes()[constructor.getParameterCount()-1]):result;
    }
    private static final ThreadLocal<Class<?>> referenceNativeOwner=new ThreadLocal<>();
    static MethodHandle nativeConstructorForReference(MethodHandles.Lookup lookup,Class<?> owner,MethodType type)throws ReflectiveOperationException {
        Class<?> previous=referenceNativeOwner.get();referenceNativeOwner.set(owner);
        try{return lookup.findConstructor(owner,type);}finally{if(previous==null)referenceNativeOwner.remove();else referenceNativeOwner.set(previous);}
    }
    static MethodHandle constructorReferenceTarget(MethodHandles.Lookup lookup,Class<?> owner,MethodType type)throws ReflectiveOperationException {
        MethodHandle target=findConstructor(lookup,owner,type);if(target==null)target=lookup.findConstructor(owner,type);
        return target;
    }
    static MethodHandle futureCallerConstructorReference(Class<?> owner,String descriptor,MethodHandle before) {
        // Keep the ordinary hot path typed: no Object[] collector before a caller cohort exists.
        return new RecordConstructorTarget(owner,descriptor,before).dispatcher();
    }
    private static Object constructCallerSnapshot(Class<?> owner,String descriptor,MethodHandle prepared,MethodHandle physical,Object generation,Object[] values)throws Throwable {
        var context=RecordCaller.capture(owner);
        if(context!=null)return constructCallerContext(owner,descriptor,values,context);
        // Unpublished roots remain privately callable; only an ordinary replacement of an
        // attached cohort switches a held root to the ordinary native/added-member route.
        if(generation==RecordCaller.ordinaryGeneration(owner))
            return constructSnapshot(owner,prepared,values,null,RecordCaller.captureParents(owner));
        return constructOrdinaryCaller(owner,descriptor,physical,values);
    }
    private static Object constructCallerReference(Class<?> owner,String descriptor,MethodHandle before,Object[] values)throws Throwable {
        var context=RecordCaller.capture(owner);
        return context==null?constructOrdinaryCaller(owner,descriptor,before,values):constructCallerContext(owner,descriptor,values,context);
    }
    private static Object constructOrdinaryCaller(Class<?> owner,String descriptor,MethodHandle before,Object[] values)throws Throwable {

            if(before!=null)return before.asFixedArity().invokeWithArguments(values);
            // A virtual-only constructor may survive through the ordinary added-member path.
            // Resolve its current root rather than retaining the previous cohort's initializer.
            Constructor<?> current;
            try{current=owner.getDeclaredConstructor(MethodType.fromMethodDescriptorString(descriptor,owner.getClassLoader()).parameterArray());}
            catch(NoSuchMethodException absent){throw new NoSuchMethodError("Retired record-cohort caller constructor: "+descriptor);}
            Construction ordinary=constructors.get(owner).get(current);
            if(ordinary==null||MethodForge.snapshotTarget(current)!=null)
                throw new NoSuchMethodError("Retired record-cohort caller constructor: "+descriptor);
            return constructSnapshot(owner,ordinary.initializer,values,null,RecordCaller.captureParents(owner));
    }
    private static Object constructCallerContext(Class<?> owner,String descriptor,Object[] values,RecordCaller.Construction context)throws Throwable {
        MethodHandle initializer=context.own().initializers.get(descriptor);
        if(initializer==null)throw new NoSuchMethodError("Retired record-cohort caller constructor: "+descriptor);
        return constructSnapshot(owner,initializer,values,null,context);
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
        if((declaring==Object.class||declaring==Record.class&&frame.receiver.getClass().isRecord())
                &&descriptor.equals("()V"))return frame.receiver;
        MethodHandle target;
        if(frame.recordState!=null&&declaring==frame.receiver.getClass()) {
            target=frame.recordState.membersInternal().initializerTargets().get(descriptor);
            if(target==null)throw new NoSuchMethodError("Retired record delegation: "+descriptor);
        } else if(frame.callerStates.containsKey(declaring)) {
            target=frame.callerStates.get(declaring).initializers.get(descriptor);
            if(target==null)throw new NoSuchMethodError("Retired record-cohort caller delegation: "+descriptor);
        } else target=initializers.get(declaring).get(descriptor);
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
        Frame callerFrame=constructionFrames.get().peek();
        if(callerFrame!=null&&callerFrame.callerStates.containsKey(owner)) {
            if(callerFrame.receiver!=receiver||!owner.isInstance(receiver))throw new IllegalStateException("Caller field write outside its captured construction frame");
            Field field=callerFrame.callerStates.get(owner).fields.stream().filter(f->f.getName().equals(name)).findFirst().orElseThrow(()->new NoSuchFieldException(name));
            RecordStorage.put(field,receiver,value);return;
        }
        if(owner.isRecord()) {
            Frame frame=constructionFrames.get().peek();
            if(frame==null||frame.receiver!=receiver||receiver.getClass()!=owner)
                throw new IllegalStateException("Record field write outside its logical construction frame");
            Field field;
            if(frame.recordState!=null)field=frame.recordState.membersInternal().fields().stream()
                    .filter(f->f.getName().equals(name)).findFirst().orElseThrow(()->new NoSuchFieldException(name));
            else field=owner.getDeclaredField(name);
            RecordStorage.put(field,receiver,value);return;
        }
        Field field=owner.getDeclaredField(name);field.setAccessible(true);field.set(receiver,value);
    }
    public static void finishConstruction(){UnsafeAccess.storeFence();}
}
