/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import com.onurkat.reclazz.agent.RecordStockSelfCheck;
import com.onurkat.reclazz.bootstrap.*;
import com.onurkat.reclazz.transform.ReflectionMethodTransformer;
import java.lang.instrument.*;import java.lang.invoke.*;import java.lang.reflect.*;import java.nio.file.Path;import java.util.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
class RecordReflectionIsolationTest {
 @TempDir Path tmp;
 private void run(Class<?> agent,String mode)throws Exception{var h=new RecordComponentMetadataTest();h.tmp=tmp;h.runProbe(Probe.class,"RECORD_REFLECTION_ISOLATION_OK mode="+mode,agent);}
 @Test void failedRecordTargetKeepsOrdinaryAddedMemberReflection()throws Exception{run(RecordFaultAgent.class,"record");}
 @Test void failedRecordInitializationKeepsOrdinaryAddedMemberReflection()throws Exception{run(ModuleFaultAgent.class,"module");}
 @Test void failedCoreGuardsDisableBothCapabilities()throws Exception{run(CoreFaultAgent.class,"core");}
 public static class RecordFaultAgent{public static void premain(String jar,Instrumentation inst)throws Exception{Faults.install(jar,inst,"record");}}
 public static class ModuleFaultAgent{public static void premain(String jar,Instrumentation inst)throws Exception{Faults.install(jar,inst,"module");}}
 public static class CoreFaultAgent{public static void premain(String jar,Instrumentation inst)throws Exception{Faults.install(jar,inst,"core");}}
 public static class Faults {
  static void install(String jar,Instrumentation inst,String mode)throws Exception{
   System.setProperty("probe.reflectionFault",mode);
   var proxy=(Instrumentation)Proxy.newProxyInstance(Instrumentation.class.getClassLoader(),new Class<?>[]{Instrumentation.class},(p,m,a)->{
    if(m.getName().equals("retransformClasses")){
     boolean record=Arrays.stream((Class<?>[])a[0]).anyMatch(c->c.getName().equals("sun.reflect.generics.reflectiveObjects.TypeVariableImpl"));
     if(mode.equals("record")&&record||mode.equals("core")&&!record)throw new UnmodifiableClassException("test "+mode+" hook failure");
    }
    if(mode.equals("module")&&m.getName().equals("redefineModule")&&((Map<?,?>)a[3]).containsKey("sun.reflect.generics.factory"))throw new IllegalArgumentException("test record module failure");
    try{return m.invoke(inst,a);}catch(InvocationTargetException failure){throw failure.getCause();}
   });
   RecordComponentMetadataTest.ProbeAgent.premain(jar,proxy);
  }
 }
 public static class Ordinary { }
 public static class Probe {
  public static void main(String[] args)throws Throwable{
   String mode=System.getProperty("probe.reflectionFault");boolean core=mode.equals("core");
   if(MethodForge.isAvailable()==core)throw new AssertionError("ordinary reflection availability");
   if(ReflectionMethodTransformer.recordHooksAvailable())throw new AssertionError("record hooks must fail closed");
   LookupCapture.trust(Probe.class);LookupCapture.trust(RecordStockSelfCheck.class);LookupCapture.seal();
   var result=RecordStockSelfCheck.run(RecordComponentMetadataTest.ProbeAgent.instrumentation);if(result.usable())throw new AssertionError("S admission after hook failure");
   if(!core){
    var target=MethodHandles.constant(int.class,41);
    var method=MethodForge.forgeMethod(Ordinary.class,"added",new Class<?>[0],int.class,Modifier.PUBLIC|Modifier.STATIC,target,(byte[])null);
    if(method==null)throw new AssertionError("ordinary added root lost");
    ReflectionBridge.replaceClassState(Ordinary.class,List.of(method),List.of());
    ReflectionMethodTransformer.invalidate(RecordComponentMetadataTest.ProbeAgent.instrumentation,Ordinary.class);
    Method visible=Ordinary.class.getDeclaredMethod("added");if(!visible.invoke(null).equals(41))throw new AssertionError("ordinary reflection invocation");
    if((int)MethodHandles.lookup().unreflect(visible).invokeExact()!=41)throw new AssertionError("ordinary Lookup guard lost");
   }
   System.out.println("RECORD_REFLECTION_ISOLATION_OK mode="+mode+" checks="+(core?3:6)+" runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));
  }
 }
}
