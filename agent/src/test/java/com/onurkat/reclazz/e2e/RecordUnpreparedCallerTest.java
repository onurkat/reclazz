/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import com.onurkat.reclazz.e2e.harness.JavaLauncher;
import com.onurkat.reclazz.e2e.harness.WatchedApp;import java.nio.file.*;import java.util.*;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
class RecordUnpreparedCallerTest {
 @TempDir Path dir;
 @Test void newRecordDependencyCannotSilentlyUpgradeAnOrdinaryNativeFactory()throws Exception{run(false);}
 @Test void aCallerBeyondTwoInitialLinksKeepsTheWholeOldCohort()throws Exception{run(true);}
 private void run(boolean bounded)throws Exception{
  var protocol=new RecordStockBatchAdmissionTest();protocol.dir=dir;
  String java=JavaLauncher.resolve(Path.of(System.getProperty("reclazz.record.stock.java",Path.of(System.getProperty("java.home"),"bin","java").toString()))).toString();
  var b=WatchedApp.in(dir).javaExecutable(Path.of(java)).agentArgs("startupDelaySec=1,debounceMs=150,portFile="+dir.resolve("agent.port"))
   .jvmArgs("-Dprobe.dir="+dir,"-Dprobe.bounded="+bounded).with("P","package app;public record P(int a){}")
   .with("Caller","package app;public class Caller{"+(bounded?"public Middle middle;":"")+"public int value;public Caller(int value){this.value=value;}public static int version(){return 0;}}")
   .with("App",APP);
  if(bounded)b.with("Middle","package app;public class Middle{public Factory factory;public static int version(){return 0;}}").with("Factory","package app;public class Factory{public P record;public static int version(){return 0;}}");
  if(Boolean.getBoolean("reclazz.record.unsafe.deny"))b.jvmArgs("--sun-misc-unsafe-memory-access=deny","-Dreclazz.record.unsafe.deny=true");
  WatchedApp child=b.start();try(child){child.awaitOrFail("] Watching 1 directory","watch before definitions");Files.writeString(dir.resolve("load"),"go");child.awaitOrFail("UNPREPARED_CALLER_READY","native factory and baseline held");protocol.control("pause");
   var changes=new LinkedHashMap<>(Map.of("P","package app;public record P(int a,String b){}","Caller","package app;public class Caller{public int value;public P record;public Caller(int value){this.value=value+100;record=new P(value,\"new\");}public static int version(){return 1;}}"));if(bounded){changes.put("Middle","package app;public class Middle{public Factory factory;public static int version(){return 1;}}");changes.put("Factory","package app;public class Factory{public P record;public static int version(){return 1;}}");}child.rewriteAll(changes);
   protocol.request("SCAN\nRELOAD status request=boundaryScan","RELOAD_STATE boundaryScan status ");protocol.control("resume");protocol.receipt(child,"P","failed","unpreparedCaller");protocol.receipt(child,"Caller","failed","unpreparedCaller");if(bounded){protocol.receipt(child,"Middle","failed","unpreparedCaller");protocol.receipt(child,"Factory","failed","unpreparedCaller");}Files.writeString(dir.resolve("observe"),"go");child.awaitOrFail("UNPREPARED_CALLER_DONE|errors=0|","whole old route and usable capability, no native admission");
  }finally{Path out=Path.of(System.getProperty("reclazz.record.evidenceDir",dir.toString()));Files.createDirectories(out);Files.write(out.resolve("unprepared-caller-"+UUID.randomUUID()+".log"),child.output());}
 }
 static final String APP="""
 package app;import java.nio.file.*;import java.lang.instrument.*;import java.lang.reflect.*;import java.util.*;import java.util.function.*;
 public class App{
  static int calls;
  public static void main(String[]args)throws Exception{
   Path dir=Path.of(System.getProperty("probe.dir"));while(!Files.exists(dir.resolve("load")))Thread.sleep(10);
   P held=new P(7);IntFunction<Caller> reference=Caller::new;Caller old=reference.apply(7);
   if(Boolean.getBoolean("probe.bounded"))for(String dependency:List.of("Middle","Factory"))if((int)Class.forName("app."+dependency).getMethod("version").invoke(null)!=0)throw new AssertionError("initial dependency version");
   Class<?> agent=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent");var f=agent.getDeclaredField("instrumentation");f.setAccessible(true);Instrumentation real=(Instrumentation)f.get(null);
   Instrumentation observed=(Instrumentation)Proxy.newProxyInstance(App.class.getClassLoader(),new Class<?>[]{Instrumentation.class},(p,m,a)->{if(m.getName().equals("redefineClasses")&&Arrays.stream((ClassDefinition[])a[0]).anyMatch(d->d.getDefinitionClass()==P.class))calls++;try{return m.invoke(real,a);}catch(InvocationTargetException failure){throw failure.getCause();}});f.set(null,observed);
   Class<?> definitions=Class.forName("com.onurkat.reclazz.transform.PhysicalDefinitions");if(definitions.getMethod("get",Class.class).invoke(null,Caller.class)!=null)throw new AssertionError("unrelated caller retained a physical payload");
   System.out.println("UNPREPARED_CALLER_READY");System.out.flush();while(!Files.exists(dir.resolve("observe")))Thread.sleep(10);
   var capability=agent.getDeclaredField("stockRecordCapability");capability.setAccessible(true);Object result=capability.get(null);boolean usable=result!=null&&(boolean)result.getClass().getMethod("usable").invoke(result);
   int errors=0;if(!usable)errors++;if(calls!=0)errors++;if(Caller.version()!=0||old.value!=7||reference.apply(9).value!=9)errors++;if(P.class.getRecordComponents().length!=1||!held.toString().equals("P[a=7]"))errors++;
   if(Boolean.getBoolean("probe.bounded"))for(String dependency:List.of("Middle","Factory"))if((int)Class.forName("app."+dependency).getMethod("version").invoke(null)!=0)errors++;
   System.out.println("UNPREPARED_CALLER_DONE|errors="+errors+"|bounded="+Boolean.getBoolean("probe.bounded")+"|capability="+usable+"|calls="+calls+"|runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));System.out.flush();for(;;)Thread.sleep(100);
  }
 }
 """;
}
