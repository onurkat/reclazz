/* Copyright 2026 Onur Kat; SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.*;
import java.util.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
abstract class RabbitConsumerCascadeContract {
    @TempDir Path tmp;
    interface Backend extends AutoCloseable {
        String classpath(); String app(); String annotation(String id,String destination);
        String[] args(); void prepare(String target,String other)throws Exception;
        void send(String destination,String value)throws Exception;
    }
    abstract Backend open()throws Exception;
    @ParameterizedTest @CsvSource({"dependency,false,false","property,false,false","dependency,true,false","property,true,false","property,false,true","property,true,true"})
    void helperAndPropertyChangesCoordinateConsumer(String trigger,boolean added,boolean ownValue)throws Exception {
        try(var b=open()) {
            // Fresh native startup is the functional oracle, not a claim that Spring hot-recreates listeners unaided.
            for(int seed:new int[]{10,20,30,40}) {
                Path root=tmp.resolve("native-"+seed);String q="native-"+UUID.randomUUID();b.prepare(q,q+"-other");
                try(var app=start(b,root,trigger,seed,q,q+"-other",true,true,ownValue)) {
                    int port=port(app);ready(b,app,port);assertDefaults(port);
                    var nativeChecks=new ArrayList<org.junit.jupiter.api.function.Executable>();message(b,app,q,"native"+seed,seed,nativeChecks,ownValue);assertAll("SETUP: native expected seed "+seed,nativeChecks);
                    other(b,app,q+"-other","native"+seed);
                    assertEquals("allStopped=true",http(port,"/close"),app.tail());
                    assertFalse(app.output().stream().anyMatch(x->x.contains("[Reclazz]")),"agentless control loaded agent");
                    System.out.println("RF14_NATIVE "+getClass().getSimpleName()+" "+trigger+" seed="+seed+" PASSED");
                }
            }
            String q="watch-"+UUID.randomUUID();b.prepare(q,q+"-other");
            try(var app=start(b,tmp.resolve("watch"),trigger,10,q,q+"-other",!added,false,ownValue)) {
                int port=port(app);app.awaitOrFail("Last-known-good bytecode cache:","SETUP: watcher initialization missing");
                try {
                    System.out.println("RF14_BEFORE_WARM "+getClass().getSimpleName()+" trigger="+trigger+" added="+added+" "+http(port,"/state"));
                    if(added) {
                        app.rewrite("Listener",listener(b,q,true,ownValue));
                        assertTrue(app.awaits("Reloaded app.Listener",20)||app.awaits("Structural reload: app.Listener",5),"SETUP: listener warmup reload missing\n"+app.tail());
                    }
                    ready(b,app,port);assertDefaults(port);
                    assertTrue(http(port,"/state").contains("trackedWorkers=1;"),"SETUP: actual native worker must be tracked on agent run");
                    System.out.println("RF14_WARM "+getClass().getSimpleName()+" trigger="+trigger+" added="+added+" "+http(port,"/state"));
                    var checks=new ArrayList<org.junit.jupiter.api.function.Executable>();
                    message(b,app,q,"warm",10,checks,ownValue);other(b,app,q+"-other","warm");
                    assertAll("SETUP: warm listener must actually work",checks);checks.clear();
                    Path listener=tmp.resolve("watch/src/app/Listener.java");byte[] original=Files.readAllBytes(listener);
                    long directBefore=app.output().stream().filter(x->x.contains("Reloaded app.Listener ")||x.contains("Structural reload: app.Listener ")).count();
                    for(int stage=1;stage<=3;stage++) {
                        int expected=(stage+1)*10;
                        http(port,"/snapshot");
                        String needle=trigger.equals("dependency")?"Reloaded app.Helper":"Applied 1 property change";
                        long count=app.output().stream().filter(x->x.contains(needle)).count();
                        if(trigger.equals("dependency"))app.rewrite("Helper",helper(trigger,expected));
                        else Files.writeString(tmp.resolve("watch/classes/application.properties"),"helper.seed="+expected+"\n");
                        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                        while(System.nanoTime()<until && app.output().stream().filter(x->x.contains(needle)).count()<=count)Thread.sleep(25);
                        boolean completed=app.output().stream().filter(x->x.contains(needle)).count()>count;
                        checks.add(()->assertTrue(completed,"SAVE_NOT_APPLIED: watcher completion did not advance for " + needle + "\n" + app.tail()));
                        String state=http(port,"/state");System.out.println("RF14_STATE "+getClass().getSimpleName()+" trigger="+trigger+" added="+added+" stage="+stage+" "+state);
                        other(b,app,q+"-other","save"+stage);
                        message(b,app,q,"save"+stage,expected,checks,ownValue);
                        checks.add(()->assertTrue(state.contains("oldRunning=0"),"old target worker still active: "+state));
                        checks.add(()->assertTrue(state.contains("helperSeed="+expected+";"),"helper not rebuilt: "+state));
                        checks.add(()->assertTrue(state.contains("helperDependents=[listener];listenerDependencies=[helper];"),"native constructor edges lost: "+state));
                        checks.add(()->assertTrue(state.contains("liveSeed="+expected+";"),"current owner captured stale seed: "+state));
                        int expectedOwn=ownValue?expected:-1;
                        checks.add(()->assertTrue(state.contains("liveOwn="+expectedOwn+";"),"current owner scalar is stale: "+state));
                        checks.add(()->assertTrue(state.contains("liveOwner=true;"),"listener singleton missing: "+state));
                        checks.add(()->assertTrue(state.contains("registryCount=2;"),"unexpected registry membership: "+state));
                        checks.add(()->assertTrue(state.contains("otherSame=true;"),"unrelated container replaced: "+state));
                        checks.add(()->assertTrue(state.contains("bornWithOldActive=0;"),"replacement created before old worker stopped: "+state));
                        checks.add(()->assertArrayEquals(original,Files.readAllBytes(listener),"listener source must NOT be saved"));
                    }
                    long directAfter=app.output().stream().filter(x->x.contains("Reloaded app.Listener ")||x.contains("Structural reload: app.Listener ")).count();
                    checks.add(()->assertEquals(directBefore,directAfter,"listener was accidentally reloaded directly"));
                    assertAll("DESIRED_CONSUMER_CASCADE "+getClass().getSimpleName()+" "+trigger+" added="+added,checks);
                } finally {String closed=http(port,"/close");System.out.println("RF14_CLOSE "+closed);assertEquals("allStopped=true",closed,app.tail());}
            }
        }
    }
    private WatchedApp start(Backend b,Path root,String trigger,int seed,String q,String other,boolean annotated,boolean nativeOnly,boolean ownValue)throws Exception {
        Files.createDirectories(root.resolve("classes"));Files.writeString(root.resolve("classes/application.properties"),"helper.seed="+seed+"\n");
        var x=WatchedApp.in(root).classpath(b.classpath()).jvmArgs(b.args()).agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
            .with("App",b.app()).with("Helper",helper(trigger,seed)).with("Listener",listener(b,q,annotated,ownValue))
            .with("Other","package app; @org.springframework.stereotype.Component public class Other { "+b.annotation("other",other)+" public void receive(String value){System.out.println(\"OTHER:\"+value);} }");
        if(nativeOnly)x.withoutAgent();return x.start();
    }
    private static String helper(String trigger,int seed) {
        return trigger.equals("dependency")?"package app; @org.springframework.stereotype.Component public class Helper { public final int seed; public Helper(){ seed=compute(); } private int compute(){return "+seed+";} }"
          :"package app; @org.springframework.stereotype.Component public class Helper { public final int seed; public Helper(@org.springframework.beans.factory.annotation.Value(\"${helper.seed}\") int value){seed=value;} }";
    }
    private static String listener(Backend b,String q,boolean annotated,boolean ownValue) {
        String parameter = ownValue ? ", @org.springframework.beans.factory.annotation.Value(\"${helper.seed}\") int value" : "";
        return "package app; @org.springframework.stereotype.Component public class Listener { public final int seed,id,own; public Listener(Helper helper"
                + parameter + "){seed=helper.seed;own=" + (ownValue ? "value" : "-1")
                + ";id=App.born();} public String use(){return \"used\";} "
                + (annotated ? b.annotation("target",q)+" public void receive(String value){System.out.println(\"DATA:\"+value+\":\"+seed+\":\"+id+\":\"+own);}" : "") + " }";
    }

    private static int port(WatchedApp app){app.awaitOrFail("PORT=","SETUP: native app failed to start");return Integer.parseInt(app.latest("PORT=").substring(5));}
    private static void ready(Backend b,WatchedApp app,int port)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);String state="";
        do{state=http(port,"/state");if(state.contains("targetReady=true;")&&state.contains("otherReady=true;"))return;Thread.sleep(50);}while(System.nanoTime()<end);
        fail("SETUP: listener assignment missing "+state+"\n"+app.tail());
    }
    private static void assertDefaults(int port)throws Exception {
        String state=http(port,"/state");
        assertTrue(state.contains("ackMode=AUTO;channelTransacted=false;"),"SETUP: native default AUTO/nontransacted required: "+state);
    }
    private static void message(Backend b,WatchedApp app,String q,String value,int expected,List<org.junit.jupiter.api.function.Executable> checks,boolean ownValue)throws Exception {
        b.send(q,value);assertTrue(app.awaits("DATA:"+value+":",20),"DELIVERY_MISSING "+value+"\n"+app.tail());Thread.sleep(300);
        String line=app.latest("DATA:"+value+":");System.out.println("RF14_MESSAGE "+line+" expectedSeed="+expected);
        checks.add(()->assertTrue(line.startsWith("DATA:"+value+":"+expected+":"),"STALE_CAPTURED_DEPENDENCY expected="+expected+" actual="+line));
        int expectedOwn=ownValue?expected:-1;
        checks.add(()->assertTrue(line.endsWith(":"+expectedOwn),"STALE_OWN_CONSTRUCTOR_PROPERTY expected="+expectedOwn+" actual="+line));
        checks.add(()->assertEquals(1,app.output().stream().filter(x->x.startsWith("DATA:"+value+":")).count(),"duplicate observed delivery "+value));
    }
    private static void other(Backend b,WatchedApp app,String q,String value)throws Exception {b.send(q,value);assertTrue(app.awaits("OTHER:"+value,20),"unrelated delivery missing\n"+app.tail());}
    private static String http(int port,String path)throws Exception {return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30)).GET().build(),HttpResponse.BodyHandlers.ofString()).body();}
}
