/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import com.onurkat.reclazz.e2e.harness.JavaLauncher;

import com.fasterxml.jackson.databind.*;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.Socket;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual stock agent entry, whole-save receipts, physical proof and held caller construction. */
class RecordStockBatchAdmissionTest {
    @TempDir Path dir;
    private final ObjectMapper json=new ObjectMapper();
    private int serial;
    private static String record(boolean next){return "package app;public record P(int a"+(next?",String b":"")+"){}";}
    private static String caller(boolean next){return "package app;public class Caller {public int value;public P record;"+(next?"public String added=\"fresh\";":"")
        +"public Caller(int value){this.value=value"+(next?"+100":"")+";record=new P(value"+(next?",\"new\"":"")+");}public static int version(){return "+(next?1:0)+";}public static java.util.function.IntFunction<Caller> reference(){return Caller::new;}}";}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void watcherAndCompilerPublishStockCohortWithRealReceipts(boolean auto)throws Exception {exercise(auto,"normal");}
    void exercise(boolean auto,String mode)throws Exception {
        String java=JavaLauncher.resolve(Path.of(System.getProperty("reclazz.record.stock.java",Path.of(System.getProperty("java.home"),"bin","java").toString()))).toString();
        assertTrue(Files.isExecutable(Path.of(java)),"configured stock child JVM must exist");
        var builder=WatchedApp.in(dir).javaExecutable(Path.of(java))
            .agentArgs("startupDelaySec=1,debounceMs=250,verbose=true,autoCompile="+auto+",portFile="+dir.resolve("agent.port"))
            .jvmArgs("-Dprobe.dir="+dir,"-Dprobe.mode="+mode).with("P",record(false)).with("Caller",caller(false)).with("App",APP);
        if(Boolean.getBoolean("reclazz.record.unsafe.deny"))builder.jvmArgs("--sun-misc-unsafe-memory-access=deny","-Dreclazz.record.unsafe.deny=true");
        if(auto)builder.mavenLayout();
        WatchedApp child=builder.start();
        try(child){
            child.awaitOrFail(auto ? "] Watching 2 directories" : "] Watching 1 directory", "watch installed before application loading");
            if (auto) {
                var doctor = json.readTree(request("DOCTOR roots", "DOCTOR_RESULT roots "));
                Set<Path> watched = new HashSet<>();
                for (var entry : doctor.get("watchedDirectories")) watched.add(Path.of(entry.asText()).toRealPath());
                assertTrue(watched.contains(dir.resolve("src/main/java").toRealPath()), "Java source root");
                assertTrue(watched.contains(child.classesDir().toRealPath()), "Maven runtime resource output");
            }
            Files.writeString(dir.resolve("load"),"go");child.awaitOrFail("STOCK_READY|","stock structural engine and physical startup objects");
            control("pause");
            String changedRecord=record(true);
            if(mode.equals("interfaces"))changedRecord=changedRecord.replace("){}",") implements java.io.Serializable {}");
            if(mode.equals("visibility"))changedRecord=changedRecord.replace("public record","record");
            String changedCaller=caller(true);
            if(mode.equals("callerFinal"))changedCaller=changedCaller.replace("public class Caller","public final class Caller");
            if(mode.equals("callerVisibility"))changedCaller=changedCaller.replace("public class Caller","class Caller");
            if(auto){Files.writeString(dir.resolve("src/main/java/app/P.java"),changedRecord);Files.writeString(dir.resolve("src/main/java/app/Caller.java"),changedCaller);}
            else child.rewriteAll(Map.of("P",changedRecord,"Caller",changedCaller));
            request("SCAN\nRELOAD status request=scanned","RELOAD_STATE scanned status ");control("resume");
            String expected=mode.equals("normal")?"applied":"failed";
            receipt(child,"P",expected,mode);receipt(child,"Caller",expected,mode);
            if(mode.equals("probeError")){
                control("pause");child.rewrite("P",record(true).replace("{}","{public static int retry(){return 1;}}"));
                request("SCAN\nRELOAD status request=retryScan","RELOAD_STATE retryScan status ");control("resume");receipt(child,"P","failed",mode);
                assertEquals(1,child.output().stream().filter(l->l.equals("STOCK_PRIVATE_PROBE_ERROR")).count(),"failed capability is cached across saves");
            }
            Files.writeString(dir.resolve("observe"),"go");
            if(!mode.equals("normal")) {
                child.awaitOrFail("STOCK_FAILURE_DONE|","actual failed whole-save publication and receipt readback");
                assertTrue(child.output().stream().anyMatch(l->l.startsWith("STOCK_FAILURE_DONE|")&&l.contains("|errors=0|")),child.tail());
                if(mode.equals("probe")) {
                    assertTrue(child.output().contains("STOCK_PRIVATE_PROBE_NATIVE_NOOP"),child.tail());
                    assertTrue(child.output().stream().anyMatch(l->l.contains("Stock record capability check failed")&&l.contains("no confirmed transformer readback")),child.tail());
                }else if(mode.equals("probeError")){
                    assertTrue(child.output().stream().anyMatch(l->l.contains("Stock record capability check failed")&&l.contains("synthetic private probe Error")),child.tail());
                }else if(mode.equals("native")||mode.equals("activation"))assertTrue(child.output().stream().anyMatch(l->l.startsWith("STOCK_NATIVE_BOUNDARY|old=true")),child.tail());
                if(mode.equals("activation"))assertTrue(child.output().contains("STOCK_ACTIVATION_RACE_REACHED"),child.tail());
                return;
            }
            child.awaitOrFail("STOCK_ENTRY_OK|","record/caller/held roots/physical proof/ledger readback");
            assertTrue(child.output().stream().anyMatch(l->l.startsWith("STOCK_ENTRY_OK|")&&l.contains("|calls=1|width=2|")),child.tail());
            assertFalse(child.output().stream().anyMatch(l->l.contains("Constructor bodies not refreshed for app.P")),child.tail());
        }finally{
            Path evidence=Path.of(System.getProperty("reclazz.record.evidenceDir",dir.toString()));Files.createDirectories(evidence);
            Files.write(evidence.resolve("stock-entry-"+mode+"-"+auto+"-"+dir.getFileName()+".log"),child.output());
        }
    }
    void control(String action)throws Exception{String token="control"+(++serial);request("RELOAD "+action+" request="+token,"RELOAD_STATE "+token+" "+action+" ");}
    String request(String command,String prefix)throws Exception{
        try(Socket socket=new Socket("127.0.0.1",Integer.parseInt(Files.readString(dir.resolve("agent.port")).trim()))){
            socket.setSoTimeout(15000);var input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            socket.getOutputStream().write((command+"\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
            for(;;){String line=input.readLine();assertNotNull(line);String message=json.readTree(line).path("message").asText();if(message.startsWith(prefix))return message.substring(prefix.length());}
        }
    }
    void receipt(WatchedApp child,String type,String expected,String mode)throws Exception{
        Path file=child.classesDir().resolve("app/"+type+".class");JsonNode result=null;long end=System.nanoTime()+30_000_000_000L;
        while(System.nanoTime()<end){
            if(!Files.exists(file)){Thread.sleep(50);continue;}
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))),token="verify"+(++serial);
            result=json.readTree(request("VERIFY "+token+" app."+type+" "+hash,"VERIFY_RESULT "+token+" "));
            if(List.of("applied","failed","unverified").contains(result.path("status").asText())){
                assertEquals(expected,result.path("status").asText(),result+"\n"+child.tail());assertEquals(hash,result.path("observedSha256").asText());
                if(expected.equals("failed")){
                    String reason=switch(mode){
                        case "activation" -> "activation refused stale epoch";
                        case "probe","probeError" -> type.equals("Caller")?"from a changed record":"record component";
                        case "interfaces","visibility" -> "physical record header changed";
                        case "callerFinal","callerVisibility" -> "physical caller header changed";
                        case "unpreparedCaller" -> "caller was not prepared for record cohorts at initial load";
                        case "multipleRecords" -> "Several records changed in one save; save them separately or restart to apply";
                        default -> "record admission refused";
                    };
                    assertTrue(result.path("detail").asText().contains(reason),result.toString());
                    if(mode.equals("unpreparedCaller"))assertEquals(1,result.path("detail").asText().split("restart to apply",-1).length-1,"one restart instruction: "+result);
                }
                return;
            }Thread.sleep(50);
        }fail("No terminal receipt: "+result+"\n"+child.tail());
    }
    private static final String APP="""
        package app;
        import java.nio.file.*;import java.lang.reflect.*;import java.lang.instrument.*;
        public class App {
          static volatile int calls,width;static volatile boolean boundaryOld=true;static int checks,errors;
          static void failedCheck(boolean ok,String label){checks++;if(!ok)errors++;System.out.println("STOCK_FAILURE_CHECK|"+label+"|"+ok);System.out.flush();}
          // Test-only same-shape competing commit, injected AFTER actual JNI and BEFORE the real CAS.
          static void compete(Class<?> owner)throws Exception{
            Class<?> schema=Class.forName("com.onurkat.reclazz.bootstrap.RecordSchema");Field slots=schema.getDeclaredField("slots");slots.setAccessible(true);
            Object holder=((ClassValue<?>)slots.get(null)).get(owner);Field slotField=holder.getClass().getDeclaredField("slot");slotField.setAccessible(true);Object slot=slotField.get(holder);
            Field active=slot.getClass().getDeclaredField("active");active.setAccessible(true);var reference=(java.util.concurrent.atomic.AtomicReference<Object>)active.get(slot);
            synchronized(slot){Object before=reference.get();Class<?> state=before.getClass();Field members=state.getDeclaredField("members"),sequence=state.getDeclaredField("sequence"),virtual=state.getDeclaredField("virtual"),committed=state.getDeclaredField("committed");
              for(Field field:new Field[]{members,sequence,virtual,committed})field.setAccessible(true);
              Constructor<?> constructor=state.getDeclaredConstructor(Class.class,long.class,members.getType(),boolean.class);constructor.setAccessible(true);
              Object next=constructor.newInstance(owner,sequence.getLong(before)+1,members.get(before),virtual.getBoolean(before));committed.setBoolean(next,true);
              if(!reference.compareAndSet(before,next))throw new AssertionError("test competing commit lost");}
            System.out.println("STOCK_ACTIVATION_RACE_REACHED");System.out.flush();
          }
          static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("STOCK_CHECK|"+label);System.out.flush();}
          static Object agent(String type,String method,Class<?>[] signature,Object...args)throws Exception{
            var m=Class.forName(type).getDeclaredMethod(method,signature);m.setAccessible(true);return m.invoke(null,args);
          }
          public static void main(String[]args)throws Exception{
            Path dir=Path.of(System.getProperty("probe.dir"));String mode=System.getProperty("probe.mode","normal");while(!Files.exists(dir.resolve("load")))Thread.sleep(20);
            Class<?> p=Class.forName("app.P"),c=Class.forName("app.Caller");
            Constructor<?> held=p.getDeclaredConstructor(int.class),heldCaller=c.getDeclaredConstructor(int.class);
            Object originalCaller=heldCaller.newInstance(3),original=c.getDeclaredField("record").get(originalCaller);
            var reference=(java.util.function.IntFunction<?>)c.getDeclaredMethod("reference").invoke(null);
            byte[] physical=Files.readAllBytes(Path.of(p.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("app/P.class"));
            Class<?> agent=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent");var reloader=agent.getDeclaredField("structuralReloader");reloader.setAccessible(true);
            check(reloader.get(null)!=null,"real stock structural engine selected");
            var f=agent.getDeclaredField("instrumentation");f.setAccessible(true);Instrumentation real=(Instrumentation)f.get(null);
            Instrumentation observed=(Instrumentation)Proxy.newProxyInstance(App.class.getClassLoader(),new Class<?>[]{Instrumentation.class},(o,m,a)->{
              if(mode.startsWith("probe")&&m.getName().equals("redefineClasses")&&java.util.Arrays.stream((ClassDefinition[])a[0]).anyMatch(d->d.getDefinitionClass().getName().startsWith("com.example.reclazzprobe.Stock"))){
                if(mode.equals("probeError")){System.out.println("STOCK_PRIVATE_PROBE_ERROR");System.out.flush();throw new AssertionError("synthetic private probe Error");}
                System.out.println("STOCK_PRIVATE_PROBE_NATIVE_NOOP");System.out.flush();return null;
              }
              boolean cohort=m.getName().equals("redefineClasses")&&java.util.Arrays.stream((ClassDefinition[])a[0]).anyMatch(d->d.getDefinitionClass()==p);
              if(cohort){ClassDefinition[] definitions=(ClassDefinition[])a[0];calls++;width=definitions.length;
                boundaryOld &= p.getRecordComponents().length==1&&c.getDeclaredMethod("version").invoke(null).equals(0);
                System.out.println("STOCK_NATIVE_BOUNDARY|old="+boundaryOld+" mode="+mode);System.out.flush();
                if(mode.equals("native")){ClassDefinition[] refused=new ClassDefinition[definitions.length+1];refused[0]=new ClassDefinition(int.class,definitions[0].getDefinitionClassFile());System.arraycopy(definitions,0,refused,1,definitions.length);a=new Object[]{refused};}}
              try{Object result=m.invoke(real,a);if(cohort&&mode.equals("activation"))compete(p);return result;}catch(InvocationTargetException failure){throw failure.getCause();}
            });f.set(null,observed);
            System.out.println("STOCK_READY|runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));System.out.flush();
            while(!Files.exists(dir.resolve("observe")))Thread.sleep(20);
            if(!mode.equals("normal")){
              failedCheck(boundaryOld,"old record/caller at actual JNI boundary");
              failedCheck((!mode.equals("native")&&!mode.equals("activation"))?calls==0&&width==0:calls==1&&width==2,"only proven capability enters application native cohort");
              failedCheck(p.getRecordComponents().length==1,"failed save keeps old component bundle");
              failedCheck(c.getDeclaredMethod("version").invoke(null).equals(0),"failed save keeps old caller body");
              failedCheck(c.getDeclaredField("value").getInt(heldCaller.newInstance(5))==5,"failed save keeps held caller ctor");
              failedCheck(c.getDeclaredField("value").getInt(reference.apply(6))==6,"failed save keeps held constructor reference");
              failedCheck(original.toString().equals("P[a=3]"),"failed save keeps old ObjectMethods");
              try{c.getDeclaredField("added");failedCheck(false,"failed save hides new caller field");}catch(NoSuchFieldException expected){failedCheck(true,"failed save hides new caller field");}
              try{p.getDeclaredConstructor(int.class,String.class);failedCheck(false,"failed save hides new canonical");}catch(NoSuchMethodException expected){failedCheck(true,"failed save hides new canonical");}
              failedCheck(held.newInstance(9).toString().equals("P[a=9]"),"failed save keeps original canonical");
              failedCheck(!(Boolean)agent("com.onurkat.reclazz.transform.KeepBaseline","hasVirtualRecordReceipt",new Class<?>[]{Class.class},p),"failed save has no logical receipt");
              failedCheck((Boolean)agent("com.onurkat.reclazz.transform.KeepBaseline","constructorBodiesUnchanged",new Class<?>[]{Class.class,byte[].class},p,physical),"failed save keeps original physical proof");
              String ledger=agent("com.onurkat.reclazz.ui.RestartLedger","digest",new Class<?>[0]).toString();
              failedCheck(ledger.contains("app.P")&&ledger.contains("record component changes require a restart"),"real R20 pre-commit refusal ledger");
              if(mode.equals("probe")||mode.equals("probeError"))failedCheck(ledger.contains("stock record component reload")&&ledger.contains("Stock record capability check failed"),"private capability restart warning is remembered");
              System.out.println("STOCK_FAILURE_DONE|mode="+mode+"|checks="+checks+"|errors="+errors+"|calls="+calls+"|width="+width+"|runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));System.out.flush();for(;;)Thread.sleep(100);
            }
            check(boundaryOld,"old record/caller at actual native boundary");
            check(p.getRecordComponents().length==2,"new logical components");
            check(c.getDeclaredMethod("version").invoke(null).equals(1),"new caller body");
            Object fresh=heldCaller.newInstance(5);check(c.getDeclaredField("value").getInt(fresh)==105,"held physical caller constructor");
            check(c.getDeclaredField("value").getInt(reference.apply(6))==106,"retained constructor reference");
            check(c.getDeclaredField("added").get(originalCaller).equals("fresh"),"old caller virtual initializer");
            check(c.getDeclaredField("record").get(fresh).toString().equals("P[a=5, b=new]"),"caller links new canonical");
            check(original.toString().equals("P[a=3, b=null]"),"old record compatible value and added default");
            check(p.getDeclaredConstructor(int.class,String.class).newInstance(8,"x").toString().equals("P[a=8, b=x]"),"new logical canonical constructor");
            try{held.newInstance(1);throw new AssertionError("retired canonical remains callable");}catch(InvocationTargetException retired){check(retired.getCause() instanceof NoSuchMethodError,"old canonical retired");}
            byte[] next=Files.readAllBytes(Path.of(p.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("app/P.class"));
            check((Boolean)agent("com.onurkat.reclazz.transform.KeepBaseline","hasVirtualRecordReceipt",new Class<?>[]{Class.class},p),"logical record receipt exists");
            check((Boolean)agent("com.onurkat.reclazz.transform.KeepBaseline","recordLogicalConstructorBodiesUnchanged",new Class<?>[]{Class.class,byte[].class},p,next),"new logical constructor receipt matches");
            check((Boolean)agent("com.onurkat.reclazz.transform.KeepBaseline","constructorBodiesUnchanged",new Class<?>[]{Class.class,byte[].class},p,physical),"original physical constructor proof remains");
            check(agent("com.onurkat.reclazz.ui.RestartLedger","digest",new Class<?>[0]).toString().contains("Nothing from this session needs a restart"),"real restart ledger clear");
            check(calls==1&&width==2,"one actual full native cohort");
            System.out.println("STOCK_ENTRY_OK|checks="+checks+"|calls="+calls+"|width="+width+"|runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));System.out.flush();
            for(;;)Thread.sleep(100);
          }
        }
        """;
}
