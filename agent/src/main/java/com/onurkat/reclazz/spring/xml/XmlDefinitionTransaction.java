/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.util.Reflect;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.locks.Lock;

/** Resource-owned future-definition publication without destroying existing consumers. */
final class XmlDefinitionTransaction {
    private XmlDefinitionTransaction(){}
    private static final Set<String> VERSIONS=Set.of("5.3.39","6.1.14","6.2.12","7.0.10");
    private static final Set<String> DEFINITIONS=Set.of("org.springframework.beans.factory.support.GenericBeanDefinition","org.springframework.beans.factory.support.RootBeanDefinition","org.springframework.beans.factory.support.ChildBeanDefinition");
    private static final Set<String> RESETTERS=Set.of("org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor","org.springframework.context.annotation.CommonAnnotationBeanPostProcessor","org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor");
    record Edit(String name,Object previous,Object candidate){}
    record Owner(Object context,Object factory,Object parsed,BeanDefinitionDiff diff,boolean observed){}
    private record Key(Object factory,String name){}
    private record Change(Owner owner,Edit edit){}
    private record Snapshot(Key key,Object raw,Object merged,boolean stale,Object singleton,Object product,List<String> dependencies,boolean createdBefore){}

    static boolean classifyPrototype(Object factory,Object parsed,String name,Object previous,Object candidate,Path path,BeanDefinitionDiff diff) {
        if(previous==null||!"prototype".equals(SpringReflection.getScope(previous))||!"prototype".equals(SpringReflection.getScope(candidate)))return false;
        if(XmlSafetyClassifier.unchangedUnsupported(factory,parsed,name,previous,candidate))return true;
        return classifyFuture(factory,parsed,name,previous,candidate,path,diff,"prototype");
    }

