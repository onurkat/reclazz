/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import com.onurkat.reclazz.e2e.harness.JavaLauncher;

import com.fasterxml.jackson.databind.*;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.Socket;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual enhanced child, watcher/compiler entrypoints, native outcomes and VERIFY receipts. */
class RecordNativeBatchAdmissionTest {
    @TempDir Path dir;
    private final ObjectMapper json = new ObjectMapper();
    private int serial;
    private boolean stock;
    private static String record(boolean changed) { return "package app;public record P(int a"+(changed?",String b":"")+"){}"; }
    private static String caller(boolean changed) { return "package app;public class Caller {public static int version(){return "+(changed?1:0)+";}public static Object make(){return new P(7"+(changed?",\"new\"":"")+");}}"; }
    private WatchedApp start(boolean auto,String mode)throws Exception{return start(auto,mode,false);}
    private WatchedApp start(boolean auto, String mode,boolean stock) throws Exception {
        this.stock=stock;
        String java = System.getProperty(stock?"reclazz.record.stock.java":"reclazz.record.java");
        assumeTrue(java != null && !java.isBlank(), "configure reclazz.record.java for the enhanced native witness");
        java=JavaLauncher.resolve(Path.of(java)).toString();
        assertTrue(Files.isExecutable(Path.of(java)), "configured record child JVM is not executable");
        String jackson = Path.of(ObjectMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String core = Path.of(com.fasterxml.jackson.core.JsonFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String annotations = Path.of(com.fasterxml.jackson.annotation.JsonProperty.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var builder = WatchedApp.in(dir).javaExecutable(Path.of(java)).classpath(String.join(File.pathSeparator,jackson,core,annotations))
                .agentArgs("startupDelaySec=1,debounceMs=250,verbose=true,autoCompile="+auto+",portFile="+dir.resolve("agent.port"))
                .jvmArgs("-Dprobe.dir="+dir,"-Dprobe.mode="+mode,"-Dprobe.stock="+stock)
                .with("P",stock&&mode.equals("preloaded")?record(false).replace("{}","{public static int body(){return 0;}}") :record(false)).with("Caller",mode.equals("chain") ? chainCaller(0) : caller(false)).with("Factory",factory(false)).with("N","package app;public record N(String value){}").with("Noise","package app;public class Noise {public static int version(){return 0;}}")
                .with("App",APP);
        if(!stock)builder.jvmArgs("-XX:+AllowEnhancedClassRedefinition");
        if(stock&&Boolean.getBoolean("reclazz.record.unsafe.deny"))builder.jvmArgs("--sun-misc-unsafe-memory-access=deny","-Dreclazz.record.unsafe.deny=true");
        if(mode.equals("preloaded")) builder.jvmArgs("-javaagent:"+preloadAgent());
        if(mode.equals("batchFail")) writeBatchFailureClass();
        if(mode.startsWith("poison"))writeCallerObserverClass();
        if(auto)builder.mavenLayout();
        WatchedApp app=builder.start();
        app.awaitOrFail("] Watching 1 director", "watch installed before record is first loaded");
        Files.writeString(dir.resolve("load"),"go"); app.awaitOrFail("READY|0|1", "witnessed record and caller baseline");
        scan(); return app;
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void successfulWholeCohortAcrossFileAndCompilerSaves(boolean auto) throws Exception {
        WatchedApp app=start(auto,"normal");
        try(app) {
            save(app,auto,false); receipt(app,"P","applied"); receipt(app,"Caller","applied");
            observe(app,"applied");
            assertTrue(app.latest("OBS|applied|").contains("|1|2|P[a=7, b=new]|P[a=7, b=null]|1|2|true|"),app.tail());
            assertFalse(app.output().stream().anyMatch(l->l.contains("Constructor bodies not refreshed for app.P")),app.tail());
        } finally { remember(app,"native-success-"+auto); }
    }
    @Test void nativeRejectsWholeCohortBeforeAnyClassChanges() throws Exception {
        WatchedApp app=start(false,"nativeReject");
        try(app) {
            save(app,false,false); receipt(app,"P","failed"); receipt(app,"Caller","failed");
            observe(app,"rejected");
            assertTrue(app.latest("OBS|rejected|").contains("|0|1|P[a=7]|P[a=7]|1|2|false|"),app.tail());
            assertTrue(app.output().stream().anyMatch(l->l.contains("native record cohort refused") && l.contains("UnmodifiableClassException")),app.tail());
            assertFalse(app.output().stream().anyMatch(l->l.contains("Reloaded app.P ") || l.contains("Reloaded app.Caller ")),app.tail());
        } finally { remember(app,"native-rejected"); }
    }
    @Test void afterNativeRepairFailureIsUnverifiedNotRolledBack()throws Exception{repairFailure(false);}
    void repairFailure(boolean stock)throws Exception {
        WatchedApp app=start(false,"poison",stock);
        try(app) {
            save(app,false,false); receipt(app,"P","unverified"); receipt(app,"Caller","unverified");
            observe(app,"partial");
            String observation=app.latest("OBS|partial|");
            assertTrue(observation.contains("|1|2|P[a=7, b=new]|P[a=7, b=null]|1|2|true|"),app.tail());
            assertTrue(observation.contains("Jackson mapper"),observation);
            assertTrue(app.output().stream().anyMatch(l->l.contains("Record shape changes are installed") && l.contains("mapper")),app.tail());
            assertFalse(app.output().stream().anyMatch(l->l.contains("Reloaded app.P ")),app.tail());
        } finally { remember(app,"native-repair-failure"); }
    }
    private static String factory(boolean changed) { return "package app;public class Factory {public static Object make(){return new P(7"+(changed?",\"new\"":"")+");}"+(changed?"public static Object next(){return make();}":"")+"}"; }
    private static String chainCaller(int version) { return "package app;public class Caller {public static int version(){return "+version+";}public static Object make(){return Factory."+(version==0?"make()":"next()")+";}}"; }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void twoHopCallerAndFactoryPublishInOneNativeCohort(boolean auto)throws Exception{callerChain(auto,false);}
    void callerChain(boolean auto,boolean stock)throws Exception {
        WatchedApp app=start(auto,"chain",stock);
        try(app) {
            control("pause"); Map<String,String> changes=Map.of("P",record(true),"Factory",factory(true),"Caller",chainCaller(1));
            if(auto) for(var entry:changes.entrySet()) Files.writeString(dir.resolve("src/main/java/app/"+entry.getKey()+".java"),entry.getValue());
            else app.rewriteAll(changes);
            scan(); control("resume"); for(String type:List.of("P","Factory","Caller")) receipt(app,type,"applied");
            observe(app,"chain");assertTrue(app.latest("OBS|chain|").contains("|1|2|P[a=7, b=new]|P[a=7, b=null]|1|3|true|"),app.tail());
        } finally {remember(app,"native-chain-"+auto);}
    }
    @Test void laterStaleCallerRefusedAndCurrentCallerCanApply()throws Exception{staleCaller(false);}
    void staleCaller(boolean stock)throws Exception {
        WatchedApp app=start(false,"normal",stock);
        try(app) {
            byte[] stale=Files.readAllBytes(app.classesDir().resolve("app/Caller.class"));
            save(app,false,false);receipt(app,"P","applied");receipt(app,"Caller","applied");
            control("pause");Files.write(app.classesDir().resolve("app/Caller.class"),stale);scan();control("resume");receipt(app,"Caller","failed");
            observe(app,"stale");assertTrue(app.latest("OBS|stale|").contains("|1|2|P[a=7, b=new]|P[a=7, b=null]|1|2|true|"),app.tail());
            assertTrue(app.output().stream().anyMatch(l->l.contains("uses app.P.<init>(I)V")),app.tail());
            app.rewrite("Caller",caller(true).replace("return 1;","return 2;"));receipt(app,"Caller","applied");
            observe(app,"current");assertTrue(app.latest("OBS|current|").contains("|2|2|P[a=7, b=new]|P[a=7, b=null]|1|2|true|"),app.tail());
        } finally {remember(app,"native-later-stale-current");}
    }
    @Test void preWatchBodyCanRunButCannotClaimVerifiedMetadataOrUpgradeOrigin()throws Exception{preWatchBody(false);}
    void preWatchBody(boolean stock)throws Exception {
        WatchedApp app=start(false,"preloaded",stock);
        try(app) {
            app.rewrite("P",record(false).replace("{}","{public static int body(){return 1;}}"));receipt(app,"P","unverified");
            observe(app,"pre-watch-body");assertTrue(app.output().contains("PRE_WATCH_BODY=1"),app.tail());assertTrue(app.latest("OBS|pre-watch-body|").contains("|0|1|P[a=7]|P[a=7]|1|1|false|"),app.tail());
            assertTrue(app.output().stream().anyMatch(l->l.contains(stock?"was loaded before Reclazz could instrument it":"pre-watch record metadata has no witnessed baseline")),app.tail());
            save(app,false,false);receipt(app,"P","failed");receipt(app,"Caller","failed");
            observe(app,"pre-watch-shape");assertTrue(app.latest("OBS|pre-watch-shape|").contains("|0|1|P[a=7]|P[a=7]|1|1|false|"),app.tail());
        }finally{remember(app,"native-pre-watch-body-then-shape");}
    }
    @Test void loadedBeforeWatchRemainsRefused() throws Exception { unsupportedScope("preloaded","not witnessed"); }
    @Test void actualRecordSelfCheckFailureKeepsFallback() throws Exception { unsupportedScope("probeFail","record components of app.P changed"); }
    @Test void duplicateDefiningLoadersRemainRefused() throws Exception { unsupportedScope("ambiguous","not uniquely loaded"); }
    private void unsupportedScope(String mode,String reason)throws Exception{unsupportedScope(mode,reason,false);}
    void unsupportedScope(String mode,String reason,boolean stock)throws Exception {
        WatchedApp app=start(false,mode,stock);
        try(app) {
            save(app,false,false);receipt(app,"P","failed");receipt(app,"Caller","failed");observe(app,mode);
            assertTrue(app.latest("OBS|"+mode+"|").contains("|0|1|P[a=7]|P[a=7]|0|0|false|"),app.tail());
            assertTrue(app.output().stream().anyMatch(l->l.contains(reason)),app.tail());
            if(mode.equals("probeFail"))assertTrue(app.output().stream().anyMatch(l->l.equals("RECORD_PROBE_REJECTED")),app.tail());
        } finally {remember(app,"native-scope-"+mode);}
    }
    private Path preloadAgent() throws Exception {
        Path source=dir.resolve("Preload.java"),classes=Files.createDirectories(dir.resolve("preloader"));
        Files.writeString(source,"package preload;public class Preload {public static void premain(String args)throws Exception{Class.forName(\"app.P\");}}");
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","17","-d",classes.toString(),source.toString()));
        var manifest=new java.util.jar.Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Premain-Class","preload.Preload");
        Path jar=dir.resolve("preload.jar");try(var output=new java.util.jar.JarOutputStream(Files.newOutputStream(jar),manifest)){
            output.putNextEntry(new java.util.jar.JarEntry("preload/Preload.class"));output.write(Files.readAllBytes(classes.resolve("preload/Preload.class")));output.closeEntry();}
        return jar;
    }
    @Test void nativeShapeThenRejectedBodyEditKeepsCanonicalReceipt()throws Exception{rejectedBodyReceipt(false);}
    void rejectedBodyReceipt(boolean stock)throws Exception {
        WatchedApp app=start(false,"normal",stock);
        try(app) {
            save(app,false,false);receipt(app,"P","applied");receipt(app,"Caller","applied");
            Files.writeString(dir.resolve("reject-body"),"go");
            app.rewrite("P",record(true).replace("{}","{public static int body(){return 2;}}"));receipt(app,"P","failed");
            observe(app,"refused-body");assertTrue(app.latest("OBS|refused-body|").contains("|1|2|P[a=7, b=new]|P[a=7, b=null]|2|1|true|"),app.tail());
            assertFalse(app.output().stream().anyMatch(l->l.contains("Constructor bodies not refreshed for app.P")),app.tail());
        } finally {remember(app,"native-shape-then-rejected-body");}
    }
    @Test void successfulNativeConstructorBodyUpdatesPhysicalReceiptAfterShape() throws Exception {
        WatchedApp app=start(false,"normal");
        try(app) {
            save(app,false,false);receipt(app,"P","applied");receipt(app,"Caller","applied");
            app.rewrite("P",record(true).replace("{}","{public P {a+=10;}}"));receipt(app,"P","applied");
            observe(app,"constructor-body");assertTrue(app.latest("OBS|constructor-body|").contains("|1|2|P[a=17, b=new]|P[a=7, b=null]|2|1|true|"),app.tail());
            assertFalse(app.output().stream().anyMatch(l->l.contains("Constructor bodies not refreshed for app.P")),app.tail());
        } finally {remember(app,"native-constructor-body-proof");}
    }
    @Test void repeatedNativeBodySavesStayUnverifiedUntilPendingRepairsSucceed()throws Exception{pendingRepairRetry(false);}
    void pendingRepairRetry(boolean stock)throws Exception {
        WatchedApp app=start(false,"poison",stock);
        try(app) {
            save(app,false,false);receipt(app,"P","unverified");receipt(app,"Caller","unverified");
            app.rewrite("P",record(true).replace("{}","{public static int body(){return 1;}}"));receipt(app,"P","unverified");
            Files.writeString(dir.resolve("clear-poison"),"go");app.awaitOrFail("POISON_CLEARED","repair failure removed before retry");
            app.rewrite("P",record(true).replace("{}","{public static int body(){return 2;}}"));receipt(app,"P","applied");
            observe(app,"repaired");String observation=app.latest("OBS|repaired|");
            assertTrue(observation.contains("|1|2|P[a=7, b=new]|P[a=7, b=null]|3|1|true|"),app.tail());
            assertTrue(observation.contains("Nothing from this session needs a restart"),observation);
        } finally {remember(app,"native-pending-repair-retry");}
    }
    @Test void springBatchCompletionFailureIsUnverifiedAfterNativeCommit()throws Exception{batchCompletionFailure(false);}
    void batchCompletionFailure(boolean stock)throws Exception {
        WatchedApp app=start(false,"batchFail",stock);
        try(app) {
            save(app,false,false);receipt(app,"P","unverified");receipt(app,"Caller","unverified");observe(app,"batch-failure");
            assertTrue(app.output().contains("NATIVE_BATCH_FAILURE_REACHED"),app.tail());
            String observation=app.latest("OBS|batch-failure|");
            assertTrue(observation.contains("|1|2|P[a=7, b=new]|P[a=7, b=null]|1|2|true|"),app.tail());
            assertTrue(observation.contains("Spring batch completion"),observation);
            assertFalse(app.output().stream().anyMatch(l->l.contains("Structural reload: app.P ")||l.contains("Reloaded app.P ")),app.tail());
        }finally{remember(app,"native-spring-batch-failure");}
    }
    @Test void failedRecordRepairStillRefreshesCallerAndRecordOnlyRetryCannotClearCallerConcern()throws Exception{callerRepairRetry(false);}
    void callerRepairRetry(boolean stock)throws Exception {
        WatchedApp app=start(false,"poisonCaller",stock);
        try(app){
            save(app,false,false);receipt(app,"P","unverified");receipt(app,"Caller","unverified");
            assertTrue(app.output().contains("NATIVE_FRAMEWORK_REFRESH|app.Caller"),"caller's own refresh must run despite failed mapper repair\n"+app.tail());
            Files.writeString(dir.resolve("clear-poison"),"go");Files.writeString(dir.resolve("clear-caller-failure"),"go");
            app.awaitOrFail("POISON_CLEARED","mapper fault cleared");app.awaitOrFail("CALLER_FAILURE_CLEARED","caller fault cleared");
            app.rewrite("P",record(true).replace("{}","{public static int body(){return 1;}}"));receipt(app,"P","applied");
            observe(app,"record-only-retry");String pending=app.latest("OBS|record-only-retry|");
            assertTrue(pending.contains("framework refresh app.Caller")&&pending.contains("save app.Caller again to retry"),pending);
            app.rewrite("Caller",caller(true).replace("return 1;","return 2;"));receipt(app,"Caller","applied");observe(app,"caller-retry");
            assertTrue(app.latest("OBS|caller-retry|").contains("Nothing from this session needs a restart"),app.tail());
        }finally{remember(app,"native-caller-framework-retry");}
    }
    private void writeCallerObserverClass()throws Exception {
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        String name="com/example/CallerFrameworkObserver",parent="com/onurkat/reclazz/spring/SpringCacheReloader";
        writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC|Opcodes.ACC_SUPER,name,null,parent,null);
        writer.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_VOLATILE,"fail","Z",null,null).visitEnd();
        var ctor=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","(Lcom/onurkat/reclazz/platform/PlatformContext;)V",null,null);ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD,0);ctor.visitVarInsn(Opcodes.ALOAD,1);ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,parent,"<init>","(Lcom/onurkat/reclazz/platform/PlatformContext;)V",false);ctor.visitInsn(Opcodes.RETURN);ctor.visitMaxs(0,0);ctor.visitEnd();
        var method=writer.visitMethod(Opcodes.ACC_PUBLIC,"reloadCaches","(Ljava/lang/Class;)Z",null,null);method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC,"java/lang/System","out","Ljava/io/PrintStream;");method.visitLdcInsn("NATIVE_FRAMEWORK_REFRESH|");
        method.visitVarInsn(Opcodes.ALOAD,1);method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/lang/Class","getName","()Ljava/lang/String;",false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/lang/String","concat","(Ljava/lang/String;)Ljava/lang/String;",false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/io/PrintStream","println","(Ljava/lang/String;)V",false);
        org.objectweb.asm.Label done=new org.objectweb.asm.Label();method.visitFieldInsn(Opcodes.GETSTATIC,name,"fail","Z");method.visitJumpInsn(Opcodes.IFEQ,done);
        method.visitVarInsn(Opcodes.ALOAD,1);method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/lang/Class","getName","()Ljava/lang/String;",false);method.visitLdcInsn("app.Caller");method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/lang/String","equals","(Ljava/lang/Object;)Z",false);method.visitJumpInsn(Opcodes.IFEQ,done);
        method.visitTypeInsn(Opcodes.NEW,"java/lang/IllegalStateException");method.visitInsn(Opcodes.DUP);method.visitLdcInsn("test caller framework failure");method.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/IllegalStateException","<init>","(Ljava/lang/String;)V",false);method.visitInsn(Opcodes.ATHROW);
        method.visitLabel(done);method.visitInsn(Opcodes.ICONST_0);method.visitInsn(Opcodes.IRETURN);method.visitMaxs(0,0);method.visitEnd();writer.visitEnd();Files.write(dir.resolve("caller-observer.class"),writer.toByteArray());
    }
    private void writeBatchFailureClass() throws Exception {
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);String owner="com/example/NativeBatchFailure";
        writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC|Opcodes.ACC_SUPER,owner,null,"com/onurkat/reclazz/spring/SpringBeanReloader",null);
        var constructor=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","(Lcom/onurkat/reclazz/platform/PlatformContext;)V",null,null);constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD,0);constructor.visitVarInsn(Opcodes.ALOAD,1);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,"com/onurkat/reclazz/spring/SpringBeanReloader","<init>","(Lcom/onurkat/reclazz/platform/PlatformContext;)V",false);
        constructor.visitInsn(Opcodes.RETURN);constructor.visitMaxs(0,0);constructor.visitEnd();
        var end=writer.visitMethod(Opcodes.ACC_PUBLIC,"endBatch","()V",null,null);end.visitCode();
        end.visitFieldInsn(Opcodes.GETSTATIC,"java/lang/System","out","Ljava/io/PrintStream;");end.visitLdcInsn("NATIVE_BATCH_FAILURE_REACHED");
        end.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/io/PrintStream","println","(Ljava/lang/String;)V",false);
        end.visitTypeInsn(Opcodes.NEW,"java/lang/IllegalStateException");end.visitInsn(Opcodes.DUP);end.visitLdcInsn("test Spring endBatch failure after native success");
        end.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/IllegalStateException","<init>","(Ljava/lang/String;)V",false);end.visitInsn(Opcodes.ATHROW);
        end.visitMaxs(0,0);end.visitEnd();writer.visitEnd();Files.write(dir.resolve("batch-fault.class"),writer.toByteArray());
    }
    @Test void nativeObjectMethodsPreserveWidthsNullArraysNestedValuesAndCustomMethods()throws Exception{objectMethods(false);}
    void objectMethods(boolean stock)throws Exception {
        WatchedApp app=start(false,"wide",stock);
        try(app) {
            control("pause");app.rewriteAll(Map.of("P","package app;public record P(int a,boolean z,byte b,short s,char c,long j,float f,double d,String text,int[] values,N nested){public String label(){return a+\":\"+z;}@Override public String toString(){return \"custom:\"+label();}}",
                    "Caller","package app;public class Caller {public static int version(){return 1;}public static Object make(){return new P(7,true,(byte)2,(short)3,'q',1234567890123L,1.25f,2.5d,null,new int[]{1,2},new N(\"n\"));}}"));
            scan();control("resume");receipt(app,"P","applied");receipt(app,"Caller","applied");observe(app,"wide");
            assertTrue(app.output().contains("WIDE_OBJECT_METHODS=true"),app.tail());
        }finally{remember(app,"native-wide-object-methods");}
    }
    @Test void nativeConstructorViewRetiresOldCanonicalAndPreservesValidAuxiliary()throws Exception{constructorView(false);}
    void constructorView(boolean stock)throws Exception {
        WatchedApp app=start(false,"auxiliary",stock);
        try(app) {
            control("pause");app.rewriteAll(Map.of("P",record(true).replace("{}","{public P(String b){this(9,b);}}"),"Caller",caller(true)));
            scan();control("resume");receipt(app,"P","applied");receipt(app,"Caller","applied");observe(app,"auxiliary");
            assertTrue(app.output().contains("NATIVE_AUXILIARY=true"),app.tail());
        }finally{remember(app,"native-constructor-visibility");}
    }
    @Test void nonRecordSaveKeepsPerClassPath()throws Exception{ordinarySave(false);}
    void ordinarySave(boolean stock)throws Exception {
        WatchedApp app=start(false,"normal",stock);
        try(app) {
            control("pause"); app.rewriteAll(Map.of("Noise","package app;public class Noise {public static int version(){return 1;}}",
                    "Caller",caller(false).replace("return 0;","return 2;"))); scan(); control("resume");
            receipt(app,"Noise","applied"); receipt(app,"Caller","applied"); observe(app,"ordinary");
            assertTrue(app.latest("OBS|ordinary|").contains("|2|1|P[a=7]|P[a=7]|0|0|true|"),app.tail());
            assertTrue(app.latest("OBS|ordinary|").contains(stock?"|nativeWidths=[2]|1":"|nativeWidths=[1, 1]|1"),app.tail());
            if(stock)assertTrue(app.latest("OBS|ordinary|").contains("|ordinaryRecordCohort=false"),app.tail());
        } finally { remember(app,"native-non-record-isolation"); }
    }
    private void save(WatchedApp app,boolean auto,boolean invalid) throws Exception {
        control("pause");
        if(auto) {
            Files.writeString(dir.resolve("src/main/java/app/P.java"),record(true));
            Files.writeString(dir.resolve("src/main/java/app/Caller.java"),caller(true));
        } else {
            app.rewriteAll(Map.of("P",record(true),"Caller",caller(true)));
        }
        scan(); control("resume");
    }
    private void observe(WatchedApp app,String token) throws Exception {
        Files.writeString(dir.resolve("observe"),token); app.awaitOrFail("OBS|"+token+"|", "actual native metadata/caller/held record/ledger");
    }
    private String request(String command,String prefix) throws Exception {
        int port=Integer.parseInt(Files.readString(dir.resolve("agent.port")).trim());
        try(Socket socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(15000);var input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            socket.getOutputStream().write((command+"\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
            for(;;){String line=input.readLine();assertNotNull(line);String message=json.readTree(line).path("message").asText();if(message.startsWith(prefix))return message.substring(prefix.length());}
        }
    }
    private void scan() throws Exception {String token="scan"+(++serial);request("SCAN\nRELOAD status request="+token,"RELOAD_STATE "+token+" status ");}
    private void control(String action) throws Exception {String token="control"+(++serial);request("RELOAD "+action+" request="+token,"RELOAD_STATE "+token+" "+action+" ");}
    private void receipt(WatchedApp app,String type,String expected) throws Exception {
        Path file=app.classesDir().resolve("app/"+type+".class"); long end=System.nanoTime()+30_000_000_000L;JsonNode result=null;
        while(System.nanoTime()<end) {
            if(!Files.exists(file)){Thread.sleep(50);continue;}
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            String token="verify"+(++serial);result=json.readTree(request("VERIFY "+token+" app."+type+" "+hash,"VERIFY_RESULT "+token+" "));
            if(List.of("applied","failed","unverified").contains(result.path("status").asText())) {
                assertEquals(expected,result.path("status").asText(),result+"\n"+app.tail());
                assertEquals(hash,result.path("observedSha256").asText());return;
            }
            Thread.sleep(50);
        }
        fail("No terminal receipt for "+type+": "+result+"\n"+app.tail());
    }
    private void remember(WatchedApp app,String name) throws Exception {
        Path output=stock?Path.of(System.getProperty("reclazz.record.evidenceDir",Path.of("build","record-stock-evidence").toString()),name.replace("native-","stock-"))
                :Path.of("build","record-native-evidence",name);Files.createDirectories(output);Files.write(output.resolve("child.log"),app.output());
    }
    private static final String APP="""
        package app;
        import java.nio.file.*;import java.lang.reflect.*;import java.lang.instrument.*;import java.util.*;
        import com.fasterxml.jackson.databind.*;import java.util.concurrent.ConcurrentHashMap;
        public class App {
          static Class<?> frameworkObserver;
          static volatile boolean armed;static boolean ordinaryRecordCohort;static int calls,width;static final java.util.List<Object> duplicates=new java.util.ArrayList<>();static final java.util.List<Integer> nativeWidths=new java.util.ArrayList<>();
          public static class Poison extends ConcurrentHashMap<Object,Object> {
            @Override public void clear(){if(armed)throw new IllegalStateException("test retained mapper flush failure");super.clear();}
          }
          static Object agent(String type,String method,Class<?>[] signature,Object... values)throws Exception{
            Class<?> t=Class.forName(type);var m=t.getDeclaredMethod(method,signature);m.setAccessible(true);return m.invoke(null,values);
          }
          public static void main(String[] args)throws Exception {
            Path dir=Path.of(System.getProperty("probe.dir"));while(!Files.exists(dir.resolve("load")))Thread.sleep(20);
            Class<?> p=Class.forName("app.P"),caller=Class.forName("app.Caller"),noise=Class.forName("app.Noise");Object held=caller.getMethod("make").invoke(null);
            byte[] originalPhysical=Files.readAllBytes(Path.of(p.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("app/P.class"));
            if(Boolean.getBoolean("probe.stock")){
              var arguments=java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
              if(arguments.stream().anyMatch(a->a.contains("AllowEnhancedClassRedefinition")))throw new AssertionError("stock witness used enhanced flag");
              if(Boolean.getBoolean("reclazz.record.unsafe.deny")&&!arguments.contains("--sun-misc-unsafe-memory-access=deny"))throw new AssertionError("missing actual deny flag");
              System.out.println("STOCK_SHARED_RUNTIME|runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));System.out.flush();
            }
            if(System.getProperty("probe.mode").equals("wide"))Class.forName("app.N");
            java.util.Map<Object,String> heldKeys=new java.util.HashMap<>();heldKeys.put(held,"before");int heldHash=held.hashCode();
            if(System.getProperty("probe.mode").equals("ambiguous")) {
              var duplicateLoader=new java.net.URLClassLoader(new java.net.URL[]{p.getProtectionDomain().getCodeSource().getLocation()},ClassLoader.getPlatformClassLoader());
              Class<?> duplicate=Class.forName("app.P",true,duplicateLoader);if(duplicate==p)throw new AssertionError("distinct defining loader required");
              duplicates.add(duplicateLoader);duplicates.add(duplicate.getConstructor(int.class).newInstance(7));
            }
            ObjectMapper mapper=new ObjectMapper();
            if(System.getProperty("probe.mode").startsWith("poison")) {
              Field roots=ObjectMapper.class.getDeclaredField("_rootDeserializers");roots.setAccessible(true);
              Poison poisoned=new Poison();poisoned.putAll((Map)roots.get(mapper));roots.set(mapper,poisoned);
            }
            mapper.readerFor(p).readValue("{\\"a\\":3}");
            Class<?> agent=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent");Field f=agent.getDeclaredField("instrumentation");f.setAccessible(true);
            Instrumentation real=(Instrumentation)f.get(null);
            Instrumentation observed=(Instrumentation)Proxy.newProxyInstance(App.class.getClassLoader(),new Class<?>[]{Instrumentation.class},(o,m,a)->{
              if(m.getName().equals("redefineClasses")&&System.getProperty("probe.mode").equals("probeFail")
                  &&Arrays.stream((ClassDefinition[])a[0]).anyMatch(d->d.getDefinitionClass().getName().startsWith("com.onurkat.reclazz.agent.RecordProbe$$"))){
                System.out.println("RECORD_PROBE_REJECTED");System.out.flush();throw new UnmodifiableClassException("test actual record self-check refusal");
              }
              boolean cohort=m.getName().equals("redefineClasses") && Arrays.stream((ClassDefinition[])a[0]).anyMatch(d->d.getDefinitionClass()==p);
              if(cohort){calls++;width=((ClassDefinition[])a[0]).length;}
              if(Boolean.getBoolean("probe.stock")&&System.getProperty("probe.mode").equals("normal")&&m.getName().equals("redefineClasses")&&Arrays.stream((ClassDefinition[])a[0]).anyMatch(d->d.getDefinitionClass().getName().startsWith("app."))){
                Field save=agent.getDeclaredField("nativeRecordSave");save.setAccessible(true);ordinaryRecordCohort|=((ThreadLocal<?>)save.get(null)).get()!=null;
              }
              if(m.getName().equals("redefineClasses")&&Arrays.stream((ClassDefinition[])a[0]).anyMatch(d->d.getDefinitionClass().getName().startsWith("app.")))nativeWidths.add(((ClassDefinition[])a[0]).length);
              if(cohort && Files.exists(dir.resolve("reject-body")))throw new UnmodifiableClassException("test native record body refusal");
              // Exercise the real native API's defined unmodifiable-class rejection, without malformed class files.
              if(cohort && System.getProperty("probe.mode").equals("nativeReject")) {
                ClassDefinition[] incoming=(ClassDefinition[])a[0];ClassDefinition[] refused=new ClassDefinition[incoming.length+1];
                refused[0]=new ClassDefinition(int.class,incoming[0].getDefinitionClassFile());System.arraycopy(incoming,0,refused,1,incoming.length);
                a=new Object[]{refused};
              }
              try{Object result=m.invoke(real,a);if(cohort && System.getProperty("probe.mode").startsWith("poison")&&!Files.exists(dir.resolve("clear-poison")))armed=true;return result;}
              catch(InvocationTargetException failure){throw failure.getCause();}
            });f.set(null,observed);
            if(Boolean.getBoolean("probe.stock")){
              Field structural=agent.getDeclaredField("structuralReloader");structural.setAccessible(true);Object active=structural.get(null);
              if(active==null)throw new AssertionError("stock structural engine not selected");
              Field instrumentation=active.getClass().getDeclaredField("instrumentation");instrumentation.setAccessible(true);instrumentation.set(active,observed);
            }
            Field queueField=agent.getDeclaredField("reloadQueue");queueField.setAccessible(true);Object queue=queueField.get(null);
            Field handlerField=queue.getClass().getDeclaredField("handler");handlerField.setAccessible(true);Object handler=handlerField.get(queue);int capturedReloaders=0;
            for(Field captured:handler.getClass().getDeclaredFields()){captured.setAccessible(true);Object value=captured.get(handler);
              if(value!=null&&value.getClass().getName().equals("com.onurkat.reclazz.agent.ClassReloader")){
                Field instrumentation=value.getClass().getDeclaredField("instrumentation");instrumentation.setAccessible(true);instrumentation.set(value,observed);capturedReloaders++;
              }}if(capturedReloaders!=1)throw new AssertionError("real native per-class reloader must be observed");
            if(System.getProperty("probe.mode").equals("batchFail")){
              var faultLoader=new ClassLoader(App.class.getClassLoader()){Class<?> fault(byte[] bytes){return defineClass(null,bytes,0,bytes.length);}};
              Class<?> fault=faultLoader.fault(Files.readAllBytes(dir.resolve("batch-fault.class")));
              Field platform=agent.getDeclaredField("platformContext");platform.setAccessible(true);
              Object failing=fault.getConstructor(Class.forName("com.onurkat.reclazz.platform.PlatformContext")).newInstance(platform.get(null));
              Field activeSpring=agent.getDeclaredField("activeSpringOrchestrator");activeSpring.setAccessible(true);Object spring=activeSpring.get(null);
              Field beanReloader=spring.getClass().getDeclaredField("beanReloader");beanReloader.setAccessible(true);beanReloader.set(spring,failing);
            }
            if(System.getProperty("probe.mode").startsWith("poison")){
              var observerLoader=new ClassLoader(App.class.getClassLoader()){Class<?> define(byte[] bytes){return defineClass(null,bytes,0,bytes.length);}};
              frameworkObserver=observerLoader.define(Files.readAllBytes(dir.resolve("caller-observer.class")));
              frameworkObserver.getField("fail").setBoolean(null,System.getProperty("probe.mode").equals("poisonCaller"));
              Field platform=agent.getDeclaredField("platformContext");platform.setAccessible(true);
              Object observer=frameworkObserver.getConstructor(Class.forName("com.onurkat.reclazz.platform.PlatformContext")).newInstance(platform.get(null));
              Field active=agent.getDeclaredField("activeSpringOrchestrator");active.setAccessible(true);Object spring=active.get(null);
              Field cache=spring.getClass().getDeclaredField("cacheReloader");cache.setAccessible(true);cache.set(spring,observer);
            }
            System.out.println("READY|"+caller.getMethod("version").invoke(null)+"|"+p.getRecordComponents().length);System.out.flush();String last="";
            for(;;){if(Files.exists(dir.resolve("clear-caller-failure"))){Files.delete(dir.resolve("clear-caller-failure"));frameworkObserver.getField("fail").setBoolean(null,false);System.out.println("CALLER_FAILURE_CLEARED");System.out.flush();}if(armed&&Files.exists(dir.resolve("clear-poison"))){armed=false;System.out.println("POISON_CLEARED");System.out.flush();}Path signal=dir.resolve("observe");if(Files.exists(signal)){String token=Files.readString(signal);if(!token.equals(last)){
              last=token;Object fresh=caller.getMethod("make").invoke(null);
              if(System.getProperty("probe.mode").equals("wide")){
                Map<String,Object> oldValues=new LinkedHashMap<>(),newValues=new LinkedHashMap<>();RecordComponent[] components=p.getRecordComponents();
                Object[] tuple=new Object[components.length];Class<?>[] types=new Class<?>[components.length];
                for(int i=0;i<components.length;i++){RecordComponent component=components[i];oldValues.put(component.getName(),component.getAccessor().invoke(held));tuple[i]=component.getAccessor().invoke(fresh);newValues.put(component.getName(),tuple[i]);types[i]=component.getType();
                  int flags=p.getDeclaredField(component.getName()).getModifiers();if(!Modifier.isPrivate(flags)||!Modifier.isFinal(flags)||!Modifier.isPublic(component.getAccessor().getModifiers()))throw new AssertionError("component flags");}
                Map<String,Object> defaults=Map.of("a",7,"z",false,"b",(byte)0,"s",(short)0,"c",(char)0,"j",0L,"f",0f,"d",0d);
                for(var e:defaults.entrySet())if(!Objects.equals(e.getValue(),oldValues.get(e.getKey())))throw new AssertionError("old default "+e.getKey());
                for(String name:List.of("text","values","nested"))if(oldValues.get(name)!=null)throw new AssertionError("old reference default "+name);
                Map<String,Object> expected=Map.of("a",7,"z",true,"b",(byte)2,"s",(short)3,"c",'q',"j",1234567890123L,"f",1.25f,"d",2.5d);
                for(var e:expected.entrySet())if(!Objects.equals(e.getValue(),newValues.get(e.getKey())))throw new AssertionError("new primitive "+e.getKey());
                if(newValues.get("text")!=null||!Arrays.equals(new int[]{1,2},(int[])newValues.get("values"))||!newValues.get("nested").getClass().getMethod("value").invoke(newValues.get("nested")).equals("n"))throw new AssertionError("new reference values");
                Constructor<?> canonical=p.getDeclaredConstructor(types);Object equivalent=canonical.newInstance(tuple);
                if(!fresh.equals(equivalent)||fresh.hashCode()!=equivalent.hashCode()||!fresh.toString().equals(equivalent.toString())||!new HashSet<>(List.of(equivalent)).contains(fresh))throw new AssertionError("ObjectMethods contract");
                if(!Modifier.isFinal(p.getModifiers())||!Modifier.isPublic(p.getModifiers())||!Modifier.isPublic(canonical.getModifiers()))throw new AssertionError("record flags");
                try{p.getDeclaredConstructor(int.class);throw new AssertionError("retired canonical still visible");}catch(NoSuchMethodException expectedMissing){}
                if(!fresh.toString().equals("custom:7:true")||!held.toString().equals("custom:7:false"))throw new AssertionError("custom ObjectMethods body");
                if(!p.getMethod("label").invoke(fresh).equals("7:true")||!p.getMethod("label").invoke(held).equals("7:false"))throw new AssertionError("custom body");
                if(held.hashCode()==heldHash||heldKeys.containsKey(held)||heldKeys.size()!=1)throw new AssertionError("existing hash keys are not rehashed");
                System.out.println("WIDE_OBJECT_METHODS=true");System.out.flush();
              }
              if(System.getProperty("probe.mode").equals("preloaded")&&Arrays.stream(p.getDeclaredMethods()).anyMatch(m->m.getName().equals("body"))){System.out.println("PRE_WATCH_BODY="+p.getMethod("body").invoke(null));System.out.flush();}
              if(System.getProperty("probe.mode").equals("auxiliary")){
                Object auxiliary=p.getDeclaredConstructor(String.class).newInstance("aux");
                if(!auxiliary.toString().equals("P[a=9, b=aux]"))throw new AssertionError("auxiliary body");
                if(p.getDeclaredConstructors().length!=2)throw new AssertionError("complete constructor view");
                try{p.getDeclaredConstructor(int.class);throw new AssertionError("retired canonical visible");}catch(NoSuchMethodException expectedMissing){}
                System.out.println("NATIVE_AUXILIARY=true");System.out.flush();
              }
              byte[] bytes=Files.readAllBytes(Path.of(p.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("app/P.class"));
              boolean logical=Boolean.getBoolean("probe.stock")&&(Boolean)agent("com.onurkat.reclazz.transform.KeepBaseline","hasVirtualRecordReceipt",new Class<?>[]{Class.class},p);
              boolean baseline=(Boolean)agent("com.onurkat.reclazz.transform.KeepBaseline",logical?"recordLogicalConstructorBodiesUnchanged":"constructorBodiesUnchanged",new Class<?>[]{Class.class,byte[].class},p,bytes);
              if(logical&&!(Boolean)agent("com.onurkat.reclazz.transform.KeepBaseline","constructorBodiesUnchanged",new Class<?>[]{Class.class,byte[].class},p,originalPhysical))throw new AssertionError("S changed original physical constructor proof");
              System.out.println("OBS|"+token+"|"+caller.getMethod("version").invoke(null)+"|"+p.getRecordComponents().length+"|"+fresh+"|"+held+"|"+calls+"|"+width+"|"+baseline+"|"+agent("com.onurkat.reclazz.ui.RestartLedger","digest",new Class<?>[0])+"|nativeWidths="+nativeWidths+"|"+noise.getMethod("version").invoke(null)+"|ordinaryRecordCohort="+ordinaryRecordCohort);System.out.flush();
            }}Thread.sleep(20);}
          }
        }
        """;
}
