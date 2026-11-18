/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import com.sun.tools.attach.VirtualMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DynamicAttachTakesBaselineTest {
    @TempDir Path tmp;
    @Test void existingDiskChangesBecomeBaselineAndOnlyANewSaveReloads() throws Exception {
        Path request = tmp.resolve("probe.request"), port = tmp.resolve("agent.port");
        try (var app = WatchedApp.in(tmp).withoutAgent().jvmArgs("-Dprobe.request="+request)
                .with("A", source("v1"))
                .with("C0", "package app; public class C0 { public String value() { return \"control\"; } }")
                .with("App", DRIVER).start()) {
            app.awaitOrFail("READY=", "child JVM did not start before attach");
            String ready = app.output().stream().filter(x->x.startsWith("READY=")).findFirst().orElseThrow();
            String[] identity = ready.substring(6).split(":");
            long start = Long.parseLong(identity[1]);
            assertTrue(Files.getLastModifiedTime(app.classesDir().resolve("app/C0.class")).toMillis()<start);
            app.rewrite("A",source("v2"));
            long written = Files.getLastModifiedTime(app.classesDir().resolve("app/A.class")).toMillis();
            assertTrue(written>start,"fixture must hit the JVM-start catch-up route");
            VirtualMachine vm=VirtualMachine.attach(identity[0]);
            try { vm.loadAgent(WatchedApp.agentJarOrSkip(),"watchDirs="+app.classesDir()+",startupDelaySec=0,debounceMs=100,portFile="+port); }
            finally { vm.detach(); }
            // Existing readiness works on BASE too: a red run diagnoses a reload, not a missing new log line.
            app.awaitOrFail("] Watching 1 director","attach inventory never completed");
            Files.writeString(request,"after-attach");
            app.awaitOrFail("PROBE=after-attach:","no post-inventory response");
            assertTrue(app.output().contains("PROBE=after-attach:v1:control"),app.tail());
            assertEquals(0,reloads(app),app.tail());
            assertTrue(app.output().stream().anyMatch(x->x.contains("Attached to a running JVM: 3 class files taken as the baseline")),app.tail());
            app.rewrite("A",source("v3"));
            Files.writeString(request,"after-save");
            app.awaitOrFail("PROBE=after-save:v3:control","genuine save did not update running code");
            assertEquals(1,reloads(app),app.tail());
        }
    }
    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(x->x.contains("Reloaded app.") || x.contains("Structural reload: app.")).count();
    }
    private static String source(String value) { return "package app; public class A { public String value() { return \""+value+"\"; } }"; }
    private static final String DRIVER="""
        package app;
        import java.nio.file.*;
        public class App {
            public static void main(String[] args) throws Exception {
                A a=new A();C0 control=new C0();a.value();control.value();
                System.out.println("READY="+ProcessHandle.current().pid()+":"+java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime());
                Path request=Path.of(System.getProperty("probe.request"));String previous="";
                while(true) {
                    if(Files.exists(request)) {
                        String current=Files.readString(request);
                        if(!current.equals(previous)) {
                            // Complete watcher delivery, then wait behind admitted work on the serial reload executor.
                            Class<?> agent=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent");
                            var scan=agent.getDeclaredField("generatedClassScan");scan.setAccessible(true);
                            ((Runnable)scan.get(null)).run();
                            var field=agent.getDeclaredField("reloadQueue");field.setAccessible(true);Object queue=field.get(null);
                            var submit=queue.getClass().getDeclaredMethod("submit",String.class,Runnable.class);submit.setAccessible(true);
                            var done=new java.util.concurrent.CountDownLatch(1);
                            submit.invoke(queue,"Attach regression delivery barrier",(Runnable)done::countDown);
                            if(!done.await(20,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Reload delivery did not finish");
                            previous=current;System.out.println("PROBE="+current+":"+a.value()+":"+control.value());
                        }
                    }
                    Thread.sleep(25);
                }
            }
        }
        """;
}
