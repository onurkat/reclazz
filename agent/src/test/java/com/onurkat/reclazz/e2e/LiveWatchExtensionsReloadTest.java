/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LiveWatchExtensionsReloadTest {
    @TempDir Path temp;
    Path a,b,port,request;
    void compile(Path extension,String name,int value,boolean added)throws Exception {
        Path source=Files.createDirectories(extension.resolve("src/app")).resolve(name+".java");
        Files.writeString(source,"package app; public class "+name+" { public int value(){return "+value+";} "+(added?"public int added(){return 7;}":"")+" }");
        Path output=Files.createDirectories(extension.resolve("classes"));
        try(var diagnostics=new ByteArrayOutputStream()){
            int result=ToolProvider.getSystemJavaCompiler().run(null,diagnostics,diagnostics,"--release","17","-d",output.toString(),source.toString());
            assertEquals(0,result,diagnostics.toString(StandardCharsets.UTF_8));
        }
    }
    String command(String command)throws Exception { return command(command,null); }
    String command(String command,java.util.concurrent.CountDownLatch pending)throws Exception {
        int number=Integer.parseInt(Files.readString(port).strip());
        try(var socket=new Socket("127.0.0.1",number)){
            socket.setSoTimeout(30000);
            var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            var writer=new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8),true);
            var mapper=new ObjectMapper();reader.readLine();writer.println(command);
            String line;while((line=reader.readLine())!=null){String message=mapper.readTree(line).path("message").asText();
                if(message.startsWith("CONFIG ")&&message.contains(" PENDING ")&&pending!=null)pending.countDown();
                if(message.startsWith("CONFIG ")&&!message.contains(" PENDING "))return message;}
            throw new AssertionError("No terminal CONFIG reply");
        }
    }
    void probe(WatchedApp app,String token,String expected)throws Exception {
        Files.writeString(request,token);app.awaitOrFail("PROBE="+token+":"+expected,"scope reload probe did not match "+expected);
    }
    @Test void sameJvmAddsRawAndFutureClassesThenStopsRemovedExtension()throws Exception {
        Path home=Files.createDirectories(temp.resolve("commerce"));Files.createDirectories(home.resolve("bin/platform"));Files.createDirectories(home.resolve("config"));
        a=home.resolve("bin/custom/a");b=home.resolve("bin/custom/b");
        for(Path extension:List.of(a,b)){
            Files.createDirectories(extension.resolve("resources"));Files.createDirectories(extension.resolve("src"));
            Files.writeString(extension.resolve("extensioninfo.xml"),"<extensioninfo><extension name='"+extension.getFileName()+"'><coremodule/></extension></extensioninfo>");
        }
        Files.writeString(home.resolve("config/localextensions.xml"),"<hybrisconfig><extensions><extension dir='"+a+"'/><extension dir='"+b+"'/></extensions></hybrisconfig>");
        compile(a,"A",1,false);compile(b,"B",1,false);compile(b,"Future",1,false);
        port=temp.resolve("agent.port");request=temp.resolve("request");
        try(var app=WatchedApp.in(temp.resolve("driver"))
                .agentArgs("hybrisHome="+home+",watchExtensions=a,startupDelaySec=0,debounceMs=50,portFile="+port)
                .jvmArgs("-Dscope.a="+a.resolve("classes"),"-Dscope.b="+b.resolve("classes"),"-Dscope.request="+request)
                .with("App",DRIVER).start()){
            app.awaitOrFail("READY=","synthetic SAP child did not start");
            app.awaitWatcherReady();
            String ready=app.output().stream().filter(line->line.startsWith("READY=")).findFirst().orElseThrow();
            assertTrue(ready.endsWith(":true:false"),"A must be instrumented; B must already be loaded raw: "+ready);
            // Hold the production native-run admission fence before its output hold exists.
            // CodegenReloaderTest separately drives the real codegen runner through this gap.
            probe(app,"hold-scope","1:1:absent");
            var pending=new java.util.concurrent.CountDownLatch(1);
            var adding=java.util.concurrent.CompletableFuture.supplyAsync(()->{
                try{return command("CONFIG SET add base=0 watchExtensions=a;b",pending);}
                catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
            });
            assertTrue(pending.await(20,java.util.concurrent.TimeUnit.SECONDS),"held CONFIG must report PENDING");
            assertFalse(adding.isDone());assertTrue(command("CONFIG GET held").startsWith("CONFIG held OK rev=0 "));
            probe(app,"release-scope","1:1:absent");
            String added=adding.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(added.startsWith("CONFIG add OK rev=1 "),added);assertTrue(added.contains("loadedUninstrumented=1"),added);
            compile(b,"B",2,false);probe(app,"body","1:2:absent");
            compile(b,"B",3,true);probe(app,"raw-structure","1:2:absent");
            probe(app,"load-future","1:2:1");
            compile(b,"Future",2,true);probe(app,"future-structure","1:2:2/7");
            String removed=command("CONFIG SET remove base=1 watchExtensions=b");assertTrue(removed.startsWith("CONFIG remove OK rev=2 "),removed);
            compile(a,"A",9,false);probe(app,"removed","1:2:2/7");
            assertEquals(ready.split(":")[0].substring(6),app.output().stream().filter(line->line.startsWith("PID=")).reduce((x,y)->y).orElseThrow().substring(4));
        }
    }
    private static final String DRIVER="""
        package app;
        import java.nio.file.*;
        public class App {
            public static void main(String[] args)throws Exception {
                Class<?> agentType=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent");
                var scanReady=agentType.getDeclaredField("generatedClassScan");scanReady.setAccessible(true);
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                while(scanReady.get(null)==null&&System.nanoTime()<deadline)Thread.sleep(10);
                if(scanReady.get(null)==null)throw new IllegalStateException("Agent inventory did not become ready");
                var loader=new java.net.URLClassLoader(new java.net.URL[]{Path.of(System.getProperty("scope.a")).toUri().toURL(),Path.of(System.getProperty("scope.b")).toUri().toURL()},App.class.getClassLoader());
                Thread.currentThread().setContextClassLoader(loader);
                Class<?> a=Class.forName("app.A",true,loader),b=Class.forName("app.B",true,loader);
                Object one=a.getConstructor().newInstance(),two=b.getConstructor().newInstance(),future=null;Class<?> futureType=null;
                System.out.println("READY="+ProcessHandle.current().pid()+":"+marker(a)+":"+marker(b));
                AutoCloseable scopePermit=null;
                String previous="";Path request=Path.of(System.getProperty("scope.request"));
                while(true){
                    if(Files.exists(request)){
                        String token=Files.readString(request);
                        if(!token.equals(previous)){
                            Class<?> agent=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent");
                            var scan=agent.getDeclaredField("generatedClassScan");scan.setAccessible(true);((Runnable)scan.get(null)).run();
                            var field=agent.getDeclaredField("reloadQueue");field.setAccessible(true);Object queue=field.get(null);
                            var submit=queue.getClass().getDeclaredMethod("submit",String.class,Runnable.class);submit.setAccessible(true);
                            var done=new java.util.concurrent.CountDownLatch(1);submit.invoke(queue,"Scope probe barrier",(Runnable)done::countDown);
                            if(!done.await(20,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Scope work did not drain");
                            if(token.equals("hold-scope")) {
                                var fenceField=agent.getDeclaredField("scopeFence");fenceField.setAccessible(true);Object fence=fenceField.get(null);
                                scopePermit=(AutoCloseable)fence.getClass().getMethod("tryEnterRun",Runnable.class).invoke(fence,(Runnable)()->{throw new AssertionError("unexpected deferred run");});
                                if(scopePermit==null)throw new IllegalStateException("Native run admission failed");
                            }
                            if(token.equals("release-scope")){scopePermit.close();scopePermit=null;}
                            if(token.equals("load-future")){futureType=Class.forName("app.Future",true,loader);future=futureType.getConstructor().newInstance();}
                            String last=future==null?"absent":String.valueOf(futureType.getMethod("value").invoke(future));
                            if(future!=null)try{last+="/"+futureType.getMethod("added").invoke(future);}catch(NoSuchMethodException absent){}
                            System.out.println("PID="+ProcessHandle.current().pid());
                            System.out.println("PROBE="+token+":"+a.getMethod("value").invoke(one)+":"+b.getMethod("value").invoke(two)+":"+last);
                            previous=token;
                        }
                    }
                    Thread.sleep(25);
                }
            }
            static boolean marker(Class<?> type)throws Exception {
                var field=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent").getDeclaredField("transformContext");field.setAccessible(true);
                Object context=field.get(null);return (boolean)context.getClass().getMethod("isTransformed",Class.class).invoke(context,type);
            }
        }
        """;
}
