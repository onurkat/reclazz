/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.annotation.Annotation;
import java.lang.invoke.*;
import java.lang.ref.*;
import java.lang.reflect.*;
import java.util.*;

/** Private candidate-owned generic scopes and annotation data, published by the record Slot. */
public final class RecordHeader {
    private static final StackWalker WALKER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static Class<?> scopeType,factoryType,classRepository,methodRepository,constructorRepository,fieldRepository;
    private static MethodHandle coreMake,classMake,methodMake,constructorMake,fieldMake,classParameters,methodParameters,constructorParameters;
    private static MethodHandle signature,annotationDataConstructor,typeVariableMake,wildcardMake;
    private static VarHandle classInfo;
    private static VarHandle methodSignature,constructorSignature,fieldSignature,methodInfo,constructorInfo,fieldInfo;
    private static volatile boolean available;
    public static Throwable initializationFailure;
    private RecordHeader() { }
    public static synchronized boolean initialize() {
        LookupCapture.requireTrusted(WALKER.getCallerClass(),"initializing record class metadata");
        if(available)return true;
        try {
            scopeType=Class.forName("sun.reflect.generics.scope.Scope");factoryType=Class.forName("sun.reflect.generics.factory.GenericsFactory");
            Class<?> core=Class.forName("sun.reflect.generics.factory.CoreReflectionFactory");
            var coreLookup=MethodHandles.privateLookupIn(core,MethodHandles.lookup());
            coreMake=coreLookup.findStatic(core,"make",MethodType.methodType(core,GenericDeclaration.class,scopeType));
            classRepository=Class.forName("sun.reflect.generics.repository.ClassRepository");
            methodRepository=Class.forName("sun.reflect.generics.repository.MethodRepository");
            constructorRepository=Class.forName("sun.reflect.generics.repository.ConstructorRepository");
            fieldRepository=Class.forName("sun.reflect.generics.repository.FieldRepository");
            classMake=maker(classRepository);methodMake=maker(methodRepository);constructorMake=maker(constructorRepository);fieldMake=maker(fieldRepository);
            Class<?> signatures=Class.forName("[Lsun.reflect.generics.tree.FieldTypeSignature;");
            Class<?> typeVariable=Class.forName("sun.reflect.generics.reflectiveObjects.TypeVariableImpl");
            typeVariableMake=MethodHandles.privateLookupIn(typeVariable,MethodHandles.lookup()).findStatic(typeVariable,"make",MethodType.methodType(typeVariable,GenericDeclaration.class,String.class,signatures,factoryType));
            Class<?> wildcard=Class.forName("sun.reflect.generics.reflectiveObjects.WildcardTypeImpl");
            wildcardMake=MethodHandles.privateLookupIn(wildcard,MethodHandles.lookup()).findStatic(wildcard,"make",MethodType.methodType(wildcard,signatures,signatures,factoryType));
            classParameters=parameters(classRepository);methodParameters=parameters(methodRepository);constructorParameters=parameters(constructorRepository);
            var classes=MethodHandles.privateLookupIn(Class.class,MethodHandles.lookup());
            classInfo=classes.findVarHandle(Class.class,"genericInfo",classRepository);
            signature=classes.findVirtual(Class.class,"getGenericSignature0",MethodType.methodType(String.class));
            Class<?> data=Class.forName("java.lang.Class$AnnotationData");
            annotationDataConstructor=MethodHandles.privateLookupIn(data,MethodHandles.lookup()).findConstructor(data,MethodType.methodType(void.class,Map.class,Map.class,int.class));
            var methods=MethodHandles.privateLookupIn(Method.class,MethodHandles.lookup());
            methodSignature=methods.findVarHandle(Method.class,"signature",String.class);methodInfo=methods.findVarHandle(Method.class,"genericInfo",methodRepository);
            var constructors=MethodHandles.privateLookupIn(Constructor.class,MethodHandles.lookup());
            constructorSignature=constructors.findVarHandle(Constructor.class,"signature",String.class);constructorInfo=constructors.findVarHandle(Constructor.class,"genericInfo",constructorRepository);
            var fields=MethodHandles.privateLookupIn(Field.class,MethodHandles.lookup());
            fieldSignature=fields.findVarHandle(Field.class,"signature",String.class);fieldInfo=fields.findVarHandle(Field.class,"genericInfo",fieldRepository);
            available=true;return true;
        }catch(Throwable failure){initializationFailure=failure;return false;}
    }
    private static MethodHandle maker(Class<?> repository)throws ReflectiveOperationException {
        return MethodHandles.privateLookupIn(repository,MethodHandles.lookup()).findStatic(repository,"make",MethodType.methodType(repository,String.class,factoryType));
    }
    private static MethodHandle parameters(Class<?> repository)throws ReflectiveOperationException {
        return MethodHandles.privateLookupIn(repository,MethodHandles.lookup()).findVirtual(repository,"getTypeParameters",MethodType.methodType(TypeVariable[].class));
    }
    private static RuntimeException failure(Throwable failure){return failure instanceof RuntimeException r?r:new IllegalStateException("Record generic metadata unavailable",failure);}

