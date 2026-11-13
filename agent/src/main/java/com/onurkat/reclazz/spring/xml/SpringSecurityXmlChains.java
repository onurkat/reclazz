/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.SpringSecurityXmlResources;
import com.onurkat.reclazz.platform.SpringXmlResources;
import com.onurkat.reclazz.spring.SpringSecurityReloader;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;

/** Owns served XML policy in a child; the application's boot definitions stay intact. */
final class SpringSecurityXmlChains {
    private SpringSecurityXmlChains() { }
    private record Served(SpringSecurityXmlResources.Snapshot parse,Object chain,Object child) { }
    private static final Map<Object,Map<Path,Served>> served=new WeakHashMap<>();
    record Outcome(boolean handled,int applied,String failure) { }

    static synchronized Outcome apply(Object context,Object live,Object parsed,Path file) {
        var next=SpringSecurityXmlResources.snapshot(parsed,file);
        boolean security=false;
        for(String name:SpringReflection.getBeanDefinitionNames(parsed)) {
            if("org.springframework.security.web.DefaultSecurityFilterChain".equals(
                    SpringReflection.getBeanClassName(SpringReflection.getBeanDefinition(parsed,name))))security=true;
        }
        if(!security&&SpringSecurityXmlResources.snapshot(live,file)==null)return new Outcome(false,0,null);
        Object child=null;
        try {
            Path path=SpringXmlResources.canonical(file);
            var original=SpringSecurityXmlResources.snapshot(live,file);
            require(original!=null&&next!=null,"security XML ownership was not captured or has multiple http groups");
            Map<Path,Served> records=served.get(live);
            Served previous=records==null?null:records.get(path);
            var baseline=previous==null?original:previous.parse;
            require(baseline.scope().equals(next.scope()),"only intercept-url access expressions may change; scope/authentication changed");
            if(baseline.content().equals(next.content()))return new Outcome(true,0,null);
            Object oldChain=previous==null?call(live,"getBean",original.chain()):previous.chain;
            ClassLoader loader=(ClassLoader)call(live,"getBeanClassLoader");
            Class<?> app=Class.forName("org.springframework.context.ApplicationContext",false,loader);
            child=Class.forName("org.springframework.context.support.GenericApplicationContext",false,loader)
                    .getConstructor(app).newInstance(context);
            call(child,"setClassLoader",loader);
            Object environment=call(context,"getEnvironment");call(child,"setEnvironment",environment);
            Object factory=call(child,"getBeanFactory");
            Set<String> names=group(parsed,next);
            for(String name:names)call(factory,"registerBeanDefinition",name,
                    call(SpringReflection.getBeanDefinition(parsed,name),"cloneBeanDefinition"));
            // No local aliases for parent authentication services: resolution must
            // reach the exact existing parent instance, not a second user store.
            call(child,"refresh");
            Object replacement=call(child,"getBean",next.chain());
            require(replacement.getClass()==oldChain.getClass(),"chain type changed");
            if(records==null) {
                records=new HashMap<>();served.put(live,records);
                attachCleanup(context,live,loader);
            }
            SpringSecurityReloader.replaceXmlChain(context,oldChain,replacement);
            records.put(path,new Served(next,replacement,child));child=null;
            // Existing requests may still be using the retired chain. Closing
            // standard child beans follows a committed swap, never a failed one.
            if(previous!=null)close(previous.child);
            return new Outcome(true,1,null);
        } catch(Exception|LinkageError failure) {
            close(child);
            return new Outcome(true,0,"security XML chain kept; restart required: "+SpringReflection.rootCause(failure));
        }
    }
    private static Set<String> group(Object parsed,SpringSecurityXmlResources.Snapshot snapshot)throws Exception {
        Set<String> names=new LinkedHashSet<>();
        for(String name:snapshot.beans()) {
            String type=SpringReflection.getBeanClassName(SpringReflection.getBeanDefinition(parsed,name));
            if("org.springframework.security.web.FilterChainProxy".equals(type)
                    ||"org.springframework.beans.factory.config.ListFactoryBean".equals(type)
                    ||"org.springframework.security.config.http.UserDetailsServiceFactoryBean".equals(type))continue;
            require(type==null||type.startsWith("org.springframework.security."),"unsupported security namespace component");
            names.add(name);
        }
        for(int pass=0;pass<32;pass++) {
            Set<String> refs=new LinkedHashSet<>();
            for(String name:names)references(SpringReflection.getBeanDefinition(parsed,name),refs,0);
            boolean changed=false;
            for(String ref:refs) {
                if(!SpringReflection.containsBeanDefinition(parsed,ref))continue;
                Object definition=SpringReflection.getBeanDefinition(parsed,ref);
                String type=SpringReflection.getBeanClassName(definition);
                if(type!=null&&type.startsWith("org.springframework.security.config.http."))changed|=names.add(ref);
            }
            if(!changed)return names;
        }
        throw new IllegalArgumentException("security graph bound");
    }
    private static void references(Object value,Set<String> out,int depth)throws Exception {
        if(value==null)return;if(depth>32)throw new IllegalArgumentException("security value bound");
        String type=value.getClass().getName();
        if(type.equals("org.springframework.beans.factory.config.RuntimeBeanReference"))out.add((String)call(value,"getBeanName"));
        else if(type.equals("org.springframework.beans.factory.config.BeanDefinitionHolder"))references(call(value,"getBeanDefinition"),out,depth+1);
        else if(value instanceof Map<?,?> map)for(var e:map.entrySet()){references(e.getKey(),out,depth+1);references(e.getValue(),out,depth+1);}
        else if(value instanceof Iterable<?> values)for(Object v:values)references(v,out,depth+1);
        else {
            ClassLoader loader=value.getClass().getClassLoader();
            if(loader==null)return;
            Class<?> definition=Class.forName("org.springframework.beans.factory.config.BeanDefinition",false,loader);
            if(definition.isInstance(value)) {
                Object factory=call(value,"getFactoryBeanName");if(factory!=null)out.add((String)factory);
                Object args=call(value,"getConstructorArgumentValues");
                for(Object holder:((Map<?,?>)call(args,"getIndexedArgumentValues")).values())references(call(holder,"getValue"),out,depth+1);
                for(Object holder:(List<?>)call(args,"getGenericArgumentValues"))references(call(holder,"getValue"),out,depth+1);
                for(Object pv:(Object[])call(call(value,"getPropertyValues"),"getPropertyValues"))references(call(pv,"getValue"),out,depth+1);
            }
        }
    }
    private static void attachCleanup(Object context,Object live,ClassLoader loader)throws Exception {
        Class<?> listener=Class.forName("org.springframework.context.ApplicationListener",false,loader);
        java.lang.ref.WeakReference<Object> owner=new java.lang.ref.WeakReference<>(live);
        Object callback=Proxy.newProxyInstance(loader,new Class<?>[]{listener},(proxy,method,args)->{
            if(method.getName().equals("onApplicationEvent")&&args[0].getClass().getName().equals("org.springframework.context.event.ContextClosedEvent")
                    &&call(args[0],"getApplicationContext")==context) {
                synchronized(SpringSecurityXmlChains.class) {
                    Map<Path,Served> states=served.remove(owner.get());
                    if(states!=null)for(Served state:states.values())close(state.child);
                }
            }
            if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
            if(method.getName().equals("equals"))return proxy==args[0];
            if(method.getName().equals("toString"))return "Reclazz XML policy cleanup";
            return null;
        });
        call(context,"addApplicationListener",callback);
    }
    private static void close(Object child) {if(child!=null)try{call(child,"close");}catch(Exception ignored){} }
    private static void require(boolean condition,String reason){if(!condition)throw new IllegalArgumentException(reason);}
    private static Object call(Object target,String name,Object...args)throws ReflectiveOperationException {
        for(Method method:target.getClass().getMethods()) {
            if(!method.getName().equals(name)||method.getParameterCount()!=args.length)continue;
            boolean fits=true;Class<?>[] types=method.getParameterTypes();
            for(int i=0;i<args.length;i++)if(args[i]!=null&&!types[i].isInstance(args[i]))fits=false;
            if(fits)return method.invoke(target,args);
        }
        throw new NoSuchMethodException(target.getClass().getName()+"."+name);
    }
}
