/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.Socket;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only listener/ledger census. No instrumentation wrapper or payload-mismatch claim. */
class ConstructorRefusalCensusTest {
    @TempDir Path tmp;
    private static final ObjectMapper JSON=new ObjectMapper();
    private int request;
    private record Step(Map<String,String> sources,Set<String> owners,boolean batch) {}
    private static Step save(String name,String body){return new Step(Map.of(name,body),Set.of("app."+name),false);}
    private static String holder(int v,String fields,String methods,int a,String value){
        return "package app; public class Holder { public int a; "+fields+" public Holder(){a="+a+";} "+methods
                +" public String describe(){return \""+v+":\"+a"+value+";} }";
    }
    private static String color(int v,String constants,String fields,String methods,String value){
        return "package app; public enum Color { "+constants+"; "+fields+methods
                +" public String describe(){return \""+v+"\""+value+";} }";
    }
    @Test void enumAppend() throws Exception {
        run("enum-append",Map.of("Color",color(0,"RED,BLUE","","","")),true,List.of(
                save("Color",color(1,"RED,BLUE,GREEN","","","")),
                save("Color",color(2,"RED,BLUE,GREEN","","",""))),false);
    }
    @Test void enumAppendThenInstanceField() throws Exception {
        run("enum-append-field",Map.of("Color",color(0,"RED,BLUE","","","")),true,List.of(
                save("Color",color(1,"RED,BLUE,GREEN","","","")),
                save("Color",color(2,"RED,BLUE,GREEN","int added=7;","","+\":\"+added"))),false);
    }
    @Test void enumMethodThenAppend() throws Exception {
        run("enum-method-append",Map.of("Color",color(0,"RED,BLUE","","","")),true,List.of(
                save("Color",color(1,"RED,BLUE","","public int added(){return 11;}","")),
                save("Color",color(2,"RED,BLUE,GREEN","","public int added(){return 11;}",""))),false);
    }
    @Test void annotationOnSecondStructuralSave() throws Exception {
        String mark="package app; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Mark { int value(); }";
        String zero=holder(0,"","",1,"").replace("public class Holder","@Mark(0) public class Holder");
        String one=holder(1,"","public int added(){return 1;}",1,"").replace("public class Holder","@Mark(0) public class Holder");
        String two=holder(2,"int b=7;","public int added(){return 1;}",2,"+\":\"+b").replace("public class Holder","@Mark(2) public class Holder");
        run("annotation-second-structural",Map.of("Holder",zero,"Mark",mark),false,List.of(save("Holder",one),save("Holder",two)),false);
    }
    @Test void lambdaThenAddedField() throws Exception {
        String lambda="public java.util.function.IntSupplier added(){return ()->11;}";
        run("lambda-then-field",Map.of("Holder",holder(0,"","",1,"")),false,List.of(
                save("Holder",holder(1,"",lambda,1,"+\":lambda=\"+added().getAsInt()")),
                save("Holder",holder(2,"int b=7;",lambda,2,"+\":\"+b+\":lambda=\"+added().getAsInt()"))),false);
    }
    @Test void constructorOverloadThenAddedField() throws Exception {
        String check="public int overloadValue(){try{return Holder.class.getConstructor(int.class).newInstance(5).a;}catch(Exception e){throw new IllegalStateException(e);}}";
        String overload="public Holder(int value){a=value;}"+check;
        run("constructor-overload-field",Map.of("Holder",holder(0,"","",1,"")),false,List.of(
                save("Holder",holder(1,"",overload,1,"+\":overload=\"+overloadValue()")),
                save("Holder",holder(2,"int b=7;",overload,2,"+\":\"+b+\":overload=\"+overloadValue()")),
                save("Holder",holder(3,"int b=7;","public Holder(int value){a=value+10;}"+check,2,"+\":\"+b+\":overload=\"+overloadValue()"))),false);
    }

