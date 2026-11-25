/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import java.lang.instrument.*;
import java.lang.reflect.Proxy;
import java.util.List;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real annotation-only JVM redefine and native Spring creation, shared by the 6/7 test tasks. */
class SpringPlainSingletonProxyTest {
    @org.junit.jupiter.api.BeforeEach
    void verifyRequestedSpringPatch() throws Exception {
        com.onurkat.reclazz.e2e.harness.SpringPatchWitness.verify();
    }

    @org.springframework.stereotype.Service
    public static class Work {
        int calls;
        public boolean transaction(){return TransactionSynchronizationManager.isActualTransactionActive();}
        public int cached(){return ++calls;}
    }
    public static class ConstructorHolder { static boolean fail; final Work work; ConstructorHolder(Work work){if(fail)throw new IllegalStateException("synthetic constructor failure");this.work=work;} }
    public static class FieldHolder { @Autowired Work work; }
    public static class SetterHolder { Work work; @Autowired public void setWork(Work value){work=value;} }
    public static class ProviderHolder { final ObjectProvider<Work> work; ProviderHolder(ObjectProvider<Work> work){this.work=work;} }
    public static class LazyHolder { final Work work; LazyHolder(Work work){this.work=work;} }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement(proxyTargetClass=true) @EnableCaching(proxyTargetClass=true)
    public static class Config {
        @Bean Work work(){return new Work();}
        @Bean ConstructorHolder constructorHolder(Work w){return new ConstructorHolder(w);}
        @Bean FieldHolder fieldHolder(){return new FieldHolder();}
        @Bean SetterHolder setterHolder(){return new SetterHolder();}
        @Bean ProviderHolder providerHolder(ObjectProvider<Work> w){return new ProviderHolder(w);}
        @Bean LazyHolder lazyHolder(@Lazy Work w){return new LazyHolder(w);}
        @Bean org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource unrelatedNameSource(){return new org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource();}
        @Bean CacheManager cacheManager(){return new ConcurrentMapCacheManager("numbers","other");}
        @Bean PlatformTransactionManager transactionManager(){return new AbstractPlatformTransactionManager(){
            protected Object doGetTransaction(){return new Object();}
            protected void doBegin(Object t,TransactionDefinition d){}
            protected void doCommit(DefaultTransactionStatus s){}
            protected void doRollback(DefaultTransactionStatus s){}
        };}
    }
    static byte[] original()throws Exception {try(var in=Work.class.getResourceAsStream("/"+Work.class.getName().replace('.','/')+".class")){return in.readAllBytes();}}
    static ClassFileTransformer annotate(Instrumentation inst)throws Exception {
        ClassFileTransformer transformer=new ClassFileTransformer(){
            @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,java.security.ProtectionDomain domain,byte[] bytes){
                if(type!=Work.class)return null;
                var writer=new ClassWriter(0);new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9,writer){
                    @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){var m=super.visitMethod(a,n,d,s,e);
                        if(n.equals("transaction"))m.visitAnnotation("Lorg/springframework/transaction/annotation/Transactional;",true).visitEnd();
                        if(n.equals("cached")){var x=m.visitAnnotation("Lorg/springframework/cache/annotation/Cacheable;",true);var names=x.visitArray("cacheNames");names.visit(null,"numbers");names.visitEnd();x.visitEnd();}return m;}
                },0);return writer.toByteArray();
            }
        };
        inst.addTransformer(transformer,true);try{inst.retransformClasses(Work.class);}catch(Exception failure){inst.removeTransformer(transformer);throw failure;}
        SpringOperationSourceReloader.clearSpringAnnotationCaches(Work.class);return transformer;
    }
    static SpringBeanReloader reloader(List<Object> contexts,Instrumentation inst)throws Exception {
        PlatformContext p=(PlatformContext)Proxy.newProxyInstance(SpringPlainSingletonProxyTest.class.getClassLoader(),new Class<?>[]{PlatformContext.class},(o,m,a)->m.getName().equals("getAllApplicationContexts")?contexts:null);
        var reloader=new SpringBeanReloader(p);
        try{reloader.getClass().getMethod("setLoadedClasses",java.util.function.Supplier.class).invoke(reloader,(java.util.function.Supplier<Class<?>[]>)()->java.util.Arrays.stream(inst.getAllLoadedClasses()).filter(c->c.getName().startsWith(SpringPlainSingletonProxyTest.class.getName())).toArray(Class<?>[]::new));}catch(NoSuchMethodException baseline){}
        return reloader;
    }
    @Test void nativeRecreationAdmitsProxyAndRecreatesConstructorFieldSetterProviderAndWarmLazyConsumers()throws Exception {
        Instrumentation inst=ByteBuddyAgent.install();ClassFileTransformer edit=null;RestartLedger.clear();
        try(var ctx=new AnnotationConfigApplicationContext(Config.class)) {
            Work old=ctx.getBean("work",Work.class);assertFalse(AopUtils.isAopProxy(old));assertFalse(old.transaction());assertEquals(1,old.cached());assertEquals(2,old.cached());
            var constructor=ctx.getBean("constructorHolder",ConstructorHolder.class);var lazy=ctx.getBean(LazyHolder.class);assertFalse(lazy.work.transaction());
            edit=annotate(inst);reloader(List.of(ctx),inst).refreshBean(Work.class.getName(),Work.class);
            Work current=ctx.getBean("work",Work.class);assertTrue(AopUtils.isAopProxy(current),"native creation must clear cached negative proxy/source decisions");
            assertNotSame(old,current);assertTrue(current.transaction());assertEquals(current.cached(),current.cached(),"native cache interceptor is effective after a plain baseline");
            assertNotSame(constructor,ctx.getBean("constructorHolder",ConstructorHolder.class));assertSame(current,ctx.getBean("constructorHolder",ConstructorHolder.class).work);
            assertSame(current,ctx.getBean(FieldHolder.class).work);assertSame(current,ctx.getBean(SetterHolder.class).work);
            assertSame(current,ctx.getBean(ProviderHolder.class).work.getObject());assertTrue(ctx.getBean(LazyHolder.class).work.transaction(),"warmed lazy source must not retain old raw service");
            assertEquals(List.of("Nothing from this session needs a restart."),RestartLedger.digest());
        }finally{if(edit!=null){inst.removeTransformer(edit);inst.retransformClasses(Work.class);}SpringOperationSourceReloader.clearSpringAnnotationCaches(Work.class);RestartLedger.clear();}
    }
    @Test void coldNativeTransactionAdviceIsCreatedOnlyByNativeProxyCreation()throws Exception {coldNativeAdvice(true,false);}
    @Test void coldNativeCacheAdviceWithoutInitializationCallbackRetainsOldBean()throws Exception {coldNativeAdvice(false,false);}
    @Test void refusedConsumerDoesNotInstantiateColdNativeAdvice()throws Exception {coldNativeAdvice(true,true);}
    private void coldNativeAdvice(boolean transaction,boolean refuse)throws Exception {
        Instrumentation inst=ByteBuddyAgent.install();ClassFileTransformer edit=null;RestartLedger.clear();
        try(var ctx=new org.springframework.context.support.GenericApplicationContext()) {
            var f=ctx.getDefaultListableBeanFactory();
            Object source=transaction?new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource():new org.springframework.cache.annotation.AnnotationCacheOperationSource();
            f.registerSingleton("source",source);
            var advice=new org.springframework.beans.factory.support.RootBeanDefinition(transaction?org.springframework.transaction.interceptor.TransactionInterceptor.class:org.springframework.cache.interceptor.CacheInterceptor.class);
            advice.setLazyInit(true);
            advice.getPropertyValues().add(transaction?"transactionAttributeSource":"cacheOperationSource",source);
            advice.getPropertyValues().add(transaction?"transactionManager":"cacheManager",transaction?new Config().transactionManager():new ConcurrentMapCacheManager("numbers"));
            f.registerBeanDefinition("coldAdvice",advice);
            org.springframework.aop.support.AbstractBeanFactoryPointcutAdvisor advisor;
            if(transaction){var a=new org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor();a.setTransactionAttributeSource((org.springframework.transaction.interceptor.TransactionAttributeSource)source);advisor=a;}
            else {var a=new org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor();a.setCacheOperationSource((org.springframework.cache.interceptor.CacheOperationSource)source);advisor=a;}
            advisor.setAdviceBeanName("coldAdvice");advisor.setBeanFactory(f);f.registerSingleton("coldAdvisor",advisor);
            var creator=new org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator();creator.setProxyTargetClass(true);creator.setBeanFactory(f);f.addBeanPostProcessor(creator);
            ctx.registerBeanDefinition("work",new org.springframework.beans.factory.support.RootBeanDefinition(Work.class));
            if(refuse){var holder=new org.springframework.beans.factory.support.RootBeanDefinition(DisposableHolder.class);holder.getConstructorArgumentValues().addIndexedArgumentValue(0,new org.springframework.beans.factory.config.RuntimeBeanReference("work"));ctx.registerBeanDefinition("callbackHolder",holder);}
            ctx.refresh();
            Work old=ctx.getBean(Work.class);assertFalse(AopUtils.isAopProxy(old));assertFalse(f.containsSingleton("coldAdvice"));
            edit=annotate(inst);reloader(List.of(ctx),inst).refreshBean(Work.class.getName(),Work.class);
            Work current=ctx.getBean(Work.class);
            if(refuse||!transaction){assertSame(old,current);assertFalse(f.containsSingleton("coldAdvice"));assertTrue(RestartLedger.digest().stream().anyMatch(line->line.contains(refuse?"destroy callback":"cold cache advice initialization")),RestartLedger.digest().toString());return;}
            assertNotSame(old,current,RestartLedger.digest().toString());assertTrue(AopUtils.isAopProxy(current));assertTrue(f.containsSingleton("coldAdvice"));
            if(transaction)assertTrue(current.transaction());else assertEquals(current.cached(),current.cached());
            assertEquals(List.of("Nothing from this session needs a restart."),RestartLedger.digest());
        }finally{if(edit!=null){inst.removeTransformer(edit);inst.retransformClasses(Work.class);}SpringOperationSourceReloader.clearSpringAnnotationCaches(Work.class);RestartLedger.clear();}
    }
    @Test void frameworkMessageMetadataAndTerminalValuesDoNotBecomeAnApplicationHeapWalk()throws Exception {
        Object old=new Object();var retiring=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object,Boolean>());retiring.add(old);
        var messages=new org.springframework.context.support.StaticMessageSource();
        for(int i=0;i<5000;i++)messages.addMessage("key"+i,java.util.Locale.ROOT,"value"+i);
        var metadata=new org.springframework.context.support.MessageSourceAccessor(messages);
        assertFalse(SpringProxyReferences.references(new Box(metadata),retiring,"metadataHolder",java.util.Set.of()));
        var values=new java.util.ArrayList<String>();for(int i=0;i<5000;i++)values.add("value"+i);
        assertFalse(SpringProxyReferences.references(new Box(values),retiring,"terminalHolder",java.util.Set.of()));
        assertThrows(IllegalStateException.class,()->SpringProxyReferences.references(new Box(new java.util.concurrent.atomic.AtomicReference<>(old)),retiring,"oldHolder",java.util.Set.of()));
        assertThrows(IllegalStateException.class,()->SpringProxyReferences.references(new Box(new org.springframework.aop.target.SingletonTargetSource(old)),retiring,"oldNativeHolder",java.util.Set.of()));
    }
    public static class ExternalRegistry extends java.util.AbstractMap<String,Object> {
        public java.util.Set<java.util.Map.Entry<String,Object>> entrySet(){throw new AssertionError("external registry iteration must not execute");}
    }
    public static class ExternalConsumer {final Object value;ExternalConsumer(Object value){this.value=value;}}
    @Test void watchedWrapperScopeDoesNotEnterExternalRegistriesOrLoseDirectNativeConsumers()throws Exception {
        Object old=new Work();var retiring=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object,Boolean>());retiring.add(old);
        var scope=java.util.Set.<Class<?>>of(Work.class,Box.class);
        assertFalse(inspectApplicationReferences(new Box(new ExternalRegistry()),retiring,"externalRegistry",scope));
        assertTrue(inspectApplicationReferences(new ExternalConsumer(old),retiring,"directNativeConsumer",scope));
        try{inspectApplicationReferences(new Box(new java.util.concurrent.atomic.AtomicReference<>(old)),retiring,"retainedApplicationBean",scope);fail("retained old bean must be named");}
        catch(java.lang.reflect.InvocationTargetException expected){assertInstanceOf(IllegalStateException.class,expected.getCause());}
    }
    private static boolean inspectApplicationReferences(Object bean,java.util.Set<Object> retiring,String label,java.util.Set<Class<?>> scope)throws Exception {
        java.lang.reflect.Method inspect;
        try{inspect=SpringProxyReferences.class.getDeclaredMethod("references",Object.class,java.util.Set.class,String.class,java.util.Set.class,java.util.Set.class);}
        catch(NoSuchMethodException baseline){inspect=SpringProxyReferences.class.getDeclaredMethod("references",Object.class,java.util.Set.class,String.class,java.util.Set.class);}
        var args=new java.util.ArrayList<Object>(java.util.List.of(bean,retiring,label,java.util.Set.of()));if(inspect.getParameterCount()==5)args.add(scope);
        return (Boolean)inspect.invoke(null,args.toArray());
    }
    @Test void twoNativeCreatorsRecreateAndVerifyEverySingletonProxyLayer()throws Exception {
        Instrumentation inst=ByteBuddyAgent.install();ClassFileTransformer edit=null;SpringReloadOrchestrator orchestrator=null;RestartLedger.clear();
        try(var ctx=new AnnotationConfigApplicationContext(Config.class)) {
            var f=ctx.getDefaultListableBeanFactory();var extra=new org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator();extra.setProxyTargetClass(true);extra.setBeanFactory(f);f.addBeanPostProcessor(extra);
            Work old=ctx.getBean(Work.class);assertFalse(AopUtils.isAopProxy(old));edit=annotate(inst);
            orchestrator=new SpringReloadOrchestrator((PlatformContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},(o,m,a)->m.getName().equals("getAllApplicationContexts")?List.of(ctx):null));
            orchestrator.setLoadedClasses(()->java.util.Arrays.stream(inst.getAllLoadedClasses()).filter(c->c.getName().startsWith(getClass().getName())).toArray(Class<?>[]::new));
            var delayed=new java.util.ArrayList<Runnable>();orchestrator.setPreviousOperationDescriptors(java.util.Set.of());
            orchestrator.setOperationScheduler((r,d)->{delayed.add(r);return ()->delayed.remove(r);},Runnable::run);
            orchestrator.onClassReloaded(Work.class.getName(),Work.class,false,true);
            for(var repair:List.copyOf(delayed))repair.run();
            Work current=ctx.getBean(Work.class);assertNotSame(old,current,RestartLedger.digest().toString());assertTrue(AopUtils.isAopProxy(current));assertTrue(current.transaction());assertEquals(current.cached(),current.cached());
            assertSame(current,ctx.getBean(ConstructorHolder.class).work);assertEquals(Work.class,SpringProxyReferences.target(current).getClass());
            var outer=(org.springframework.aop.framework.Advised)current;assertTrue(outer.getTargetSource().getTarget() instanceof org.springframework.aop.framework.Advised);
            assertNull(OperationMetadataInspection.methods(ctx,Work.class).unsupported());
            assertEquals(List.of("Nothing from this session needs a restart."),RestartLedger.digest());
        }finally{if(orchestrator!=null)orchestrator.closeOperationRepairs();if(edit!=null){inst.removeTransformer(edit);inst.retransformClasses(Work.class);}SpringOperationSourceReloader.clearSpringAnnotationCaches(Work.class);RestartLedger.clear();}
    }
    public static class EarlierMetadataOwner {
        @org.springframework.transaction.annotation.Transactional(readOnly=true)public boolean tx(){return TransactionSynchronizationManager.isCurrentTransactionReadOnly();}
        @org.springframework.cache.annotation.Cacheable("before")public String cached(){return "value";}
    }
    @org.springframework.stereotype.Service public static class BodyOnlyOwner {public String read(){return "body";}}
    @Test void plainBodySavePreservesPreviouslyRepairedTransactionAndCacheMetadata()throws Exception {
        Instrumentation inst=ByteBuddyAgent.install();ClassFileTransformer edit=null;SpringReloadOrchestrator orchestrator=null;RestartLedger.clear();
        try(var ctx=new org.springframework.context.support.GenericApplicationContext()) {
            var f=ctx.getDefaultListableBeanFactory();var tx=new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource(true);var cache=new org.springframework.cache.annotation.AnnotationCacheOperationSource(true);
            var txAdvice=new org.springframework.transaction.interceptor.TransactionInterceptor(new Config().transactionManager(),tx);
            var cacheAdvice=new org.springframework.cache.interceptor.CacheInterceptor();cacheAdvice.setCacheOperationSource(cache);cacheAdvice.setCacheManager(new ConcurrentMapCacheManager("before","after"));
            var txAdvisor=new org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor();txAdvisor.setTransactionAttributeSource(tx);txAdvisor.setAdvice(txAdvice);
            var cacheAdvisor=new org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor();cacheAdvisor.setCacheOperationSource(cache);cacheAdvisor.setAdvice(cacheAdvice);
            f.registerSingleton("txSource",tx);f.registerSingleton("cacheSource",cache);f.registerSingleton("txAdvisor",txAdvisor);f.registerSingleton("cacheAdvisor",cacheAdvisor);
            var creator=new org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator();creator.setProxyTargetClass(true);creator.setBeanFactory(f);f.addBeanPostProcessor(creator);
            ctx.registerBeanDefinition("earlier",new org.springframework.beans.factory.support.RootBeanDefinition(EarlierMetadataOwner.class));ctx.registerBeanDefinition("body",new org.springframework.beans.factory.support.RootBeanDefinition(BodyOnlyOwner.class));ctx.refresh();
            assertTrue(AopUtils.isAopProxy(ctx.getBean("earlier")));assertFalse(AopUtils.isAopProxy(ctx.getBean("body")));
            var heldTx=EarlierMetadataOwner.class.getMethod("tx");var heldCache=EarlierMetadataOwner.class.getMethod("cached");assertTrue(tx.getTransactionAttribute(heldTx,EarlierMetadataOwner.class).isReadOnly());assertEquals(java.util.Set.of("before"),cache.getCacheOperations(heldCache,EarlierMetadataOwner.class).iterator().next().getCacheNames());
            edit=new ClassFileTransformer(){@Override public byte[] transform(ClassLoader l,String n,Class<?> c,java.security.ProtectionDomain d,byte[] bytes){
                if(c!=EarlierMetadataOwner.class&&c!=BodyOnlyOwner.class)return null;var writer=new ClassWriter(0);new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9,writer){
                    @Override public MethodVisitor visitMethod(int a,String name,String desc,String sig,String[] exceptions){var next=super.visitMethod(a,name,desc,sig,exceptions);return new MethodVisitor(Opcodes.ASM9,next){
                        @Override public AnnotationVisitor visitAnnotation(String annotation,boolean visible){if(c==EarlierMetadataOwner.class&&(annotation.equals("Lorg/springframework/transaction/annotation/Transactional;")||annotation.equals("Lorg/springframework/cache/annotation/Cacheable;")))return null;return super.visitAnnotation(annotation,visible);}
                        @Override public void visitCode(){super.visitCode();if(c==BodyOnlyOwner.class)super.visitInsn(Opcodes.NOP);}
                        @Override public void visitEnd(){if(c==EarlierMetadataOwner.class&&name.equals("tx")){var annotation=super.visitAnnotation("Lorg/springframework/transaction/annotation/Transactional;",true);annotation.visit("readOnly",false);annotation.visitEnd();}
                            if(c==EarlierMetadataOwner.class&&name.equals("cached")){var annotation=super.visitAnnotation("Lorg/springframework/cache/annotation/Cacheable;",true);var names=annotation.visitArray("cacheNames");names.visit(null,"after");names.visitEnd();annotation.visitEnd();}super.visitEnd();}
                    };}
                },0);return writer.toByteArray();}};
            inst.addTransformer(edit,true);inst.retransformClasses(EarlierMetadataOwner.class);
            PlatformContext platform=(PlatformContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},(o,m,a)->m.getName().equals("getAllApplicationContexts")?List.of(ctx):null);
            orchestrator=new SpringReloadOrchestrator(platform);orchestrator.setLoadedClasses(()->java.util.Arrays.stream(inst.getAllLoadedClasses()).filter(c->c.getName().startsWith(getClass().getName())).toArray(Class<?>[]::new));var delayed=new java.util.ArrayList<Runnable>();
            orchestrator.setOperationScheduler((r,d)->{delayed.add(r);return()->delayed.remove(r);},Runnable::run);orchestrator.setPreviousOperationDescriptors(java.util.Set.of());
            orchestrator.onHelperReloaded(EarlierMetadataOwner.class.getName(),EarlierMetadataOwner.class,false,true,false,java.util.Set.of(),null);for(var repair:List.copyOf(delayed))repair.run();
            assertFalse(tx.getTransactionAttribute(heldTx,EarlierMetadataOwner.class).isReadOnly(),"A was repaired before B save");assertEquals(java.util.Set.of("after"),cache.getCacheOperations(heldCache,EarlierMetadataOwner.class).iterator().next().getCacheNames());
            inst.retransformClasses(BodyOnlyOwner.class);orchestrator.onClassReloaded(BodyOnlyOwner.class.getName(),BodyOnlyOwner.class,false,false);
            assertFalse(tx.getTransactionAttribute(heldTx,EarlierMetadataOwner.class).isReadOnly(),"plain B save must preserve A's repaired retained-Method answer");assertEquals(java.util.Set.of("after"),cache.getCacheOperations(heldCache,EarlierMetadataOwner.class).iterator().next().getCacheNames(),"plain B save must preserve A's new cache name");
        }finally{if(orchestrator!=null)orchestrator.closeOperationRepairs();if(edit!=null){inst.removeTransformer(edit);inst.retransformClasses(EarlierMetadataOwner.class,BodyOnlyOwner.class);}SpringOperationSourceReloader.clearSpringAnnotationCaches(EarlierMetadataOwner.class);RestartLedger.clear();}
    }
    @Test void frameworkSingletonRetainingRawBeanIsNamedAndNeverMutated()throws Exception {
        Instrumentation inst=ByteBuddyAgent.install();ClassFileTransformer edit=null;RestartLedger.clear();
        try(var ctx=new AnnotationConfigApplicationContext(Config.class)) {
            Work old=ctx.getBean(Work.class);var holder=new org.springframework.aop.target.SingletonTargetSource(old);ctx.getBeanFactory().registerSingleton("frameworkHolder",holder);
            edit=annotate(inst);reloader(List.of(ctx),inst).refreshBean(Work.class.getName(),Work.class);
            assertSame(old,ctx.getBean(Work.class));assertSame(old,holder.getTarget());assertFalse(AopUtils.isAopProxy(ctx.getBean(Work.class)));
            assertTrue(RestartLedger.digest().stream().anyMatch(line->line.contains("frameworkHolder")&&line.contains("target")),RestartLedger.digest().toString());
        }finally{if(edit!=null){inst.removeTransformer(edit);inst.retransformClasses(Work.class);}SpringOperationSourceReloader.clearSpringAnnotationCaches(Work.class);RestartLedger.clear();}
    }
    public static class RollbackContext {public boolean isActive(){return true;}}
    public static class RollbackFactory {
        public final GapLock singletonLock=new GapLock();public boolean singletonsCurrentlyInDestruction;Object value=new Object();
        public void destroySingleton(String name){assertTrue(singletonLock.isHeldByCurrentThread());value=null;singletonLock.destroyed=true;}
        public void registerSingleton(String name,Object old){assertTrue(singletonLock.isHeldByCurrentThread());value=old;singletonLock.restored=true;}
        public void registerDependentBean(String dependency,String name){}
    }
    public static class GapLock extends java.util.concurrent.locks.ReentrantLock {boolean destroyed,restored;int exposedGaps;@Override public void unlock(){if(destroyed&&!restored)exposedGaps++;super.unlock();}}
    @Test void rollbackDoesNotReleaseNativeLockBetweenDestroyAndOldRegistration()throws Exception {
        var factory=new RollbackFactory();Object old=new Object();var bean=new SpringProxyRecreation.Bean(new RollbackContext(),factory,"value",old);
        var saved=java.util.Arrays.stream(SpringProxyRecreation.class.getDeclaredClasses()).filter(c->c.getSimpleName().equals("Saved")).findFirst().orElseThrow();var constructor=saved.getDeclaredConstructors()[0];constructor.setAccessible(true);Object snapshot=constructor.newInstance(bean,old,new Object(),List.of());
        var rollback=SpringProxyRecreation.class.getDeclaredMethod("rollback",List.class,List.class,Throwable.class);rollback.setAccessible(true);var cause=new IllegalStateException("synthetic candidate failure");rollback.invoke(null,List.of(snapshot),List.of(),cause);
        assertEquals(0,cause.getSuppressed().length);assertSame(old,factory.value);assertEquals(0,factory.singletonLock.exposedGaps,"native lock release must not expose an absent/unrestored singleton slot");
    }
    static Work staticHolder;
    static Object staticWeakHolder;
    public static class DisposableHolder implements org.springframework.beans.factory.DisposableBean {
        static int destroyed;final Work work;DisposableHolder(Work work){this.work=work;}public void destroy(){destroyed++;}
    }
    @Configuration(proxyBeanMethods=false) public static class CallbackConfig {
        @Bean DisposableHolder disposableHolder(Work work){return new DisposableHolder(work);}
    }
    public static class ContainerHolder { final java.util.List<Work> values;ContainerHolder(Work work){values=List.of(work);} }
    public static class Box { final Object value;Box(Object value){this.value=value;} }
    public static class ChildHolder { final Work work;public ChildHolder(Work work){this.work=work;} }
    @Test void callbackConsumerIsNamedAndOldGraphSurvivesBeforeAnyDestroy()throws Exception {
        Instrumentation inst=ByteBuddyAgent.install();ClassFileTransformer edit=null;DisposableHolder.destroyed=0;RestartLedger.clear();
        try(var ctx=new AnnotationConfigApplicationContext(Config.class,CallbackConfig.class)) {
            Work old=ctx.getBean("work",Work.class);var holder=ctx.getBean(DisposableHolder.class);edit=annotate(inst);
            reloader(List.of(ctx),inst).refreshBean(Work.class.getName(),Work.class);
            assertSame(old,ctx.getBean("work",Work.class));assertSame(holder,ctx.getBean(DisposableHolder.class));assertSame(old,holder.work);assertEquals(0,DisposableHolder.destroyed);
            assertTrue(RestartLedger.digest().stream().anyMatch(x->x.contains("disposableHolder")&&x.contains("destroy callback")),RestartLedger.digest().toString());
        }finally{if(edit!=null){inst.removeTransformer(edit);inst.retransformClasses(Work.class);}SpringOperationSourceReloader.clearSpringAnnotationCaches(Work.class);RestartLedger.clear();}
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"manual","static","container","map","weak","deep","duplicate","census"})
    void unprovableConsumerKeepsRawBeanAndNamesTheCause(String shape)throws Exception {
        Instrumentation inst=ByteBuddyAgent.install();ClassFileTransformer edit=null;RestartLedger.clear();
        try(var ctx=new AnnotationConfigApplicationContext(Config.class)) {
            Work old=ctx.getBean("work",Work.class);var holder=ctx.getBean("constructorHolder",ConstructorHolder.class);
            if(shape.equals("manual"))ctx.getBeanFactory().registerSingleton("manualHolder",new ConstructorHolder(old));
            if(shape.equals("container"))ctx.getBeanFactory().registerSingleton("containerHolder",new ContainerHolder(old));
            if(shape.equals("static"))staticHolder=old;
            if(shape.equals("weak"))staticWeakHolder=new java.lang.ref.WeakReference<>(old);
            if(shape.equals("duplicate"))ctx.getBeanFactory().registerSingleton("duplicateRoot",old);
            if(shape.equals("map"))ctx.getBeanFactory().registerSingleton("mapHolder",java.util.Map.of("work",old));
            if(shape.equals("deep"))ctx.getBeanFactory().registerSingleton("deepHolder",new Box(new Box(new Box(new Box(old)))));
            edit=annotate(inst);var r=reloader(List.of(ctx),inst);if(shape.equals("census"))r.setLoadedClasses(()->null);r.refreshBean(Work.class.getName(),Work.class);
            assertSame(old,ctx.getBean("work",Work.class));assertSame(holder,ctx.getBean("constructorHolder",ConstructorHolder.class));assertSame(old,holder.work);
            String reason=switch(shape){case "manual"->"manualHolder";case "static"->"staticHolder";case "container"->"containerHolder";case "map"->"mapHolder";case "weak"->"staticWeakHolder";case "deep"->"deepHolder";case "duplicate"->"duplicateRoot";default->"census unavailable";};
            assertTrue(RestartLedger.digest().stream().anyMatch(x->x.contains(reason)),RestartLedger.digest().toString());
        }finally{staticHolder=null;staticWeakHolder=null;if(edit!=null){inst.removeTransformer(edit);inst.retransformClasses(Work.class);}SpringOperationSourceReloader.clearSpringAnnotationCaches(Work.class);RestartLedger.clear();}
    }
    @Test void failedConsumerCreationRestoresExactOldInstancesAndEdgesThenRetrySucceeds()throws Exception {
        Instrumentation inst=ByteBuddyAgent.install();ClassFileTransformer edit=null;RestartLedger.clear();
        try(var ctx=new AnnotationConfigApplicationContext(Config.class)) {
            Work old=ctx.getBean("work",Work.class);var constructor=ctx.getBean("constructorHolder",ConstructorHolder.class);var field=ctx.getBean(FieldHolder.class);var setter=ctx.getBean(SetterHolder.class);
            edit=annotate(inst);ConstructorHolder.fail=true;var r=reloader(List.of(ctx),inst);r.refreshBean(Work.class.getName(),Work.class);
            assertSame(old,ctx.getBean("work",Work.class));assertSame(constructor,ctx.getBean("constructorHolder",ConstructorHolder.class));assertSame(field,ctx.getBean(FieldHolder.class));assertSame(setter,ctx.getBean(SetterHolder.class));
            assertSame(old,constructor.work);assertSame(old,field.work);assertSame(old,setter.work);
            assertTrue(java.util.Arrays.asList(ctx.getBeanFactory().getDependentBeans("work")).contains("constructorHolder"));
            assertTrue(RestartLedger.digest().stream().anyMatch(x->x.contains("unverified")),RestartLedger.digest().toString());
            ConstructorHolder.fail=false;r.refreshBean(Work.class.getName(),Work.class);
            Work fresh=ctx.getBean("work",Work.class);assertTrue(AopUtils.isAopProxy(fresh));assertTrue(fresh.transaction());assertSame(fresh,ctx.getBean("constructorHolder",ConstructorHolder.class).work);
        }finally{ConstructorHolder.fail=false;if(edit!=null){inst.removeTransformer(edit);inst.retransformClasses(Work.class);}SpringOperationSourceReloader.clearSpringAnnotationCaches(Work.class);RestartLedger.clear();}
    }
    @Test void parentOwnedServiceRecreatesItsNativeChildConstructorConsumer()throws Exception {
        Instrumentation inst=ByteBuddyAgent.install();ClassFileTransformer edit=null;RestartLedger.clear();
        try(var parent=new AnnotationConfigApplicationContext(Config.class);var child=new AnnotationConfigApplicationContext()) {
            child.setParent(parent);child.registerBean("childHolder",ChildHolder.class);child.refresh();var old=child.getBean(ChildHolder.class);assertSame(parent.getBean(Work.class),old.work);
            edit=annotate(inst);reloader(List.of(parent,child),inst).refreshBean(Work.class.getName(),Work.class);
            Work current=parent.getBean(Work.class);assertTrue(AopUtils.isAopProxy(current));assertNotSame(old,child.getBean(ChildHolder.class));assertSame(current,child.getBean(ChildHolder.class).work);assertTrue(current.transaction());
            assertEquals(List.of("Nothing from this session needs a restart."),RestartLedger.digest());
        }finally{if(edit!=null){inst.removeTransformer(edit);inst.retransformClasses(Work.class);}SpringOperationSourceReloader.clearSpringAnnotationCaches(Work.class);RestartLedger.clear();}
    }

    public static class CustomAnnotationSource extends org.springframework.transaction.annotation.AnnotationTransactionAttributeSource { }
    @Test void cachedNegativeCustomSourceIsRefusedWithoutDestroyingThePlainSingleton()throws Exception {
        Instrumentation inst=ByteBuddyAgent.install();ClassFileTransformer edit=null;RestartLedger.clear();
        try(var ctx=new org.springframework.context.support.GenericApplicationContext()) {
            var f=ctx.getDefaultListableBeanFactory();var source=new CustomAnnotationSource();
            var interceptor=new org.springframework.transaction.interceptor.TransactionInterceptor(new Config().transactionManager(),source);
            var advisor=new org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor();advisor.setTransactionAttributeSource(source);advisor.setAdvice(interceptor);
            f.registerSingleton("customSource",source);f.registerSingleton("customAdvisor",advisor);
            var creator=new org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator();creator.setProxyTargetClass(true);creator.setBeanFactory(f);f.addBeanPostProcessor(creator);
            ctx.registerBeanDefinition("work",new org.springframework.beans.factory.support.RootBeanDefinition(Work.class));ctx.refresh();
            Work old=ctx.getBean("work",Work.class);assertFalse(AopUtils.isAopProxy(old));source.getTransactionAttribute(Work.class.getMethod("transaction"),Work.class);
            edit=annotate(inst);reloader(List.of(ctx),inst).refreshBean(Work.class.getName(),Work.class);
            assertSame(old,ctx.getBean("work",Work.class));assertTrue(RestartLedger.digest().stream().anyMatch(x->x.contains("unsupported operation source")),RestartLedger.digest().toString());
        }finally{if(edit!=null){inst.removeTransformer(edit);inst.retransformClasses(Work.class);}SpringOperationSourceReloader.clearSpringAnnotationCaches(Work.class);RestartLedger.clear();}
    }

    @Test void bodyOnlyRetryBypassesKeepAndResolvesOnlyThisOwnersProxyConcern()throws Exception {
        Instrumentation inst=ByteBuddyAgent.install();ClassFileTransformer edit=null;RestartLedger.clear();
        try(var ctx=new org.springframework.context.support.GenericApplicationContext()) {
            var f=ctx.getDefaultListableBeanFactory();var source=new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();
            var interceptor=new org.springframework.transaction.interceptor.TransactionInterceptor(new Config().transactionManager(),source);
            var advisor=new org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor();advisor.setTransactionAttributeSource(source);advisor.setAdvice(interceptor);
            f.registerSingleton("source",source);f.registerSingleton("advisor",advisor);var creator=new org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator();creator.setProxyTargetClass(true);creator.setBeanFactory(f);f.addBeanPostProcessor(creator);
            ctx.registerBeanDefinition("work",new org.springframework.beans.factory.support.RootBeanDefinition(Work.class));
            var holder=new org.springframework.beans.factory.support.RootBeanDefinition(ConstructorHolder.class);holder.getConstructorArgumentValues().addIndexedArgumentValue(0,new org.springframework.beans.factory.config.RuntimeBeanReference("work"));ctx.registerBeanDefinition("constructorHolder",holder);ctx.refresh();
            Work old=ctx.getBean("work",Work.class);edit=annotate(inst);ConstructorHolder.fail=true;var r=reloader(List.of(ctx),inst);r.refreshBean(Work.class.getName(),Work.class);assertSame(old,ctx.getBean("work",Work.class));
            var bytes=new java.util.concurrent.atomic.AtomicReference<byte[]>();ClassFileTransformer observer=new ClassFileTransformer(){@Override public byte[] transform(ClassLoader l,String n,Class<?> c,java.security.ProtectionDomain d,byte[] b){if(c==Work.class)bytes.set(b.clone());return null;}};
            inst.addTransformer(observer,true);try{inst.retransformClasses(Work.class);}finally{inst.removeTransformer(observer);}
            var input=new com.onurkat.reclazz.transform.KeepBaseline.Input(com.onurkat.reclazz.transform.KeepBaseline.fingerprint(bytes.get()),bytes.get());
            assertTrue(SingletonKeepPolicy.forBean(f,"work",Work.class,input).keep(),"the ordinary body-only path would retain this raw singleton");
            String subject=SpringProxyRecreation.subject(f,"work");RestartLedger.note(subject,"unrelated listener concern");ConstructorHolder.fail=false;
            r.refreshBean(Work.class.getName(),Work.class,null,input);
            Work fresh=ctx.getBean("work",Work.class);assertTrue(AopUtils.isAopProxy(fresh));assertTrue(fresh.transaction());assertSame(fresh,ctx.getBean(ConstructorHolder.class).work);
            assertEquals(List.of("unrelated listener concern"),RestartLedger.reasonsFor(subject),"resolve only this recovered proxy concern, retaining other reasons on the same owner");
        }finally{ConstructorHolder.fail=false;if(edit!=null){inst.removeTransformer(edit);inst.retransformClasses(Work.class);}SpringOperationSourceReloader.clearSpringAnnotationCaches(Work.class);RestartLedger.clear();}
    }

    public static class AsyncWork {public java.util.concurrent.CompletableFuture<String> read(){return java.util.concurrent.CompletableFuture.completedFuture(Thread.currentThread().getName());}}
    @Configuration(proxyBeanMethods=false) @org.springframework.scheduling.annotation.EnableAsync(proxyTargetClass=true)
    public static class AsyncConfig {
        @Bean AsyncWork asyncWork(){return new AsyncWork();}
        @Bean org.springframework.core.task.SimpleAsyncTaskExecutor taskExecutor(){return new org.springframework.core.task.SimpleAsyncTaskExecutor("r29-native-async-");}
    }
    public static class ValidationWork {public String echo(@jakarta.validation.constraints.NotBlank String value){return value;}}
    @Configuration(proxyBeanMethods=false) public static class ValidationConfig {
        @Bean ValidationWork validationWork(){return new ValidationWork();}
        @Bean static org.springframework.validation.beanvalidation.MethodValidationPostProcessor methodValidation(){return new org.springframework.validation.beanvalidation.MethodValidationPostProcessor();}
    }
    static ClassFileTransformer annotateType(Instrumentation inst,Class<?> owner,String descriptor,String method)throws Exception {
        ClassFileTransformer edit=new ClassFileTransformer(){@Override public byte[] transform(ClassLoader l,String n,Class<?> type,java.security.ProtectionDomain d,byte[] bytes){
            if(type!=owner)return null;var writer=new ClassWriter(0);new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9,writer){
                @Override public void visit(int v,int a,String n,String sig,String parent,String[] interfaces){super.visit(v,a,n,sig,parent,interfaces);if(method==null)super.visitAnnotation(descriptor,true).visitEnd();}
                @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){var m=super.visitMethod(a,n,d,s,e);if(n.equals(method))m.visitAnnotation(descriptor,true).visitEnd();return m;}
            },0);return writer.toByteArray();}};
        inst.addTransformer(edit,true);try{inst.retransformClasses(owner);}catch(Exception failure){inst.removeTransformer(edit);throw failure;}SpringOperationSourceReloader.clearSpringAnnotationCaches(owner);return edit;
    }
    @Test void formerlyPlainAsyncServiceUsesItsNativeExecutorAfterAnnotationAddition()throws Exception {
        Instrumentation inst=ByteBuddyAgent.install();ClassFileTransformer edit=null;RestartLedger.clear();
        try(var ctx=new AnnotationConfigApplicationContext(AsyncConfig.class)) {
            AsyncWork old=ctx.getBean(AsyncWork.class);String caller=Thread.currentThread().getName();assertFalse(AopUtils.isAopProxy(old));assertEquals(caller,old.read().get());
            edit=annotateType(inst,AsyncWork.class,"Lorg/springframework/scheduling/annotation/Async;","read");reloader(List.of(ctx),inst).refreshBean(AsyncWork.class.getName(),AsyncWork.class);
            AsyncWork current=ctx.getBean(AsyncWork.class);assertTrue(AopUtils.isAopProxy(current));assertNotSame(old,current);assertTrue(current.read().get(5,java.util.concurrent.TimeUnit.SECONDS).startsWith("r29-native-async-"));
            assertEquals(List.of("Nothing from this session needs a restart."),RestartLedger.digest());
        }finally{if(edit!=null){inst.removeTransformer(edit);inst.retransformClasses(AsyncWork.class);}SpringOperationSourceReloader.clearSpringAnnotationCaches(AsyncWork.class);RestartLedger.clear();}
    }
    @Test void formerlyPlainValidatedServiceEnforcesNativeMethodConstraints()throws Exception {
        Instrumentation inst=ByteBuddyAgent.install();ClassFileTransformer edit=null;RestartLedger.clear();
        try(var ctx=new AnnotationConfigApplicationContext(ValidationConfig.class)) {
            ValidationWork old=ctx.getBean(ValidationWork.class);assertFalse(AopUtils.isAopProxy(old));assertEquals("",old.echo(""));
            edit=annotateType(inst,ValidationWork.class,"Lorg/springframework/validation/annotation/Validated;",null);reloader(List.of(ctx),inst).refreshBean(ValidationWork.class.getName(),ValidationWork.class);
            ValidationWork current=ctx.getBean(ValidationWork.class);assertTrue(AopUtils.isAopProxy(current));assertNotSame(old,current);assertThrows(jakarta.validation.ConstraintViolationException.class,()->current.echo(""));assertEquals("valid",current.echo("valid"));
            assertEquals(List.of("Nothing from this session needs a restart."),RestartLedger.digest());
        }finally{if(edit!=null){inst.removeTransformer(edit);inst.retransformClasses(ValidationWork.class);}SpringOperationSourceReloader.clearSpringAnnotationCaches(ValidationWork.class);RestartLedger.clear();}
    }
    public static class SubWork extends Work { }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement(proxyTargetClass=true) public static class SubConfig {
        @Bean SubWork subWork(){return new SubWork();}
        @Bean PlatformTransactionManager transactionManager(){return new Config().transactionManager();}
    }
    @Test void unverifiedSubclassTargetKeepsItsOldInstance()throws Exception {
        Instrumentation inst=ByteBuddyAgent.install();ClassFileTransformer edit=null;RestartLedger.clear();
        try(var ctx=new AnnotationConfigApplicationContext(SubConfig.class)) {
            SubWork old=ctx.getBean(SubWork.class);assertFalse(AopUtils.isAopProxy(old));edit=annotate(inst);reloader(List.of(ctx),inst).refreshBean(Work.class.getName(),Work.class);
            assertSame(old,ctx.getBean(SubWork.class));assertTrue(RestartLedger.digest().stream().anyMatch(x->x.contains("subclass target ownership")),RestartLedger.digest().toString());
        }finally{if(edit!=null){inst.removeTransformer(edit);inst.retransformClasses(Work.class);}SpringOperationSourceReloader.clearSpringAnnotationCaches(Work.class);RestartLedger.clear();}
    }

    public static class PlainWork {public boolean transaction(){return TransactionSynchronizationManager.isActualTransactionActive();}}
    public static class PlainHolder {final PlainWork work;PlainHolder(PlainWork work){this.work=work;}}
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement(proxyTargetClass=true) public static class PlainConfig {
        @Bean PlainWork plainWork(){return new PlainWork();}@Bean PlainHolder plainHolder(PlainWork work){return new PlainHolder(work);}
        @Bean PlatformTransactionManager transactionManager(){return new Config().transactionManager();}
    }
    @Test void unannotatedFactoryServiceUsesTheHelperRouteToGainItsFirstProxy()throws Exception {
        Instrumentation inst=ByteBuddyAgent.install();ClassFileTransformer edit=null,body=null;RestartLedger.clear();SpringReloadOrchestrator orchestrator=null;
        try(var ctx=new AnnotationConfigApplicationContext(PlainConfig.class)) {
            PlainWork old=ctx.getBean(PlainWork.class);var holder=ctx.getBean(PlainHolder.class);assertFalse(AopUtils.isAopProxy(old));
            edit=annotateType(inst,PlainWork.class,"Lorg/springframework/transaction/annotation/Transactional;","transaction");
            PlatformContext platform=(PlatformContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},(o,m,a)->m.getName().equals("getAllApplicationContexts")?List.of(ctx):null);
            orchestrator=new SpringReloadOrchestrator(platform);orchestrator.setLoadedClasses(()->java.util.Arrays.stream(inst.getAllLoadedClasses()).filter(c->c.getName().startsWith(getClass().getName())).toArray(Class<?>[]::new));
            var timers=new java.util.ArrayList<Runnable>();
            orchestrator.setPreviousOperationDescriptors(java.util.Set.of());orchestrator.setOperationScheduler((r,d)->{timers.add(r);return ()->timers.remove(r);},Runnable::run);
            orchestrator.onHelperReloaded(PlainWork.class.getName(),PlainWork.class,false,true,false,java.util.Set.of(),null);
            PlainWork current=ctx.getBean(PlainWork.class);assertTrue(AopUtils.isAopProxy(current));assertNotSame(old,current);assertTrue(current.transaction());assertNotSame(holder,ctx.getBean(PlainHolder.class));assertSame(current,ctx.getBean(PlainHolder.class).work);
            assertEquals(List.of("Nothing from this session needs a restart."),RestartLedger.digest());
            body=new ClassFileTransformer(){@Override public byte[] transform(ClassLoader l,String n,Class<?> c,java.security.ProtectionDomain d,byte[] bytes){
                if(c!=PlainWork.class)return null;var writer=new ClassWriter(0);new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9,writer){
                    @Override public MethodVisitor visitMethod(int a,String n,String d,String sig,String[] e){var next=super.visitMethod(a,n,d,sig,e);if(!n.equals("transaction"))return next;return new MethodVisitor(Opcodes.ASM9,next){@Override public void visitCode(){super.visitCode();super.visitInsn(Opcodes.NOP);}};}
                },0);return writer.toByteArray();}};
            inst.addTransformer(body,true);inst.retransformClasses(PlainWork.class);
            orchestrator.onHelperReloaded(PlainWork.class.getName(),PlainWork.class,false,false,false,java.util.Set.of(),null);
            for(Runnable timer:List.copyOf(timers))timer.run();
            assertSame(current,ctx.getBean(PlainWork.class));assertTrue(current.transaction());assertEquals(List.of("Nothing from this session needs a restart."),RestartLedger.digest(),"a pending birth repair stays valid across a body-only save");
        }finally{if(orchestrator!=null)orchestrator.closeOperationRepairs();if(body!=null)inst.removeTransformer(body);if(edit!=null){inst.removeTransformer(edit);inst.retransformClasses(PlainWork.class);}SpringOperationSourceReloader.clearSpringAnnotationCaches(PlainWork.class);RestartLedger.clear();}
    }
    public static class AspectWork {public String read(){return "plain";}}
    public static class AspectHolder {final AspectWork work;AspectHolder(AspectWork work){this.work=work;}}
    @org.aspectj.lang.annotation.Aspect public static class ToggleAspect {
        @org.aspectj.lang.annotation.Around("execution(* absent(..))")public Object around(org.aspectj.lang.ProceedingJoinPoint call)throws Throwable{return "advised:"+call.proceed();}
    }
    @Configuration(proxyBeanMethods=false) @EnableAspectJAutoProxy(proxyTargetClass=true) public static class AspectConfig {
        @Bean AspectWork aspectWork(){return new AspectWork();}@Bean AspectHolder aspectHolder(AspectWork work){return new AspectHolder(work);}@Bean ToggleAspect toggleAspect(){return new ToggleAspect();}
    }
    @Test void newlyMatchingAspectRecreatesThePlainTargetAndItsConstructorConsumer()throws Exception {
        Instrumentation inst=ByteBuddyAgent.install();ClassFileTransformer edit=null;RestartLedger.clear();
        try(var ctx=new AnnotationConfigApplicationContext(AspectConfig.class)) {
            AspectWork old=ctx.getBean(AspectWork.class);var holder=ctx.getBean(AspectHolder.class);assertFalse(AopUtils.isAopProxy(old));assertEquals("plain",old.read());
            edit=new ClassFileTransformer(){@Override public byte[] transform(ClassLoader l,String n,Class<?> type,java.security.ProtectionDomain d,byte[] bytes){
                if(type!=ToggleAspect.class)return null;var writer=new ClassWriter(0);new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9,writer){
                    @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){return new MethodVisitor(Opcodes.ASM9,super.visitMethod(a,n,d,s,e)){
                        @Override public AnnotationVisitor visitAnnotation(String desc,boolean visible){var delegate=super.visitAnnotation(desc,visible);if(!desc.equals("Lorg/aspectj/lang/annotation/Around;"))return delegate;
                            return new AnnotationVisitor(Opcodes.ASM9,delegate){@Override public void visit(String name,Object value){super.visit(name,name.equals("value")?"execution(* read(..))":value);}};}
                    };}
                },0);return writer.toByteArray();}};inst.addTransformer(edit,true);inst.retransformClasses(ToggleAspect.class);
            PlatformContext platform=(PlatformContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},(o,m,a)->m.getName().equals("getAllApplicationContexts")?List.of(ctx):null);
            var reloader=new SpringAopReloader(platform);reloader.setLoadedClasses(()->java.util.Arrays.stream(inst.getAllLoadedClasses()).filter(c->c.getName().startsWith(getClass().getName())).toArray(Class<?>[]::new));assertTrue(reloader.reloadAopProxies(ToggleAspect.class));
            AspectWork current=ctx.getBean(AspectWork.class);assertTrue(AopUtils.isAopProxy(current));assertNotSame(old,current);assertEquals("advised:plain",current.read());assertNotSame(holder,ctx.getBean(AspectHolder.class));assertSame(current,ctx.getBean(AspectHolder.class).work);
            assertEquals(List.of("Nothing from this session needs a restart."),RestartLedger.digest());
        }finally{if(edit!=null){inst.removeTransformer(edit);inst.retransformClasses(ToggleAspect.class);}SpringOperationSourceReloader.clearSpringAnnotationCaches(ToggleAspect.class);RestartLedger.clear();}
    }

}
