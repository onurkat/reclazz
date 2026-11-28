/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.JavaLauncher;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import java.nio.file.*;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A body-only edit of two previously admitted records still takes the multi-record guard. */
class RecordBodyOnlyMultiSaveTest {
    @TempDir Path dir;
    private static String record(String name,int version) {
        return "package app;public record "+name+"(int a"+(version==0?"":",String b")+"){"
                +(version==0?"":"public "+name+"(int a){this(a,\"added\");}")
                +"public static int version(){return "+version+";}}";
    }
    @Test void twoAdmittedRecordsWithOnlyBodyEditsExplainTheUnchangedRefusal()throws Exception {
        var protocol=new RecordStockBatchAdmissionTest();protocol.dir=dir;
        Path java=JavaLauncher.resolve(Path.of(System.getProperty("reclazz.record.stock.java",Path.of(System.getProperty("java.home"),"bin","java").toString())));
        var builder=WatchedApp.in(dir).javaExecutable(java)
                .agentArgs("startupDelaySec=1,debounceMs=150,portFile="+dir.resolve("agent.port"))
                .jvmArgs("-Dprobe.dir="+dir).with("P",record("P",0)).with("Q",record("Q",0)).with("App",APP);
        if(Boolean.getBoolean("reclazz.record.unsafe.deny"))builder.jvmArgs("--sun-misc-unsafe-memory-access=deny","-Dreclazz.record.unsafe.deny=true");
        WatchedApp child=builder.start();
        try(child) {
            child.awaitOrFail("] Watching 1 directory","watch before record definitions");Files.writeString(dir.resolve("load"),"go");
            child.awaitOrFail("MULTI_BODY_READY","both physical records loaded");
            for(String name:List.of("P","Q")) {
                protocol.control("pause");child.rewrite(name,record(name,1));
                protocol.request("SCAN\nRELOAD status request=scan"+name,"RELOAD_STATE scan"+name+" status ");protocol.control("resume");
                protocol.receipt(child,name,"applied","normal");
            }
            Files.writeString(dir.resolve("admitted"),"go");child.awaitOrFail("MULTI_BODY_ADMITTED","both prior admitted shapes and bodies");
            protocol.control("pause");child.rewriteAll(Map.of("P",record("P",2),"Q",record("Q",2)));
            protocol.request("SCAN\nRELOAD status request=multiScan","RELOAD_STATE multiScan status ");protocol.control("resume");
            protocol.receipt(child,"P","failed","multipleRecords");protocol.receipt(child,"Q","failed","multipleRecords");
            Files.writeString(dir.resolve("observe"),"go");child.awaitOrFail("MULTI_BODY_DONE|errors=0|","both old admitted bodies and schemas survive refusal");
            Path coldLog = dir.resolve("cold.log");
            Process cold = new ProcessBuilder(java.toString(), "-cp", child.classesDir().toString(), "app.App", "cold")
                    .redirectErrorStream(true).redirectOutput(coldLog.toFile()).start();
            try {
                org.junit.jupiter.api.Assertions.assertTrue(cold.waitFor(20, TimeUnit.SECONDS));
                org.junit.jupiter.api.Assertions.assertEquals(0, cold.exitValue(), () -> {
                    try { return Files.readString(coldLog); } catch (IOException e) { return e.toString(); }
                });
                org.junit.jupiter.api.Assertions.assertTrue(Files.readString(coldLog).contains("COLD=2/2/2/2"));
            } finally { if (cold.isAlive()) cold.destroyForcibly(); }
        } finally {
            Path out=Path.of(System.getProperty("reclazz.record.evidenceDir",dir.toString()));Files.createDirectories(out);
            Files.write(out.resolve("multi-body-"+UUID.randomUUID()+".log"),child.output());
        }
    }
    private static final String APP="""
            package app;import java.nio.file.*;
            public class App {
                public static void main(String[]args)throws Exception {
                    if (args.length > 0 && args[0].equals("cold")) {
                        System.out.println("COLD=" + P.version() + "/" + Q.version() + "/"
                                + P.class.getRecordComponents().length + "/" + Q.class.getRecordComponents().length);
                        return;
                    }
                    Path dir=Path.of(System.getProperty("probe.dir"));while(!Files.exists(dir.resolve("load")))Thread.sleep(10);
                    P heldP=new P(7);Q heldQ=new Q(9);System.out.println("MULTI_BODY_READY");System.out.flush();
                    while(!Files.exists(dir.resolve("admitted")))Thread.sleep(10);
                    if(P.version()!=1||Q.version()!=1||P.class.getRecordComponents().length!=2||Q.class.getRecordComponents().length!=2)throw new AssertionError("prior record admissions");
                    System.out.println("MULTI_BODY_ADMITTED");System.out.flush();while(!Files.exists(dir.resolve("observe")))Thread.sleep(10);
                    int errors=0;if(P.version()!=1||Q.version()!=1)errors++;if(P.class.getRecordComponents().length!=2||Q.class.getRecordComponents().length!=2)errors++;
                    if(heldP.a()!=7||heldQ.a()!=9)errors++;
                    System.out.println("MULTI_BODY_DONE|errors="+errors+"|runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));System.out.flush();
                    for(;;)Thread.sleep(100);
                }
            }
            """;
}
