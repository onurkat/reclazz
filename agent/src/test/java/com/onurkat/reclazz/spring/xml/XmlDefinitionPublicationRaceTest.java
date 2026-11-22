/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.AbstractBeanFactory;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.lang.instrument.*;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

public class XmlDefinitionPublicationRaceTest {
 @TempDir Path temp;
 public static class Value {String text;public void setText(String value){text=value;}}
 public static class First {}
 public static class Second {}
 public static class Listener extends Value implements org.springframework.context.ApplicationListener<org.springframework.context.ApplicationEvent> {static int calls;public void onApplicationEvent(org.springframework.context.ApplicationEvent e){calls++;}}
 static volatile Thread reloadThread,getterThread;
 static volatile CountDownLatch startGetter,mergedCaptured,releaseGetter,getterDone;
 static final AtomicBoolean armed=new AtomicBoolean();static volatile boolean instanceAppears,invalidationDone;static int gapUnlocks;
 public static void beforeRawPut(){
  if(Thread.currentThread()!=reloadThread||!armed.compareAndSet(true,false))return;
  startGetter.countDown();await(mergedCaptured,"getter did not capture frozen merged definition");
 }
 public static void afterMerged(Object merged,String name){
  if(Thread.currentThread()!=getterThread||!name.equals("value"))return;
  mergedCaptured.countDown();await(releaseGetter,"getter release missing");
 }
 public static void afterInvalidate(){
  if(Thread.currentThread()!=reloadThread)return;invalidationDone=true;
  if(instanceAppears)throw new IllegalStateException("synthetic post-invalidation failure");
 }
 public static void afterUnlock(){if(Thread.currentThread()==reloadThread&&startGetter!=null&&startGetter.getCount()==0&&!invalidationDone)gapUnlocks++;}
 public static void beforeRollback(){if(Thread.currentThread()==reloadThread&&instanceAppears){releaseGetter.countDown();await(getterDone,"racing singleton did not complete before rollback");}}
 static void await(CountDownLatch latch,String message){try{if(!latch.await(8,TimeUnit.SECONDS))throw new IllegalStateException(message);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
 @BeforeEach void before(){ApplicationContextHolder.clear();RestartLedger.clear();Listener.calls=0;armed.set(false);reloadThread=getterThread=null;startGetter=null;instanceAppears=false;invalidationDone=false;gapUnlocks=0;}
 @AfterEach void after(){armed.set(false);if(releaseGetter!=null)releaseGetter.countDown();ApplicationContextHolder.clear();RestartLedger.clear();reloadThread=getterThread=null;}
 static String beans(String dependency,String text){return "<bean id='a' class='"+First.class.getName()+"' lazy-init='true'/><bean id='b' class='"+Second.class.getName()+"' lazy-init='true'/><bean id='value' class='"+Value.class.getName()+"' lazy-init='true' depends-on='"+dependency+"'><property name='text' value='"+text+"'/></bean>";}
 @Test void markCreatedBetweenAdmissionAndRawPublicationCannotBeReportedAsApplied()throws Exception {race(false);}
 @Test void singletonAppearingDuringMetadataRollbackNeedsRestartWithoutFalseIncompleteRestoration()throws Exception {race(true);}
 void race(boolean appears)throws Exception {
  try(var s=new XmlDefinitionTestScope(temp.resolve("race.xml"),beans("a","before"))){
   var f=s.context.getBeanFactory();Object raw=f.getBeanDefinition("value"),merged=f.getMergedBeanDefinition("value");assertTrue(f.isConfigurationFrozen());
   startGetter=new CountDownLatch(1);mergedCaptured=new CountDownLatch(1);releaseGetter=new CountDownLatch(1);getterDone=new CountDownLatch(1);instanceAppears=appears;reloadThread=Thread.currentThread();AtomicReference<Object> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
   getterThread=new Thread(()->{try{await(startGetter,"publication never started");result.set(s.context.getBean("value"));}catch(Throwable e){failure.set(e);}finally{getterDone.countDown();}},"r30-cold-getter");
   try(var hooks=new Hooks()){
    getterThread.start();armed.set(true);
    try{s.reload(beans("b","after"));}finally{armed.set(false);releaseGetter.countDown();getterThread.join(10000);}
    assertFalse(getterThread.isAlive());assertNull(failure.get(),String.valueOf(failure.get()));assertNotNull(result.get());
    assertTrue(s.warned(),s.events.toString());assertTrue(s.events.stream().anyMatch(e->e.contains("restart")),s.events.toString());
    assertSame(raw,f.getBeanDefinition("value"));assertSame(merged,f.getMergedBeanDefinition("value"));assertEquals("before",((Value)result.get()).text);
    assertFalse(s.events.stream().anyMatch(e->e.contains("restoration incomplete")),s.events.toString());assertEquals(0,gapUnlocks,"native singleton lock released between raw put and merged invalidation");
   }
  }finally{reloadThread=getterThread=null;startGetter=null;}
 }
 @ParameterizedTest @ValueSource(booleans={true,false})
 void changingBetweenPrototypeListenerAndPlainTypeKeepsNativeEventRegistration(boolean listenerBefore)throws Exception {
  Class<?> old=listenerBefore?Listener.class:Value.class,next=listenerBefore?Value.class:Listener.class;String prefix="<bean id='value' class='",suffix="' scope='prototype'><property name='text' value='before'/></bean>";
  try(var s=new XmlDefinitionTestScope(temp.resolve("listener.xml"),prefix+old.getName()+suffix)){
   var f=s.context.getBeanFactory();Object raw=f.getBeanDefinition("value"),merged=f.getMergedBeanDefinition("value");int calls=Listener.calls;
   s.reload(prefix+next.getName()+suffix.replace("before","after"));
   assertDoesNotThrow(()->s.context.publishEvent(new org.springframework.context.ApplicationEvent("synthetic"){}));
   assertTrue(s.warned(),s.events.toString());assertSame(raw,f.getBeanDefinition("value"));assertSame(merged,f.getMergedBeanDefinition("value"));assertEquals(calls+(listenerBefore?1:0),Listener.calls);
  }
 }
 static final class Hooks implements AutoCloseable {
  final Instrumentation inst=net.bytebuddy.agent.ByteBuddyAgent.install();final Class<?> txn=XmlDefinitionTransaction.class,nativeFactory=AbstractBeanFactory.class;final ClassFileTransformer hook;
  Hooks()throws Exception {
   hook=new ClassFileTransformer(){@Override public byte[] transform(ClassLoader l,String n,Class<?> type,java.security.ProtectionDomain d,byte[] bytes){
    if(type!=txn&&type!=nativeFactory)return null;ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9,writer){
     @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions){MethodVisitor next=super.visitMethod(access,name,desc,signature,exceptions);boolean nativeMerged=type==nativeFactory&&name.equals("getMergedLocalBeanDefinition");if(type==nativeFactory&&!nativeMerged)return next;
      return new MethodVisitor(Opcodes.ASM9,next){
       void call(String method){super.visitMethodInsn(Opcodes.INVOKESTATIC,"com/onurkat/reclazz/spring/xml/XmlDefinitionPublicationRaceTest",method,"()V",false);}
       @Override public void visitCode(){super.visitCode();if(type==txn&&name.equals("rollback"))call("beforeRollback");}
       @Override public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean itf){if(type==txn&&owner.equals("java/util/Map")&&method.equals("put"))call("beforeRawPut");super.visitMethodInsn(opcode,owner,method,descriptor,itf);if(type==txn&&name.equals("locked")&&method.equals("unlock"))call("afterUnlock");}
       @Override public void visitInsn(int opcode){if(nativeMerged&&opcode==Opcodes.ARETURN){super.visitInsn(Opcodes.DUP);super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"com/onurkat/reclazz/spring/xml/XmlDefinitionPublicationRaceTest","afterMerged","(Ljava/lang/Object;Ljava/lang/String;)V",false);}if(type==txn&&name.equals("invalidate")&&opcode==Opcodes.RETURN)call("afterInvalidate");super.visitInsn(opcode);if(type==txn&&name.equals("locked")&&opcode==Opcodes.MONITOREXIT)call("afterUnlock");}
      };
     }
    },0);return writer.toByteArray();}
   };
   inst.addTransformer(hook,true);inst.retransformClasses(txn,nativeFactory);
  }
  public void close()throws Exception {armed.set(false);releaseGetter.countDown();inst.removeTransformer(hook);inst.retransformClasses(txn,nativeFactory);}
 }
}
