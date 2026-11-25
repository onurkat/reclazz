/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Spring-free process with Jackson3 only, actual startup-agent getter saves. */
class JacksonThreeGetterReloadTest {
    @TempDir Path tmp;
    @Test void addedGetterReachesExistingMapperAndObjectWithoutRestart() throws Exception {
        String jackson=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p->{String n=Path.of(p).getFileName().toString();return n.startsWith("jackson-databind-3.")||n.startsWith("jackson-core-3.")||n.startsWith("jackson-annotations-");})
                .collect(Collectors.joining(File.pathSeparator));
        assumeTrue(jackson.contains("jackson-databind-3."),"Jackson3 is isolated to Seven");
        try(var app=WatchedApp.in(tmp).classpath(jackson).agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("App",APP).with("Dto",dto(null)).start()) {
            String expected=System.getProperty("reclazz.jackson3.expectedVersion");
            if(expected!=null) app.awaitOrFail("JACKSON_VERSION="+expected,"child JVM Jackson version");
            app.awaitOrFail("PORT=","HTTP server did not start");app.awaitWatcherReady();
            int port=Integer.parseInt(app.latest("PORT=").substring(5));var client=HttpClient.newHttpClient();
            String initial=get(client,port);long pid=new com.fasterxml.jackson.databind.ObjectMapper().readTree(initial).path("pid").asLong();
            assertTrue(pid>0,initial);assertFalse(initial.contains("detail"),initial);
            for(String value:new String[]{"first","second"}) {
                app.rewrite("Dto",dto(value));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);String last=initial;String failure="";
                while(System.nanoTime()<deadline) {
                    try {
                        String response=get(client,port);var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
                        assertEquals(pid,json.path("pid").asLong(),response);last=response;
                        if(value.equals(json.path("dto").path("detail").asText()))break;
                    } catch(java.io.IOException | AssertionError transientResponse) {failure=transientResponse.toString();}
                    Thread.sleep(50);
                }
                var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(last);
                assertEquals(value,json.path("dto").path("detail").asText(),last+"\n"+failure+"\n"+app.tail());
                assertEquals("original",json.path("dto").path("name").asText());
            }
        }
    }
    private static String get(HttpClient client,int port) throws Exception {
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/")).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());return response.body();
    }
    private static String dto(String value) {
        return "package app; public class Dto { public String getName() { return \"original\"; } "+(value==null?"":"public String getDetail() { return \""+value+"\"; }")+" }";
    }
    private static final String APP="""
        package app;
        public class App {
            public static void main(String[] args) throws Exception {
                try { Class.forName("com.fasterxml.jackson.databind.ObjectMapper"); throw new AssertionError("Jackson2 must be absent"); }
                catch(ClassNotFoundException expected) { }
                System.out.println("JACKSON_VERSION="+tools.jackson.databind.cfg.PackageVersion.VERSION);
                var mapper=new tools.jackson.databind.json.JsonMapper();var dto=new Dto();
                var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
                server.createContext("/",exchange->{
                    try { byte[] body=mapper.writeValueAsBytes(java.util.Map.of("pid",ProcessHandle.current().pid(),"dto",dto));exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body); }
                    catch(Throwable failure) { byte[] error=failure.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(500,error.length);exchange.getResponseBody().write(error); }
                    finally { exchange.close(); }
                });
                server.start();System.out.println("PORT="+server.getAddress().getPort());
            }
        }
        """;
}
