/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PreservedSingletons;
import com.onurkat.reclazz.ui.*;
import com.onurkat.reclazz.util.Reflect;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

/** Native creation of a formerly plain singleton, bounded to an inspected callback-free graph. */
final class SpringProxyRecreation {
    private static final Set<String> VERSIONS=Set.of("6.1.14","6.2.12","7.0.10");
    private static final Set<String> CREATORS=Set.of(
            "org.springframework.aop.framework.autoproxy.InfrastructureAdvisorAutoProxyCreator",
            "org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator",
            "org.springframework.aop.aspectj.autoproxy.AspectJAwareAdvisorAutoProxyCreator",
            "org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator");
    private static final Set<String> ADVISING=Set.of("org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor",
            "org.springframework.validation.beanvalidation.MethodValidationPostProcessor");
    private static final Set<String> DEFINITIONS=Set.of("org.springframework.beans.factory.support.RootBeanDefinition",
            "org.springframework.beans.factory.support.GenericBeanDefinition",
            "org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition",
            "org.springframework.context.annotation.ScannedGenericBeanDefinition",
            "org.springframework.context.annotation.ConfigurationClassBeanDefinitionReader$ConfigurationClassBeanDefinition",
            "org.springframework.context.support.GenericApplicationContext$ClassDerivedBeanDefinition");
    private static final String CONCERN="previously unproxied singleton: native proxy recreation unverified: ";
    private static final Map<Object,Integer> SCOPE_IDS=Collections.synchronizedMap(new WeakHashMap<>());
    private static int nextScope;
    static String subject(Object factory,String name){return "Spring bean "+name+" [context "+SCOPE_IDS.computeIfAbsent(factory,key->++nextScope)+"]";}
    static boolean pending(Object factory,String name){return RestartLedger.reasonsFor(subject(factory,name)).stream().anyMatch(r->r.startsWith(CONCERN));}
    static boolean annotated(Class<?> type){return type!=null&&knownProxyAnnotation(type,new HashSet<>());}
    record Bean(Object context,Object factory,String name,Object instance){}
    record Receipt(java.lang.ref.WeakReference<Object> factory,String name,java.lang.ref.WeakReference<Object> proxy) { }
    record Outcome(boolean handled,boolean applied,Map<Object,Object> replacements,List<Receipt> receipts) {
        static Outcome untouched(){return new Outcome(false,false,Map.of(),List.of());}
    }
    private static final class RequiresProxy extends IllegalStateException { RequiresProxy(String reason){super(reason);} }
    private record Saved(Bean bean,Object raw,Object merged,List<String> dependencies){}
    private SpringProxyRecreation(){}