    @Test void addedStaticAndInstanceThenAnotherSave() throws Exception {
        run("static-instance-second-save",Map.of("Holder",holder(0,"","",1,"")),false,List.of(
                save("Holder",holder(1,"static int s=8; int b=7;","",1,"+\":\"+s+\":\"+b")),
                save("Holder",holder(2,"static int s=8; int b=7; int c=11;","",2,"+\":\"+s+\":\"+b+\":\"+c"))),false);
    }
    @Test void superclassFieldAfterMethodThenSubclassConstructor() throws Exception {
        String parent0="package app; public class Parent { public int a=1; public String base(){return \"p0\";} }";
        String parent1="package app; public class Parent { public int a=1; public String base(){return \"p1\";} public int added(){return 1;} }";
        String parent2="package app; public class Parent { public int a; public int b=7; public Parent(){a=2;} public String base(){return \"p2\";} public int added(){return 1;} }";
        String child0="package app; public class Holder extends Parent { public Holder(){} public String describe(){return base()+\":\"+a;} }";
        String child3="package app; public class Holder extends Parent { public Holder(){super();a=3;} public String describe(){return base()+\":\"+a+\":\"+b;} }";
        run("superclass-history",Map.of("Parent",parent0,"Holder",child0),false,List.of(save("Parent",parent1),save("Parent",parent2),save("Holder",child3)),false);
    }
    @Test void firstStructuralSaveBatchedWithAnotherClass() throws Exception {
        String noise0="package app; public class Noise { public String value(){return \"n0\";} }";
        String noise1="package app; public class Noise { public String value(){return \"n1\";} public int added(){return 1;} }";
        run("batch-history",Map.of("Holder",holder(0,"","",1,""),"Noise",noise0),false,List.of(
                new Step(Map.of("Holder",holder(1,"","public int added(){return 1;}",1,""),"Noise",noise1),Set.of("app.Holder","app.Noise"),true),
                save("Holder",holder(2,"int b=7;","public int added(){return 1;}",2,"+\":\"+b"))),false);
    }
    @Test void knownZeroRefusalControl() throws Exception {
        run("zero-control",Map.of("Holder",holder(0,"","",1,"")),false,List.of(
                save("Holder",holder(1,"","public int added(){return 1;}",1,"")),
                save("Holder",holder(2,"int b=7;","public int added(){return 1;}",2,"+\":\"+b"))),true);
    }

