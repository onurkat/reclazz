/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.StaticReferenceInspection;
import com.onurkat.reclazz.util.Reflect;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Inspects bounded managed holders; it does not walk arbitrary application heap/stack aliases. */
public final class SpringProxyReferences {
    private SpringProxyReferences(){}
    record ProxyChain(List<Object> proxies,Object target){}
    static Object target(Object bean)throws Exception {return chain(bean).target();}
    static ProxyChain chain(Object bean)throws Exception {
        if(bean==null)return new ProxyChain(List.of(),null);
        Class<?> advised;
        try{advised=Class.forName("org.springframework.aop.framework.Advised",false,bean.getClass().getClassLoader());}
        catch(ClassNotFoundException absent){return new ProxyChain(List.of(),bean);}
        var proxies=new ArrayList<Object>();var seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());Object current=bean;
        while(advised.isInstance(current)) {
            if(!seen.add(current)||proxies.size()>=64)throw new IllegalStateException("cyclic or excessive native singleton proxy chain");
            proxies.add(current);Object source=advised.getMethod("getTargetSource").invoke(current);
            if(!source.getClass().getName().equals("org.springframework.aop.target.SingletonTargetSource"))return new ProxyChain(List.copyOf(proxies),null);
            current=source.getClass().getMethod("getTarget").invoke(source);
        }
        return new ProxyChain(List.copyOf(proxies),current);
    }
    static boolean references(Object bean,Set<Object> retiring,String label,Set<Object> managed)throws Exception {
        return references(bean,retiring,label,managed,null);
    }
    static boolean references(Object bean,Set<Object> retiring,String label,Set<Object> managed,Set<Class<?>> application)throws Exception {
        Object target=target(bean);
        if(target==null)return false;
        if((target instanceof Map<?,?>||target instanceof Collection<?>||target instanceof Object[]||target instanceof Optional<?>||target instanceof AtomicReference<?>||target instanceof java.lang.ref.Reference<?>)
                &&opaqueHolds(target,retiring,Collections.newSetFromMap(new IdentityHashMap<>()),managed,true,application))
            throw new IllegalStateException(label+" is a container retaining the old singleton");
        boolean found=false;
        for(Class<?> type=target.getClass();type!=null&&type!=Object.class;type=type.getSuperclass()) {
            boolean nativeOwner=framework(type);
            for(Field field:type.getDeclaredFields()) {
                if(Modifier.isStatic(field.getModifiers())||field.getType().isPrimitive()||com.onurkat.reclazz.bootstrap.InjectedNames.isInjected(field.getName()))continue;
                if(!field.trySetAccessible()) {
                    boolean relevant=retiring.stream().anyMatch(old->field.getType().isInstance(old));
                    if(!nativeOwner||relevant)throw new IllegalStateException(label+" has an unreadable holder field "+field.getName());
                    continue;
                }
                Object value=field.get(target);
                if(retiring.contains(value)||lazyHolds(value,retiring)) {
                    if(nativeOwner)throw new IllegalStateException(label+" framework holder field "+type.getName()+"."+field.getName()+" retains the old singleton");
                    found=true;continue;
                }
                if(nativeOwner)continue; // One direct level only; no native-internal heap traversal.
                if(opaqueHolds(value,retiring,Collections.newSetFromMap(new IdentityHashMap<>()),managed,false,application))
                    throw new IllegalStateException(label+" has an opaque/container reference in "+field.getName());
            }
        }
        return found;
    }
    private static boolean lazyHolds(Object value,Set<Object> retiring)throws Exception {
        if(value==null)return false;
        Class<?> advised;
        try{advised=Class.forName("org.springframework.aop.framework.Advised",false,value.getClass().getClassLoader());}
        catch(ClassNotFoundException absent){return false;}
        if(!advised.isInstance(value))return false;
        Object source=advised.getMethod("getTargetSource").invoke(value);String name=source.getClass().getName();
        if(name.equals("org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver$LazyDependencyTargetSource")) {
            Field cache=Reflect.findField(source.getClass(),"cachedTarget");
            if(cache==null)throw new IllegalStateException("unrecognized lazy target cache");
            return retiring.contains(cache.get(source));
        }
        // Spring 6.1's native anonymous source resolves through its factory on each call.
        if(name.equals("org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver$1"))return false;
        if(name.equals("org.springframework.aop.target.SingletonTargetSource")) {
            Object nested=target(value);
            if(retiring.contains(nested))throw new IllegalStateException("a retained proxy wraps the old raw singleton");
        }
        return false;
    }
    private static boolean opaqueHolds(Object value,Set<Object> retiring,Set<Object> seen,Set<Object> managed,boolean containerRoot,Set<Class<?>> application)throws Exception {
        if(value==null)return false;
        if(retiring.contains(value))return true;
        if(!containerRoot&&managed.contains(value))return false;
        boolean container=value instanceof Object[]||value.getClass().getClassLoader()==null&&(value instanceof Optional<?>||value instanceof AtomicReference<?>||value instanceof java.lang.ref.Reference<?>
                ||value instanceof Map<?,?>||value instanceof Collection<?>);
        boolean singletonSource=value.getClass().getName().equals("org.springframework.aop.target.SingletonTargetSource");
        if(!container) {
            if(value instanceof Class<?>||value instanceof Member||value instanceof ClassLoader||value instanceof java.lang.annotation.Annotation||value.getClass().isEnum())return false;
            if(lazyHolds(value,retiring))return true;
            // Match references(): native framework internals are not an arbitrary application heap census.
            // Known containers and native singleton/lazy targets keep their explicit checks above/below.
            if(!singletonSource&&framework(value.getClass()))return false;
            if(!singletonSource&&application!=null&&!application.contains(value.getClass())&&!application.contains(value.getClass().getNestHost()))return false;
        }
        if(!seen.add(value))return false;
        if(seen.size()>4096)throw new IllegalStateException("opaque reference graph exceeds verified inspection bound");
        if(singletonSource)return opaqueHolds(value.getClass().getMethod("getTarget").invoke(value),retiring,seen,managed,false,application);
        if(container&&value instanceof Optional<?> o)return o.isPresent()&&opaqueHolds(o.get(),retiring,seen,managed,false,application);
        if(container&&value instanceof AtomicReference<?> a)return opaqueHolds(a.get(),retiring,seen,managed,false,application);
        if(container&&value instanceof java.lang.ref.Reference<?> ref)return opaqueHolds(ref.get(),retiring,seen,managed,false,application);
        if(container&&value instanceof Map<?,?> map){for(var e:map.entrySet())if(opaqueHolds(e.getKey(),retiring,seen,managed,false,application)||opaqueHolds(e.getValue(),retiring,seen,managed,false,application))return true;return false;}
        if(container&&value instanceof Collection<?> items){for(Object x:items)if(opaqueHolds(x,retiring,seen,managed,false,application))return true;return false;}
        if(value instanceof Object[] items){for(Object x:items)if(opaqueHolds(x,retiring,seen,managed,false,application))return true;return false;}
        if(value instanceof Class<?>||value instanceof Member||value instanceof ClassLoader||value instanceof java.lang.annotation.Annotation||value.getClass().isEnum()||value.getClass().getClassLoader()==null)return false;
        // Native container ownership is inspected via its singleton registry, never by recursively entering it.
        ClassLoader loader=value.getClass().getClassLoader();
        for(String api:List.of("org.springframework.beans.factory.BeanFactory","org.springframework.context.ApplicationContext"))try {
            if(Class.forName(api,false,loader).isInstance(value))return false;
        }catch(ClassNotFoundException absent){}
        for(Class<?> t=value.getClass();t!=null&&t!=Object.class&&t.getClassLoader()!=null;t=t.getSuperclass()) {
            if(framework(t))continue;
            for(Field f:t.getDeclaredFields()) {
            if(Modifier.isStatic(f.getModifiers())||f.getType().isPrimitive()||com.onurkat.reclazz.bootstrap.InjectedNames.isInjected(f.getName()))continue;
            if(!f.trySetAccessible())throw new IllegalStateException("unreadable opaque holder "+t.getName()+"."+f.getName());
            if(opaqueHolds(f.get(value),retiring,seen,managed,false,application))return true;
            }
        }
        return false;
    }
    static void staticHolders(Class<?>[] census,Set<Object> retiring,Set<Object> managed)throws Exception {
        if(census==null)throw new IllegalStateException("loaded-class reference census unavailable");
        Set<Class<?>> application=new HashSet<>(Arrays.asList(census));
        for(Class<?> type:census) {
            if(type==null||type.getClassLoader()==null||type.isArray()||type.isPrimitive()||framework(type))continue;
            for(Field field:type.getDeclaredFields()) {
                if(!Modifier.isStatic(field.getModifiers())||field.getType().isPrimitive()||com.onurkat.reclazz.bootstrap.InjectedNames.isInjected(field.getName()))continue;
                Object value=StaticReferenceInspection.read(field); // Field.get would initialize a cold holder.
                if(opaqueHolds(value,retiring,Collections.newSetFromMap(new IdentityHashMap<>()),managed,false,application))
                    throw new IllegalStateException("static holder "+type.getName()+"."+field.getName()+" retains the old singleton");
            }
        }
    }
    private static boolean framework(Class<?> type) {
        String n=type.getName();return type.getClassLoader()==null||n.startsWith("java.")||n.startsWith("javax.")||n.startsWith("jakarta.")
                ||n.startsWith("org.springframework.")||n.startsWith("org.apache.")||n.startsWith("org.aspectj.")||n.startsWith("net.bytebuddy.")
                ||n.startsWith("com.sun.")||n.startsWith("jdk.")||n.startsWith("org.junit.")||n.startsWith("org.gradle.")||n.startsWith("org.jacoco.");
    }

    record HandlerCache(Object mapping,Object handler,Field cache,Object before,java.util.concurrent.locks.Lock lock) {
        void clear()throws Exception{if(lock!=null)lock.lock();try{if(cache!=null)cache.set(handler,null);}finally{if(lock!=null)lock.unlock();}}
        void restore()throws Exception{if(lock!=null)lock.lock();try{if(cache!=null)cache.set(handler,before);}finally{if(lock!=null)lock.unlock();}}
    }
    static List<HandlerCache> mvc(List<SpringProxyRecreation.Bean> beans,Set<Object> retiring)throws Exception {
        var result=new ArrayList<HandlerCache>();
        for(var bean:beans) {
            Object mapping=bean.instance();Class<?> contract;
            try{contract=Class.forName("org.springframework.web.servlet.handler.AbstractHandlerMethodMapping",false,mapping.getClass().getClassLoader());}
            catch(ClassNotFoundException absent){continue;}
            if(!contract.isInstance(mapping))continue;
            var lock=SpringMvcReloader.registryWriteLock(mapping);if(lock==null)throw new IllegalStateException("MVC mapping "+bean.name()+" has no verified registry lock");
            lock.lock();try {
                for(Object handler:((Map<?,?>)contract.getMethod("getHandlerMethods").invoke(mapping)).values()) {
                    Object owner=handler.getClass().getMethod("getBean").invoke(handler);
                    if(!(owner instanceof String name)) {
                        if(retiring.contains(owner))throw new IllegalStateException("MVC literal handler retains retiring bean in "+bean.name());
                        continue;
                    }
                    Object source=SpringProxyRecreation.source(bean.factory(),name);
                    Object instance=source==null?null:SpringProxyRecreation.call(source,"getSingleton",SpringProxyRecreation.call(source,"canonicalName",name));
                    if(!retiring.contains(instance))continue;
                    Field cache=Reflect.findField(handler.getClass(),"resolvedBeanHandlerMethod");
                    if(cache!=null&&(Modifier.isFinal(cache.getModifiers())||Modifier.isStatic(cache.getModifiers())))throw new IllegalStateException("MVC handler cache is not writable: "+name);
                    result.add(new HandlerCache(mapping,handler,cache,cache==null?null:cache.get(handler),lock));
                }
            }finally{lock.unlock();}
        }
        return result;
    }
}
