/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import com.onurkat.reclazz.e2e.harness.JavaLauncher;
import com.onurkat.reclazz.e2e.harness.WatchedApp;import java.nio.file.*;import java.util.UUID;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
class RecordOrdinaryConstructorCostTest {
 @TempDir Path dir;
 @Test void ordinaryReferencesKeepNativeFactoriesAndNoPhysicalPayload()throws Exception{
  String java=JavaLauncher.resolve(Path.of(System.getProperty("reclazz.record.stock.java",Path.of(System.getProperty("java.home"),"bin","java").toString()))).toString();
  var b=WatchedApp.in(dir).javaExecutable(Path.of(java)).agentArgs("startupDelaySec=1,debounceMs=100")
   .jvmArgs("-Dprobe.dir="+dir).with("PlainFoo","package app;public class PlainFoo{static{Initialization.count++;}public int value;public PlainFoo(int value){this.value=value;}}")
   .with("Initialization","package app;public class Initialization{public static int count;}").with("App",APP);
  String baseline=System.getenv("RECLAZZ_RECORD_BASELINE_JAR");if(baseline!=null)b.withoutAgent().jvmArgs("-javaagent:"+baseline+"=watchDirs="+dir.resolve("classes")+",startupDelaySec=1,debounceMs=100");
  if(Boolean.getBoolean("reclazz.record.unsafe.deny"))b.jvmArgs("--sun-misc-unsafe-memory-access=deny","-Dreclazz.record.unsafe.deny=true");
  WatchedApp child=b.start();try(child){child.awaitOrFail("] Watching 1 directory","watch before ordinary constructor factories");Files.writeString(dir.resolve("load"),"go");child.awaitOrFail("ORDINARY_CTOR_DONE|","ordinary factory benchmark/readback");assertTrue(child.output().stream().anyMatch(x->x.startsWith("ORDINARY_CTOR_DONE|errors=0|")),child.tail());}
  finally{Path out=Path.of(System.getProperty("reclazz.record.evidenceDir",dir.toString()));Files.createDirectories(out);Files.write(out.resolve("ordinary-constructor-"+UUID.randomUUID()+".log"),child.output());}
 }
 static final String APP="""
 package app;import java.util.*;import java.util.function.*;import java.nio.file.*;import java.util.UUID;
 public class App{
  static volatile Object sink;static Supplier<PlainFoo> capturing(int value){return ()->new PlainFoo(value);}
  static void benchmark(IntFunction<PlainFoo> direct)throws Exception{
   int count=500000;double[] refs=new double[9],captures=new double[9];
   for(int r=-10;r<9;r++){
    long start=System.nanoTime();for(int i=0;i<count;i++)sink=direct.apply(i);double ref=(System.nanoTime()-start)/(double)count;
    if(((PlainFoo)sink).value!=count-1)throw new AssertionError("direct constructor values");
    start=System.nanoTime();for(int i=0;i<count;i++)sink=capturing(i);double capture=(System.nanoTime()-start)/(double)count;
    if(((Supplier<PlainFoo>)sink).get().value!=count-1)throw new AssertionError("captured constructor values");
    if(r>=0){refs[r]=ref;captures[r]=capture;System.out.printf(Locale.ROOT,"PERF_ROUND %d reference_ns=%.3f capture_ns=%.3f%n",r,ref,capture);}
   }
   Arrays.sort(refs);Arrays.sort(captures);System.out.printf(Locale.ROOT,"PERF ordinary_ctor_reference median_ns=%.3f min_ns=%.3f max_ns=%.3f%n",refs[4],refs[0],refs[8]);System.out.printf(Locale.ROOT,"PERF ordinary_capturing_factory median_ns=%.3f min_ns=%.3f max_ns=%.3f%n",captures[4],captures[0],captures[8]);System.out.flush();
  }
  public static void main(String[]args)throws Exception{
   Path dir=Path.of(System.getProperty("probe.dir"));while(!Files.exists(dir.resolve("load")))Thread.sleep(10);
   IntFunction<PlainFoo> direct=PlainFoo::new;Supplier<PlainFoo> captured=capturing(7);int errors=0;
   boolean lazy=Initialization.count==0,directNative=direct.getClass().getName().contains("$$Lambda"),captureNative=captured.getClass().getName().contains("$$Lambda");
   Class<?> physical=Class.forName("com.onurkat.reclazz.transform.PhysicalDefinitions");boolean untracked=physical.getMethod("get",Class.class).invoke(null,PlainFoo.class)==null;
   Class<?> cache=Class.forName("com.onurkat.reclazz.transform.TransformedClassCache");byte[] emitted=(byte[])cache.getMethod("get",String.class).invoke(null,"app/App");boolean plainBootstrap=emitted!=null&&!new String(emitted,java.nio.charset.StandardCharsets.ISO_8859_1).contains(String.join("","RecordConstructor","References"));
   if(!lazy)errors++;if(!directNative)errors++;if(!captureNative)errors++;if(!untracked)errors++;if(!plainBootstrap)errors++;benchmark(direct);if(Initialization.count!=1)errors++;
   System.out.println("ORDINARY_CTOR_DONE|errors="+errors+"|lazy="+lazy+"|directNative="+directNative+"|captureNative="+captureNative+"|untracked="+untracked+"|plainBootstrap="+plainBootstrap+"|runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));System.out.flush();for(;;)Thread.sleep(100);
  }
 }
 """;
}
