/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.transform.KeepBaseline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class SingletonKeepPolicyTest {
    @TempDir Path tmp;
    int compilation;
    byte[] compile(String body) throws Exception { return compileType("public class Bean",body); }
    byte[] compileType(String declaration,String body) throws Exception {
        Path dir=Files.createDirectories(tmp.resolve("compile"+(compilation++)));
        Path source=dir.resolve("Bean.java");
        Files.writeString(source,"package example; "+declaration+" { "+body+" }");
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-g","-classpath",System.getProperty("java.class.path"),"-d",dir.toString(),source.toString()));
        return Files.readAllBytes(dir.resolve("example/Bean.class"));
    }
    private SingletonKeepPolicy.Decision decision(String old,String next,Set<String> creation,boolean cglib)throws Exception {
        return SingletonKeepPolicy.compare(new KeepBaseline.Input(KeepBaseline.fingerprint(compile(old)),compile(next)),creation,cglib);
    }
    @Test void bodyEditAndAddedMethodKeep()throws Exception {
        assertTrue(decision("public int value(){return 1;}","public int value(){return 2;} public int added(){return 3;}",Set.of(),false).keep());
    }
    @Test void methodRemovalAndSignatureChangesStayOutsideRetentionScope() throws Exception {
        for (boolean cglib : List.of(false, true)) {
            var removed = decision("public void callback(){}", "", Set.of(), cglib);
            assertFalse(removed.keep()); assertTrue(removed.reason().contains("method removed"));
            assertFalse(decision("public void callback(){}", "public void callback(int value){}", Set.of(), cglib).keep());
        }
    }
    @Test void businessBodyCanRemoveACompilerGeneratedLambdaWithoutRecreating() throws Exception {
        assertTrue(decision("public Runnable value(){return () -> System.out.println(1);}",
                "public Runnable value(){return null;}", Set.of(), false).keep());
    }
    @Test void existingMemberMetadataChangesRecreateButAnnotatedAdditionsMayKeep() throws Exception {
        String scheduled="@org.springframework.scheduling.annotation.Scheduled(fixedDelay=25) public void tick(){} public int value(){return 1;}";
        assertFalse(decision(scheduled,scheduled.replace("fixedDelay=25","fixedDelay=40"),Set.of(),false).keep());
        assertTrue(decision(scheduled,scheduled.replace("return 1","return 2"),Set.of(),false).keep());
        assertFalse(decision("public int field;","@Deprecated public int field;",Set.of(),false).keep());
        assertTrue(decision("public int value(){return 1;}","public int value(){return 2;} @org.springframework.web.bind.annotation.GetMapping public int added(){return 3;}",Set.of(),false).keep());
    }
    @Test void constructorEditRecreates()throws Exception {
        assertFalse(decision("int count;","int count; public Bean(){count=5;}",Set.of(),false).keep());
    }
    @Test void liftedFieldWithoutOtherConstructorChangeKeeps()throws Exception {
        assertTrue(decision("int count;","int count; java.util.List<String> tags=new java.util.ArrayList<>();",Set.of(),false).keep());
    }
    @Test void liftedFieldPlusConstructorSideEffectRecreates()throws Exception {
        assertFalse(decision("int count;","int count; java.util.List<String> tags=new java.util.ArrayList<>(); public Bean(){count=5;}",Set.of(),false).keep());
    }
    @Test void constructorParameterInitializerRecreates()throws Exception {
        assertFalse(decision("public Bean(int value){}","int added; public Bean(int value){added=value;}",Set.of(),false).keep());
    }
    @Test void allConstructorRoutesMustMatch()throws Exception {
        String old="public Bean(){} public Bean(int ignored){}";
        String next="java.util.List<String> tags=new java.util.ArrayList<>(); public Bean(){} public Bean(int ignored){}";
        assertTrue(decision(old,next,Set.of(),false).keep());
        assertFalse(decision(old,next.replace("public Bean(int ignored){}","public Bean(int ignored){System.setProperty(\"test\",\"changed\");}"),Set.of(),false).keep());
    }
    @Test void removedAndRetypedFieldsRecreate()throws Exception {
        assertFalse(decision("int count;","",Set.of(),false).keep());
        assertFalse(decision("int count;","long count;",Set.of(),false).keep());
    }
    @Test void injectionAndLifecycleMetadataChangesRecreate()throws Exception {
        assertFalse(decision("Object context;","@org.springframework.beans.factory.annotation.Autowired Object context;",Set.of(),false).keep());
        assertFalse(decision("public void init(){}","@javax.annotation.PostConstruct public void init(){}",Set.of(),false).keep());
    }
    @Test void classAnnotationChangesRecreate()throws Exception {
        byte[] a=compile("");
        var node=KeepBaseline.read(a);node.visibleAnnotations=new ArrayList<>();node.visibleAnnotations.add(new org.objectweb.asm.tree.AnnotationNode("Ljava/lang/Deprecated;"));
        var writer=new org.objectweb.asm.ClassWriter(0);node.accept(writer);
        assertFalse(SingletonKeepPolicy.compare(new KeepBaseline.Input(KeepBaseline.fingerprint(a),writer.toByteArray()),Set.of(),false).keep());
    }
    @Test void callbackBodiesAreComparedButOrdinaryBodiesMayChange()throws Exception {
        String old="public int init(){return 3;} public int value(){return 1;}";
        assertFalse(decision(old,old.replace("return 3","return 7"),Set.of("init:()I"),false).keep());
        assertTrue(decision(old,old.replace("return 1","return 2"),Set.of("init:()I"),false).keep());
    }
    @Test void missingBaselineAndCreationDigestRecreate()throws Exception {
        assertFalse(SingletonKeepPolicy.compare(new KeepBaseline.Input(null,compile("")),Set.of(),false).keep());
        assertFalse(decision("","",Set.of("inherited:()V"),false).keep());
    }
    @Test void cglibNewPublicMethodRecreatesWhileBodyEditKeeps()throws Exception {
        assertFalse(decision("","public void added(){}",Set.of(),true).keep());
        assertTrue(decision("public int value(){return 1;}","public int value(){return 2;}",Set.of(),true).keep());
    }
    static class BeanLoader extends ClassLoader {
        BeanLoader() { super(SingletonKeepPolicyTest.class.getClassLoader()); }
        Class<?> define(byte[] bytes) { return defineClass("example.Bean",bytes,0,bytes.length); }
    }
    private SingletonKeepPolicy.Decision beanDecision(String declaration,String old,String next,
            java.util.function.Consumer<org.springframework.beans.factory.support.RootBeanDefinition> metadata) throws Exception {
        byte[] before=compileType(declaration,old), after=compileType(declaration,next);
        Class<?> type=new BeanLoader().define(before);
        var definition=new org.springframework.beans.factory.support.RootBeanDefinition(type);metadata.accept(definition);
        var factory=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        factory.registerBeanDefinition("bean",definition);
        factory.registerSingleton("bean",type.getConstructor().newInstance());
        return SingletonKeepPolicy.forBean(factory,"bean",type,new KeepBaseline.Input(KeepBaseline.fingerprint(before),after));
    }
    @Test void everyAnnotationCreationSourceDetectsBodyEdits() throws Exception {
        for(String annotation:List.of("javax.annotation.PostConstruct","javax.annotation.PreDestroy","org.springframework.beans.factory.annotation.Autowired","javax.annotation.Resource","org.springframework.beans.factory.annotation.Value(\"text\")")) {
            String old="int flag; @"+annotation+" public void callback(String value){flag=3;} public int value(){return 1;}";
            // Lifecycle callbacks use the native no-argument shape.
            if(annotation.contains("Construct")||annotation.contains("Destroy"))old=old.replace("String value","");
            assertFalse(beanDecision("public class Bean",old,old.replace("flag=3","flag=7"),d->{}).keep(),annotation);
            assertTrue(beanDecision("public class Bean",old,old.replace("return 1","return 2"),d->{}).keep(),annotation);
        }
    }
    @Test void injectDescriptorsDetectCreationEditsWithoutAddingApiDependencies() throws Exception {
        for(String descriptor:List.of("Ljavax/inject/Inject;","Ljakarta/inject/Inject;","Ljakarta/annotation/Resource;","Ljakarta/annotation/PostConstruct;","Ljakarta/annotation/PreDestroy;")) {
            byte[][] raw={compile("int flag; public void callback(){flag=3;}"),compile("int flag; public void callback(){flag=7;}")};
            for(int i=0;i<raw.length;i++) {
                var node=KeepBaseline.read(raw[i]);
                var callback=node.methods.stream().filter(m->m.name.equals("callback")).findFirst().orElseThrow();
                callback.visibleAnnotations=new ArrayList<>(List.of(new org.objectweb.asm.tree.AnnotationNode(descriptor)));
                var writer=new org.objectweb.asm.ClassWriter(0);node.accept(writer);raw[i]=writer.toByteArray();
            }
            Class<?> type=new BeanLoader().define(raw[0]);
            var factory=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
            factory.registerBeanDefinition("bean",new org.springframework.beans.factory.support.RootBeanDefinition(type));factory.registerSingleton("bean",type.getConstructor().newInstance());
            assertFalse(SingletonKeepPolicy.forBean(factory,"bean",type,new KeepBaseline.Input(KeepBaseline.fingerprint(raw[0]),raw[1])).keep(),descriptor);
        }
    }

    @Test void initializingAndDisposableBodiesRequireRecreation() throws Exception {
        for(String callback:List.of("afterPropertiesSet","destroy")) {
            String old="int flag; public void "+callback+"(){flag=3;}";
            assertFalse(beanDecision("public class Bean",old,old.replace("flag=3","flag=7"),d->{}).keep(),callback);
        }
    }
    @Test void configuredLifecycleAndInferredDestroyBodiesAreCreationCode() throws Exception {
        for(String callback:List.of("init","dispose","close","shutdown")) {
            String old="int flag; public void "+callback+"(){flag=3;} public int value(){return 1;}";
            java.util.function.Consumer<org.springframework.beans.factory.support.RootBeanDefinition> configure=d->{
                if(callback.equals("init"))d.setInitMethodName(callback);
                else d.setDestroyMethodName(callback.equals("dispose")?callback:"(inferred)");
            };
            assertFalse(beanDecision("public class Bean",old,old.replace("flag=3","flag=7"),configure).keep(),callback);
            assertTrue(beanDecision("public class Bean",old,old.replace("return 1","return 2"),configure).keep(),callback);
        }
    }
    @Test void configuredPropertySetterBodyIsCreationCode() throws Exception {
        String old="int flag; public void setLimit(int limit){flag=3;} public int value(){return 1;}";
        assertFalse(beanDecision("public class Bean",old,old.replace("flag=3","flag=7"),d->d.getPropertyValues().add("limit",3)).keep());
        assertTrue(beanDecision("public class Bean",old,old.replace("return 1","return 2"),d->d.getPropertyValues().add("limit",3)).keep());
        assertFalse(beanDecision("public class Bean",old,old,d->d.getPropertyValues().add("nested.limit",3)).keep());
    }
    @Test void awareInterfaceCallbacksAreCreationCode() throws Exception {
        Map<String,String> callbacks=Map.of(
                "org.springframework.beans.factory.BeanNameAware","setBeanName(String value)",
                "org.springframework.beans.factory.BeanFactoryAware","setBeanFactory(org.springframework.beans.factory.BeanFactory value)",
                "org.springframework.context.ApplicationContextAware","setApplicationContext(org.springframework.context.ApplicationContext value)",
                "org.springframework.context.EnvironmentAware","setEnvironment(org.springframework.core.env.Environment value)",
                "org.springframework.web.context.ServletContextAware","setServletContext(javax.servlet.ServletContext value)");
        for(var entry:callbacks.entrySet()) {
            String old="int flag; public void "+entry.getValue()+"{flag=3;} public int value(){return 1;}";
            String declaration="public class Bean implements "+entry.getKey();
            assertFalse(beanDecision(declaration,old,old.replace("flag=3","flag=7"),d->{}).keep(),entry.getKey());
            assertTrue(beanDecision(declaration,old,old.replace("return 1","return 2"),d->{}).keep(),entry.getKey());
        }
    }
    @Test void ambiguousDefinitionCreationModesFailClosed() throws Exception {
        for(int mode:List.of(1,2,3))assertFalse(beanDecision("public class Bean","","",d->d.setAutowireMode(mode)).keep());
        assertFalse(beanDecision("public class Bean","public Object lookup(){return null;}","public Object lookup(){return null;}",
                d->d.getMethodOverrides().addOverride(new org.springframework.beans.factory.support.LookupOverride("lookup","other"))).keep());
        assertFalse(beanDecision("public class Bean","public Object lookup(){return null;}","public Object lookup(){return null;}",
                d->d.getMethodOverrides().addOverride(new org.springframework.beans.factory.support.ReplaceOverride("lookup","other"))).keep());
        assertFalse(beanDecision("public class Bean","","",d->d.setFactoryMethodName("build")).keep());
        assertFalse(beanDecision("public class Bean extends java.util.ArrayList<Object>","","",d->d.setInitMethodName("size")).keep(),"inherited digest unavailable");
    }
    @Test void missingDefinitionAndWrongClassOwnershipFailClosed() throws Exception {
        byte[] raw=compile("");Class<?> type=new BeanLoader().define(raw);Object bean=type.getConstructor().newInstance();
        var factory=new org.springframework.beans.factory.support.DefaultListableBeanFactory();factory.registerSingleton("bean",bean);
        var input=new KeepBaseline.Input(KeepBaseline.fingerprint(raw),raw);
        assertFalse(SingletonKeepPolicy.forBean(factory,"bean",type,input).keep(),"no readable definition");
        factory.registerBeanDefinition("bean",new org.springframework.beans.factory.support.RootBeanDefinition(type));
        assertFalse(SingletonKeepPolicy.forBean(factory,"bean",String.class,input).keep(),"unrelated reloaded class");
    }
    @Test void factoryBeanAndDynamicTargetSourcesFailClosed() throws Exception {
        String declaration="public class Bean implements org.springframework.beans.factory.FactoryBean<Object>";
        String methods="public Object getObject(){return new Object();} public Class<?> getObjectType(){return Object.class;}";
        assertFalse(beanDecision(declaration,methods,methods,d->{}).keep());
        byte[] raw=compile("");Class<?> type=new BeanLoader().define(raw);Object bean=type.getConstructor().newInstance();
        var proxy=new org.springframework.aop.framework.ProxyFactory();proxy.setProxyTargetClass(true);
        proxy.setTargetSource(new org.springframework.aop.TargetSource() {
            public Class<?> getTargetClass(){return type;}public boolean isStatic(){return false;}
            public Object getTarget(){return bean;}public void releaseTarget(Object target){}
        });
        var factory=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        factory.registerBeanDefinition("bean",new org.springframework.beans.factory.support.RootBeanDefinition(type));factory.registerSingleton("bean",proxy.getProxy());
        assertFalse(SingletonKeepPolicy.forBean(factory,"bean",type,new KeepBaseline.Input(KeepBaseline.fingerprint(raw),raw)).keep());
    }

    @org.junit.jupiter.api.AfterEach void resetWatchedPredicate() { KeepBaseline.setWatchedPredicate(null); }
    private record Inherited(Class<?> type, byte[] before, byte[] after, byte[] base) { }
    private Inherited inherited(String baseDeclaration,String members,boolean intermediate) throws Exception {
        Path dir=Files.createDirectories(tmp.resolve("inherited"+(compilation++)));
        Files.writeString(dir.resolve("Base.java"),"package example; public class Base "+baseDeclaration+" { "+members+" }");
        Files.writeString(dir.resolve("Parent.java"),"package example; public class Parent extends Base { public Parent(){} }");
        String child="package example; public class Bean extends "+(intermediate?"Parent":"Base")+" { public int value(){return 1;} }";
        Files.writeString(dir.resolve("Bean.java"),child);
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",dir.toString(),dir.resolve("Base.java").toString(),dir.resolve("Parent.java").toString(),dir.resolve("Bean.java").toString()));
        byte[] base=Files.readAllBytes(dir.resolve("example/Base.class"));
        var loader=new ClassLoader(getClass().getClassLoader()) {
            Class<?> define(String name,byte[] raw){return defineClass(name,raw,0,raw.length);}
        };
        loader.define("example.Base",base);
        if(intermediate)loader.define("example.Parent",Files.readAllBytes(dir.resolve("example/Parent.class")));
        byte[] before=Files.readAllBytes(dir.resolve("example/Bean.class"));var type=loader.define("example.Bean",before);
        Files.writeString(dir.resolve("Bean.java"),child.replace("return 1","return 2"));
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",dir+java.io.File.pathSeparator+System.getProperty("java.class.path"),"-d",dir.toString(),dir.resolve("Bean.java").toString()));
        return new Inherited(type,before,Files.readAllBytes(dir.resolve("example/Bean.class")),base);
    }
    private SingletonKeepPolicy.Decision inheritedDecision(Inherited fixture,java.util.function.Consumer<org.springframework.beans.factory.support.RootBeanDefinition> metadata) throws Exception {
        var factory=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var definition=new org.springframework.beans.factory.support.RootBeanDefinition(fixture.type());metadata.accept(definition);
        factory.registerBeanDefinition("bean",definition);factory.registerSingleton("bean",fixture.type().getConstructor().newInstance());
        return SingletonKeepPolicy.forBean(factory,"bean",fixture.type(),new KeepBaseline.Input(KeepBaseline.fingerprint(fixture.before()),fixture.after()));
    }
    @Test void inheritedExternalCreationMembersKeep() throws Exception {
        KeepBaseline.setWatchedPredicate(name->false);
        for(String callback:List.of("setter","aware","initializing","annotation","init","dispose","close","shutdown")) {
            String declaration=callback.equals("aware")?"implements org.springframework.beans.factory.BeanNameAware":"";
            String method=switch(callback) {
                case "setter" -> "public void setLimit(int value){}";
                case "aware" -> "public void setBeanName(String name){}";
                case "initializing" -> "public void afterPropertiesSet(){}";
                case "annotation" -> "@javax.annotation.PostConstruct public void initialized(){}";
                default -> "public void "+callback+"(){}";
            };
            var fixture=inherited(declaration,method,false);
            var decision=inheritedDecision(fixture,d->{
                switch(callback) {
                    case "setter" -> d.getPropertyValues().add("limit",3);
                    case "init" -> d.setInitMethodName("init");
                    case "dispose" -> d.setDestroyMethodName("dispose");
                    case "close", "shutdown" -> d.setDestroyMethodName("(inferred)");
                }
            });
            assertTrue(decision.keep(),callback+": "+decision.reason());
        }
    }
    @Test void watchedNeverReloadedAncestorKeepsThenAnyAdvanceRefuses() throws Exception {
        var fixture=inherited("","public void setLimit(int value){}",false);
        Class<?> base=fixture.type().getSuperclass();
        KeepBaseline.capture(base.getClassLoader(),"example/Base",fixture.base());
        var kept=inheritedDecision(fixture,d->d.getPropertyValues().add("limit",3));assertTrue(kept.keep(),kept.reason());
        KeepBaseline.advance(base,fixture.base());
        var refused=inheritedDecision(fixture,d->d.getPropertyValues().add("limit",3));
        assertFalse(refused.keep());assertTrue(refused.reason().contains("watched superclass example.Base"),refused.reason());
    }
    @Test void watchedAncestorWithoutBaselineRefuses() throws Exception {
        var fixture=inherited("","public void setLimit(int value){}",false);
        assertFalse(inheritedDecision(fixture,d->d.getPropertyValues().add("limit",3)).keep());
    }
    @Test void intermediateWatchedParentWithoutCreationMethodsStillGuarded() throws Exception {
        var fixture=inherited("","public void setLimit(int value){}",true);
        KeepBaseline.setWatchedPredicate(name->name.equals("example/Parent"));
        Class<?> parent=fixture.type().getSuperclass();
        Path dir=tmp.resolve("inherited"+(compilation-1));byte[] raw=Files.readAllBytes(dir.resolve("example/Parent.class"));
        KeepBaseline.capture(parent.getClassLoader(),"example/Parent",raw);
        assertTrue(inheritedDecision(fixture,d->d.getPropertyValues().add("limit",3)).keep());
        KeepBaseline.advance(parent,new byte[]{0});
        var refused=inheritedDecision(fixture,d->d.getPropertyValues().add("limit",3));
        assertFalse(refused.keep());assertTrue(refused.reason().contains("example.Parent"),refused.reason());
    }

    @Test void unavailableCreationMethodStillRefusesWhenSingletonIsASubclass() throws Exception {
        KeepBaseline.setWatchedPredicate(name->false);
        var fixture=inherited("","public void setLimit(int value){}",false);
        var factory=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var definition=new org.springframework.beans.factory.support.RootBeanDefinition(fixture.type());
        definition.setInitMethodName("missingInit");factory.registerBeanDefinition("bean",definition);
        factory.registerSingleton("bean",fixture.type().getConstructor().newInstance());
        Class<?> base=fixture.type().getSuperclass();
        var input=new KeepBaseline.Input(KeepBaseline.fingerprint(fixture.base()),fixture.base());
        var decision=SingletonKeepPolicy.forBean(factory,"bean",base,input);
        assertFalse(decision.keep(),decision.reason());
        assertTrue(decision.reason().contains("missingInit"),decision.reason());
    }

    @Test void debugInformationDoesNotChangeFingerprint()throws Exception {
        byte[] bytes=compile("public int value(){return 1;}");
        var reader=new org.objectweb.asm.ClassReader(bytes);var writer=new org.objectweb.asm.ClassWriter(0);
        reader.accept(writer,org.objectweb.asm.ClassReader.SKIP_DEBUG|org.objectweb.asm.ClassReader.SKIP_FRAMES);
        assertEquals(KeepBaseline.fingerprint(bytes),KeepBaseline.fingerprint(writer.toByteArray()));
    }
}