    private static boolean classifyFuture(Object factory,Object parsed,String name,Object previous,Object candidate,Path path,BeanDefinitionDiff diff,String kind) {
        try {
            for(Object definition:List.of(previous,candidate)) {
                require(DEFINITIONS.contains(definition.getClass().getName()),"custom prototype definition");
                require(call(definition,"getInstanceSupplier")==null,"prototype instance supplier");
                if(nonempty(SpringReflection.getFactoryBeanName(definition))||nonempty(SpringReflection.getFactoryMethodName(definition)))return false;
                if(!Boolean.TRUE.equals(call(call(definition,"getMethodOverrides"),"isEmpty")))return false;
                require((Integer)call(definition,"getRole")==0&&!Boolean.TRUE.equals(call(definition,"isSynthetic")),"infrastructure prototype definition");
            }
            for(String flag:List.of("isPrimary","isAutowireCandidate","isLazyInit"))if(!Objects.equals(call(previous,flag),call(candidate,flag)))return false;
            ClassLoader loader=(ClassLoader)call(factory,"getBeanClassLoader");
            Object before=call(factory,"getMergedBeanDefinition",name),after=call(parsed,"getMergedBeanDefinition",name);
            Class<?> contract=Class.forName("org.springframework.beans.factory.FactoryBean",false,loader);
            for(Object merged:List.of(before,after)) {
                Class<?> type=Class.forName(Objects.requireNonNull(SpringReflection.getBeanClassName(merged),"prototype class unavailable"),false,loader);
                if(contract.isAssignableFrom(type))return false;
                for(String infrastructure:List.of("org.springframework.beans.factory.config.BeanPostProcessor","org.springframework.beans.factory.config.BeanFactoryPostProcessor","org.springframework.context.ApplicationContext","org.springframework.context.ApplicationListener"))
                    require(!Class.forName(infrastructure,false,loader).isAssignableFrom(type),"infrastructure prototype type");
            }
            require(XmlBeanRecreator.owns(previous,path)&&XmlBeanRecreator.owns(candidate,path),"prototype is not owned by this exact XML resource");
            require(new HashSet<>(Arrays.asList((String[])call(factory,"getAliases",name))).equals(new HashSet<>(Arrays.asList((String[])call(parsed,"getAliases",name)))),"prototype aliases changed");
            new XmlRawValueComparator(candidate.getClass().getClassLoader()).verifiedStandardDefinition(candidate);
            diff.definitionEdits.add(new Edit(name,previous,call(candidate,"cloneBeanDefinition")));
        }catch(Exception|LinkageError failure){diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(name,kind+" definition not updated; restart required: "+SpringReflection.rootCause(failure)));}
        return true;
    }

    static boolean classifyColdPolicy(Object factory,Object parsed,String name,Object previous,Object candidate,Path path,BeanDefinitionDiff diff) {
        if(previous==null||!singleton(previous)||!singleton(candidate))return false;
        try {
            boolean changed=!Arrays.equals((String[])call(previous,"getDependsOn"),(String[])call(candidate,"getDependsOn"))||!Objects.equals(call(previous,"getAutowireMode"),call(candidate,"getAutowireMode"));
            if(!changed)return false;
            if(SpringReflection.getExistingSingleton(factory,name)!=null||created(factory,name))return false;
            // Only dependency policy and declared property values are admitted for a cold singleton.
            // Constructor/class/scope/parent/lifecycle/selection metadata continue through their guards.
            Object normalized=call(candidate,"cloneBeanDefinition");
            normalized.getClass().getMethod("setDependsOn",String[].class).invoke(normalized,new Object[]{call(previous,"getDependsOn")});
            normalized.getClass().getMethod("setAutowireMode",int.class).invoke(normalized,call(previous,"getAutowireMode"));
            Class<?> values=Class.forName("org.springframework.beans.MutablePropertyValues",false,previous.getClass().getClassLoader());
            normalized.getClass().getMethod("setPropertyValues",values).invoke(normalized,call(previous,"getPropertyValues"));
            var comparator=new XmlRawValueComparator(candidate.getClass().getClassLoader());
            if(!comparator.verifiedStandardDefinition(previous).equals(comparator.verifiedStandardDefinition(normalized)))return false;
            return classifyFuture(factory,parsed,name,previous,candidate,path,diff,"cold singleton");
        }catch(Exception|LinkageError failure){diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(name,"cold dependency definition not updated; restart required: "+SpringReflection.rootCause(failure)));return true;}
    }

    static Map<Object,Integer> apply(List<Owner> owners,List<Object> contexts) {
        var changes=new ArrayList<Change>();
        for(Owner owner:owners)for(Edit edit:owner.diff().definitionEdits)changes.add(new Change(owner,edit));
        if(changes.isEmpty())return Map.of();
        var saved=new ArrayList<Snapshot>();var published=new ArrayList<Change>();
        try {
            for(Change change:changes) {
                require(change.owner().observed(),"native loaded XML reader/owner evidence unavailable");
                active(change.owner().context());standard(change.owner().factory());
                require(SpringReflection.getBeanDefinition(change.owner().factory(),change.edit().name())==change.edit().previous(),"definition changed before publication: "+change.edit().name());
                for(Owner other:owners)for(var denied:other.diff().unsafe)
                    require(!denied.beanName.equals(change.edit().name()),"another XML owner refused "+change.edit().name()+": "+denied.reason);
            }
            Set<Key> affected=affected(changes,contexts);
            for(Key key:affected) {
                standard(key.factory());
                require(SpringReflection.getExistingSingleton(key.factory(),key.name())==null,"live singleton descendant/definition requires restart: "+key.name());
                validateResetters(key.factory());
                Object merged=map(key.factory(),"mergedBeanDefinitions").get(key.name());boolean stale=false;
                boolean creationRecorded=created(key.factory(),key.name());
                if(creationRecorded)require(!singleton(merged!=null?merged:call(key.factory(),"getMergedBeanDefinition",key.name())),"previously created singleton definition/descendant requires restart: "+key.name());
                if(merged!=null){Field field=Reflect.findField(merged.getClass(),"stale");require(field!=null&&field.getType()==boolean.class,"merged definition layout unavailable");stale=field.getBoolean(merged);}
                saved.add(new Snapshot(key,SpringReflection.getBeanDefinition(key.factory(),key.name()),merged,stale,null,map(key.factory(),"factoryBeanObjectCache").get(key.name()),List.of((String[])call(key.factory(),"getDependenciesForBean",key.name())),creationRecorded));
                require(saved.get(saved.size()-1).product()==null,"cached FactoryBean product requires a separate transaction: "+key.name());
            }
            Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());var factories=new ArrayList<Object>();
            for(Change change:changes)if(seen.add(change.owner().factory()))factories.add(change.owner().factory());
            for(Snapshot snapshot:saved)if(seen.add(snapshot.key().factory()))factories.add(snapshot.key().factory());
            for(Object factory:factories)locked(factory,()->{
                for(Change change:changes)if(change.owner().factory()==factory) {
                    active(change.owner().context());
                    require(SpringReflection.getBeanDefinition(factory,change.edit().name())==change.edit().previous(),"definition changed before publication: "+change.edit().name());
                }
                for(Snapshot snapshot:saved)if(snapshot.key().factory()==factory)unchangedCreation(snapshot);
                // Raw replacements and every affected merged definition in this factory share one native lock hold.
                // markBeanAsCreated runs outside that lock, so verify its history again after invalidation.
                for(Change change:changes)if(change.owner().factory()==factory) {
                    published.add(change);
                    map(factory,"beanDefinitionMap").put(change.edit().name(),change.edit().candidate());
                }
                for(Snapshot snapshot:saved)if(snapshot.key().factory()==factory)invalidate(snapshot.key());
                for(Snapshot snapshot:saved)if(snapshot.key().factory()==factory)unchangedCreation(snapshot);
            });
            // A later owner can expose an earlier owner's concurrent creation; never report a partial cohort applied.
            for(Snapshot snapshot:saved)locked(snapshot.key().factory(),()->unchangedCreation(snapshot));
            Map<Object,Integer> counts=new IdentityHashMap<>();for(Change change:changes)counts.merge(change.owner().factory(),1,Integer::sum);return counts;
        }catch(Exception|LinkageError failure) {
            Set<String> raced=rollback(published,saved,failure);
            String concurrent=raced.isEmpty()?"":"; concurrent native creation ("+String.join(",",raced)+") retained; existing instances were not destroyed";
            for(Change change:changes)change.owner().diff().unsafe.add(new BeanDefinitionDiff.UnsafeChange(change.edit().name(),"definition publication failed; original definition metadata kept/restored; restart required: "+SpringReflection.rootCause(failure)+concurrent+(failure.getSuppressed().length==0?"":"; definition metadata restoration incomplete")));
            return Map.of();
        }
    }

    private static Set<Key> affected(List<Change> changes,List<Object> contexts)throws Exception {
        Set<Key> result=new LinkedHashSet<>();for(Change change:changes)result.add(new Key(change.owner().factory(),change.edit().name()));
        var all=new ArrayList<Key>();Set<Object> factories=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Object context:contexts){if(!Boolean.TRUE.equals(call(context,"isActive")))continue;Object factory=SpringReflection.getBeanFactory(context);if(factory==null||!factories.add(factory))continue;for(String name:(String[])call(factory,"getBeanDefinitionNames"))all.add(new Key(factory,name));}
        for(Change change:changes)if(factories.add(change.owner().factory()))for(String name:(String[])call(change.owner().factory(),"getBeanDefinitionNames"))all.add(new Key(change.owner().factory(),name));
        boolean more;do{more=false;for(Key key:all) {
            if(result.contains(key))continue;Object raw=SpringReflection.getBeanDefinition(key.factory(),key.name());String parent=SpringReflection.getParentName(raw);if(parent==null)continue;
            Key source=parent(key,parent);if(source!=null&&result.contains(source)){result.add(key);more=true;}
        }}while(more);
        for(Change change:changes)for(Key key:all) {
            if(SpringReflection.getBeanDefinition(key.factory(),key.name())!=change.edit().previous())continue;
            boolean participating=changes.stream().anyMatch(c->c.owner().factory()==key.factory()&&c.edit().name().equals(key.name()));
            require(participating,"shared raw definition has an unparticipating owner: "+key.name());
        }
        return result;
    }
    private static Key parent(Key child,String parent)throws Exception {
        Object factory=child.factory();String canonical=(String)call(factory,"canonicalName",parent);
        if(canonical.equals(child.name()))factory=call(factory,"getParentBeanFactory");
        Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        while(factory!=null&&seen.add(factory)) {
            canonical=(String)call(factory,"canonicalName",canonical);
            if(Boolean.TRUE.equals(call(factory,"containsBeanDefinition",canonical)))return new Key(factory,canonical);
            factory=call(factory,"getParentBeanFactory");
        }
        return null;
    }
    private static void invalidate(Key key)throws Exception {
        Method clear=Reflect.findMethod(key.factory().getClass(),"clearMergedBeanDefinition",String.class);require(clear!=null,"merged definition invalidation unavailable");clear.invoke(key.factory(),key.name());
        ClassLoader loader=key.factory().getClass().getClassLoader();Class<?> contract=Class.forName("org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor",false,loader);
        for(Object processor:(List<?>)call(key.factory(),"getBeanPostProcessors"))if(contract.isInstance(processor))processor.getClass().getMethod("resetBeanDefinition",String.class).invoke(processor,key.name());
        clearTypes(key.factory());
    }
    private static void validateResetters(Object factory)throws Exception {
        Class<?> contract=Class.forName("org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor",false,factory.getClass().getClassLoader());
        for(Object processor:(List<?>)call(factory,"getBeanPostProcessors"))if(contract.isInstance(processor)) {
            Method reset=processor.getClass().getMethod("resetBeanDefinition",String.class);
            String type=processor.getClass().getName(),version=processor.getClass().getPackage().getImplementationVersion();
            boolean nativeReset=RESETTERS.contains(type)&&VERSIONS.contains(version);
            // These exact ORM reset implementations only remove the named injection metadata cache entry.
            if(type.equals("org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor"))nativeReset=Set.of("6.1.14","6.2.12","7.0.10").contains(version);
            require(reset.getDeclaringClass()==contract||nativeReset,"unverified merged-definition reset callback: "+type);
        }
    }
    private static void unchangedCreation(Snapshot snapshot)throws Exception {
        require(SpringReflection.getExistingSingleton(snapshot.key().factory(),snapshot.key().name())==snapshot.singleton()
                &&(snapshot.createdBefore()||!created(snapshot.key().factory(),snapshot.key().name())),
                "native bean creation raced with definition publication: "+snapshot.key().name());
    }
    private static Set<String> rollback(List<Change> published,List<Snapshot> saved,Throwable failure) {
        Set<String> raced=new LinkedHashSet<>();if(published.isEmpty())return raced;
        for(int i=published.size()-1;i>=0;i--){Change change=published.get(i);try{locked(change.owner().factory(),()->map(change.owner().factory(),"beanDefinitionMap").put(change.edit().name(),change.edit().previous()));}catch(Exception|LinkageError restore){failure.addSuppressed(restore);}}
        for(Snapshot snapshot:saved)try{locked(snapshot.key().factory(),()->{
            // Application-owned creation must not prevent restoring definition metadata or cause destruction.
            Map<String,Object> merged=map(snapshot.key().factory(),"mergedBeanDefinitions");
            if(snapshot.merged()==null)merged.remove(snapshot.key().name());else{Reflect.findField(snapshot.merged().getClass(),"stale").setBoolean(snapshot.merged(),snapshot.stale());merged.put(snapshot.key().name(),snapshot.merged());}
            clearTypes(snapshot.key().factory());
            if(SpringReflection.getExistingSingleton(snapshot.key().factory(),snapshot.key().name())!=snapshot.singleton()
                    ||(!snapshot.createdBefore()&&created(snapshot.key().factory(),snapshot.key().name())))raced.add(snapshot.key().name());
        });}catch(Exception|LinkageError restore){failure.addSuppressed(restore);}
        return raced;
    }
    private static void clearTypes(Object factory)throws Exception {Method clear=Reflect.findMethod(factory.getClass(),"clearByTypeCache");require(clear!=null,"type cache invalidation unavailable");clear.invoke(factory);}
    private static void standard(Object factory)throws Exception {
        require(factory.getClass().getName().equals("org.springframework.beans.factory.support.DefaultListableBeanFactory")&&VERSIONS.contains(factory.getClass().getPackage().getImplementationVersion()),"unsupported definition factory/version");
        require(!Boolean.TRUE.equals(required(factory,"singletonsCurrentlyInDestruction")),"factory is closing");
        require(Boolean.TRUE.equals(call(factory,"isAllowBeanDefinitionOverriding")),"definition overriding is disabled");
    }
    private static void active(Object context)throws Exception {require(Boolean.TRUE.equals(call(context,"isActive")),"context is not active");}
    @FunctionalInterface private interface Action {void run()throws Exception;}
    private static void locked(Object factory,Action action)throws Exception {
        Object lock=Reflect.readField(factory,"singletonLock");if(lock instanceof Lock nativeLock){nativeLock.lock();try{action.run();}finally{nativeLock.unlock();}}
        else{Object mutex=call(factory,"getSingletonMutex");require(mutex!=null,"native singleton lock unavailable");synchronized(mutex){action.run();}}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object factory,String name)throws Exception {Object map=required(factory,name);require(map instanceof Map<?,?>,"native map unavailable: "+name);return (Map<String,Object>)map;}
    private static Object required(Object object,String name)throws Exception {Field field=Reflect.findField(object.getClass(),name);require(field!=null,"native field unavailable: "+name);return field.get(object);}
    private static Object call(Object object,String name,Object...args)throws Exception {
        for(Method method:object.getClass().getMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length) {
            boolean matches=true;for(int i=0;i<args.length;i++)if(args[i]!=null&&!method.getParameterTypes()[i].isInstance(args[i]))matches=false;
            if(matches)return method.invoke(object,args);
        }
        throw new NoSuchMethodException(object.getClass().getName()+"."+name);
    }
    private static boolean singleton(Object definition){String scope=SpringReflection.getScope(definition);return scope==null||scope.isEmpty()||scope.equals("singleton");}
    private static boolean created(Object factory,String name)throws Exception {Object names=required(factory,"alreadyCreated");require(names instanceof Set<?>,"creation history unavailable");return ((Set<?>)names).contains(name);}
    private static boolean nonempty(String value){return value!=null&&!value.isEmpty();}
    private static void require(boolean condition,String reason){if(!condition)throw new IllegalStateException(reason);}
}
