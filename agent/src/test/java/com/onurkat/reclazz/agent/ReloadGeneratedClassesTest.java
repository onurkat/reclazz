/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.hybris.codegen.CodegenReloader;
import com.onurkat.reclazz.platform.HybrisContext;
import com.onurkat.reclazz.platform.ExtensionInfo;
import com.onurkat.reclazz.watcher.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ReloadGeneratedClassesTest {
 @TempDir Path home;
 @Test @DisabledOnOs(OS.WINDOWS)
 void dependentClassMustNotReachReloadBeforeAntFinishesAndRootsAreExposed() throws Exception {
  Path platform=home.resolve("bin/platform"),ext=home.resolve("bin/custom/demo");
  Files.createDirectories(platform.resolve("apache-ant/bin"));Files.createDirectories(ext.resolve("resources"));
  Path xml=ext.resolve("resources/demo-items.xml");Files.writeString(xml,"<items/>\n");
  Files.writeString(platform.resolve("setantenv.sh"),"export PATH=\"$PWD/apache-ant/bin:$PATH\"\n");
  Path ant=platform.resolve("apache-ant/bin/ant");
  Files.writeString(ant,"#!/bin/sh\nmkdir -p generated\nprintf model > generated/Model.class\ntouch partial.ready\nwhile [ ! -f release ]; do sleep 0.02; done\nprintf enum > generated/State.class\ntouch compile.done\necho BUILD SUCCESSFUL\n");
  assertTrue(ant.toFile().setExecutable(true));
  HybrisContext context=new HybrisContext(home);context.getExtensions().put("demo",new ExtensionInfo("demo",ext,List.of(),true,false,true));
  var f=new ReloadBuildTest.Fixture();var exposed=new AtomicBoolean();var schema=new AtomicBoolean();
  List<String> order=new CopyOnWriteArrayList<>();
  var tasks=new ConcurrentLinkedQueue<Runnable>();
  ReloadQueue queue=new ReloadQueue(event->{f.applied.add((int)event.getBytes()[0]);order.add("class");},
      new ReloadQueue.Bracket(){public void begin(){} public void end(){}},tasks::add,
      new ReloadStall(f.now::get,30000),f.now::get,f.now::addAndGet,path->f.disk.get(path));
  Runnable pump=()->{Runnable task;while((task=tasks.poll())!=null)task.run();};
  CodegenReloader reloader=new CodegenReloader(context,true,()->{assertTrue(exposed.get());order.add("schema");schema.set(true);return true;},()->{assertTrue(Files.exists(platform.resolve("compile.done")));exposed.set(true);order.add("exposed");},()->queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{}));
  try {
   reloader.handle(new ChangeEvent(xml,ChangeEvent.Type.MODIFIED,"demo","resources"));
   await(()->Files.exists(platform.resolve("partial.ready")));
   assertFalse(Files.exists(platform.resolve("compile.done")));assertFalse(exposed.get());
   f.disk.put(f.path("Model"),new byte[]{1});
   queue.enqueueClassFile(new ChangeEvent(f.path("Model"),ChangeEvent.Type.MODIFIED,"demo","classes"));queue.submitClassBatch(1);pump.run();
   System.out.println("CODEGEN blocked-ant=true exposed="+exposed.get()+" class-callbacks="+f.applied.size());
   assertTrue(f.applied.isEmpty(),"dependent model reached class reload while ant was blocked and generated roots were not exposed");
   Files.createFile(platform.resolve("release"));
   await(()->{pump.run();return !running(reloader);});pump.run();
   assertTrue(schema.get());assertEquals(List.of("exposed","class","schema"),order);
  } finally {
   Files.writeString(platform.resolve("release"),"");await(()->{pump.run();return !running(reloader);});
   var field=CodegenReloader.class.getDeclaredField("executor");field.setAccessible(true);((ExecutorService)field.get(reloader)).shutdownNow();
  }
 }
 @Test void holdsOnlyGeneratedPathsAndReadsFinalBytesBeforeCompleting() {
  var f=new ReloadBuildTest.Fixture();
  var hold=f.queue.holdGeneratedClasses(List.of(Path.of("/output/generated")),()->{});
  f.write("Other",7);f.write("generated/Model",1);f.run();assertEquals(List.of(7),f.applied);
  f.disk.put(f.path("generated/Model"),new byte[]{3});
  var done=hold.compiled(()->{}).toCompletableFuture();assertFalse(done.isDone());
  f.run();assertTrue(done.isDone());assertFalse(done.isCompletedExceptionally());assertEquals(List.of(7,3),f.applied);
 }
 @Test void failedNativeBuildDoesNotTrapCorrectedXmlOrReleasePartialOutput() {
  var f=new ReloadBuildTest.Fixture();var hold=f.queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{});
  f.write("Model",1);hold.failed();
  f.queue.submitAutomatic("items.xml","corrected XML",()->f.applied.add(9));f.run();assertEquals(List.of(9),f.applied);
  var retry=f.queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{});f.write("Model",2);
  var done=retry.compiled(()->{}).toCompletableFuture();f.run();assertFalse(done.isCompletedExceptionally());assertEquals(List.of(9,2),f.applied);
 }
 @Test void nativeCompletionWaitsForBothForeignBuildOwnerAndManualPause() {
  for(boolean buildFirst:List.of(false,true)) {
   var f=new ReloadBuildTest.Fixture();assertTrue(f.queue.build("foreign","started",()->{}));f.queue.control("pause",()->{});
   var hold=f.queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{});f.write("Model",1);
   var done=hold.compiled(()->{}).toCompletableFuture();f.run();assertFalse(done.isDone());assertEquals("named",f.queue.buildHoldKind());
   assertFalse(f.queue.build("native","ok",()->{}));
   if(buildFirst)assertTrue(f.queue.build("foreign","ok",()->{}));else f.queue.control("resume",()->{});
   f.run();assertFalse(done.isDone());assertTrue(f.applied.isEmpty());
   if(buildFirst)f.queue.control("resume",()->{});else assertTrue(f.queue.build("foreign","ok",()->{}));
   f.run();assertTrue(done.isDone());assertFalse(done.isCompletedExceptionally());assertEquals(List.of(1),f.applied);
  }
 }
 @Test void exposureFailureAndScanFailureKeepTheWholeBatchForRetry() {
  for(boolean expose:List.of(false,true)) {
   var f=new ReloadBuildTest.Fixture();
   var hold=f.queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{if(!expose)throw new IllegalStateException("scan");});
   f.write("A",1);f.write("B",2);
   var done=hold.compiled(()->{if(expose)throw new IllegalStateException("expose");}).toCompletableFuture();f.run();
   assertTrue(done.isCompletedExceptionally());assertTrue(f.applied.isEmpty());
   var retry=f.queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{});var recovered=retry.compiled(()->{}).toCompletableFuture();f.run();
   assertFalse(recovered.isCompletedExceptionally());assertEquals(List.of(1,2),f.applied);
  }
 }
 @Test void unreadableGeneratedFileDoesNotPartiallyAdmitItsSiblings() {
  var f=new ReloadBuildTest.Fixture();var hold=f.queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{});
  f.write("A",1);f.write("B",2);f.disk.remove(f.path("B"));
  var done=hold.compiled(()->{}).toCompletableFuture();f.run();assertTrue(done.isCompletedExceptionally());assertTrue(f.applied.isEmpty());
  f.disk.put(f.path("B"),new byte[]{3});var retry=f.queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{});
  var recovered=retry.compiled(()->{}).toCompletableFuture();f.run();assertFalse(recovered.isCompletedExceptionally());assertEquals(List.of(1,3),f.applied);
 }
 @Test void aNewGenerationDuringCaptureInvalidatesTheEarlierCompletion() {
  var f=new ReloadBuildTest.Fixture();var old=f.queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{});f.write("Model",1);
  var latest=new java.util.concurrent.atomic.AtomicReference<com.onurkat.reclazz.ext.PremiumContext.GeneratedOutput>();
  f.duringRead=()->{f.duringRead=()->{};latest.set(f.queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{}));f.write("Model",2);};
  var done=old.compiled(()->{}).toCompletableFuture();f.run();assertTrue(done.isCompletedExceptionally());assertTrue(f.applied.isEmpty());
  var recovered=latest.get().compiled(()->{}).toCompletableFuture();f.run();assertFalse(recovered.isCompletedExceptionally());assertEquals(List.of(2),f.applied);
 }
 @Test void handlerFailureAndShutdownNeverReportSuccessfulAdmission() {
  var f=new ReloadBuildTest.Fixture();var hold=f.queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{});f.write("Model",1);
  f.duringApply=()->{throw new IllegalStateException("handler");};var done=hold.compiled(()->{}).toCompletableFuture();f.run();assertTrue(done.isCompletedExceptionally());
  var retry=f.queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{});var waiting=retry.compiled(()->{}).toCompletableFuture();f.queue.shutdown();f.run();assertTrue(waiting.isCompletedExceptionally());
 }
 @Test void aQueuedClassTaskCannotEscapeAHoldStartedBeforeItsCaptureFinishes() {
  var f=new ReloadBuildTest.Fixture();f.write("Model",1);
  var token=new java.util.concurrent.atomic.AtomicReference<com.onurkat.reclazz.ext.PremiumContext.GeneratedOutput>();
  f.duringRead=()->{f.duringRead=()->{};token.set(f.queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{}));};
  f.run();assertTrue(f.applied.isEmpty());var done=token.get().compiled(()->{}).toCompletableFuture();f.run();assertFalse(done.isCompletedExceptionally());assertEquals(List.of(1),f.applied);
 }
 @Test @DisabledOnOs(OS.WINDOWS)
 void failedAntCanRetryFromCorrectedXmlWithoutUnlockingClasses() throws Exception {
  Path platform=home.resolve("bin/platform"),ext=home.resolve("bin/custom/demo");
  Files.createDirectories(platform.resolve("apache-ant/bin"));Files.createDirectories(ext.resolve("resources"));
  Path xml=ext.resolve("resources/demo-items.xml");Files.writeString(xml,"<items>bad</items>");
  Files.writeString(platform.resolve("setantenv.sh"),"export PATH=\"$PWD/apache-ant/bin:$PATH\"\n");
  Path ant=platform.resolve("apache-ant/bin/ant");
  Files.writeString(ant,"#!/bin/sh\necho run >> runs\nif [ ! -f failed.once ]; then touch partial.ready; while [ ! -f release ]; do sleep 0.02; done; touch failed.once; exit 1; fi\necho BUILD SUCCESSFUL\n");assertTrue(ant.toFile().setExecutable(true));
  HybrisContext context=new HybrisContext(home);context.getExtensions().put("demo",new ExtensionInfo("demo",ext,List.of(),true,false,true));
  var f=new ReloadBuildTest.Fixture();var tasks=new ConcurrentLinkedQueue<Runnable>();var schema=new AtomicBoolean();
  ReloadQueue queue=new ReloadQueue(event->f.applied.add((int)event.getBytes()[0]),
      new ReloadQueue.Bracket(){public void begin(){} public void end(){}},tasks::add,
      new ReloadStall(f.now::get,30000),f.now::get,f.now::addAndGet,path->f.disk.get(path));
  Runnable pump=()->{Runnable task;while((task=tasks.poll())!=null)task.run();};
  var reloader=new CodegenReloader(context,true,()->{schema.set(true);return true;},()->{},
      ()->queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{}));
  try {
   reloader.handle(new ChangeEvent(xml,ChangeEvent.Type.MODIFIED,"demo","resources"));await(()->Files.exists(platform.resolve("partial.ready")));
   f.disk.put(f.path("Model"),new byte[]{1});queue.enqueueClassFile(new ChangeEvent(f.path("Model"),ChangeEvent.Type.MODIFIED,"demo","classes"));queue.submitClassBatch(1);pump.run();assertTrue(f.applied.isEmpty());
   Files.createFile(platform.resolve("release"));await(()->!running(reloader));pump.run();assertTrue(f.applied.isEmpty());assertFalse(schema.get());
   Files.writeString(xml,"<items>corrected</items>");f.disk.put(f.path("Model"),new byte[]{2});
   // A later native compile emits a fresh watcher event; the failed event was dropped.
   queue.enqueueClassFile(new ChangeEvent(f.path("Model"),ChangeEvent.Type.MODIFIED,"demo","classes"));
   queue.submitAutomatic("items.xml","corrected XML",()->reloader.handle(new ChangeEvent(xml,ChangeEvent.Type.MODIFIED,"demo","resources")));pump.run();
   await(()->{pump.run();return !running(reloader);});pump.run();
   assertEquals(2,Files.readAllLines(platform.resolve("runs")).size());assertTrue(schema.get());assertEquals(List.of(2),f.applied);
  } finally {
   Files.writeString(platform.resolve("release"),"");await(()->{pump.run();return !running(reloader);});
   var field=CodegenReloader.class.getDeclaredField("executor");field.setAccessible(true);((ExecutorService)field.get(reloader)).shutdownNow();queue.shutdown();
  }
 }
 @Test void explicitFailedHoldDiscardsItsOutputAndReleasesLaterWrites() {
  var f=new ReloadBuildTest.Fixture();var hold=f.queue.holdGeneratedClasses(List.of(Path.of("/output/generated")),()->{});
  f.write("generated/Model",1);f.write("Other",7);f.run();assertEquals(List.of(7),f.applied);
  hold.failed();assertTrue(hold.compiled(()->fail("failed hold exposed roots")).toCompletableFuture().isCompletedExceptionally());
  f.run();assertEquals(List.of(7),f.applied,"failed build's pending output must be discarded");
  assertEquals(0,f.queue.control("status",()->{}).pendingClasses());
  f.write("generated/Model",2);f.run();assertEquals(List.of(7,2),f.applied);
  hold.failed();f.run();assertEquals(List.of(7,2),f.applied,"repeated failure redelivered output");
 }
 @Test void supersededOldFailureCannotReleaseTheNewHold() {
  var f=new ReloadBuildTest.Fixture();var old=f.queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{});
  f.write("Model",1);f.run();var current=f.queue.holdGeneratedClasses(List.of(Path.of("/other")),()->{});
  old.failed();assertTrue(old.compiled(()->fail("old hold exposed roots")).toCompletableFuture().isCompletedExceptionally());
  f.write("Model",2);f.run();assertTrue(f.applied.isEmpty());
  var done=current.compiled(()->{}).toCompletableFuture();f.run();assertFalse(done.isCompletedExceptionally());assertEquals(List.of(2),f.applied);
 }
 @Test void inlineSupersessionCallbackCannotDeliverPendingClasses() {
  var applied=new ArrayList<Integer>();
  ReloadQueue queue=new ReloadQueue(e->applied.add((int)e.getBytes()[0]),new ReloadQueue.Bracket(){public void begin(){}public void end(){}},
      Runnable::run,new ReloadStall(()->0L,30000),()->0L,millis->{},path->new byte[]{1});
  var old=queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{});
  queue.enqueueClassFile(new ChangeEvent(Path.of("/output/Model.class"),ChangeEvent.Type.MODIFIED,"m","classes"));
  // A synchronous completion callback must observe the replacement hold already installed.
  queue.control("pause",()->{});
  old.compiled(()->{}).whenComplete((v,e)->{queue.control("resume",()->{});queue.submitClassBatch(1);});
  var next=queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{});
  old.failed();assertTrue(applied.isEmpty());next.compiled(()->{});assertEquals(List.of(1),applied);queue.shutdown();
 }
 @Test void failedGeneratedHoldKeepsForeignBuildAndPauseGates() {
  for(boolean buildFirst:List.of(false,true)) {
   var f=new ReloadBuildTest.Fixture();f.queue.build("owner","started",()->{});f.queue.control("pause",()->{});
   var hold=f.queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{});f.write("Model",1);hold.failed();
   f.write("Model",2);f.run();assertTrue(f.applied.isEmpty());assertEquals("named",f.queue.buildHoldKind());
   if(buildFirst)f.queue.build("owner","ok",()->{});else f.queue.control("resume",()->{});
   f.run();assertTrue(f.applied.isEmpty());
   if(buildFirst)f.queue.control("resume",()->{});else f.queue.build("owner","ok",()->{});
   f.run();assertEquals(List.of(2),f.applied);
  }
 }
 @Test void generatedFailureDuringOrdinaryCaptureInvalidatesStaleBytes() {
  var f=new ReloadBuildTest.Fixture();f.write("Model",0);
  f.duringRead=()->{f.duringRead=()->{};var hold=f.queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{});f.write("Model",1);hold.failed();f.write("Model",2);};
  f.run();assertEquals(List.of(2),f.applied);
 }
 @Test void explicitFailureDuringGeneratedCaptureDiscardsDrainedOutput() {
  var f=new ReloadBuildTest.Fixture();var hold=f.queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{});f.write("Model",1);f.run();
  f.duringRead=()->{f.duringRead=()->{};hold.failed();};
  var done=hold.compiled(()->{}).toCompletableFuture();f.run();assertTrue(done.isCompletedExceptionally());assertTrue(f.applied.isEmpty());
  assertEquals(0,f.queue.control("status",()->{}).pendingClasses());
  f.write("Model",2);f.run();assertEquals(List.of(2),f.applied);
 }
 @Test void shutdownRetiresGeneratedHoldWithoutSubmittingOrDelivering() {
  var f=new ReloadBuildTest.Fixture();var hold=f.queue.holdGeneratedClasses(List.of(Path.of("/output")),()->{});f.write("Model",1);
  int tasks=f.tasks.size();f.queue.shutdown();assertEquals(tasks,f.tasks.size());
  assertTrue(hold.compiled(()->fail("shutdown exposure")).toCompletableFuture().isCompletedExceptionally());
  f.run();assertTrue(f.applied.isEmpty());assertEquals(0,f.queue.control("status",()->{}).pendingClasses());
 }
 interface Check { boolean done() throws Exception; }
 static void await(Check check) throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!check.done()){if(System.nanoTime()>end)throw new AssertionError("fixture timeout");Thread.sleep(10);}}
 static boolean running(CodegenReloader r) throws Exception {var field=CodegenReloader.class.getDeclaredField("running");field.setAccessible(true);return field.getBoolean(r);}
}