    private void run(String name,Map<String,String> initial,boolean isEnum,List<Step> steps,boolean zero) throws Exception {
        Path evidence=Path.of(Optional.ofNullable(System.getenv("RECLAZZ_R26_CENSUS"))
                .orElse(tmp.resolve("evidence").toString())).resolve(name);Files.createDirectories(evidence);
        Path marker=tmp.resolve("save.txt");Files.writeString(marker,"0");Path port=tmp.resolve("status.port");Path sensor=sensorJar();
        var builder=WatchedApp.in(tmp.resolve("app")).classpath(sensor.toString())
                .jvmArgs("-Dr26.save="+marker).agentArgs("verbose=true,startupDelaySec=1,debounceMs=200,statusPort=0,portFile="+port);
        initial.forEach(builder::with);builder.with("App",driver(isEnum,initial.containsKey("Noise")));
        List<String> observations=new ArrayList<>();boolean completed=false;
        try(WatchedApp app=builder.start()) {
            try {
                app.awaitOrFail("R26_SENSOR_READY","listener and ledger must be installed");
                app.awaitOrFail("OBS ","baseline old/fresh objects");app.awaitOrFail("] Watching 1 director","watcher ready");
                int save=0;
                for(Step step:steps) {
                    Files.writeString(marker,Integer.toString(++save));
                    if(step.batch)control(port,"pause",false);
                    app.rewriteAll(step.sources);
                    if(step.batch){control(port,"status",true);control(port,"resume",false);}
                    String observation=afterReceipts(app,save,step.owners);
                    observations.add(observation);
                    if(name.equals("constructor-overload-field")) {
                        String expected=switch(save) {
                            case 1 -> "OBS old1:1:overload=5|new1:1:overload=5";
                            case 2 -> "OBS old2:1:7:overload=5|new2:2:7:overload=5";
                            case 3 -> "OBS old3:1:7:overload=15|new3:2:7:overload=15";
                            default -> throw new AssertionError(save);
                        };
                        assertEquals(expected,observation,"physical and added constructors follow their latest bodies; existing a stays unchanged");
                    }
                    if(name.equals("superclass-history") && save==3)
                        assertEquals("OBS oldp2:1:7|newp2:3:7", observation);
                    assertFalse(app.output().stream().anyMatch(s->s.startsWith("R26_SENSOR_ERROR")),app.tail());
                }
                if(steps.stream().anyMatch(Step::batch))assertTrue(app.output().stream().anyMatch(s->s.contains("2 class files changed together")),"batch path was not witnessed");
                completed=true;
            } finally {
                Map<String,Object> result=report(name,app.output(),observations,completed);
                Files.writeString(evidence.resolve("result.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result)+"\n");
                Files.write(evidence.resolve("summary.txt"),app.output().stream().filter(s->s.startsWith("R26_")||s.startsWith("RUNTIME=")||s.contains("Structural hot-reload")||s.contains("2 class files changed together")).toList());
                System.out.println("CENSUS "+name+" refused="+result.get("refused")+" hits="+((List<?>)result.get("hits")).size()+" completed="+completed);
                if(completed)assertEquals(false,result.get("refused"),"the known-zero control must have no constructor refusal");
            }
        }
    }
    private static String afterReceipts(WatchedApp app,int save,Set<String> expected) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(System.nanoTime()<end) {
            List<String> lines=app.output();Set<String> owners=new HashSet<>();int last=-1;
            for(int i=0;i<lines.size();i++)if(lines.get(i).startsWith("R26_EVENT|RECEIPT|"+save+"|")) {
                String[] a=lines.get(i).split("\\|",5);String message=decode(a[4]);owners.add(owner(message));last=i;
            }
            if(owners.containsAll(expected))for(int i=last+1;i<lines.size();i++)if(lines.get(i).startsWith("OBS "))return lines.get(i);
            Thread.sleep(100);
        }
        fail("No completed reload receipt/observation for save"+save+" owners="+expected+": "+app.tail());return null;
    }
    private static String owner(String message) {
        String prefix=message.startsWith("Structural reload: ")?"Structural reload: ":"Reloaded ";
        return message.substring(prefix.length()).split("[ (]",2)[0];
    }
    private static String decode(String text){return new String(Base64.getDecoder().decode(text),StandardCharsets.UTF_8);}
    private static Map<String,Object> report(String name,List<String> lines,List<String> observations,boolean completed) {
        List<Map<String,Object>> hits=new ArrayList<>(),receipts=new ArrayList<>();List<String> ledger=new ArrayList<>();
        for(String line:lines)if(line.startsWith("R26_EVENT|")) {
            String[] a=line.split("\\|",5);String message=decode(a[4]);var event=new LinkedHashMap<String,Object>();
            event.put("save",Integer.parseInt(a[2]));event.put("level",a[3]);event.put("summary",message);
            if(a[1].equals("RECEIPT")){event.put("class",owner(message));receipts.add(event);}
            if(a[1].equals("HIT")){
                int start=message.indexOf(" in ");String cls;
                if(start>=0)cls=message.substring(start+4,message.indexOf(" read their default",start));
                else cls=message.substring("Constructor bodies not refreshed for ".length()).split(":",2)[0];
                event.put("class",cls);hits.add(event);
            }
            if(a[1].equals("LEDGER") && message.contains("a constructor the JVM would not redefine"))ledger.add(message);
        }
        var result=new LinkedHashMap<String,Object>();result.put("sequence",name);result.put("completed",completed);
        result.put("refused",!hits.isEmpty()||!ledger.isEmpty());result.put("hits",hits);result.put("receipts",receipts);result.put("ledger",ledger);result.put("observations",observations);
        result.put("runtime",lines.stream().filter(s->s.startsWith("RUNTIME=")).toList());
        result.put("observationErrors",lines.stream().filter(s->s.startsWith("R26_OBS_CAUSE")).toList());
        result.put("sensorErrors",lines.stream().filter(s->s.startsWith("R26_SENSOR_ERROR")).toList());return result;
    }
    private void control(Path file,String action,boolean scan) throws Exception {
        try(Socket socket=new Socket("127.0.0.1",Integer.parseInt(Files.readString(file).trim()))) {
            socket.setSoTimeout(15000);String id="census"+(++request),prefix="RELOAD_STATE "+id+" "+action+" ";
            var out=socket.getOutputStream();out.write(((scan?"SCAN\n":"")+"RELOAD "+action+" request="+id+"\n").getBytes(StandardCharsets.UTF_8));out.flush();
            var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            while(true){String line=reader.readLine();assertNotNull(line,"control connection closed");if(line.contains(prefix))return;}
        }
    }
    private static String driver(boolean isEnum,boolean noise) {
        String setup=isEnum?"Color old=Color.RED; java.util.EnumSet<Color> warm=java.util.EnumSet.of(old); java.util.EnumMap<Color,String> map=new java.util.EnumMap<>(Color.class);map.put(old,\"old\");":"Holder old=new Holder();";
        String value=isEnum?"old.describe()+\":values=\"+java.util.Arrays.toString(Color.values())":"\"old\"+old.describe()+\"|new\"+new Holder().describe()";
        return "package app; public class App { public static void main(String[] args)throws Exception { research.Census.install(); System.out.println(\"RUNTIME=\"+System.getProperty(\"java.vm.name\")+\" \"+System.getProperty(\"java.runtime.version\")+\" \"+System.getProperty(\"java.vendor\")); "+setup+(noise?"Noise noise=new Noise();":"")
                +" boolean reportedError=false; while(true){try{System.out.println(\"OBS \"+("+value+")"+(noise?"+\":noise=\"+noise.value()":"")+");}catch(Throwable e){System.out.println(\"OBS_ERROR \"+e);if(!reportedError){Throwable cause=e;for(int i=0;cause!=null && i<5;i++,cause=cause.getCause())System.out.println(\"R26_OBS_CAUSE \"+cause);reportedError=true;}}Thread.sleep(100);} } }";
    }
    private Path sensorJar() throws Exception {
        Path source=tmp.resolve("sensor/research/Census.java");Files.createDirectories(source.getParent());
        Files.writeString(source,"""
                package research;
                import java.lang.reflect.*;
                import java.nio.file.*;
                import java.nio.charset.StandardCharsets;
                import java.util.*;
                public class Census {
                    public static void install()throws Exception {
                        Class<?> reporter=Class.forName("com.onurkat.reclazz.ui.StatusReporter");
                        Class<?> listener=Class.forName("com.onurkat.reclazz.ui.StatusReporter$StatusListener");
                        Class<?> ledger=Class.forName("com.onurkat.reclazz.ui.RestartLedger");
                        Object observer=Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},(proxy,method,args)->{
                            if(method.getName().equals("onEvent")){
                                String level=(String)args[0],message=(String)args[1];
                                boolean hit=message.contains("read their default even on objects created after this reload") || message.startsWith("Constructor bodies not refreshed for ");
                                boolean receipt=level.equals("RELOAD") || level.equals("STRUCTURAL_RELOAD");
                                if(hit || receipt)try{
                                    String save=Files.readString(Path.of(System.getProperty("r26.save"))).trim();
                                    if(hit)emit("HIT",save,level,message);
                                    if(receipt){emit("RECEIPT",save,level,message);emit("LEDGER",save,"LEDGER",ledger.getMethod("digest").invoke(null).toString());}
                                }catch(Throwable error){System.out.println("R26_SENSOR_ERROR "+error);}
                                return null;
                            }
                            if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                            if(method.getName().equals("equals"))return proxy==args[0];
                            return "R26-test-listener";
                        });
                        reporter.getMethod("addListener",listener).invoke(null,observer);
                        System.out.println("R26_SENSOR_READY");
                    }
                    private static void emit(String kind,String save,String level,String message){
                        System.out.println("R26_EVENT|"+kind+"|"+save+"|"+level+"|"+Base64.getEncoder().encodeToString(message.getBytes(StandardCharsets.UTF_8)));
                    }
                }
                """);
        Path classes=Files.createDirectories(tmp.resolve("sensor-classes"));
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","17","-d",classes.toString(),source.toString()));
        Path jar=tmp.resolve("sensor.jar");try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(jar));var files=Files.walk(classes)){
            for(Path p:files.filter(Files::isRegularFile).toList()){out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\','/')));out.write(Files.readAllBytes(p));out.closeEntry();}
        }return jar;
    }
}
