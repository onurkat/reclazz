/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.util.Reflect;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentMap;

/** Exact owned configuration records; detached binding precedes atomic singleton replacement. */
public final class SpringRecordCaches {
    private SpringRecordCaches() { }
    private record Owner(Object context,Object factory,String name,Class<?> type,Object previous,
                         Method clear,Method initialize,Method merged,Method disposable,
                         ConcurrentMap<String,Object> singletons,Map<?,?> binderCache) { }
    private record Pending(WeakReference<Object> context,WeakReference<Object> previous,WeakReference<Object> next) { }
    private static final List<Pending> pending=new ArrayList<>();
    private static void remember(Object context,Object previous,Object next) {
        synchronized(pending){
            pending.removeIf(p->p.context.get()==null||p.previous.get()==null||p.next.get()==null);
            for(int i=0;i<pending.size();i++) {
                Pending p=pending.get(i);
                if(p.context.get()==context&&p.next.get()==previous)pending.set(i,new Pending(p.context,p.previous,new WeakReference<>(next)));
            }
            pending.removeIf(p->p.context.get()==context&&p.previous.get()==previous);
            pending.add(new Pending(new WeakReference<>(context),new WeakReference<>(previous),new WeakReference<>(next)));
        }
    }
    public static final class Prepared {
        private final List<Object> contexts;private final List<Owner> owners;
        private Prepared(List<Object> contexts,List<Owner> owners){this.contexts=contexts;this.owners=owners;}
        public boolean owns(Class<?> type){return owners.stream().anyMatch(owner->owner.type==type);}
        public Map<String,String> apply() {
            Map<String,String> failures=new LinkedHashMap<>();Set<Class<?>> replaced=Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Map<?,?>> cleared=Collections.newSetFromMap(new IdentityHashMap<>());
            for(Owner owner:owners) {
                String surface="configuration-properties "+owner.type.getName()+"/"+owner.name;
                try {
                    if(owner.singletons.get(owner.name)!=owner.previous)throw new IllegalStateException("configuration owner changed after preflight");
                    Object bound=PropertyChangeCheck.bindRecordCandidate(owner.context,owner.name,owner.previous);
                    if(bound==null||bound==owner.previous||!owner.type.isInstance(bound))throw new IllegalStateException("configuration binder did not produce a new record");
                    // The detached bind (including validation) has succeeded; no dependent is destroyed or recreated.
                    Object next=owner.initialize.invoke(owner.factory,bound,owner.name);
                    if(next==null||!owner.type.isInstance(next))throw new IllegalStateException("configuration initialization changed owner type");
                    owner.clear.invoke(owner.factory,owner.name);
                    if(owner.binderCache!=null&&cleared.add(owner.binderCache))owner.binderCache.clear();
                    owner.singletons.compute(owner.name,(name,current)->{
                        if(current!=owner.previous)throw new IllegalStateException("configuration owner changed during bind");return next;
                    });
                    remember(owner.context,owner.previous,next);replaced.add(owner.type);
                    Object definition=owner.merged.invoke(owner.factory,owner.name);
                    owner.disposable.invoke(owner.factory,owner.name,next,definition);
                }catch(Exception|LinkageError failure){failures.put(surface,com.onurkat.reclazz.ui.Failures.describe(failure));}
            }
            IdentityHashMap<Object,Object> replacements=new IdentityHashMap<>();
            synchronized(pending){
                for(Pending p:pending){Object context=p.context.get(),old=p.previous.get(),next=p.next.get();
                    if(old!=null&&next!=null&&replaced.contains(old.getClass())&&contexts.stream().anyMatch(c->c==context))replacements.put(old,next);
                }
            }
            if(!replacements.isEmpty()) {
                Thread thread=Thread.currentThread();List<String> warnings=new ArrayList<>();
                com.onurkat.reclazz.ui.StatusReporter.StatusListener listener=(level,message)->{
                    if(Thread.currentThread()==thread&&(level.equals("WARN")||level.equals("ERROR")))warnings.add(message);
                };
                com.onurkat.reclazz.ui.StatusReporter.addListener(listener);
                try{SpringBeanReloader.healStaleReferences(contexts,replacements);}
                finally{com.onurkat.reclazz.ui.StatusReporter.removeListener(listener);}
                if(!warnings.isEmpty())failures.put("configuration-properties stale references",String.join("; ",warnings));
                else synchronized(pending){pending.removeIf(p->replacements.containsKey(p.previous.get()));}
            }
            return Map.copyOf(failures);
        }
    }
    @SuppressWarnings("unchecked")
    public static Prepared prepare(List<Object> contexts,Collection<Class<?>> records)throws ReflectiveOperationException {
        List<Owner> owners=new ArrayList<>();Set<Object> seenFactories=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Object context:contexts) {
            Object factory=context.getClass().getMethod("getBeanFactory").invoke(context);
            if(factory==null)throw new IllegalStateException("configuration context has no bean factory");
            if(!seenFactories.add(factory))continue;
            for(Class<?> record:records) {
                boolean config=Arrays.stream(record.getAnnotations()).anyMatch(a->a.annotationType().getName().equals("org.springframework.boot.context.properties.ConfigurationProperties"));
                if(!record.isRecord()||!config)continue;
                String[] names=(String[])context.getClass().getMethod("getBeanNamesForType",Class.class,boolean.class,boolean.class).invoke(context,record,true,false);
                for(String name:names) {
                    if(!(boolean)factory.getClass().getMethod("containsBeanDefinition",String.class).invoke(factory,name))continue;
                    Object previous=factory.getClass().getMethod("getSingleton",String.class).invoke(factory,name);
                    if(previous==null)continue;
                    if(!record.isInstance(previous))throw new IllegalStateException("configuration record owner type changed");
                    ClassLoader loader=context.getClass().getClassLoader();
                    Class<?> binderOwner=Class.forName("org.springframework.boot.context.properties.ConfigurationPropertiesBinder",false,loader);
                    Field binderNameField=Reflect.findField(binderOwner,"BEAN_NAME");
                    if(binderNameField==null)throw new NoSuchFieldException("ConfigurationPropertiesBinder.BEAN_NAME");
                    String binderName=(String)binderNameField.get(null);
                    Object bindingOwner=factory.getClass().getMethod("getSingleton",String.class).invoke(factory,binderName);
                    if(!binderOwner.isInstance(bindingOwner))bindingOwner=required(factory.getClass(),"getCachedObjectForFactoryBean",String.class).invoke(factory,binderName);
                    if(!binderOwner.isInstance(bindingOwner))throw new IllegalStateException("configuration binding infrastructure is not already initialized");
                    Field existingBinder=Reflect.findField(bindingOwner.getClass(),"binder");
                    if(existingBinder==null)throw new NoSuchFieldException("ConfigurationPropertiesBinder.binder");
                    Object binder=existingBinder.get(bindingOwner);
                    if(binder==null)throw new IllegalStateException("configuration Binder is not already initialized");
                    Map<?,?> cache=null;Field cacheField=Reflect.findField(binder.getClass(),"cache");
                    if(cacheField!=null){Object value=cacheField.get(binder);if(!(value instanceof Map<?,?> map))throw new IllegalStateException("Binder cache is not a Map");cache=map;}
                    else for(Class<?> type=binder.getClass();type!=null;type=type.getSuperclass())for(Field field:type.getDeclaredFields())if(field.getName().equals("cache"))throw new IllegalAccessException("Binder cache is present but inaccessible");
                    Field singletonField=Reflect.findField(factory.getClass(),"singletonObjects");
                    Object singletonMap=singletonField==null?null:singletonField.get(factory);
                    if(!(singletonMap instanceof ConcurrentMap<?,?>))throw new IllegalStateException("configuration singleton registry is not a concurrent map");
                    Class<?> definition=Class.forName("org.springframework.beans.factory.support.RootBeanDefinition",false,loader);
                    owners.add(new Owner(context,factory,name,record,previous,required(factory.getClass(),"clearMergedBeanDefinition",String.class),
                            required(factory.getClass(),"initializeBean",Object.class,String.class),required(factory.getClass(),"getMergedBeanDefinition",String.class),
                            required(factory.getClass(),"registerDisposableBeanIfNecessary",String.class,Object.class,definition),
                            (ConcurrentMap<String,Object>)singletonMap,cache));
                }
            }
        }
        return new Prepared(List.copyOf(contexts),List.copyOf(owners));
    }
    private static Method required(Class<?> type,String name,Class<?>...args)throws NoSuchMethodException {
        Method found=Reflect.findMethod(type,name,args);if(found==null)throw new NoSuchMethodException(type.getName()+"."+name);return found;
    }
}