    static Outcome refresh(List<Object> contexts,Object context,String name,Class<?> changed,
                           Supplier<Class<?>[]> census,PreservedSingletons preserved) {
        if(changed==null)return Outcome.untouched();
        Object factory=SpringBeans.getBeanFactory(context);boolean candidate=false;
        try {
            if(factory==null)return Outcome.untouched();
            Object old=call(factory,"getSingleton",name);
            if(old==null||proxy(old,factory)||!changed.isInstance(old))return Outcome.untouched();
            if(old.getClass()!=changed) {
                candidate=knownProxyAnnotation(changed,new HashSet<>());
                if(matches(factory,name,old.getClass()))throw new RequiresProxy("subclass target ownership is not verified: "+name+" ("+old.getClass().getName()+")");
                return Outcome.untouched();
            }
            candidate=knownProxyAnnotation(changed,new HashSet<>());
            if(!matches(factory,name,changed))return Outcome.untouched();
            candidate=true;
            Class<?>[] loaded=census.get();if(loaded==null)throw new IllegalStateException("loaded-class reference census unavailable");
            Set<Class<?>> application=new HashSet<>(Arrays.asList(loaded));
            var all=inventory(contexts);Bean root=all.stream().filter(b->b.factory()==factory&&b.name().equals(name)&&b.instance()==old).findFirst().orElseThrow();
            var selected=new LinkedHashSet<Bean>();selected.add(root);
            Set<Object> managed=identities(all);
            boolean more;
            do {
                more=false;Set<Object> retiring=identities(selected);
                for(Bean bean:all)if(!selected.contains(bean)) {
                    boolean dependent=false;
                    for(String dependency:(String[])call(bean.factory(),"getDependenciesForBean",bean.name())) {
                        Object owner=source(bean.factory(),dependency);
                        String canonical=owner==null?dependency:(String)call(owner,"canonicalName",dependency);
                        if(selected.stream().anyMatch(b->b.factory()==owner&&b.name().equals(canonical))){dependent=true;break;}
                    }
                    if(dependent||SpringProxyReferences.references(bean.instance(),retiring,bean.name(),managed,application)){selected.add(bean);more=true;}
                }
            }while(more);
            Set<Object> retiring=identities(selected);
            for(Bean selectedBean:selected)for(Bean other:all)
                if(other!=selectedBean&&other.instance()==selectedBean.instance())throw new IllegalStateException("same singleton identity also registered as "+other.name());
            var saved=new ArrayList<Saved>();
            for(Bean bean:selected)saved.add(snapshot(bean,preserved));
            SpringProxyReferences.staticHolders(loaded,retiring,managed);
            var handlers=SpringProxyReferences.mvc(all,retiring);
            List<Saved> order=order(saved,managed);
            revalidate(all);
            var replacements=new IdentityHashMap<Object,Object>();
            var removed=new ArrayList<Saved>();
            try {
                for(int i=order.size()-1;i>=0;i--) {
                    Saved s=order.get(i);active(s.bean());
                    locked(s.bean().factory(),()->{
                        if(call(s.bean().factory(),"getSingleton",s.bean().name())!=s.bean().instance())throw new IllegalStateException("singleton changed before proxy recreation: "+s.bean().name());
                        removed.add(s);call(s.bean().factory(),"destroySingleton",s.bean().name());
                    });
                }
                for(Saved s:order) {
                    Bean bean=s.bean();active(bean);
                    locked(bean.factory(),()->{
                        Method clear=Reflect.findMethod(bean.factory().getClass(),"clearMergedBeanDefinition",String.class);
                        if(clear==null)throw new IllegalStateException("constructor metadata invalidation unavailable");
                        clear.invoke(bean.factory(),bean.name());
                        Object fresh=call(bean.factory(),"getBean",bean.name());
                        if(fresh==null||fresh==bean.instance())throw new IllegalStateException("native creation did not replace "+bean.name());
                        replacements.put(bean.instance(),fresh);
                    });
                }
                Object fresh=call(factory,"getSingleton",name);
                if(!proxy(fresh,factory)||SpringProxyReferences.target(fresh)==null||SpringProxyReferences.target(fresh).getClass()!=changed)
                    throw new IllegalStateException("native creation did not produce a verified proxy for "+name);
                var currentBeans=inventory(contexts);Set<Object> currentManaged=identities(currentBeans);
                for(Bean bean:currentBeans)if(SpringProxyReferences.references(bean.instance(),retiring,bean.name(),currentManaged,application))
                    throw new IllegalStateException("consumer still retains old singleton: "+bean.name());
                for(var handler:handlers)handler.clear();
                String subject=subject(factory,name);
                for(String reason:RestartLedger.reasonsFor(subject))if(reason.startsWith(CONCERN))RestartLedger.resolve(subject,reason);
                ReloadEffects.note("bean "+name+" gained a native proxy; "+Plural.of(order.size(),"singleton")+" re-created");
                var receipts=new ArrayList<Receipt>();
                for(Bean bean:selected)if(bean.instance().getClass()==changed) {
                    Object current=call(bean.factory(),"getSingleton",bean.name());
                    if(proxy(current,bean.factory()))receipts.add(new Receipt(new java.lang.ref.WeakReference<>(bean.factory()),bean.name(),new java.lang.ref.WeakReference<>(current)));
                }
                return new Outcome(true,true,replacements,List.copyOf(receipts));
            }catch(Exception|LinkageError failure) {
                int suppressed=failure.getSuppressed().length;
                rollback(removed,handlers,failure);
                if(failure.getSuppressed().length>suppressed)throw new IllegalStateException("old singleton graph could not be completely restored",failure);
                throw failure;
            }
        }catch(Exception|LinkageError failure) {
            if(failure instanceof RequiresProxy)candidate=true;
            if(!candidate)return Outcome.untouched();
            String reason=CONCERN+Failures.describe(failure);
            RestartLedger.note(subject(factory,name),reason);StatusReporter.warn(name+" needs restart: "+reason);
            return new Outcome(true,false,Map.of(),List.of());
        }
    }

