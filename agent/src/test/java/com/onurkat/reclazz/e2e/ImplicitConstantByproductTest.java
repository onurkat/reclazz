/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ImplicitConstantByproductTest {
    @TempDir Path tmp;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void changedDiskByproductRebuildsAlreadyLoadedInlinedConsumer(boolean loadOwner) throws Exception {
        String owner="package app; public class Owner { public static final int VALUE=1; }";
        try(WatchedApp app=WatchedApp.in(tmp).mavenLayout()
                .agentArgs("startupDelaySec=5,debounceMs=200,autoCompile=true")
                .jvmArgs("-Dfixture.root="+tmp,"-Dfixture.loadOwner="+loadOwner)
                .with("Owner",owner)
                .with("Consumer","package app; public class Consumer { public int value(){return Owner.VALUE;} }")
                .with("Trigger","package app; public class Trigger { public int value(){return Owner.VALUE;} }")
                .with("App", """
                    package app;
                    public class App {
                      public static void main(String[] args) throws Exception {
                        Consumer consumer=new Consumer();
                        if(Boolean.getBoolean("fixture.loadOwner"))Class.forName("app.Owner");
                        var field=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent").getDeclaredField("instrumentation");
                        field.setAccessible(true);var inst=(java.lang.instrument.Instrumentation)field.get(null);
                        boolean loaded=java.util.Arrays.stream(inst.getAllLoadedClasses()).anyMatch(c->c.getName().equals("app.Owner"));
                        System.out.println("OWNER_LOADED="+loaded);
                        var root=java.nio.file.Path.of(System.getProperty("fixture.root"));
                        var source=root.resolve("src/main/java/app/Owner.java");
                        java.nio.file.Files.writeString(source,"package app; public class Owner { public static final int VALUE=2; }");
                        int exit=javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,
                          "-g","-parameters","--release",System.getProperty("java.specification.version"),
                          "-d",root.resolve("target/classes").toString(),source.toString());
                        if(exit!=0)throw new AssertionError("external compiler failed");
                        // Completed before registration, with an old class mtime: the test's
                        // trigger is an implicit source compile, not startup catch-up.
                        java.nio.file.Files.setLastModifiedTime(root.resolve("target/classes/app/Owner.class"),
                          java.nio.file.attribute.FileTime.fromMillis(1000));
                        System.out.println("EXTERNAL_DISK=2");
                        boolean censusPrinted=false;
                        while(true){
                          if(!censusPrinted && java.nio.file.Files.exists(root.resolve("before-trigger"))){
                            boolean before=java.util.Arrays.stream(inst.getAllLoadedClasses()).anyMatch(c->c.getName().equals("app.Owner"));
                            System.out.println("OWNER_BEFORE_TRIGGER="+before);censusPrinted=true;
                          }
                          System.out.println("CONSUMER="+consumer.value());Thread.sleep(25);
                        }
                      }
                    }
                    """).start()) {
            app.awaitOrFail("OWNER_LOADED="+loadOwner,"independent loaded-class census");
            app.awaitOrFail("EXTERNAL_DISK=2","external output ready");
            app.awaitOrFail("] Watching ","source watcher ready");
            app.awaitOrFail("CONSUMER=1","running consumer still has inlined old value");
            assertFalse(app.output().stream().anyMatch(l->l.contains("CONSUMER=2")),app.tail());
            Files.writeString(tmp.resolve("before-trigger"),"census");
            app.awaitOrFail("OWNER_BEFORE_TRIGGER="+loadOwner,"owner loaded-state immediately before implicit compilation");
            Files.writeString(tmp.resolve("src/main/java/app/Trigger.java"),
                    "package app; public class Trigger { public int value(){return Owner.VALUE+10;} }");
            boolean updated=app.awaits("CONSUMER=2");
            assertTrue(updated,()->"implicit owner output must chase and rebuild old consumer:\n"+String.join("\n",app.output().stream().filter(l->!l.startsWith("CONSUMER=")).toList()));
            System.out.println("[byproduct] ownerLoaded="+loadOwner+" disk=2 consumer=1->2");
        }
    }
}
