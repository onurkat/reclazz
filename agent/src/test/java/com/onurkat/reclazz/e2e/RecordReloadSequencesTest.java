/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import com.onurkat.reclazz.e2e.harness.JavaLauncher;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Repeated real agent saves, old objects/hash keys and retained canonical descriptors. */
class RecordReloadSequencesTest {
    @TempDir Path dir;
    @Test void addThenRemove()throws Exception{run("int a","int a,String b","int a","P[a=7, b=one]","P[a=7]","P[a=7, b=null]","P[a=7]",false,false);}
    @Test void removeThenRetype()throws Exception{run("int a,String b","int a","String a","P[a=7]","P[a=two]","P[a=7]","P[a=null]",false,false);}
    @Test void retypeTwice()throws Exception{run("int a","String a","long a","P[a=one]","P[a=11]","P[a=null]","P[a=0]",false,false);}
    @Test void reorderThenAdd()throws Exception{run("int a,String b","String b,int a","String b,int a,long c","P[b=one, a=7]","P[b=two, a=7, c=11]","P[b=old, a=7]","P[b=old, a=7, c=0]",false,false);}
    @Test void bodyOnlyAfterShape()throws Exception{run("int a","int a,String b","int a,String b","P[a=7, b=one]","P[a=17, b=two]","P[a=7, b=null]","P[a=7, b=null]",true,false);}
    @Test void rejectedSaveDoesNotRetargetActiveCanonical()throws Exception{run("int a","int a,String b","int a,String b","P[a=7, b=one]","P[a=7, b=one]","P[a=7, b=null]","P[a=7, b=null]",true,true);}
    private static String record(String shape,boolean changedBody){return "package app;public record P("+shape+")"+(changedBody?"{public P {a+=10;}}":"{}");}
    private static String caller(String shape,String word,int version){
        String args=String.join(",",Arrays.stream(shape.split(",")).map(s->s.startsWith("int ")?"7":s.startsWith("long ")?"11L":"\""+word+"\"").toList());
        return "package app;public class Caller {public static int version(){return "+version+";}public static Object make(){return new P("+args+");}}";
    }
    private void run(String before,String first,String last,String freshOne,String freshTwo,String oldOne,String oldTwo,boolean body,boolean reject)throws Exception{
        var protocol=new RecordStockBatchAdmissionTest();protocol.dir=dir;
        String java=JavaLauncher.resolve(Path.of(System.getProperty("reclazz.record.stock.java",Path.of(System.getProperty("java.home"),"bin","java").toString()))).toString();
        var builder=WatchedApp.in(dir).javaExecutable(Path.of(java)).agentArgs("startupDelaySec=1,debounceMs=250,verbose=true,portFile="+dir.resolve("agent.port"))
            .jvmArgs("-Dprobe.dir="+dir).with("P",record(before,false)).with("Caller",caller(before,"old",0)).with("App",APP);
        if(Boolean.getBoolean("reclazz.record.unsafe.deny"))builder.jvmArgs("--sun-misc-unsafe-memory-access=deny","-Dreclazz.record.unsafe.deny=true");
        WatchedApp child=builder.start();
        try(child){
            child.awaitOrFail("] Watching 1 director","watcher before record baseline");Files.writeString(dir.resolve("load"),"go");child.awaitOrFail("SEQUENCE_READY|","physical startup record and retained original canonical");
            save(protocol,child,first,"one",1,false,false);observe(child,"one",1,freshOne,oldOne);
            save(protocol,child,last,"two",2,body,reject);observe(child,reject?"rejected":"two",reject?1:2,freshTwo,oldTwo);
            if(body&&!reject)assertTrue(child.output().contains("SEQUENCE_HELD_CANONICAL|P[a=17, b=one]"),child.tail());
            assertFalse(child.output().stream().anyMatch(l->l.contains("Constructor bodies not refreshed for app.P")),child.tail());
        }finally{
            Path evidence=Path.of(System.getProperty("reclazz.record.evidenceDir",dir.toString()));Files.createDirectories(evidence);
            Files.write(evidence.resolve("sequence-"+UUID.randomUUID()+".log"),child.output());
        }
    }
    private void save(RecordStockBatchAdmissionTest protocol,WatchedApp child,String shape,String word,int version,boolean body,boolean reject)throws Exception{
        protocol.control("pause");if(reject)Files.writeString(dir.resolve("reject"),"go");
        child.rewriteAll(Map.of("P",record(shape,body),"Caller",caller(shape,word,version)));
        protocol.request("SCAN\nRELOAD status request=scanned"+version,"RELOAD_STATE scanned"+version+" status ");protocol.control("resume");
        protocol.receipt(child,"P",reject?"failed":"applied",reject?"native":"normal");protocol.receipt(child,"Caller",reject?"failed":"applied",reject?"native":"normal");
    }
    private void observe(WatchedApp child,String token,int version,String fresh,String old)throws Exception{
        Files.writeString(dir.resolve("observe"),token);child.awaitOrFail("SEQUENCE_DONE|"+token+"|","same-save record/caller and old object observations");
        String line=child.latest("SEQUENCE_DONE|"+token+"|");assertTrue(line.contains("|errors=0|"),child.tail());
        assertTrue(line.contains("|version="+version+"|fresh="+fresh+"|old="+old+"|"),line);
    }
    private static final String APP="""
        package app;
        import java.nio.file.*;import java.lang.reflect.*;import java.lang.instrument.*;import java.util.*;
        public class App {
          static int errors,checks;static void check(boolean ok,String label){checks++;if(!ok)errors++;System.out.println("SEQUENCE_CHECK|"+label+"|"+ok);System.out.flush();}
          static Object agent(String type,String method,Class<?>[]types,Object...args)throws Exception{var m=Class.forName(type).getDeclaredMethod(method,types);m.setAccessible(true);return m.invoke(null,args);}
          static Object defaultValue(Class<?> type){return type.isPrimitive()?Array.get(Array.newInstance(type,1),0):null;}
          public static void main(String[]args)throws Exception{
            Path dir=Path.of(System.getProperty("probe.dir"));while(!Files.exists(dir.resolve("load")))Thread.sleep(20);
            Class<?> p=Class.forName("app.P"),c=Class.forName("app.Caller");Object original=c.getMethod("make").invoke(null);
            var initialComponents=p.getRecordComponents();Class<?>[] initialTypes=Arrays.stream(initialComponents).map(RecordComponent::getType).toArray(Class<?>[]::new);
            var initial=p.getDeclaredConstructor(initialTypes);Object[] initialValues=new Object[initialTypes.length];Map<String,Class<?>> oldTypes=new HashMap<>();Map<String,Object> oldValues=new HashMap<>();
            for(int i=0;i<initialComponents.length;i++){var component=initialComponents[i];initialValues[i]=component.getAccessor().invoke(original);oldTypes.put(component.getName(),component.getType());oldValues.put(component.getName(),initialValues[i]);}
            Map<Object,String> keys=new HashMap<>();keys.put(original,"initial");int originalHash=original.hashCode();
            byte[] physical=Files.readAllBytes(Path.of(p.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("app/P.class"));
            Class<?> engine=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent");Field field=engine.getDeclaredField("instrumentation");field.setAccessible(true);var real=(Instrumentation)field.get(null);
            var observed=Proxy.newProxyInstance(App.class.getClassLoader(),new Class<?>[]{Instrumentation.class},(o,m,a)->{
              if(m.getName().equals("redefineClasses")&&Arrays.stream((ClassDefinition[])a[0]).anyMatch(d->d.getDefinitionClass()==p)&&Files.exists(dir.resolve("reject")))throw new UnsupportedOperationException("test benign native record body refusal");
              try{return m.invoke(real,a);}catch(InvocationTargetException failure){throw failure.getCause();}
            });field.set(null,observed);
            System.out.println("SEQUENCE_READY|runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));System.out.flush();
            String last="";Constructor<?> accepted=null;Object[] acceptedValues=null;Class<?>[] acceptedTypes=null;byte[] acceptedRaw=null;
            for(;;){Path signal=dir.resolve("observe");if(Files.exists(signal)){String token=Files.readString(signal);if(!token.equals(last)){
              last=token;errors=0;checks=0;Object fresh=c.getMethod("make").invoke(null);var components=p.getRecordComponents();Class<?>[] types=Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);Object[] values=new Object[types.length];
              for(int i=0;i<components.length;i++){var component=components[i];values[i]=component.getAccessor().invoke(fresh);Object expected=oldTypes.get(component.getName())==component.getType()?oldValues.get(component.getName()):defaultValue(component.getType());check(Objects.equals(expected,component.getAccessor().invoke(original)),"old compatible/default component "+component.getName());}
              check(p.getDeclaredConstructor(types).newInstance(values).getClass()==p,"current canonical descriptor is callable");Object equivalent=c.getMethod("make").invoke(null);check(fresh.equals(equivalent)&&fresh.hashCode()==equivalent.hashCode()&&fresh.toString().equals(equivalent.toString())&&new HashSet<>(List.of(equivalent)).contains(fresh),"current ObjectMethods and new hash key");
              check(keys.size()==1&&(originalHash==original.hashCode()||!keys.containsKey(original)),"existing hash keys are not rehashed");
              boolean visible;try{p.getDeclaredConstructor(initialTypes);visible=true;}catch(NoSuchMethodException retired){visible=false;}
              if(visible)check(initial.newInstance(initialValues).getClass()==p,"retained original descriptor follows current canonical");
              else try{initial.newInstance(initialValues);check(false,"retired original descriptor");}catch(InvocationTargetException retired){check(retired.getCause() instanceof NoSuchMethodError,"retired original descriptor");}
              byte[] raw=Files.readAllBytes(Path.of(p.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("app/P.class"));
              if(token.equals("one")){accepted=p.getDeclaredConstructor(types);acceptedValues=values;acceptedTypes=types;acceptedRaw=raw;}
              else if(token.equals("rejected")){check(accepted.newInstance(acceptedValues).equals(fresh),"rejected body save does not retarget held canonical");check((Boolean)agent("com.onurkat.reclazz.transform.KeepBaseline","recordLogicalConstructorBodiesUnchanged",new Class<?>[]{Class.class,byte[].class},p,acceptedRaw),"failed save keeps accepted logical proof");}
              else {boolean active;try{p.getDeclaredConstructor(acceptedTypes);active=true;}catch(NoSuchMethodException retired){active=false;}
                if(active){Object held=accepted.newInstance(acceptedValues);check(held.getClass()==p,"retained first-epoch canonical follows next accepted body");System.out.println("SEQUENCE_HELD_CANONICAL|"+held);}
                else try{accepted.newInstance(acceptedValues);check(false,"first-epoch canonical retires");}catch(InvocationTargetException retired){check(retired.getCause() instanceof NoSuchMethodError,"first-epoch canonical retires");}}
              if(!token.equals("rejected"))check((Boolean)agent("com.onurkat.reclazz.transform.KeepBaseline","recordLogicalConstructorBodiesUnchanged",new Class<?>[]{Class.class,byte[].class},p,raw),"current logical proof matches accepted save");
              check((Boolean)agent("com.onurkat.reclazz.transform.KeepBaseline","constructorBodiesUnchanged",new Class<?>[]{Class.class,byte[].class},p,physical),"original physical proof survives sequence");
              System.out.println("SEQUENCE_DONE|"+token+"|checks="+checks+"|errors="+errors+"|version="+c.getMethod("version").invoke(null)+"|fresh="+fresh+"|old="+original+"|runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));System.out.flush();
            }}Thread.sleep(20);}
          }
        }
        """;
}
