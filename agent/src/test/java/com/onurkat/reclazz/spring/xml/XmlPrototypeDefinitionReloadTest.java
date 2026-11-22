/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.config.TypedStringValue;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

public class XmlPrototypeDefinitionReloadTest {
    @TempDir Path temp;
    public static class Value {static int created;String text;public Value(){created++;}public void setText(String value){text=value;}}
    public static class Closing extends Value implements DisposableBean {static int closed;public void destroy(){closed++;}}
    public static class FutureFailure extends Value {@Override public void setText(String text){if(text.equals("bad"))throw new IllegalArgumentException("synthetic future failure");super.setText(text);}}
    public static class Cold extends Value {static{System.setProperty("r30.prototype.initialized","yes");}}
    public static class Holder implements DisposableBean {static int closed;final Value value;public Holder(Value value){this.value=value;}public void destroy(){closed++;}}
    public static class Recipe extends Value implements InitializingBean {static int initialized;public Recipe(String text){this.text=text;}public void afterPropertiesSet(){initialized++;text+=":init";}}
    public static class Dependency extends Value {}
    public static class Injected {Dependency dependency;@org.springframework.beans.factory.annotation.Autowired public void setDependency(@org.springframework.beans.factory.annotation.Qualifier("dependency") Dependency value){dependency=value;}}
    static volatile boolean publicationFailure;
    public static void failPublication(String name){if(publicationFailure&&name.equals("other"))throw new IllegalStateException("synthetic metadata publication failure");}
    @BeforeEach void before(){ApplicationContextHolder.clear();RestartLedger.clear();Value.created=Closing.closed=Holder.closed=Recipe.initialized=0;publicationFailure=false;System.clearProperty("r30.prototype.initialized");}
    @AfterEach void after(){ApplicationContextHolder.clear();RestartLedger.clear();System.clearProperty("r30.prototype.initialized");}
    static String bean(Class<?> type,String value){return "<bean id='value' class='"+type.getName()+"' scope='prototype'><property name='text' value='"+value+"'/></bean>";}
    static String parent(String value){return "<bean id='template' class='"+Value.class.getName()+"' scope='prototype' abstract='true'><property name='text' value='"+value+"'/></bean>";}
    @Test void editOnlyChangesFuturePrototypeInstancesAndConstructsNothingAtReload()throws Exception {
        try(var s=new XmlDefinitionTestScope(temp.resolve("prototype.xml"),bean(Value.class,"before"))){
            Value old=s.context.getBean("value",Value.class);Object raw=s.context.getBeanFactory().getBeanDefinition("value");int count=Value.created;
            s.reload(bean(Value.class,"after"));assertFalse(s.warned(),s.events.toString());assertEquals(count,Value.created);assertEquals("before",old.text);
            assertEquals("before",((TypedStringValue)((org.springframework.beans.factory.config.BeanDefinition)raw).getPropertyValues().get("text")).getValue());
            Value fresh=s.context.getBean("value",Value.class);assertNotSame(old,fresh);assertEquals("after",fresh.text);
            s.reload(bean(Value.class,"before"));assertFalse(s.warned(),s.events.toString());assertEquals("before",s.context.getBean("value",Value.class).text);assertEquals("after",fresh.text);
        }
    }
    @Test void unchangedPrototypeKeepsRawAndMergedIdentity()throws Exception {
        try(var s=new XmlDefinitionTestScope(temp.resolve("noop.xml"),bean(Value.class,"before"))){var f=s.context.getBeanFactory();Object raw=f.getBeanDefinition("value"),merged=f.getMergedBeanDefinition("value");s.reload(bean(Value.class,"before"));assertFalse(s.warned(),s.events.toString());assertSame(raw,f.getBeanDefinition("value"));assertSame(merged,f.getMergedBeanDefinition("value"));assertEquals(0,Value.created);}
    }
    @Test void coldPrototypeClassIsNotInitializedUntilFutureGetBean()throws Exception {
        try(var s=new XmlDefinitionTestScope(temp.resolve("cold.xml"),bean(Cold.class,"before"))){assertNull(System.getProperty("r30.prototype.initialized"));s.reload(bean(Cold.class,"after"));assertFalse(s.warned(),s.events.toString());assertNull(System.getProperty("r30.prototype.initialized"));assertEquals("after",s.context.getBean("value",Value.class).text);assertEquals("yes",System.getProperty("r30.prototype.initialized"));}
    }
    @Test void prototypeParentEditInvalidatesMergedPrototypeChildren()throws Exception {
        String child="<bean id='child' parent='template' scope='prototype'/>";
        try(var s=new XmlDefinitionTestScope(temp.resolve("parent.xml"),parent("before")+child)){
            Value old=s.context.getBean("child",Value.class);int count=Value.created;s.reload(parent("after")+child);assertFalse(s.warned(),s.events.toString());assertEquals(count,Value.created);assertEquals("before",old.text);assertEquals("after",s.context.getBean("child",Value.class).text);
        }
    }
    @Test void liveSingletonDescendantRefusesBeforeAnyDestroyOrDefinitionMutation()throws Exception {
        String child="<bean id='child' parent='template' class='"+Closing.class.getName()+"' scope='singleton'/>";
        try(var s=new XmlDefinitionTestScope(temp.resolve("held-child.xml"),parent("before")+child)){
            var f=s.context.getBeanFactory();Object raw=f.getBeanDefinition("template"),merged=f.getMergedBeanDefinition("template"),held=s.context.getBean("child");s.reload(parent("after")+child);
            assertTrue(s.warned());assertSame(raw,f.getBeanDefinition("template"));assertSame(merged,f.getMergedBeanDefinition("template"));assertSame(held,s.context.getBean("child"));assertEquals(0,Closing.closed);assertTrue(s.events.stream().anyMatch(e->e.contains("child")),s.events.toString());
        }
    }
    @Test void liveChildInOneOwnerPreventsPublishingTheSamePrototypeEditInAnother()throws Exception {
        Path xml=temp.resolve("shared.xml");try(var a=new XmlDefinitionTestScope(xml,parent("before"));var b=new XmlDefinitionTestScope(xml,parent("before"))){
            var child=new GenericBeanDefinition();child.setParentName("template");child.setBeanClass(Closing.class);child.setScope("singleton");b.context.registerBeanDefinition("heldChild",child);Object held=b.context.getBean("heldChild");Object rawA=a.context.getBeanFactory().getBeanDefinition("template"),rawB=b.context.getBeanFactory().getBeanDefinition("template");
            a.reload(parent("after"));assertTrue(a.warned());assertSame(rawA,a.context.getBeanFactory().getBeanDefinition("template"));assertSame(rawB,b.context.getBeanFactory().getBeanDefinition("template"));assertSame(held,b.context.getBean("heldChild"));assertEquals(0,Closing.closed);
        }
    }
    @Test void futurePrototypeConstructorFailureDoesNotUndoPublishedDefinitionOrOldInstance()throws Exception {
        try(var s=new XmlDefinitionTestScope(temp.resolve("future.xml"),bean(FutureFailure.class,"before"))){Value held=s.context.getBean("value",Value.class);int count=Value.created;s.reload(bean(FutureFailure.class,"bad"));assertFalse(s.warned(),s.events.toString());assertEquals(count,Value.created);assertThrows(org.springframework.beans.BeansException.class,()->s.context.getBean("value"));assertEquals("before",held.text);assertEquals("bad",((TypedStringValue)s.context.getBeanFactory().getBeanDefinition("value").getPropertyValues().get("text")).getValue());}
    }
    @Test void existingSingletonConsumerAndItsPrototypeAreNeverDestroyedOrRewired()throws Exception {
        String holder="<bean id='holder' class='"+Holder.class.getName()+"'><constructor-arg ref='value'/></bean>";
        try(var s=new XmlDefinitionTestScope(temp.resolve("consumer.xml"),bean(Value.class,"before")+holder)){
            Holder held=s.context.getBean("holder",Holder.class);Value original=held.value;int created=Value.created;s.reload(bean(Value.class,"after")+holder);
            assertFalse(s.warned(),s.events.toString());assertEquals(0,Holder.closed);assertEquals(created,Value.created);assertSame(held,s.context.getBean("holder"));assertSame(original,held.value);assertEquals("before",original.text);assertEquals("after",s.context.getBean("value",Value.class).text);
        }
    }
    @Test void prototypeChildInAnotherFactoryGetsFreshMergedParentValues()throws Exception {
        try(var parent=new XmlDefinitionTestScope(temp.resolve("parent-context.xml"),parent("before"));var child=new XmlDefinitionTestScope(temp.resolve("child-context.xml"),"<bean id='child' parent='template' scope='prototype'/>",parent.context)){
            Value old=child.context.getBean("child",Value.class);int created=Value.created;parent.reload(parent("after"));assertFalse(parent.warned(),parent.events.toString());assertEquals(created,Value.created);assertEquals("before",old.text);assertEquals("after",child.context.getBean("child",Value.class).text);
        }
    }
    @Test void constructorAndInitializationChangesRunOnlyOnFuturePrototypeCreation()throws Exception {
        String before="<bean id='value' class='"+Recipe.class.getName()+"' scope='prototype'><constructor-arg value='before'/></bean>";
        try(var s=new XmlDefinitionTestScope(temp.resolve("recipe.xml"),before)){
            Value old=s.context.getBean("value",Value.class);assertEquals("before:init",old.text);int created=Value.created,initialized=Recipe.initialized;s.reload(before.replace("value='before'","value='after'"));assertFalse(s.warned(),s.events.toString());assertEquals(created,Value.created);assertEquals(initialized,Recipe.initialized);assertEquals("before:init",old.text);assertEquals("after:init",s.context.getBean("value",Value.class).text);
        }
    }
    @Test void futureAutowiredSetterResolvesAgainWhenAnXmlOverrideIsRemoved()throws Exception {
        String infrastructure="<context:annotation-config xmlns:context='http://www.springframework.org/schema/context'/><bean id='dependency' class='"+Dependency.class.getName()+"'><property name='text' value='native'/></bean><bean id='explicit' class='"+Dependency.class.getName()+"'><property name='text' value='xml'/></bean>";
        String override="<bean id='value' class='"+Injected.class.getName()+"' scope='prototype'><property name='dependency' ref='explicit'/></bean>";
        try(var s=new XmlDefinitionTestScope(temp.resolve("injection.xml"),infrastructure+override)){
            Injected old=s.context.getBean("value",Injected.class);assertEquals("xml",old.dependency.text);s.reload(infrastructure+override.replace("<property name='dependency' ref='explicit'/>",""));assertFalse(s.warned(),s.events.toString());assertEquals("xml",old.dependency.text);assertEquals("native",s.context.getBean("value",Injected.class).dependency.text);
        }
    }
    @Test void failureAfterRawPublicationRestoresBothRawAndMergedIdentities()throws Exception {
        var inst=net.bytebuddy.agent.ByteBuddyAgent.install();java.lang.instrument.ClassFileTransformer fault=null;
        String beans=bean(Value.class,"before")+bean(Value.class,"before").replace("id='value'","id='other'");
        try(var s=new XmlDefinitionTestScope(temp.resolve("rollback.xml"),beans)){
            var f=s.context.getBeanFactory();Object valueRaw=f.getBeanDefinition("value"),otherRaw=f.getBeanDefinition("other"),valueMerged=f.getMergedBeanDefinition("value"),otherMerged=f.getMergedBeanDefinition("other");
            fault=new java.lang.instrument.ClassFileTransformer(){@Override public byte[] transform(ClassLoader l,String n,Class<?> type,java.security.ProtectionDomain domain,byte[] bytes){
                if(type!=org.springframework.beans.factory.support.AbstractBeanFactory.class)return null;
                var writer=new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9,writer){
                    @Override public org.objectweb.asm.MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions){var next=super.visitMethod(access,name,desc,signature,exceptions);if(!name.equals("clearMergedBeanDefinition")||!desc.equals("(Ljava/lang/String;)V"))return next;return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9,next){@Override public void visitCode(){super.visitCode();super.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD,1);super.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC,"com/onurkat/reclazz/spring/xml/XmlPrototypeDefinitionReloadTest","failPublication","(Ljava/lang/String;)V",false);}};}
                },0);return writer.toByteArray();}};
            inst.addTransformer(fault,true);inst.retransformClasses(org.springframework.beans.factory.support.AbstractBeanFactory.class);publicationFailure=true;s.reload(beans.replace("value='before'","value='after'"));publicationFailure=false;
            assertTrue(s.warned(),s.events.toString());assertTrue(s.events.stream().anyMatch(e->e.contains("publication failed")));assertFalse(s.events.stream().anyMatch(e->e.contains("restoration incomplete")),s.events.toString());
            assertSame(valueRaw,f.getBeanDefinition("value"));assertSame(otherRaw,f.getBeanDefinition("other"));assertSame(valueMerged,f.getMergedBeanDefinition("value"));assertSame(otherMerged,f.getMergedBeanDefinition("other"));assertEquals(0,Value.created);assertEquals("before",s.context.getBean("value",Value.class).text);
        }finally{publicationFailure=false;if(fault!=null){inst.removeTransformer(fault);inst.retransformClasses(org.springframework.beans.factory.support.AbstractBeanFactory.class);}}
    }

    @Test void unrelatedClosedContextDoesNotBlockAnActivePrototypeOwner()throws Exception {
        var closed=new org.springframework.context.support.GenericApplicationContext();closed.refresh();ApplicationContextHolder.register(closed);closed.close();
        try(var live=new XmlDefinitionTestScope(temp.resolve("active.xml"),bean(Value.class,"before"))){live.reload(bean(Value.class,"after"));assertFalse(live.warned(),live.events.toString());assertEquals("after",live.context.getBean("value",Value.class).text);}
    }
    public static class UnknownResetter implements org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor {
        static int resets;public void postProcessMergedBeanDefinition(org.springframework.beans.factory.support.RootBeanDefinition d,Class<?> t,String n){}
        public void resetBeanDefinition(String name){resets++;throw new IllegalStateException("custom reset must not execute");}
    }
    @Test void unverifiedCustomResetCallbackIsRefusedBeforePublicationOrCallbackExecution()throws Exception {
        try(var s=new XmlDefinitionTestScope(temp.resolve("custom-reset.xml"),bean(Value.class,"before"))){var f=s.context.getBeanFactory();Object raw=f.getBeanDefinition("value"),merged=f.getMergedBeanDefinition("value");UnknownResetter.resets=0;f.addBeanPostProcessor(new UnknownResetter());s.reload(bean(Value.class,"after"));assertTrue(s.warned());assertEquals(0,UnknownResetter.resets);assertSame(raw,f.getBeanDefinition("value"));assertSame(merged,f.getMergedBeanDefinition("value"));}
    }

    @Test void inheritedPrototypeChildWithoutAnExplicitScopeIsNotMistakenForACreatedSingleton()throws Exception {
        String child="<bean id='child' parent='template'/>";
        try(var s=new XmlDefinitionTestScope(temp.resolve("inherited-prototype.xml"),parent("before")+child)){
            var f=s.context.getBeanFactory();Value old=s.context.getBean("child",Value.class);assertEquals("prototype",f.getMergedBeanDefinition("child").getScope());int created=Value.created;
            s.reload(parent("after")+child);assertFalse(s.warned(),s.events.toString());assertEquals(created,Value.created);assertEquals("before",old.text);assertEquals("after",s.context.getBean("child",Value.class).text);
        }
    }

}
