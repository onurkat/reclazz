/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real native Orika from an installed SDK; no framework cache is cleared by the test. */
class OrikaRollbackReloadTest {
    @TempDir Path tmp;

    @Test void removedAddedPropertyIsDroppedFromRetainedPlan() throws Exception {
        try (var app = app(false)) {
            int port = ready(app); expect(port,"/","code");
            change(app,"Dto",dto(1)); expect(port,"/","code:v2");
            change(app,"Dto",dto(0)); expect(port,"/","code");
        }
    }
    @Test void reAddAfterRemovalMapsAgain() throws Exception {
        try (var app = app(false)) {
            int port = ready(app); expect(port,"/","code");
            for (int i=0;i<3;i++) {
                change(app,"Dto",dto(1)); expect(port,"/","code:v2");
                change(app,"Dto",dto(0)); expect(port,"/","code");
            }
        }
    }
    @Test void partialRemovalKeepsTheOtherAddedProperty() throws Exception {
        try (var app = app(false)) {
            int port = ready(app); expect(port,"/","code");
            change(app,"Dto",dto(2)); expect(port,"/","code:v2:other");
            change(app,"Dto",dto(1)); expect(port,"/","code:v2");
            change(app,"Dto",dto(0)); expect(port,"/","code");
        }
    }
    @Test void finalAddedSourceGetterRemovalRestoresWarmedPlan() throws Exception {
        try (var app = app(true)) {
            int port = ready(app); expect(port,"/","code:null");
            change(app,"Source",source(true)); expect(port,"/","code:v2");
            change(app,"Source",source(false)); expect(port,"/","code:null");
            change(app,"Source",source(true)); expect(port,"/","code:v2");
        }
    }
    @Test void userDefinedFieldMapToRemovedAccessorIsKeptAndWarned() throws Exception {
        try (var app = app(false)) {
            int port = ready(app); expect(port,"/","code");
            change(app,"Dto",dto(1)); expect(port,"/","code:v2");
            expect(port,"/user","null:v2");
            change(app,"Dto",dto(0));
            var response=get(port,"/user");
            assertEquals(500,response.statusCode(),response.body());
            assertTrue(response.body().contains("NoSuchMethodError"),response.body());
            app.awaitOrFail("application-defined Orika mapping", "No explicit mapping warning");
            var ledger=get(port,"/ledger"); assertEquals(200,ledger.statusCode());
            assertTrue(ledger.body().contains("app.Dto.extra"),ledger.body());
            assertTrue(ledger.body().contains("mapping configuration"),ledger.body());
            change(app,"Dto",dto(1)); expect(port,"/user","null:v2");
            assertFalse(get(port,"/ledger").body().contains("app.Dto.extra"),"Only the recovered mapping concern is resolved");
        }
    }
    @Test void removedPropertyWithUnicodeAccessorRestoresThePlan() throws Exception {
        try (var app=app(false)) {
            int port=ready(app); expect(port,"/","code");
            change(app,"Source",source(true).replace("public String getOther()", "public String getÜber(){return \"v2\";} public String getOther()"));
            change(app,"Dto",dto(1).replace("Extra","Über").replace("extra","über")); expect(port,"/","code:v2");
            change(app,"Dto",dto(0)); expect(port,"/","code");
            change(app,"Source",source(true)); expect(port,"/","code");
        }
    }
    @Test void unrelatedPlansUntouched() throws Exception {
        try (var app = app(false)) {
            int port=ready(app); expect(port,"/","code");
            var original=get(port,"/other"); assertEquals(200,original.statusCode());
            change(app,"Dto",dto(1)); expect(port,"/","code:v2");
            assertEquals(original.body(),get(port,"/other").body());
            change(app,"Dto",dto(0)); expect(port,"/","code");
            assertEquals(original.body(),get(port,"/other").body());
        }
    }
    private WatchedApp app(boolean sourceEdit) throws Exception {
        String cp=System.getenv("RECLAZZ_ORIKA_TEST_CLASSPATH");
        assumeTrue(cp!=null&&!cp.isBlank(),"local SDK classpath not supplied");
        return WatchedApp.in(tmp).classpath(cp).jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("App",APP).with("Dto",dto(sourceEdit?1:0)).with("Source",source(!sourceEdit))
                .with("OtherSource","package app; public class OtherSource { public String getCode(){return \"stable\";} }")
                .with("OtherDto","package app; public class OtherDto { private String code; public String getCode(){return code;} public void setCode(String v){code=v;} }")
                .start();
    }
    private int ready(WatchedApp app) {
        app.awaitOrFail("PORT=","HTTP fixture not ready");app.awaitOrFail("] Watching 1 directory","watcher baseline not ready");
        return Integer.parseInt(app.latest("PORT=").substring(5));
    }
    private void change(WatchedApp app,String owner,String text) throws Exception {
        int mark=app.output().size();app.rewrite(owner,text);
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while(System.nanoTime()<deadline) {
            if(app.output().stream().skip(mark).anyMatch(s->s.contains("Reloaded app."+owner+" (")||s.contains("Structural reload: app."+owner+" (")))return;
            Thread.sleep(20);
        }
        fail("No exact owner reload: "+owner+"\n"+app.tail());
    }
    private HttpResponse<String> get(int port,String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .timeout(Duration.ofSeconds(8)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private void expect(int port,String path,String expected) throws Exception {
        var response=get(port,path);assertEquals(200,response.statusCode(),response.body());assertEquals(expected,response.body());
        assertEquals(expected,get(port,path).body(),"retained mapper stays current");
    }
    private static String source(boolean extra) {
        return "package app; public class Source { public String getCode(){return \"code\";} public String getOther(){return \"other\";} "+
                (extra?"public String getExtra(){return \"v2\";}":"")+" }";
    }
    private static String dto(int stage) {
        return "package app; public class Dto { private String code; public String getCode(){return code;} public void setCode(String v){code=v;} "+
                (stage>0?"private String extra; public String getExtra(){return extra;} public void setExtra(String v){extra=v;}":"")+
                (stage>1?"private String other; public String getOther(){return other;} public void setOther(String v){other=v;}":"")+
                "public String describe(){return code"+(stage>0?"+\":\"+extra":"")+(stage>1?"+\":\"+other":"")+";} }";
    }
    private static final String APP="""
        package app;
        import ma.glasnost.orika.impl.DefaultMapperFactory;
        import ma.glasnost.orika.metadata.*;
        public class App {
            static String debug(Object factory) {
                try {
                    var cls=Class.forName("com.onurkat.reclazz.bootstrap.OrikaBridge");
                    var f=cls.getDeclaredField("refreshed");f.setAccessible(true);
                    Object state=((java.util.Map)f.get(null)).get(factory);
                    var key=new MapperKey(TypeFactory.valueOf(Source.class),TypeFactory.valueOf(Dto.class));
                    Object plan=((DefaultMapperFactory)factory).getClassMap(key);
                    var resolverField=DefaultMapperFactory.class.getDeclaredField("propertyResolverStrategy");resolverField.setAccessible(true);
                    var resolver=(ma.glasnost.orika.property.PropertyResolverStrategy)resolverField.get(factory);
                    return "state="+state+" currentPlan="+System.identityHashCode(plan)+" currentProperties="+resolver.getProperties(Dto.class).keySet();
                } catch(Throwable e){return e.toString();}
            }
            public static void main(String[] args) throws Exception {
                var factory=new DefaultMapperFactory.Builder().build();var mapper=factory.getMapperFacade();
                var user=new DefaultMapperFactory.Builder().build();var userReady=new java.util.concurrent.atomic.AtomicBoolean();
                var otherKey=new MapperKey(TypeFactory.valueOf(OtherSource.class),TypeFactory.valueOf(OtherDto.class));
                var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
                server.createContext("/",exchange->{int status=200;String body;
                    try {
                        switch(exchange.getRequestURI().getPath()) {
                            case "/other": body=mapper.map(new OtherSource(),OtherDto.class).getCode()+":"+System.identityHashCode(factory.getClassMap(otherKey));break;
                            case "/user":
                                if(userReady.compareAndSet(false,true))user.classMap(Source.class,Dto.class).field("extra","extra").register();
                                body=user.getMapperFacade().map(new Source(),Dto.class).describe();break;
                            case "/ledger": body=Class.forName("com.onurkat.reclazz.ui.RestartLedger").getMethod("digest").invoke(null).toString();break;
                            default: body=mapper.map(new Source(),Dto.class).describe();
                        }
                    } catch(Throwable failure){status=500;body=failure.toString()+" | "+debug(factory);failure.printStackTrace();}
                    byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);
                    try(var stream=exchange.getResponseBody()){stream.write(bytes);}
                });server.start();System.out.println("PORT="+server.getAddress().getPort());
            }
        }
        """;
}
