/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.transform.KeepBaseline;

import com.onurkat.reclazz.reload.InstanceInitialiserSlicer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.lang.reflect.Method;

/** Conservative instance-retention decision. Every unknown input takes the existing recreation path. */
public final class SingletonKeepPolicy {
    private SingletonKeepPolicy() { }
    public record Decision(boolean keep,String reason) { }
    private static Decision no(String reason) { return new Decision(false,reason); }
    public static Decision compare(KeepBaseline.Input input,Set<String> creationMethods,boolean cglib) {
        if(input==null||input.old()==null||input.newRaw()==null)return no("no baseline");
        try {
            var old=input.old(); var next=KeepBaseline.fingerprint(input.newRaw());
            if(!old.annotations().equals(next.annotations()))return no("class annotations changed");
            if(!old.injection().equals(next.injection()))return no("injection points changed");
            if(!old.lifecycle().equals(next.lifecycle()))return no("lifecycle callbacks changed");
            if(!next.fields().containsAll(old.fields()))return no("instance field removed or retyped");
            if(!next.methods().keySet().containsAll(old.methods().keySet()))return no("method removed or signature changed");
            Set<String> existingMembers=new HashSet<>(old.fields()); existingMembers.addAll(old.methods().keySet());
            for(String descriptor:old.constructors().keySet())existingMembers.add("<init>:"+descriptor);
            for(String member:existingMembers)
                if(!old.memberAnnotations().getOrDefault(member, "").equals(next.memberAnnotations().getOrDefault(member, "")))
                    return no("member annotations changed: "+member);
            Set<String> added=new HashSet<>(next.fields()); added.removeAll(old.fields());
            var plan=InstanceInitialiserSlicer.planFor(input.newRaw(),added);
            if(!plan.refused.isEmpty())return no("initialiser cannot be lifted: "+plan.refused);
            var cls=new ClassNode();new org.objectweb.asm.ClassReader(input.newRaw()).accept(cls,0);
            Map<String,Long> constructors=new HashMap<>();
            for(MethodNode m:cls.methods)if(m.name.equals("<init>")) {
                var runs=plan.runs.getOrDefault(m.desc,Map.of()).values();
                AbstractInsnNode[] nodes=m.instructions.toArray();
                for(int i=0;i<nodes.length;i++) {
                    final int index=i;
                    if(runs.stream().anyMatch(run->index>=run.start()&&index<run.end()))m.instructions.remove(nodes[i]);
                }
                constructors.put(m.desc,KeepBaseline.digest(m));
            }
            if(!old.constructors().equals(constructors))return no("constructor changed");
            for(String member:creationMethods) {
                Long before=old.methods().get(member), after=next.methods().get(member);
                if(before==null||after==null||!before.equals(after))return no("creation method "+member+" changed or unavailable");
            }
            if(cglib&&!old.publicMethods().containsAll(next.publicMethods()))return no("CGLIB public method added");
            return new Decision(true,"state preserved");
        }catch(Exception|LinkageError e) { return no("keep metadata unavailable: "+e.getClass().getSimpleName()); }
    }
    static Decision forBean(Object factory,String name,Class<?> type,KeepBaseline.Input input) {
        if(input==null||input.old()==null)return no("no baseline for "+(type==null?name:type.getName()));
        try {
            if(type==null)return no("unknown bean type");
            if(!Boolean.TRUE.equals(call(factory,"isSingleton",name)))return no("non-singleton");
            Object bean=call(factory,"getSingleton",name);
            if(bean==null)return no("no initialized singleton");
            Object definition=call(factory,"getMergedBeanDefinition",name);
            if(call(definition,"getFactoryMethodName")!=null)return no("factory method product");
            if(((Number)call(definition,"getResolvedAutowireMode")).intValue()!=0)return no("definition autowire mode");
            if(!Boolean.TRUE.equals(call(call(definition,"getMethodOverrides"),"isEmpty")))return no("method overrides");
            ClassLoader loader=factory.getClass().getClassLoader();
            if(Class.forName("org.springframework.beans.factory.FactoryBean",false,loader).isAssignableFrom(type))return no("FactoryBean product");
            Class<?> utils=Class.forName("org.springframework.aop.support.AopUtils",false,loader);
            boolean cglib=(Boolean)utils.getMethod("isCglibProxy",Object.class).invoke(null,bean);
            boolean jdk=(Boolean)utils.getMethod("isJdkDynamicProxy",Object.class).invoke(null,bean);
            Object target=bean;
            if(cglib||jdk) {
                Object source=call(bean,"getTargetSource");
                if(!Boolean.TRUE.equals(call(source,"isStatic")))return no("dynamic proxy target");
                target=call(source,"getTarget");
            }
            if(target==null||!type.isInstance(target))return no("unknown proxy or class ownership");
            ClassNode cls=KeepBaseline.read(input.newRaw());
            if(cls.visibleAnnotations!=null&&cls.visibleAnnotations.stream().anyMatch(a->a.desc.equals("Lorg/springframework/context/annotation/Configuration;")))return no("configuration class");
            // An inherited creation member has no digest in this class's raw baseline: refuse below.
            Set<String> creation=new HashSet<>();
            for(Class<?> owner=target.getClass();owner!=null&&owner!=Object.class;owner=owner.getSuperclass()) {
                if((cglib||jdk)&&owner==target.getClass()&&owner.getName().contains("$$"))continue;
                for(Method m:owner.getDeclaredMethods()) {
                    if(m.isSynthetic())continue;
                    boolean callback=m.getName().equals("afterPropertiesSet")||m.getName().equals("destroy");
                    for(var a:m.getDeclaredAnnotations()) {
                        String desc=org.objectweb.asm.Type.getDescriptor(a.annotationType());
                        callback|=KeepBaseline.injection(desc)||KeepBaseline.lifecycle(desc);
                    }
                    if(callback)creation.add(m.getName()+":"+org.objectweb.asm.Type.getMethodDescriptor(m));
                }
            }
            // Also read raw annotations: added methods may not be visible through native reflection yet.
            for(MethodNode m:cls.methods) {
                var annotations=new ArrayList<AnnotationNode>();
                if(m.visibleAnnotations!=null)annotations.addAll(m.visibleAnnotations);
                if(m.invisibleAnnotations!=null)annotations.addAll(m.invisibleAnnotations);
                if(annotations.stream().anyMatch(a->KeepBaseline.injection(a.desc)||KeepBaseline.lifecycle(a.desc)))creation.add(m.name+":"+m.desc);
            }
            for(String getter:List.of("getInitMethodName","getDestroyMethodName")) {
                Object configured=call(definition,getter);
                if(configured instanceof String method&&!method.isBlank()) {
                    if(method.equals("(inferred)")) {
                        for(String candidate:List.of("close","shutdown")) addNamed(creation,target.getClass(),candidate,false);
                    }else addNamed(creation,target.getClass(),method,true);
                }
            }
            Object[] properties=(Object[])call(call(definition,"getPropertyValues"),"getPropertyValues");
            for(Object property:properties) {
                String prop=(String)call(property,"getName");
                if(prop.isEmpty()||prop.contains(".")||prop.contains("["))return no("nested property binding");
                addNamed(creation,target.getClass(),"set"+Character.toUpperCase(prop.charAt(0))+prop.substring(1),true);
            }
            addAware(creation,type,new HashSet<>());
            return compare(input,creation,cglib);
        }catch(Exception|LinkageError e) { return no("bean creation metadata unavailable: "+e.getClass().getSimpleName()); }
    }
    private static void addAware(Set<String> creation,Class<?> type,Set<Class<?>> seen) {
        if(type==null||!seen.add(type))return;
        for(Class<?> iface:type.getInterfaces()) {
            if(iface.getName().startsWith("org.springframework.")&&iface.getSimpleName().endsWith("Aware"))
                for(Method m:iface.getMethods())creation.add(m.getName()+":"+org.objectweb.asm.Type.getMethodDescriptor(m));
            addAware(creation,iface,seen);
        }
        addAware(creation,type.getSuperclass(),seen);
    }
    private static void addNamed(Set<String> creation,Class<?> owner,String name,boolean required) {
        boolean found=false;
        for(Class<?> c=owner;c!=null&&c!=Object.class;c=c.getSuperclass())for(Method m:c.getDeclaredMethods())
            if(m.getName().equals(name)&&!m.isSynthetic()) { creation.add(name+":"+org.objectweb.asm.Type.getMethodDescriptor(m));found=true; }
        if(required&&!found)creation.add(name+":<unavailable>");
    }
    private static Object call(Object owner,String name,Object... args)throws Exception {
        for(Method m:owner.getClass().getMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length) {
            boolean matches=true;
            for(int i=0;i<args.length;i++)if(args[i]!=null&&!m.getParameterTypes()[i].isInstance(args[i]))matches=false;
            if(matches)return m.invoke(owner,args);
        }
        throw new NoSuchMethodException(name);
    }
}
