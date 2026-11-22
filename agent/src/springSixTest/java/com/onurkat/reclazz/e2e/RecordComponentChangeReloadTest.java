/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;

/** R20 fallback acceptance. The private stock probe is deliberately ineffective; enabled S is
 * covered independently by RecordStock* admission, sequence, scope and caller-chain tests. */
class RecordComponentChangeReloadTest {
    @TempDir Path dir;
    @Test void addComponentWithCallerSameSave() throws Exception { shape("add"); }
    @Test void removeComponent() throws Exception { shape("remove"); }
    @Test void retypeComponent() throws Exception { shape("type"); }
    private void shape(String edit) throws Exception {
        String before=edit.equals("remove")?"String text,String marker":"String text";
        String after=switch(edit){case "add"->"String text,String marker";case "remove"->"String text";default->"int text";};
        String oldArgs=edit.equals("remove")?"\"before\",\"seed\"":"\"before\"";
        String newArgs=switch(edit){case "add"->"\"after\",\"mark\"";case "remove"->"\"after\"";default->"7";};
        Path signal=dir.resolve("signal");
        WatchedApp app=WatchedApp.in(dir).classpath(jacksonClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=200,verbose=true")
                .jvmArgs("-Dr20.signal="+signal)
                .with("Greeting",record(before)).with("Caller",caller(0,oldArgs)).with("App",APP).start();
        try(app) {
            app.awaitOrFail("READY","record held before save");app.awaitOrFail("] Watching 1 directory","watcher ready");
            app.rewriteAll(Map.of("Greeting",record(after),"Caller",caller(1,newArgs)));
            assertTrue(app.awaits("record components of app.Greeting changed",15),"record shape must be refused before publication; see owned child log");
            fallbackReached(app);
            Files.writeString(signal,"go");
            String text=edit.equals("remove")?"Greeting[text=before, marker=seed]":"Greeting[text=before]";
            String json=edit.equals("remove")?"{\"text\":\"before\",\"marker\":\"seed\"}":"{\"text\":\"before\"}";
            app.awaitOrFail("SAFE|0|before|"+text+"|"+text+"|true|"+json+"|"+(jackson3Present()?json:"not-present"),"fallback caller/held/fresh record and present Jackson families remain usable");
            app.awaitOrFail("PENDING|go|","restart ledger reply follows the SAFE observation");
            assertTrue(app.output().stream().anyMatch(s->s.contains("PENDING|")&&s.contains("app.Greeting")),"PENDING must identify refused record");
            assertFalse(app.output().stream().anyMatch(s->s.contains("[STRC]")&&s.contains("app.Greeting")),"no structural success receipt for refused record");
        } finally { remember(app,"shape-"+edit); }
    }
    static final String ORIGINAL="package app;public record Greeting(String text){}";
    static final String ADDED="package app;public record Greeting(String text,String marker){public Greeting(String text){this(text,\"default\");}}";
    static final String FACTORY="package app;public class Factory {public static Object make(){return new Greeting(\"before\");}}";
    static String factory(boolean safe){return FACTORY.substring(0,FACTORY.length()-1)+"public static Object makeWithMarker(){return new Greeting("+(safe?"\"after\"":"\"after\",\"mark\"")+");}}";}
    static String factoryCaller(int version){return "package app;public class Caller {public static int version(){return "+version+";}public static Object make(){return Factory."+(version==0?"make()":"makeWithMarker()")+";}}";}
    WatchedApp fixture(boolean auto)throws Exception { return fixture(auto,false); }
    WatchedApp fixture(boolean auto,boolean otherLoader)throws Exception {
        var b=WatchedApp.in(dir).classpath(jacksonClasspath()).agentArgs("startupDelaySec=1,debounceMs=200,verbose=true,autoCompile="+auto)
                .jvmArgs("-Dr20.signal="+dir.resolve("signal"),"-Dr20.other="+otherLoader).with("Greeting",ORIGINAL).with("Factory",FACTORY).with("Caller",factoryCaller(0)).with("App",APP);
        if(auto)b.mavenLayout();WatchedApp app=b.start();app.awaitOrFail("READY","baseline loaded");app.awaitOrFail("] Watching 1 directory","watcher ready");return app;
    }
    static void fresh(WatchedApp app,int start,String needle)throws Exception {
        long end=System.nanoTime()+20_000_000_000L;
        while(System.nanoTime()<end){var out=app.output();if(out.subList(Math.min(start,out.size()),out.size()).stream().anyMatch(s->s.contains(needle))){if(needle.equals("record components of app.Greeting changed"))fallbackReached(app);return;}Thread.sleep(50);}
        fail("Expected new child evidence: "+needle+"; full child log is retained");
    }
    private static void fallbackReached(WatchedApp app){
        assertTrue(app.output().contains("R20_PRIVATE_STOCK_PROBE_NATIVE_NOOP"),"actual private stock probe boundary reached");
        assertTrue(app.output().stream().anyMatch(s->s.contains("Stock record capability check failed")),"actual failed self-check retains R20 fallback");
    }
    void prove(WatchedApp app,String token,int version,String freshText)throws Exception {
        String request=token+":"+version;Files.writeString(dir.resolve("signal"),request);
        String json="{\"text\":\"before\"}";
        app.awaitOrFail("CHECK|"+request+"|SAFE|"+version+"|before|Greeting[text=before]|Greeting[text="+freshText+"]|"+freshText.equals("before")+"|"+json+"|"+(jackson3Present()?json:"not-present"),"existing and new instances stay callable");
        app.awaitOrFail("PENDING|"+request+"|","restart ledger reply follows the CHECK observation");
    }
    static void remember(WatchedApp app,String name)throws Exception {
        String destination=System.getenv("RECLAZZ_R20_TEST_OUTPUT");if(destination==null)return;Path out=Path.of(destination);Files.createDirectories(out);Files.write(out.resolve(name+".log"),app.output());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void twoHopFactoryControllerBothOrders(boolean reverse)throws Exception {
        var app=fixture(false);try(app){int start=app.output().size();var save=new LinkedHashMap<String,String>();
            if(reverse)save.put("Caller",factoryCaller(1));save.put("Greeting",ADDED);save.put("Factory",factory(false));save.put("Caller",factoryCaller(1));app.rewriteAll(save);
            fresh(app,start,"uses app.Factory.makeWithMarker");prove(app,"two-hop",0,"before");
        }finally{remember(app,"two-hop-"+reverse);}
    }
    @Test void twoHopViaCompilerRoute()throws Exception {
        var app=fixture(true);try(app){int start=app.output().size();for(var e:Map.of("Greeting",ADDED,"Factory",factory(false),"Caller",factoryCaller(1)).entrySet())Files.writeString(dir.resolve("src/main/java/app/"+e.getKey()+".java"),e.getValue());fresh(app,start,"uses app.Factory.makeWithMarker");prove(app,"compiler",0,"before");}finally{remember(app,"compiler");}
    }
    @Test void laterTwoHopCallerSaveRefused()throws Exception { later(false,false); }
    @Test void laterTwoHopCallerAfterRecordRestoreRefused()throws Exception { later(true,false); }
    @Test void sequentialShapesStaleCallerRefused()throws Exception { later(false,true); }
    void later(boolean restore,boolean anotherShape)throws Exception {
        var app=fixture(false);try(app){int start=app.output().size();app.rewriteAll(Map.of("Greeting",ADDED,"Factory",factory(false)));fresh(app,start,"record components of app.Greeting changed");
            if(anotherShape){start=app.output().size();app.rewrite("Greeting","package app;public record Greeting(String text,int count){public Greeting(String text){this(text,0);}}");fresh(app,start,"record components of app.Greeting changed");}
            if(restore){start=app.output().size();app.rewrite("Greeting",ORIGINAL);fresh(app,start,"Record component shape restored: app.Greeting");}
            start=app.output().size();app.rewrite("Caller",factoryCaller(1));fresh(app,start,"uses app.Factory.makeWithMarker");prove(app,"later",0,"before");
        }finally{remember(app,"later-"+restore+"-"+anotherShape);}
    }
    @Test void factoryAcceptedLaterThenCallerApplies()throws Exception {
        var app=fixture(false);try(app){int start=app.output().size();app.rewriteAll(Map.of("Greeting",ADDED,"Factory",factory(false)));fresh(app,start,"record components of app.Greeting changed");
            start=app.output().size();app.rewrite("Greeting",ORIGINAL);fresh(app,start,"Record component shape restored: app.Greeting");
            start=app.output().size();app.rewrite("Factory",factory(true));fresh(app,start,"Structural reload: app.Factory");
            app.rewrite("Caller",factoryCaller(1));prove(app,"accepted",1,"after");
        }finally{remember(app,"factory-accepted");}
    }
    @Test void laterCallerSaveRefused()throws Exception {
        var app=fixture(false);try(app){int start=app.output().size();app.rewrite("Greeting",ADDED);fresh(app,start,"record components of app.Greeting changed");
            start=app.output().size();app.rewrite("Caller",caller(1,"\"after\",\"mark\""));fresh(app,start,"uses app.Greeting.<init>");prove(app,"direct-later",0,"before");
        }finally{remember(app,"direct-later");}
    }
    @Test void restoreThenStaleCallerRefusedNewCallerApplies()throws Exception {
        var app=fixture(false);try(app){int start=app.output().size();app.rewrite("Greeting",ADDED);fresh(app,start,"record components of app.Greeting changed");
            start=app.output().size();app.rewrite("Caller",caller(2,"\"stale\",\"mark\""));fresh(app,start,"uses app.Greeting.<init>");byte[] stale=Files.readAllBytes(app.classesDir().resolve("app/Caller.class"));
            start=app.output().size();app.rewrite("Greeting",ORIGINAL);fresh(app,start,"Record component shape restored: app.Greeting");
            app.rewrite("Caller",caller(1,"\"before\""));prove(app,"safe-before-stale",1,"before");
            start=app.output().size();Files.write(app.classesDir().resolve("app/Caller.class"),stale);fresh(app,start,"uses app.Greeting.<init>");prove(app,"stale-after-restore",1,"before");
            app.rewrite("Caller",caller(3,"\"before\""));prove(app,"safe-after-stale",3,"before");
            assertTrue(app.output().stream().anyMatch(line->line.contains("PENDING|safe-after-stale:3|")&&!line.contains("app.Greeting")),"successful original-shape restore clears PENDING only");
        }finally{remember(app,"restore-stale");}
    }
    @Test void failedRestoreKeepsPending()throws Exception {
        var app=fixture(false);try(app){int start=app.output().size();app.rewrite("Greeting",ADDED);fresh(app,start,"record components of app.Greeting changed");
            Files.createFile(dir.resolve("fail-native"));start=app.output().size();app.rewrite("Greeting",ORIGINAL.replace("{}","{public int restored(){return 1;}}"));fresh(app,start,"test R20 native restore failure");prove(app,"failed-restore",0,"before");
            assertTrue(app.output().stream().anyMatch(line->line.contains("PENDING|failed-restore:0|")&&line.contains("app.Greeting")),"failed native admission cannot clear PENDING");
            assertFalse(app.output().stream().anyMatch(line->line.contains("Record component shape restored:")));
        }finally{remember(app,"failed-restore");}
    }
    @Test void sameNameOtherLoaderUnaffected()throws Exception {
        var app=fixture(false,true);try(app){int start=app.output().size();app.rewrite("Greeting",ADDED);fresh(app,start,"record components of app.Greeting changed");prove(app,"other-loader",0,"before");
            app.awaitOrFail("OTHER|other-loader:0|Greeting[text=before]|Greeting[text=before]|true|1","other defining loader keeps native construction, ObjectMethods and metadata");
        }finally{remember(app,"other-loader");}
    }
    @Test void bodyOnlyRecordEditReloads()throws Exception {
        String record="package app;public record Greeting(String text){public static String body(){return \"one\";}}";
        var app=WatchedApp.in(dir).agentArgs("startupDelaySec=1,debounceMs=100").with("Greeting",record).with("App","package app;public class App {public static void main(String[]args)throws Exception{new Greeting(\"before\");System.out.println(\"READY\");while(true){if(Greeting.body().equals(\"two\"))System.out.println(\"BODY_TWO\");Thread.sleep(50);}}}").start();
        try(app){app.awaitOrFail("READY","record loaded");app.awaitOrFail("] Watching 1 directory","watcher ready");app.rewrite("Greeting",record.replace("one","two"));app.awaitOrFail("BODY_TWO","unchanged component shape body reloads");}finally{remember(app,"body-only");}
    }
    static String record(String components){return "package app;public record Greeting("+components+"){}";}
    static String caller(int version,String args){return "package app;public class Caller {public static int version(){return "+version+";}public static Object make(){return new Greeting("+args+");}}";}
    static boolean jackson3Present() {
        try { Class.forName("tools.jackson.databind.json.JsonMapper"); return true; }
        catch(ClassNotFoundException absent) { return false; }
    }
    static String jacksonClasspath() throws Exception {
        var entries=new LinkedHashSet<String>();
        // This is the explicit stock/fallback matrix. Boot3 runs Jackson2; Boot4 also checks its real Jackson3.
        for(String name:List.of("com.fasterxml.jackson.databind.ObjectMapper","com.fasterxml.jackson.core.JsonFactory","com.fasterxml.jackson.annotation.JsonProperty","tools.jackson.databind.json.JsonMapper","tools.jackson.core.json.JsonFactory")) {
            try { entries.add(Path.of(Class.forName(name).getProtectionDomain().getCodeSource().getLocation().toURI()).toString()); }
            catch(ClassNotFoundException absent) { if(!name.startsWith("tools.jackson.")) throw absent; }
        }
        return String.join(File.pathSeparator,entries);
    }
    static final String APP="""
        package app;import java.nio.file.*;import java.util.*;
        public class App {
          static String json(Object v,String name)throws Exception {Class<?> type;try{type=Class.forName(name);}catch(ClassNotFoundException absent){if(name.startsWith("tools.jackson."))return "not-present";throw absent;}Object mapper=type.getConstructor().newInstance();return (String)mapper.getClass().getMethod("writeValueAsString",Object.class).invoke(mapper,v);}
          public static void main(String[]args)throws Exception {
            Class<?> agent=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent");
            var reloaderField=agent.getDeclaredField("structuralReloader");reloaderField.setAccessible(true);Object reloader=reloaderField.get(null);
            var instField=reloader.getClass().getDeclaredField("instrumentation");instField.setAccessible(true);var real=(java.lang.instrument.Instrumentation)instField.get(reloader);
            var fault=Path.of(System.getProperty("r20.signal")).resolveSibling("fail-native");
            var proxy=java.lang.reflect.Proxy.newProxyInstance(App.class.getClassLoader(),new Class<?>[]{java.lang.instrument.Instrumentation.class},(p,m,a)->{
              if(m.getName().equals("redefineClasses")&&java.util.Arrays.stream((java.lang.instrument.ClassDefinition[])a[0]).anyMatch(d->d.getDefinitionClass().getName().startsWith("com.example.reclazzprobe.Stock"))){
                System.out.println("R20_PRIVATE_STOCK_PROBE_NATIVE_NOOP");System.out.flush();return null;
              }
              if(m.getName().equals("redefineClasses")&&Files.exists(fault))for(var definition:(java.lang.instrument.ClassDefinition[])a[0])if(definition.getDefinitionClass().getName().equals("app.Greeting"))throw new ClassFormatError("test R20 native restore failure");
              try{return m.invoke(real,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            });instField.set(reloader,proxy);
            var stockInstrumentation=agent.getDeclaredField("instrumentation");stockInstrumentation.setAccessible(true);stockInstrumentation.set(null,proxy);
            Object held=Caller.make();Class<?> duplicate=null;Object duplicateHeld=null;
            if(Boolean.getBoolean("r20.other")){
              Path root=Path.of(System.getProperty("r20.signal")).resolveSibling("other-loader");Files.createDirectories(root.resolve("app"));
              Path original=Path.of(App.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("app/Greeting.class");Files.copy(original,root.resolve("app/Greeting.class"));
              var loader=new java.net.URLClassLoader(new java.net.URL[]{root.toUri().toURL()},ClassLoader.getPlatformClassLoader());duplicate=Class.forName("app.Greeting",true,loader);duplicateHeld=duplicate.getConstructor(String.class).newInstance("before");
              if(duplicate==held.getClass()||duplicate.getClassLoader()==held.getClass().getClassLoader())throw new AssertionError("distinct defining loader required");
            }
            System.out.println("READY");
            Path signal=Path.of(System.getProperty("r20.signal"));String previous="";
            while(true){if(Files.exists(signal)){String token=Files.readString(signal);int expected=token.contains(":")?Integer.parseInt(token.substring(token.lastIndexOf(':')+1)):0;
            if(!token.equals(previous)&&Caller.version()==expected){previous=token;
            try {Object fresh=Caller.make();System.out.println("CHECK|"+token+"|SAFE|"+Caller.version()+"|"+held.getClass().getMethod("text").invoke(held)+"|"+held+"|"+fresh+"|"+held.equals(fresh)+"|"+json(held,"com.fasterxml.jackson.databind.ObjectMapper")+"|"+json(held,"tools.jackson.databind.json.JsonMapper"));}
            catch(Throwable failure){System.out.println("UNSAFE|"+failure);failure.printStackTrace();}
            if(duplicate!=null){Object otherFresh=duplicate.getConstructor(String.class).newInstance("before");System.out.println("OTHER|"+token+"|"+duplicateHeld+"|"+otherFresh+"|"+duplicateHeld.equals(otherFresh)+"|"+duplicate.getRecordComponents().length);}
            System.out.println("PENDING|"+token+"|"+Class.forName("com.onurkat.reclazz.ui.RestartLedger").getMethod("digest").invoke(null));
            }}Thread.sleep(50);}

          }
        }
        """;
}
