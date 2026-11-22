/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/** Record component copies keep their real annotation carrier, without a global copy registry. */
public final class RecordReflection {
    private static final byte[] MARKER=new byte[0];
    private static MethodHandle constructor;
    private static VarHandle clazz,name,type,accessor,signature,root,annotations;
    private static volatile boolean available;
    public static Throwable initializationFailure;
    private RecordReflection() { }

    /** Called under the same module opening as MethodForge, before installing the JDK hooks. */
    public static synchronized boolean initialize() {
        LookupCapture.requireTrusted(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass(),"initializing record reflection");
        if(available)return true;
        try {
            var lookup=MethodHandles.privateLookupIn(RecordComponent.class,MethodHandles.lookup());
            constructor=lookup.findConstructor(RecordComponent.class,MethodType.methodType(void.class));
            clazz=lookup.findVarHandle(RecordComponent.class,"clazz",Class.class);
            name=lookup.findVarHandle(RecordComponent.class,"name",String.class);
            type=lookup.findVarHandle(RecordComponent.class,"type",Class.class);
            accessor=lookup.findVarHandle(RecordComponent.class,"accessor",java.lang.reflect.Method.class);
            signature=lookup.findVarHandle(RecordComponent.class,"signature",String.class);
            root=lookup.findVarHandle(RecordComponent.class,"root",RecordComponent.class);
            annotations=lookup.findVarHandle(RecordComponent.class,"annotations",byte[].class);
            available=true;
        } catch(Throwable failure){initializationFailure=failure;available=false;}
        return available;
    }
    record Template(Class<?> owner,RecordSchema.Component component,RecordComponent metadata) { }
    static List<Template> prepare(Class<?> owner,List<RecordSchema.Component> components) {
        var result=new ArrayList<Template>();
        RecordComponent[] physical=null;
        for(var component:components) {
            RecordComponent source=component.metadata();
            if(source==null) {
                if(physical==null)physical=ReflectionBridge.physicalRecordComponents(owner);
                for(var candidate:physical)if(candidate.getName().equals(component.name())&&candidate.getType()==component.type()){
                    source=candidate;break;
                }
            }
            if(source==null||!source.getName().equals(component.name())||source.getType()!=component.type())
                throw new IllegalArgumentException("Record component metadata missing or different: "+component.name());
            Class<?> carrier=source.getDeclaringRecord();
            if(carrier!=owner&&!(carrier.isHidden()&&carrier.getClassLoader()==owner.getClassLoader()
                    &&carrier.getNestHost()==owner.getNestHost()))
                throw new IllegalArgumentException("Record component metadata belongs to another owner");
            var template=new Template(owner,component,source);
            // The enabling capability gate initializes the factory before any S publication.
            // Resolve the carrier's fallible parsers now, never at the schema CAS.
            source.getDeclaredAnnotations();source.getGenericType();source.getAnnotatedType();
            if(available)copy(template);
            result.add(template);
        }
        return List.copyOf(result);
    }
    static RecordComponent[] copy(List<Template> templates) {
        if(!available)throw new IllegalStateException("Record component reflection unavailable",initializationFailure);
        var result=new RecordComponent[templates.size()];
        for(int i=0;i<result.length;i++)result[i]=copy(templates.get(i));
        return result;
    }
    private static RecordComponent copy(Template template) {
        try {
            RecordComponent value=(RecordComponent)constructor.invoke();
            clazz.set(value,template.owner);name.set(value,template.component.name());type.set(value,template.component.type());
            accessor.set(value,MethodForge.reflectionCopy(template.component.accessor()));
            signature.set(value,template.metadata.getGenericSignature());root.set(value,template.metadata);
            // The JDK declaration-annotation parser delegates to root. The marker only identifies
            // our copies to the type-annotation hooks; it is never parsed as classfile data.
            annotations.set(value,MARKER);
            return value;
        } catch(Throwable failure){throw new IllegalStateException("Could not copy record component",failure);}
    }
    public static boolean isVirtual(RecordComponent component) {
        return available&&annotations.get(component)==MARKER;
    }
    public static boolean hasPhysicalGenericView(RecordComponent component){return !isVirtual(component)&&RecordHeader.hasView(component.getDeclaringRecord());}
    public static Type physicalGenericType(RecordComponent component){return RecordHeader.physicalComponent(component);}
    public static Type genericType(RecordComponent component) {
        RecordComponent source=(RecordComponent)root.get(component);
        var view=MethodForge.genericView(component.getAccessor());
        return view==null?ReflectionTypes.type(source.getGenericType(),source.getDeclaringRecord(),component.getDeclaringRecord()):component.getAccessor().getGenericReturnType();
    }
    public static AnnotatedType annotatedType(RecordComponent component) {
        RecordComponent source=(RecordComponent)root.get(component);
        var view=MethodForge.genericView(component.getAccessor());
        return ReflectionTypes.wrap(source.getAnnotatedType(),source.getDeclaringRecord(),component.getDeclaringRecord(),view==null?null:view::resolve);
    }
}
