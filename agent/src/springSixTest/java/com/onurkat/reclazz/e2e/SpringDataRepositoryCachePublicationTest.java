/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class SpringDataRepositoryCachePublicationTest {
    @TempDir Path tmp;
    static String field(String name)throws Exception {
        var f=SpringDataRepositoryReloadTest.class.getDeclaredField(name);f.setAccessible(true);return (String)f.get(null);
    }
    static String records(int id)throws Exception {
        var m=SpringDataRepositoryReloadTest.class.getDeclaredMethod("records",int.class,boolean.class);m.setAccessible(true);return (String)m.invoke(null,id,false);
    }
    @Test void staleTypeLookupCannotPublishAnEmptyCacheAfterRepositoryRefresh()throws Exception {
        String cp=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .filter(p->p.endsWith(".jar")&&!Path.of(p).getFileName().toString().startsWith("agent-"))
            .collect(Collectors.joining(File.pathSeparator));
        String appSource=field("APP").replace("public class App {","public class App { static final java.util.concurrent.atomic.AtomicBoolean firstFailure=new java.util.concurrent.atomic.AtomicBoolean();")
            .replace("catch(Throwable failure){reply=", "catch(Throwable failure){if(firstFailure.compareAndSet(false,true))failure.printStackTrace(System.out);reply=")
            .replace("public static class Holder", DELAYED_CACHE+" public static class Holder")
            .replace("String identity=", "var cacheField=org.springframework.beans.factory.support.DefaultListableBeanFactory.class.getDeclaredField(\"allBeanNamesByType\");cacheField.setAccessible(true);var cache=new DelayedCache();cache.putAll((java.util.Map<Class<?>,String[]>)cacheField.get(ctx.getBeanFactory()));cacheField.set(ctx.getBeanFactory(),cache);String identity=");
        var app=WatchedApp.in(tmp).classpath(cp).agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
            .childClassLoader().with("App",appSource).with("Row",field("ROW")).with("Records",records(1)).start();
        var running=new AtomicBoolean(true);var executor=Executors.newFixedThreadPool(3);
        var failures=new ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger>();
        String evidence=System.getenv("RECLAZZ_REPOSITORY_TEST_OUTPUT");
        Path out=evidence==null?null:Path.of(evidence);if(out!=null)Files.createDirectories(out);
        try(app) {
            app.awaitOrFail("PORT=","application not ready");app.awaitOrFail("] Watching ","watcher not ready");
            int port=Integer.parseInt(app.latest("PORT=").substring(5));var uri=URI.create("http://127.0.0.1:"+port+"/");
            var client=HttpClient.newHttpClient();String initial=get(client,uri);assertTrue(initial.endsWith("|before|before"),app.tail());
            String identity=initial.substring(0,initial.indexOf('|'));
            for(int i=0;i<3;i++)executor.submit(()->{var c=HttpClient.newHttpClient();while(running.get())try{String body=get(c,uri);if(body.startsWith("TRANSIENT:"))failures.computeIfAbsent(body,k->new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();}catch(Exception e){if(running.get())failures.computeIfAbsent(e.toString(),k->new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();}});
            for(int save=0;save<3;save++) {
                int id=save%2==0?2:1;app.rewrite("Records",records(id));
                String expected=identity+(id==1?"|before|before":"|after|after");String observed="";long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                while(System.nanoTime()<deadline){observed=get(client,uri);if(expected.equals(observed))break;Thread.sleep(50);}
                assertEquals(expected,observed,"save="+save+"; "+app.tail());
                System.out.println("SAVE_OK="+save);
            }
        } finally {
            running.set(false);executor.shutdownNow();executor.awaitTermination(10,TimeUnit.SECONDS);
            if(out!=null){
                Files.write(out.resolve("child.log"),app.output());
                Files.write(out.resolve("transient-bodies.txt"),failures.entrySet().stream().map(e->e.getValue()+" "+e.getKey()).sorted().toList());
            }
        }
    }
    // Delay only publication: the empty candidate is computed by real Spring during the swap.
    static final String DELAYED_CACHE="""
        public static class DelayedCache extends java.util.concurrent.ConcurrentHashMap<Class<?>,String[]> {
            final java.util.concurrent.atomic.AtomicBoolean first=new java.util.concurrent.atomic.AtomicBoolean();
            final java.util.concurrent.CountDownLatch computed=new java.util.concurrent.CountDownLatch(1),cleared=new java.util.concurrent.CountDownLatch(1);
            @Override public String[] put(Class<?> key,String[] value){
                if(key==Records.class && value.length==0 && first.compareAndSet(false,true)){
                    computed.countDown();System.out.println("EMPTY_TYPE_COMPUTED");
                    try{if(!cleared.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("reload cache clear missing");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    System.out.println("EMPTY_TYPE_PUBLISHED_AFTER_CLEAR");
                }
                return super.put(key,value);
            }
            @Override public void clear(){super.clear();if(computed.getCount()==0 && cleared.getCount()!=0){System.out.println("RELOAD_CACHE_CLEAR_RELEASED");cleared.countDown();}}
        }
        """;
    static String get(HttpClient c,URI uri)throws Exception{return c.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofString()).body();}
}