    /** Owned by the record State or by its disposable caller View; never shared across owners. */
    public static final class View {
        final Class<?> owner,carrier;final Context context,sourceContext;final Object repository,annotations;
        final AnnotatedType superclass;final AnnotatedType[] interfaces;
        private View(Class<?> owner,Class<?> carrier,Context context,Context sourceContext,Object repository,Object annotations) {
            this.owner=owner;this.carrier=carrier;this.context=context;this.sourceContext=sourceContext;this.repository=repository;this.annotations=annotations;
            superclass=ReflectionTypes.wrap(carrier.getAnnotatedSuperclass(),carrier,owner,context::resolve);
            interfaces=ReflectionTypes.wrap(carrier.getAnnotatedInterfaces(),carrier,owner,context::resolve);
        }
    }
    public static View prepare(Class<?> owner,Class<?> carrier) {
        LookupCapture.requireTrusted(WALKER.getCallerClass(),"preparing record class metadata");
        if(!available)throw new IllegalStateException("Record class metadata factory unavailable",initializationFailure);
        RecordSchema.validateCarrier(owner,carrier);
        if(!owner.isRecord()||!carrier.isHidden()||!carrier.isRecord())throw new IllegalArgumentException("Record header requires its inert hidden carrier");
        return prepareHeader(owner,carrier);
    }
    public static View prepareCaller(Class<?> owner,Class<?> carrier) {
        LookupCapture.requireTrusted(WALKER.getCallerClass(),"preparing record-cohort caller class metadata");
        if(!available)throw new IllegalStateException("Caller class metadata factory unavailable",initializationFailure);
        if(owner.isRecord()||!carrier.isHidden()||carrier.getClassLoader()!=owner.getClassLoader()||carrier.getNestHost()!=owner.getNestHost())
            throw new IllegalArgumentException("Caller header requires its own inert hidden carrier");
        return prepareHeader(owner,carrier);
    }
    private static View prepareHeader(Class<?> owner,Class<?> carrier) {
        baselines.get(owner); // Capture physical variables/annotations while the public Class is still unchanged.
        try {
            var annotations=new LinkedHashMap<Class<? extends Annotation>,Annotation>();
            var declared=new LinkedHashMap<Class<? extends Annotation>,Annotation>();
            if(!owner.isRecord()&&owner.getSuperclass()!=null)for(var value:owner.getSuperclass().getAnnotations())
                if(value.annotationType().isAnnotationPresent(java.lang.annotation.Inherited.class))annotations.put(value.annotationType(),value);
            for(var value:carrier.getAnnotations())annotations.put(value.annotationType(),value);
            for(var value:carrier.getDeclaredAnnotations())declared.put(value.annotationType(),value);
            Object data=annotationDataConstructor.invoke(Map.copyOf(annotations),Map.copyOf(declared),0);
            String generic=(String)signature.invoke(carrier);
            Context sourceContext=new Context(carrier,null,null,classParameters,owner);
            Object sourceRepository=generic==null?null:classMake.invoke(generic,sourceContext.factory());
            sourceContext.repository=sourceRepository;
            // Only our inert private carrier changes here. Application Class stays physical.
            classInfo.set(carrier,sourceRepository);sourceContext.variables();sourceContext.prewarm();
            Context context=new Context(owner,carrier,null,classParameters);
            Object repository=generic==null?null:classMake.invoke(generic,context.factory());
            context.repository=repository;context.variables();context.prewarm();
            return new View(owner,carrier,context,sourceContext,repository,data);
        }catch(Throwable failure){throw failure(failure);}
    }
    static final class MemberView {
        final Object repository;final Context context;
        MemberView(Object repository,Context context){this.repository=repository;this.context=context;}
        Type resolve(TypeVariable<?> source){return context.resolve(source);}
    }
    public static void bind(Executable member,View view) {
        LookupCapture.requireTrusted(WALKER.getCallerClass(),"binding record member generic metadata");
        if(member.getDeclaringClass()!=view.owner)throw new IllegalArgumentException("Foreign record member header");
        Executable source=MethodForge.metadataSource(member);if(source==null)throw new IllegalArgumentException("Missing private member metadata");
        MemberView privateSource=member(source,null,view.sourceContext);
        (source instanceof Method?methodInfo:constructorInfo).set(source,privateSource.repository);
        MemberView binding=member(member,source,view.context);
        MethodForge.bindGenericView(member,binding);
        prewarm(member);
    }
    public static void bind(Field field,View view) {
        LookupCapture.requireTrusted(WALKER.getCallerClass(),"binding record field generic metadata");
        if(field.getDeclaringClass()!=view.owner)throw new IllegalArgumentException("Foreign record field header");
        Field source=MemberForge.metadataSource(field);if(source==null)throw new IllegalArgumentException("Missing private field metadata");
        fieldInfo.set(source,member(source,null,view.sourceContext).repository);
        MemberView binding=member(field,null,view.context);MemberForge.bindGenericView(field,binding);
        field.getGenericType();field.getAnnotatedType();
    }
    public static void bind(RecordComponent component,View view) {
        LookupCapture.requireTrusted(WALKER.getCallerClass(),"binding private record component generic metadata");
        if(component.getDeclaringRecord()!=view.carrier)throw new IllegalArgumentException("Foreign record component metadata");
        try {
            String generic=(String)componentSignature.get(component);
            componentInfo.set(component,generic==null?null:fieldMake.invoke(generic,view.sourceContext.factory()));
            component.getGenericType();component.getAnnotatedType();
        }catch(Throwable failure){throw failure(failure);}
    }
    private static void prewarm(Executable member) {
        for(var variable:member.getTypeParameters()){variable.getBounds();variable.getDeclaredAnnotations();variable.getAnnotatedBounds();}
        if(member instanceof Method method)method.getGenericReturnType();
        member.getGenericParameterTypes();member.getGenericExceptionTypes();
        member.getAnnotatedReturnType();member.getAnnotatedParameterTypes();member.getAnnotatedExceptionTypes();member.getAnnotatedReceiverType();
    }
    private static MemberView member(Member member,Executable source,Context parent) {
        try {
            boolean field=member instanceof Field;
            String generic=(String)(field?fieldSignature:member instanceof Method?methodSignature:constructorSignature).get(member);
            if(field)return new MemberView(generic==null?null:fieldMake.invoke(generic,parent.factory()),parent);
            Context context=new Context((Executable)member,source,parent,member instanceof Method?methodParameters:constructorParameters);
            Object repository=generic==null?null:(member instanceof Method?methodMake:constructorMake).invoke(generic,context.factory());
            context.repository=repository;context.variables();return new MemberView(repository,context);
        }catch(Throwable failure){throw failure(failure);}
    }
    private static View current(Class<?> owner){if(!owner.isRecord()){RecordCaller.View caller=RecordCaller.current(owner);return caller==null?null:caller.header;}RecordSchema.State state=RecordSchema.virtualState(owner);return state==null?null:state.headerInternal();}
    public static boolean hasView(Class<?> owner){return current(owner)!=null;}
    public static Object genericInfo(Class<?> owner){View view=current(owner);return view==null?null:view.repository;}
    public static Object annotationData(Class<?> owner){View view=current(owner);return view==null?null:view.annotations;}
    public static AnnotatedType annotatedSuperclass(Class<?> owner){View view=current(owner);return view==null?null:view.superclass;}
    public static AnnotatedType[] annotatedInterfaces(Class<?> owner){View view=current(owner);return view==null?null:view.interfaces.clone();}
    public static boolean hasMemberView(Object member) {
        if(member instanceof Executable e&&MethodForge.genericView(e)!=null)return true;
        if(member instanceof Field f&&MemberForge.genericView(f)!=null)return true;
        return member instanceof Member m&&current(m.getDeclaringClass())!=null;
    }
    public static Object memberGenericInfo(Object value) {
        Member member=(Member)value;
        MemberView logical=value instanceof Executable e?MethodForge.genericView(e):MemberForge.genericView((Field)value);
        if(logical!=null)return logical.repository;
        View view=current(member.getDeclaringClass());if(view==null)return null;
        VarHandle cache=value instanceof Field?fieldInfo:value instanceof Method?methodInfo:constructorInfo;
        Object existing=cache.get(value);Baseline baseline=baselines.get(view.owner);
        synchronized(baseline.repositories) {
            if(existing!=null&&baseline.repositories.containsKey(existing))return existing;
            MemberView physical=member(member,null,baseline.context);Object repository=physical.repository;
            if(repository!=null){baseline.repositories.put(repository,Boolean.TRUE);cache.set(value,repository);}
            return repository;
        }
    }
    static Type physicalComponent(RecordComponent component) {
        try {
            String generic=(String)componentSignature.get(component);if(generic==null)return component.getType();
            Object repository=fieldMake.invoke(generic,baselines.get(component.getDeclaringRecord()).context.factory());
            return (Type)fieldGenericType.invoke(repository);
        }catch(Throwable failure){throw failure(failure);}
    }
    private static VarHandle componentSignature,componentInfo;
    private static MethodHandle fieldGenericType;
    public static void initializeComponents()throws ReflectiveOperationException {
        LookupCapture.requireTrusted(WALKER.getCallerClass(),"initializing record generic components");
        componentInfo=MethodHandles.privateLookupIn(RecordComponent.class,MethodHandles.lookup()).findVarHandle(RecordComponent.class,"genericInfo",fieldRepository);
        componentSignature=MethodHandles.privateLookupIn(RecordComponent.class,MethodHandles.lookup()).findVarHandle(RecordComponent.class,"signature",String.class);
        fieldGenericType=MethodHandles.privateLookupIn(fieldRepository,MethodHandles.lookup()).findVirtual(fieldRepository,"getGenericType",MethodType.methodType(Type.class));
    }
    private static final class Baseline {
        final Context context;final Map<Object,Boolean> repositories=new WeakHashMap<>();
        Baseline(Class<?> owner) {
            context=new Context(owner,null,null,null);
            context.fixed=owner.getTypeParameters();context.variables();
            for(var variable:context.fixed) {
                Annotation[] annotations=variable.getDeclaredAnnotations();Type[] bounds=variable.getBounds();AnnotatedType[] annotated=variable.getAnnotatedBounds();
                put(variable,new VariableData(null,null,annotations,annotated));
            }
        }
    }
    private static final ClassValue<Baseline> baselines=new ClassValue<>(){@Override protected Baseline computeValue(Class<?> owner){return new Baseline(owner);}};

