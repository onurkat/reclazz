/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.bootstrap.*;
import java.lang.invoke.*;import java.lang.reflect.*;import java.nio.file.Path;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;

class RecordInitializerOrderingTest {
 @TempDir Path tmp;
 @Test void bothConstructorsKeepTheCohortUntilEveryOrdinaryInitializerIsWritten()throws Exception{var h=new RecordComponentMetadataTest();h.tmp=tmp;h.runProbe(Probe.class,"RECORD_INITIALIZER_ORDER_OK");}
 public record Owner(int value){}
 public static class Caller {
  public int value;public Caller(int n){value=n;}public Caller(String n){value=n.length();}
  public static Caller preInt(Caller r,int n)throws Throwable{MemberForge.delegate(Caller.class,Object.class,"()V",new Object[0]);r.value=-100+n;return r;}
  public static Caller preString(Caller r,String n)throws Throwable{MemberForge.delegate(Caller.class,Object.class,"()V",new Object[0]);r.value=-200+n.length();return r;}
  public static Caller cohortInt(Caller r,int n)throws Throwable{MemberForge.delegate(Caller.class,Object.class,"()V",new Object[0]);r.value=100+n;return r;}
  public static Caller cohortString(Caller r,String n)throws Throwable{MemberForge.delegate(Caller.class,Object.class,"()V",new Object[0]);r.value=200+n.length();return r;}
  public static Caller ordinaryInt(Caller r,int n)throws Throwable{MemberForge.delegate(Caller.class,Object.class,"()V",new Object[0]);r.value=1000+n;return r;}
  public static Caller ordinaryString(Caller r,String n)throws Throwable{MemberForge.delegate(Caller.class,Object.class,"()V",new Object[0]);r.value=2000+n.length();return r;}
  public static Caller routeInt(Caller r,int n)throws Throwable{return(Caller)MemberForge.delegate(Caller.class,Caller.class,"(I)V",new Object[]{n});}
  public static Caller routeString(Caller r,String n)throws Throwable{return(Caller)MemberForge.delegate(Caller.class,Caller.class,"(Ljava/lang/String;)V",new Object[]{n});}
 }
 public static class Template {public Template(int n){}public Template(String n){}}
 public static class Probe {
  static int checks;
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
  static MethodHandle target(String name,Class<?> arg)throws Exception{return MethodHandles.lookup().findStatic(Caller.class,name,MethodType.methodType(Caller.class,Caller.class,arg));}
  public static void main(String[]args)throws Throwable {
   LookupCapture.trust(Probe.class);LookupCapture.seal();
   var metadata=RecordCallerMembersTest.Probe.metadata(Caller.class,Template.class);
   var first=MemberForge.constructor(Caller.class,metadata.getDeclaredConstructor(int.class),Modifier.PUBLIC,null,target("routeInt",int.class));
   var second=MemberForge.constructor(Caller.class,metadata.getDeclaredConstructor(String.class),Modifier.PUBLIC,null,target("routeString",String.class));
   MemberForge.initializer(Caller.class,"(I)V",target("preInt",int.class));MemberForge.initializer(Caller.class,"(Ljava/lang/String;)V",target("preString",String.class));
   var view=RecordCaller.prepare(Caller.class,List.of(),List.of(Caller.class.getDeclaredField("value")),List.of(first,second),Map.of("(I)V",target("cohortInt",int.class),"(Ljava/lang/String;)V",target("cohortString",String.class)));
   var slot=RecordSchema.slot(Owner.class);try(var next=slot.prepare(slot.current().members())){next.stageCallerMembers(Caller.class,view);check(next.commit(),"publish caller cohort");}
   check(((Caller)first.newInstance(3)).value==103&&((Caller)second.newInstance("a")).value==201,"both constructors use committed cohort");
   var values=new LinkedHashMap<String,MethodHandle>();values.put("(I)V",target("ordinaryInt",int.class));values.put("(Ljava/lang/String;)V",target("ordinaryString",String.class));
   var partial=new CountDownLatch(1);var release=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
   Map<String,MethodHandle> paused=new AbstractMap<>(){public Set<Entry<String,MethodHandle>> entrySet(){return new AbstractSet<>(){public int size(){return values.size();}public Iterator<Entry<String,MethodHandle>> iterator(){var entries=values.entrySet().iterator();return new Iterator<>(){int read;public boolean hasNext(){return entries.hasNext();}public Entry<String,MethodHandle> next(){if(read++==1){partial.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}return entries.next();}};}};}};
   Thread writer=new Thread(()->{try{MemberForge.initializers(Caller.class,paused);}catch(Throwable error){failure.set(error);}},"ordinary-initializer-writer");writer.start();
   try{check(partial.await(5,TimeUnit.SECONDS),"writer paused after first initializer write");check(((Caller)second.newInstance("a")).value==201,"second constructor cannot use a pre-cohort initializer");check(((Caller)first.newInstance(3)).value==103,"first constructor keeps cohort while ordinary batch is incomplete");}finally{release.countDown();writer.join(5000);}
   check(!writer.isAlive()&&failure.get()==null,"ordinary batch writer completed: "+failure.get());
   check(((Caller)first.newInstance(3)).value==1003&&((Caller)second.newInstance("a")).value==2001,"both constructors switch to the complete ordinary table");
   System.out.println("RECORD_INITIALIZER_ORDER_OK checks="+checks+" runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));
  }
 }
}