    private static List<Bean> inventory(List<Object> contexts)throws Exception {
        var result=new ArrayList<Bean>();var factories=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        for(Object context:contexts) {
            if(!Boolean.TRUE.equals(call(context,"isActive")))continue;
            Object f=SpringBeans.getBeanFactory(context);if(f==null||!factories.add(f))continue;
            for(String name:(String[])call(f,"getSingletonNames")){Object bean=call(f,"getSingleton",name);if(bean!=null)result.add(new Bean(context,f,name,bean));}
        }
        return result;
    }
    private static Set<Object> identities(Collection<Bean> beans)throws Exception {
        var set=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        for(Bean bean:beans){set.add(bean.instance());Object target=SpringProxyReferences.target(bean.instance());if(target!=null)set.add(target);}
        return set;
    }
    private static Saved snapshot(Bean bean,PreservedSingletons preserved)throws Exception {
        Object factory=bean.factory();active(bean);
        if(!factory.getClass().getName().equals("org.springframework.beans.factory.support.DefaultListableBeanFactory")
                ||!VERSIONS.contains(factory.getClass().getPackage().getImplementationVersion()))
            throw new IllegalStateException("unsupported singleton factory/version for "+bean.name());
        if(preserved.names(bean.context()).contains(bean.name()))throw new IllegalStateException("preserved consumer "+bean.name());
        if(!Boolean.TRUE.equals(call(factory,"containsBeanDefinition",bean.name())))throw new IllegalStateException("manually registered consumer "+bean.name());
        Object raw=call(factory,"getBeanDefinition",bean.name()),merged=call(factory,"getMergedBeanDefinition",bean.name());
        if(!DEFINITIONS.contains(raw.getClass().getName())||!Boolean.TRUE.equals(call(raw,"isSingleton"))||Boolean.TRUE.equals(call(raw,"isAbstract")))
            throw new IllegalStateException("unsupported creation metadata for "+bean.name());
        if(call(merged,"getInstanceSupplier")!=null)throw new IllegalStateException("custom instance supplier for "+bean.name());
        if(((Map<?,?>)required(factory,"disposableBeans")).containsKey(bean.name()))throw new IllegalStateException("destroy callback on consumer "+bean.name());
        if(((Map<?,?>)required(factory,"containedBeanMap")).containsKey(bean.name()))throw new IllegalStateException("contained-bean destruction on consumer "+bean.name());
        if(Boolean.TRUE.equals(call(factory,"isSingletonCurrentlyInCreation",bean.name())))throw new IllegalStateException("consumer is being created: "+bean.name());
        Object target=SpringProxyReferences.target(bean.instance());if(target==null)throw new IllegalStateException("dynamic/opaque proxy consumer "+bean.name());
        ClassLoader loader=factory.getClass().getClassLoader();
        for(String type:List.of("org.springframework.beans.factory.FactoryBean","org.springframework.context.ApplicationListener","org.springframework.context.Lifecycle",
                "org.springframework.beans.factory.config.BeanPostProcessor","org.springframework.beans.factory.config.BeanFactoryPostProcessor","org.springframework.beans.factory.SmartInitializingSingleton",
                "org.springframework.aop.Advisor","org.aopalliance.aop.Advice"))
            if(Class.forName(type,false,loader).isInstance(target))throw new IllegalStateException("lifecycle/listener/factory consumer "+bean.name());
        for(Class<?> owner=target.getClass();owner!=null&&owner!=Object.class;owner=owner.getSuperclass()) {
            for(var annotation:owner.getDeclaredAnnotations())if(annotation.annotationType().getName().equals("org.aspectj.lang.annotation.Aspect"))
                throw new IllegalStateException("registered aspect consumer "+bean.name());
            for(Method method:owner.getDeclaredMethods())for(var annotation:method.getDeclaredAnnotations())
                if(callback(annotation.annotationType(),new HashSet<>()))throw new IllegalStateException("registered callback consumer "+bean.name());
        }
        return new Saved(bean,raw,merged,List.of((String[])call(factory,"getDependenciesForBean",bean.name())));
    }
    private static boolean callback(Class<?> annotation,Set<Class<?>> seen) {
        if(!seen.add(annotation))return false;String type=annotation.getName();
        if(type.equals("org.springframework.context.event.EventListener")||type.equals("org.springframework.transaction.event.TransactionalEventListener")
                ||type.startsWith("org.springframework.scheduling.annotation.Scheduled")||type.endsWith(".JmsListener")||type.endsWith(".KafkaListener")||type.endsWith(".RabbitListener"))return true;
        if(type.startsWith("java.lang.annotation."))return false;
        for(var meta:annotation.getDeclaredAnnotations())if(callback(meta.annotationType(),seen))return true;return false;
    }
    private static List<Saved> order(List<Saved> saved,Set<Object> managed)throws Exception {
        var result=new ArrayList<Saved>();var visiting=new HashSet<Saved>();var done=new HashSet<Saved>();
        for(Saved s:saved)visit(s,saved,visiting,done,result,managed);return result;
    }
    private static void visit(Saved item,List<Saved> all,Set<Saved> visiting,Set<Saved> done,List<Saved> result,Set<Object> managed)throws Exception {
        if(done.contains(item))return;if(!visiting.add(item))throw new IllegalStateException("cyclic singleton consumer "+item.bean().name());
        for(Saved dep:all)if(dep!=item) {
            boolean needs=false;
            for(String name:item.dependencies()) {
                Object owner=source(item.bean().factory(),name);
                if(owner==dep.bean().factory()&&call(owner,"canonicalName",name).equals(dep.bean().name())){needs=true;break;}
            }
            if(needs||SpringProxyReferences.references(item.bean().instance(),identities(List.of(dep.bean())),item.bean().name(),managed))visit(dep,all,visiting,done,result,managed);
        }
        visiting.remove(item);done.add(item);result.add(item);
    }
    private static void revalidate(List<Bean> all)throws Exception {
        for(Bean bean:all){active(bean);if(call(bean.factory(),"getSingleton",bean.name())!=bean.instance())throw new IllegalStateException("singleton inventory changed: "+bean.name());}
    }
    private static void active(Bean bean)throws Exception {
        if(!Boolean.TRUE.equals(call(bean.context(),"isActive"))||Boolean.TRUE.equals(required(bean.factory(),"singletonsCurrentlyInDestruction")))
            throw new IllegalStateException("context is closing: "+bean.name());
    }
    private static void rollback(List<Saved> removed,List<SpringProxyReferences.HandlerCache> handlers,Throwable failure) {
        // Only graphs with no old destroy callbacks reach this point. Keep exact old objects alive.
        for(int i=removed.size()-1;i>=0;i--) {
            Saved s=removed.get(i);try{active(s.bean());locked(s.bean().factory(),()->{
                call(s.bean().factory(),"destroySingleton",s.bean().name());
                call(s.bean().factory(),"registerSingleton",s.bean().name(),s.bean().instance());
            });}catch(Exception|LinkageError rollback){failure.addSuppressed(rollback);}
        }
        for(Saved s:removed)for(String dependency:s.dependencies())try{call(s.bean().factory(),"registerDependentBean",dependency,s.bean().name());}catch(Exception|LinkageError rollback){failure.addSuppressed(rollback);}
        for(var handler:handlers)try{handler.restore();}catch(Exception|LinkageError rollback){failure.addSuppressed(rollback);}
    }
    @FunctionalInterface private interface Action { void run()throws Exception; }
    private static void locked(Object factory,Action action)throws Exception {
        Object lock=Reflect.readField(factory,"singletonLock");
        if(lock instanceof Lock nativeLock){nativeLock.lock();try{action.run();}finally{nativeLock.unlock();}}
        else {Object mutex=call(factory,"getSingletonMutex");if(mutex==null)throw new IllegalStateException("native creation lock unavailable");synchronized(mutex){action.run();}}
    }
    private static boolean matches(Object factory,String name,Class<?> type)throws Exception {
        if(proxy(call(factory,"getSingleton",name),factory))return false;
        ClassLoader loader=factory.getClass().getClassLoader();
        SpringOperationSourceReloader.clearSpringAnnotationCaches(type);
        Class<?> tx=optional("org.springframework.transaction.interceptor.TransactionAttributeSource",loader);
        Class<?> cache=optional("org.springframework.cache.interceptor.CacheOperationSource",loader);
        for(String bean:(String[])call(factory,"getSingletonNames")) {
            Object source=call(factory,"getSingleton",bean);
            if((tx!=null&&tx.isInstance(source))||(cache!=null&&cache.isInstance(source))) {
                String n=source.getClass().getName();
                if(!n.equals("org.springframework.transaction.annotation.AnnotationTransactionAttributeSource")&&!n.equals("org.springframework.cache.annotation.AnnotationCacheOperationSource"))continue;
                Object metadata=Reflect.readField(source,"attributeCache");
                if(!(metadata instanceof Map<?,?>))metadata=Reflect.readField(source,"operationCache");
                if(!(metadata instanceof Map<?,?> map))throw new IllegalStateException("unsupported operation source "+bean);
                clearOwnerMetadata(map,type);
            }
        }
        Class<?> creatorType=Class.forName("org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator",false,loader);
        Class<?> advisingType=Class.forName("org.springframework.aop.framework.AbstractAdvisingBeanPostProcessor",false,loader);
        boolean applicable=false;
        for(Object processor:(List<?>)call(factory,"getBeanPostProcessors")) {
            if(creatorType.isInstance(processor)) {
                Method key=Reflect.findMethod(processor.getClass(),"getCacheKey",Class.class,String.class);
                Map<?,?> decisions=(Map<?,?>)required(processor,"advisedBeans");decisions.remove(key.invoke(processor,type,name));
                if(knownProxyAnnotation(type,new HashSet<>()))validateCandidateSources(processor,type,loader,name);
                Method find=Reflect.findMethod(processor.getClass(),"getAdvicesAndAdvisorsForBean",Class.class,String.class,Class.forName("org.springframework.aop.TargetSource",false,loader));
                Object advisors=find.invoke(processor,type,name,null);
                if(advisors!=null){
                    if(!CREATORS.contains(processor.getClass().getName()))throw new RequiresProxy("custom proxy creator "+processor.getClass().getName());
                    for(var source:OperationMetadataInspection.operationSources((Object[])advisors,loader,true).entrySet()) {
                        Object value=source.getKey();boolean transaction=source.getValue();
                        String standard=transaction?"org.springframework.transaction.annotation.AnnotationTransactionAttributeSource":"org.springframework.cache.annotation.AnnotationCacheOperationSource";
                        String configuration=value==null?"missing source":OperationMetadataInspection.sourceConfiguration(value,transaction,true);
                        if(value==null||!value.getClass().getName().equals(standard)||configuration!=null)throw new RequiresProxy("unsupported operation source for "+name+": "+configuration);
                    }
                    applicable=true;
                }
            }else if(advisingType.isInstance(processor)) {
                ((Map<?,?>)required(processor,"eligibleBeans")).remove(type);
                Method eligible=Reflect.findMethod(processor.getClass(),"isEligible",Class.class);
                if(Boolean.TRUE.equals(eligible.invoke(processor,type))){if(!ADVISING.contains(processor.getClass().getName()))throw new RequiresProxy("custom advising processor "+processor.getClass().getName());applicable=true;}
            }
        }
        return applicable;
    }
    /** Preserve repaired answers belonging to every other owner, including retained proxy Methods. */
    private static void clearOwnerMetadata(Map<?,?> metadata,Class<?> owner)throws Exception {
        var owned=new ArrayList<Object>();
        for(Object key:new ArrayList<>(metadata.keySet())) {
            if(key==null||!key.getClass().getName().equals("org.springframework.core.MethodClassKey"))throw new IllegalStateException("unverified operation metadata key");
            Object method=required(key,"method"),target=Reflect.readField(key,"targetClass");
            if(!(method instanceof Method held)||target!=null&&!(target instanceof Class<?>))throw new IllegalStateException("unreadable operation metadata ownership");
            // A retained Method declared by owner can also serve a different target subclass.
            // Only the actual raw singleton type being recreated owns the non-null target key.
            if(target==owner||target==null&&held.getDeclaringClass()==owner)owned.add(key);
        }
        for(Object key:owned)metadata.remove(key);
    }
    private static void validateCandidateSources(Object creator,Class<?> type,ClassLoader loader,String name)throws Exception {
        Method find=Reflect.findMethod(creator.getClass(),"findCandidateAdvisors");if(find==null)return;
        Class<?> advisor=Class.forName("org.springframework.aop.PointcutAdvisor",false,loader);
        Class<?> pointcut=Class.forName("org.springframework.aop.Pointcut",false,loader);
        Class<?> filter=Class.forName("org.springframework.aop.ClassFilter",false,loader);
        for(Object candidate:(List<?>)find.invoke(creator)) {
            if(!advisor.isInstance(candidate))continue;
            Object p=advisor.getMethod("getPointcut").invoke(candidate),classFilter=pointcut.getMethod("getClassFilter").invoke(p);
            if(!Boolean.TRUE.equals(filter.getMethod("matches",Class.class).invoke(classFilter,type)))continue;
            Object tx=Reflect.readField(p,"transactionAttributeSource"),cache=Reflect.readField(p,"cacheOperationSource");
            Object source=tx!=null?tx:cache;if(source==null)continue;
            String n=source.getClass().getName();
            // This native source is method-name based, not an annotation-decision cache.
            if(n.equals("org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource"))continue;
            String standard=tx!=null?"org.springframework.transaction.annotation.AnnotationTransactionAttributeSource":"org.springframework.cache.annotation.AnnotationCacheOperationSource";
            String configuration=OperationMetadataInspection.sourceConfiguration(source,tx!=null,true);
            if(!n.equals(standard)||configuration!=null)throw new RequiresProxy("unsupported operation source for "+name+": "+n+" "+configuration);
        }
    }
    private static boolean knownProxyAnnotation(Class<?> type,Set<Class<?>> seen) {
        if(!seen.add(type))return false;
        var annotations=new ArrayList<java.lang.annotation.Annotation>(List.of(type.getDeclaredAnnotations()));
        if(!type.isAnnotation())for(Method m:type.getDeclaredMethods())annotations.addAll(List.of(m.getDeclaredAnnotations()));
        for(var a:annotations) {
            String n=a.annotationType().getName();
            if(n.equals("org.springframework.transaction.annotation.Transactional")||n.startsWith("org.springframework.cache.annotation.Cache")
                    ||n.equals("org.springframework.scheduling.annotation.Async")||n.equals("org.springframework.validation.annotation.Validated"))return true;
            if(!n.startsWith("java.lang.annotation.")&&knownProxyAnnotation(a.annotationType(),seen))return true;
        }
        if(!type.isAnnotation()) {
            for(Class<?> face:type.getInterfaces())if(knownProxyAnnotation(face,seen))return true;
            Class<?> parent=type.getSuperclass();if(parent!=null&&parent!=Object.class&&knownProxyAnnotation(parent,seen))return true;
        }
        return false;
    }
    private static Class<?> optional(String name,ClassLoader loader) {try{return Class.forName(name,false,loader);}catch(ClassNotFoundException absent){return null;}}
    private static boolean proxy(Object bean,Object factory)throws Exception {
        return bean!=null&&(Boolean)Class.forName("org.springframework.aop.support.AopUtils",false,factory.getClass().getClassLoader()).getMethod("isAopProxy",Object.class).invoke(null,bean);
    }
    private static Object required(Object owner,String field)throws Exception {
        Field f=Reflect.findField(owner.getClass(),field);if(f==null)throw new IllegalStateException("native field unavailable: "+field);return f.get(owner);
    }
    static Object source(Object factory,String name)throws Exception {
        var seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        while(factory!=null){if(!seen.add(factory))throw new IllegalStateException("cyclic parent factory");name=(String)call(factory,"canonicalName",name);if(Boolean.TRUE.equals(call(factory,"containsLocalBean",name)))return factory;factory=call(factory,"getParentBeanFactory");}return null;
    }
    static Object call(Object target,String name,Object... args)throws Exception {
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())for(Method method:type.getDeclaredMethods()) {
            if(!method.getName().equals(name)||method.getParameterCount()!=args.length)continue;
            boolean fits=true;Class<?>[] types=method.getParameterTypes();
            for(int i=0;i<args.length;i++)if(args[i]!=null&&!types[i].isInstance(args[i])){fits=false;break;}
            if(!fits)continue;method.setAccessible(true);
            try{return method.invoke(target,args);}catch(InvocationTargetException wrapper){if(wrapper.getCause() instanceof Exception e)throw e;if(wrapper.getCause() instanceof Error e)throw e;throw wrapper;}
        }
        throw new NoSuchMethodException(target.getClass().getName()+"."+name);
    }
}