    private static final class Context implements InvocationHandler {
        final GenericDeclaration declaration,source;final Context parent;final Class<?> self;final MethodHandle parameterGetter;
        volatile Object repository;TypeVariable<?>[] fixed;private volatile Map<String,TypeVariable<?>> names;
        private Object scope,factory,core;
        Context(GenericDeclaration declaration,GenericDeclaration source,Context parent,MethodHandle parameterGetter){this(declaration,source,parent,parameterGetter,parent==null?owner(declaration):parent.self);}
        Context(GenericDeclaration declaration,GenericDeclaration source,Context parent,MethodHandle parameterGetter,Class<?> self){this.declaration=declaration;this.source=source;this.parent=parent;this.parameterGetter=parameterGetter;this.self=self;}
        Map<String,TypeVariable<?>> variables() {
            Map<String,TypeVariable<?>> result=names;if(result!=null)return result;
            try {
                TypeVariable<?>[] parameters=fixed!=null?fixed:repository==null?new TypeVariable<?>[0]:(TypeVariable<?>[])parameterGetter.invoke(repository);
                var map=new LinkedHashMap<String,TypeVariable<?>>();for(var variable:parameters)map.put(variable.getName(),variable);names=result=Map.copyOf(map);
                Map<String,TypeVariable<?>> originals=new HashMap<>();if(source!=null)for(var variable:source.getTypeParameters())originals.put(variable.getName(),variable);
                for(var variable:parameters)if(source!=null)put(variable,new VariableData(new WeakReference<>(originals.get(variable.getName())),new WeakReference<>(this),null,null));
                return result;
            }catch(Throwable failure){throw failure(failure);}
        }
        void prewarm(){for(var variable:variables().values()){variable.getBounds();variable.getDeclaredAnnotations();variable.getAnnotatedBounds();}}
        Object factory()throws Throwable {
            if(factory==null) {
                scope=Proxy.newProxyInstance(null,new Class<?>[]{scopeType},this);
                core=coreMake.invoke(declaration,scope);
                factory=Proxy.newProxyInstance(null,new Class<?>[]{factoryType},this::make);

            }
            return factory;
        }
        private Object make(Object proxy,Method method,Object[] args)throws Throwable {
            return switch(method.getName()) {
                case "makeTypeVariable" -> typeVariableMake.invoke(declaration,args[0],args[1],proxy);
                case "makeWildcard" -> wildcardMake.invoke(args[0],args[1],proxy);
                case "makeParameterizedType" -> {
                    if(args[0]==self) {
                        Context root=this;while(root.parent!=null)root=root.parent;
                        Type[] arguments=(Type[])args[1];
                        if(arguments.length!=root.variables().size())throw new MalformedParameterizedTypeException("Candidate self type arity mismatch");
                        yield parameterized(self,arguments,(Type)args[2]);
                    }
                    yield delegated(method,args);
                }
                case "toString" -> "RecordGenericFactory";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy==args[0];
                default -> delegated(method,args);
            };
        }
        private Object delegated(Method method,Object[] args)throws Throwable {
            try{return method.invoke(core,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        }
        Type resolve(TypeVariable<?> variable) {
            GenericDeclaration original=variable.getGenericDeclaration();
            if(source!=null&&original.equals(source))return variables().get(variable.getName());
            return parent==null?null:parent.resolve(variable);
        }
        @Override public Object invoke(Object proxy,Method method,Object[] args) {
            return switch(method.getName()) {
                case "lookup" -> {TypeVariable<?> variable=variables().get((String)args[0]);yield variable!=null?variable:parent==null?null:parent.find((String)args[0]);}
                case "toString" -> "RecordGenericScope";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy==args[0];
                default -> throw new IllegalArgumentException("Unknown generic scope operation");
            };
        }
        private TypeVariable<?> find(String name){var variable=variables().get(name);return variable!=null?variable:parent==null?null:parent.find(name);}
    }
    private static ParameterizedType parameterized(Class<?> raw,Type[] arguments,Type declaring) {
        Type[] args=arguments.clone();Type enclosing=declaring==null?raw.getDeclaringClass():declaring;
        return new ParameterizedType() {
            public Type getRawType(){return raw;}public Type getOwnerType(){return enclosing;}public Type[] getActualTypeArguments(){return args.clone();}
            public boolean equals(Object other){return other instanceof ParameterizedType p&&raw.equals(p.getRawType())&&Objects.equals(enclosing,p.getOwnerType())&&Arrays.equals(args,p.getActualTypeArguments());}
            public int hashCode(){return Arrays.hashCode(args)^raw.hashCode()^Objects.hashCode(enclosing);}
            public String getTypeName(){return raw.getTypeName()+"<"+String.join(", ",Arrays.stream(args).map(Type::getTypeName).toList())+">";}
            public String toString(){return getTypeName();}
        };
    }
    private record VariableData(WeakReference<TypeVariable<?>> source,WeakReference<Context> context,Annotation[] frozenAnnotations,AnnotatedType[] frozenBounds) { }
    private static final class VariableKey extends WeakReference<TypeVariable<?>> {
        final int hash;
        VariableKey(TypeVariable<?> value,ReferenceQueue<TypeVariable<?>> queue){super(value,queue);hash=System.identityHashCode(value);}
        public int hashCode(){return hash;}
        public boolean equals(Object other){return this==other||other instanceof VariableKey key&&get()!=null&&get()==key.get();}
    }
    private static final class Variables {
        final ReferenceQueue<TypeVariable<?>> queue=new ReferenceQueue<>();final Map<VariableKey,VariableData> values=new HashMap<>();
        volatile List<VariableEntry> snapshot=List.of();
        void clean(){Reference<? extends TypeVariable<?>> dead;while((dead=queue.poll())!=null)values.remove(dead);}
    }
    private static final ClassValue<Variables> variables=new ClassValue<>(){@Override protected Variables computeValue(Class<?> owner){return new Variables();}};
    private static Class<?> owner(GenericDeclaration declaration){return declaration instanceof Class<?> c?c:((Executable)declaration).getDeclaringClass();}
    private static volatile boolean variableMetadata;
    private record VariableEntry(VariableKey key,VariableData data) { }
    private static void put(TypeVariable<?> variable,VariableData data){
        Variables registry=variables.get(owner(variable.getGenericDeclaration()));
        synchronized(registry){
            registry.clean();registry.values.put(new VariableKey(variable,registry.queue),data);
            registry.snapshot=registry.values.entrySet().stream().map(e->new VariableEntry(e.getKey(),e.getValue())).toList();
            variableMetadata=true;
        }
    }
    private static VariableData data(TypeVariable<?> variable){
        if(!variableMetadata)return null;
        for(VariableEntry entry:variables.get(owner(variable.getGenericDeclaration())).snapshot)
            if(entry.key.get()==variable)return entry.data;
        return null;
    }
    public static boolean hasVariableMetadata(TypeVariable<?> variable){VariableData data=data(variable);return data!=null&&(data.source!=null||data.frozenAnnotations!=null);}
    private static TypeVariable<?> source(VariableData data) {
        TypeVariable<?> source=data.source.get();
        if(source==null)throw new IllegalStateException("Live record variable lost its source metadata");
        return source;
    }
    public static Annotation[] variableAnnotations(TypeVariable<?> variable){VariableData data=data(variable);return data.frozenAnnotations!=null?data.frozenAnnotations.clone():source(data).getDeclaredAnnotations();}
    public static <T extends Annotation>T variableAnnotation(TypeVariable<?> variable,Class<T> type){Objects.requireNonNull(type);for(var value:variableAnnotations(variable))if(type.isInstance(value))return type.cast(value);return null;}
    public static AnnotatedType[] variableAnnotatedBounds(TypeVariable<?> variable){
        VariableData data=data(variable);if(data.frozenBounds!=null)return data.frozenBounds.clone();
        Context context=data.context.get();if(context==null)throw new IllegalStateException("Live record variable lost its own scope");
        return ReflectionTypes.wrap(source(data).getAnnotatedBounds(),source(data).getGenericDeclaration(),variable.getGenericDeclaration(),context::resolve);
    }
    public static Type[] physicalVariableBounds(TypeVariable<?> variable) {
        if(data(variable)!=null)return null;
        GenericDeclaration declaration=variable.getGenericDeclaration();if(!(declaration instanceof Executable member)||current(member.getDeclaringClass())==null)return null;
        Object repository=memberGenericInfo(member); // also registers the candidate-owned physical method variable scope
        try {
            var parameters=(TypeVariable<?>[])(member instanceof Method?methodParameters:constructorParameters).invoke(repository);
            for(var candidate:parameters)if(candidate.getName().equals(variable.getName())&&candidate!=variable)return candidate.getBounds();
            return null;
        }catch(Throwable failure){throw failure(failure);}
    }
}
