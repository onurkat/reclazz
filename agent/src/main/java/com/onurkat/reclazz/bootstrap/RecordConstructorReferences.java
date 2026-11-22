/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.*;
import java.util.*;

/** Watched constructor-reference templates preserve native fallback and select future caller cohorts. */
public final class RecordConstructorReferences {
    private RecordConstructorReferences() { }
    // Installed once by the engine; evaluated only while linking a constructor site.
    private static volatile java.util.function.Predicate<Class<?>> candidates=owner->true;
    private static final MethodHandle MAKE;
    static {
        try {MAKE=MethodHandles.lookup().findStatic(RecordConstructorReferences.class,"make",MethodType.methodType(Object.class,Metadata.class,MethodHandle.class,MethodHandle.class,Object[].class));}
        catch(ReflectiveOperationException failure){throw new ExceptionInInitializerError(failure);}
    }
    public static final class Metadata {
        final Class<?> capturing;final String functional,method,implementationOwner,implementationName,implementationDescriptor;
        final int implementationKind;final MethodType sam,instantiated;final List<MethodType> methods;
        private Metadata(Class<?> capturing,String functional,String method,MethodType sam,MethodType instantiated,int kind,String owner,String name,String descriptor,Object[] extras) {
            this.capturing=capturing;this.functional=functional;this.method=method;this.sam=sam;this.instantiated=instantiated;implementationKind=kind;implementationOwner=owner;implementationName=name;implementationDescriptor=descriptor;
            var bridges=new LinkedHashSet<MethodType>();bridges.add(sam);
            if(extras.length>0) {
                int flags=(Integer)extras[0],index=1;
                if((flags&LambdaMetafactory.FLAG_MARKERS)!=0)index+=1+(Integer)extras[index];
                if((flags&LambdaMetafactory.FLAG_BRIDGES)!=0){int count=(Integer)extras[index++];for(int n=0;n<count;n++)bridges.add((MethodType)extras[index++]);}
            }
            methods=List.copyOf(bridges);
        }
        /** The original capturing class's $deserializeLambda$ remains the validator. */
        public SerializedLambda serialized(Object[] captured) {
            return new SerializedLambda(capturing,functional,method,sam.toMethodDescriptorString(),implementationKind,implementationOwner,implementationName,implementationDescriptor,instantiated.toMethodDescriptorString(),captured.clone());
        }
    }
    public static CallSite bootstrap(MethodHandles.Lookup caller,String name,MethodType invokedType,Object... arguments)throws Throwable {
        MethodType sam=(MethodType)arguments[0],instantiated=(MethodType)arguments[3];Class<?> owner=(Class<?>)arguments[1],capturing=(Class<?>)arguments[4];
        String descriptor=(String)arguments[2];boolean alternative=((Integer)arguments[6]&1)!=0,nativeOnly=((Integer)arguments[6]&2)!=0;
        MethodHandle supplied=arguments[11] instanceof MethodHandle handle?handle:null;
        nativeOnly|=!candidates.test(owner);
        Object[] extras=Arrays.copyOfRange(arguments,12,arguments.length);
        MethodHandles.Lookup origin=origin(caller,capturing);
        boolean hidden=supplied!=null&&caller.revealDirect(supplied).getDeclaringClass().isHidden();
        MethodHandle implementation;
        if(!MemberForge.preparedRecord(owner)&&RecordCaller.current(owner)==null&&!hidden) {
            // This site is already restricted to watched constructor symbols by the transformer.
            // Resolve only the referenced native descriptor: enumerating every constructor would
            // load unrelated optional parameter classes and change ordinary linking semantics.
            MethodHandle nativeTarget=supplied!=null?supplied:MemberForge.nativeConstructorForReference(origin,owner,MethodType.fromMethodDescriptorString(descriptor,owner.getClassLoader()));
            if(nativeOnly||supplied!=null||owner.isRecord()) {
                if(!alternative)return LambdaMetafactory.metafactory(origin,name,invokedType,sam,nativeTarget,instantiated);
                Object[] nativeArguments=new Object[3+extras.length];nativeArguments[0]=sam;nativeArguments[1]=nativeTarget;nativeArguments[2]=instantiated;System.arraycopy(extras,0,nativeArguments,3,extras.length);
                return LambdaMetafactory.altMetafactory(origin,name,invokedType,nativeArguments);
            }
            implementation=MemberForge.futureCallerConstructorReference(owner,descriptor,nativeTarget);
        }else implementation=supplied!=null?supplied:MemberForge.constructorReferenceTarget(origin,owner,MethodType.fromMethodDescriptorString(descriptor,owner.getClassLoader()));
        var metadata=new Metadata(capturing,invokedType.returnType().getName().replace('.','/'),name,sam,instantiated,(Integer)arguments[7],(String)arguments[8],(String)arguments[9],(String)arguments[10],extras);
        byte[] bytes=Base64.getDecoder().decode((String)arguments[5]);
        var generated=origin.defineHiddenClass(bytes,false,MethodHandles.Lookup.ClassOption.NESTMATE);
        MethodHandle constructor=generated.findConstructor(generated.lookupClass(),MethodType.methodType(void.class,Metadata.class,MethodHandle[].class,Object[].class))
                .asType(MethodType.methodType(Object.class,Metadata.class,MethodHandle[].class,Object[].class));
        if(invokedType.parameterCount()==0)return new ConstantCallSite(MethodHandles.constant(invokedType.returnType(),make(metadata,implementation,constructor,new Object[0])));
        MethodHandle factory=MethodHandles.insertArguments(MAKE,0,metadata,implementation,constructor).asCollector(Object[].class,invokedType.parameterCount()).asType(invokedType);
        return new ConstantCallSite(factory);
    }
    private static MethodHandles.Lookup origin(MethodHandles.Lookup supplied,Class<?> capturing)throws IllegalAccessException {
        Class<?> actual=supplied.lookupClass();
        if(actual==capturing)return supplied;
        if(!actual.isHidden()||actual.getClassLoader()!=capturing.getClassLoader()||actual.getNestHost()!=capturing.getNestHost())throw new IllegalAccessException("Constructor-reference capturing class belongs to another nest");
        return MethodHandles.privateLookupIn(capturing,supplied);
    }
    private static Object make(Metadata metadata,MethodHandle implementation,MethodHandle constructor,Object[] captured)throws Throwable {
        MethodHandle bound=MethodHandles.insertArguments(implementation.asFixedArity(),0,captured).asType(metadata.instantiated);
        MethodHandle[] methods=new MethodHandle[metadata.methods.size()];for(int index=0;index<methods.length;index++)methods[index]=bound.asType(metadata.methods.get(index));
        return (Object)constructor.invokeExact(metadata,methods,captured.clone());
    }
}
