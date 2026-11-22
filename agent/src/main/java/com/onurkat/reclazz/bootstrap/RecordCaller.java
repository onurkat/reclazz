/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;

/** Member and constructor generations live on the caller, never on the record Slot. */
public final class RecordCaller {
    private static final StackWalker WALKER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private RecordCaller() { }
    public static final class View {
        final Class<?> owner;final ReflectionBridge.Members reflection;final RecordHeader.View header;
        final List<Method> methods;final List<Field> fields;final List<Constructor<?>> constructors;
        final Map<String,MethodHandle> initializers;
        private View(Class<?> owner,List<Method> methods,List<Field> fields,List<Constructor<?>> constructors,Map<String,MethodHandle> initializers,RecordHeader.View header) {
            this.header=header;
            this.owner=owner;this.methods=List.copyOf(methods);this.fields=List.copyOf(fields);this.constructors=List.copyOf(constructors);this.initializers=Map.copyOf(initializers);
            reflection=new ReflectionBridge.Members(this.methods,this.fields,this.constructors);
        }
        boolean method(Method method){return methods.stream().anyMatch(method::equals);}
        boolean field(Field field){return fields.stream().anyMatch(field::equals);}
        boolean name(String name,boolean field){return field?fields.stream().anyMatch(f->f.getName().equals(name)):methods.stream().anyMatch(m->m.getName().equals(name));}
    }
    public static View prepare(Class<?> owner,List<Method> methods,List<Field> fields,List<Constructor<?>> constructors,Map<String,MethodHandle> initializers) {
        LookupCapture.requireTrusted(WALKER.getCallerClass(),"preparing record-cohort caller members");
        return prepareMembers(owner,methods,fields,constructors,initializers,null);
    }
    public static View prepare(Class<?> owner,List<Method> methods,List<Field> fields,List<Constructor<?>> constructors,Map<String,MethodHandle> initializers,RecordHeader.View header) {
        LookupCapture.requireTrusted(WALKER.getCallerClass(),"preparing record-cohort caller members");
        if(header==null||header.owner!=owner)throw new IllegalArgumentException("Caller header belongs to another owner");
        return prepareMembers(owner,methods,fields,constructors,initializers,header);
    }
    private static View prepareMembers(Class<?> owner,List<Method> methods,List<Field> fields,List<Constructor<?>> constructors,Map<String,MethodHandle> initializers,RecordHeader.View header) {
        if(owner.isRecord())throw new IllegalArgumentException("Record roots belong in their record State");
        for(Member member:methods)requireOwner(owner,member);
        for(Member member:fields)requireOwner(owner,member);
        for(Member member:constructors)requireOwner(owner,member);
        Set<String> descriptors=new HashSet<>();
        for(Constructor<?> ctor:constructors)descriptors.add(MethodType.methodType(void.class,ctor.getParameterTypes()).toMethodDescriptorString());
        if(!descriptors.equals(initializers.keySet()))throw new IllegalArgumentException("Caller constructor roots and initializers differ");
        for(var entry:initializers.entrySet()) {
            RecordSchema.validateTargetOwner(owner,entry.getValue());
            MethodType descriptor=MethodType.fromMethodDescriptorString(entry.getKey(),owner.getClassLoader());
            if(descriptor.returnType()!=void.class||!descriptor.changeReturnType(owner).insertParameterTypes(0,owner).equals(entry.getValue().type()))
                throw new IllegalArgumentException("Caller constructor initializer type changed");
        }
        RecordStorage.prepare(owner);
        View view=new View(owner,methods,fields,constructors,initializers,header);
        for(Method method:view.methods){method.getDeclaredAnnotations();method.getGenericReturnType();method.getGenericParameterTypes();method.getAnnotatedReturnType();method.getAnnotatedParameterTypes();}
        for(Field field:view.fields){field.getDeclaredAnnotations();field.getGenericType();field.getAnnotatedType();}
        for(Constructor<?> ctor:view.constructors){ctor.getDeclaredAnnotations();ctor.getGenericParameterTypes();ctor.getAnnotatedParameterTypes();ctor.getParameters();}
        return view;
    }
    private static void requireOwner(Class<?> owner,Member member){if(member.getDeclaringClass()!=owner)throw new IllegalArgumentException("Caller member belongs to another owner");}
    private record Selection(RecordSchema.Slot slot,RecordSchema.CallerSite site,MethodHandle dispatcher) {
        View view(){try{return (View)dispatcher.invokeExact();}catch(Throwable failure){throw new IllegalStateException("Cannot select record caller members",failure);}}
    }
    private static final class Holder {volatile Selection selection;volatile Object ordinaryGeneration=new Object();}
    private static final ClassValue<Holder> callers=new ClassValue<>(){@Override protected Holder computeValue(Class<?> type){return new Holder();}};
    private static final java.util.concurrent.atomic.AtomicInteger histories=new java.util.concurrent.atomic.AtomicInteger();
    static View current(Class<?> owner){if(histories.get()==0)return null;Selection selection=callers.get(owner).selection;return selection==null?null:selection.view();}
    static Object ordinaryGeneration(Class<?> owner){return callers.get(owner).ordinaryGeneration;}
    record Construction(View own,Map<Class<?>,View> views) { }
    /** Capture only already-parsed views under the owning Slot, before any constructor code runs. */
    static Construction capture(Class<?> owner) {
        if(histories.get()==0)return null;
        while(true) {
            if(callers.get(owner).selection==null)return null;
            var selections=new IdentityHashMap<Holder,Selection>();
            var epochs=new IdentityHashMap<RecordSchema.Slot,RecordSchema.State>();
            var views=new IdentityHashMap<Class<?>,View>();
            for(Class<?> type=owner;type!=null;type=type.getSuperclass()) {
                Holder holder=callers.get(type);Selection selection=holder.selection;
                selections.put(holder,selection);if(selection==null)continue;
                epochs.putIfAbsent(selection.slot,selection.slot.currentInternal());
                View view=selection.view();if(view!=null)views.put(type,view);
            }
            boolean stable=true;
            for(var entry:selections.entrySet())if(entry.getKey().selection!=entry.getValue()){stable=false;break;}
            if(stable)for(var entry:epochs.entrySet())if(entry.getKey().currentInternal()!=entry.getValue()){stable=false;break;}
            if(stable){View own=views.get(owner);return own==null?null:new Construction(own,Map.copyOf(views));}
        }
    }
    /** A held virtual caller root can outlive its own cohort while a parent still has one. */
    static Construction captureParents(Class<?> owner) {
        for(Class<?> parent=owner.getSuperclass();parent!=null;parent=parent.getSuperclass()) {
            Construction inherited=capture(parent);
            if(inherited!=null)return new Construction(null,inherited.views());
        }
        return null;
    }
    static void stage(RecordSchema.Preparation preparation,RecordSchema.Slot slot,Class<?> owner,View next) {
        if(next.owner!=owner)throw new IllegalArgumentException("Caller view belongs to another owner");
        Holder holder=callers.get(owner);
        synchronized(holder) {
            Selection previous=holder.selection;
            if(previous!=null&&previous.slot!=slot&&previous.site.hasPending())throw new IllegalStateException("Caller members have another pending record cohort");
            View before=previous==null?null:previous.view();
            var baseline=MethodHandles.constant(View.class,before);
            var site=RecordSchema.callerSiteInternal(owner,slot,"caller-members",baseline);
            if(previous==null||previous.site!=site)site.rebase(baseline);
            preparation.stageTarget(site,MethodHandles.constant(View.class,next));
            ReflectionBridge.armRecordCaller(owner);
            if(previous==null)histories.incrementAndGet();
            holder.selection=new Selection(slot,site,site.dispatcherInternal());
        }
    }
    static void ordinaryReplacement(Class<?> owner) {
        Holder holder=callers.get(owner);
        synchronized(holder) {
            if(holder.selection!=null){holder.selection.site.cancelPending();holder.ordinaryGeneration=new Object();holder.selection=null;histories.decrementAndGet();}
        }
    }
}
