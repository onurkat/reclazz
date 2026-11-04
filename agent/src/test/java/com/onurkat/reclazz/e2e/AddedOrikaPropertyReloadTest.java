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
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses owner-provided SDK jars; no proprietary SDK or Orika dependency is shipped. */
class AddedOrikaPropertyReloadTest {
    @TempDir Path tmp;

    @Test void retainedDefaultPlansFollowAddedPropertiesAndKeepUserPoliciesAcrossSaves() throws Exception {
        String sdk = System.getenv("RECLAZZ_ORIKA_TEST_CLASSPATH");
        assumeTrue(sdk != null && !sdk.isBlank(), "local SDK classpath not supplied");
        try (var app = WatchedApp.in(tmp).classpath(sdk)
                .jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("App", APP).with("Dto", dto(0)).with("Source", SOURCE).start()) {
            app.awaitOrFail("PORT=", "HTTP server did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            var client = HttpClient.newHttpClient();
            assertEquals("code:v0|null:v0|code:v0|alias:v0", get(client, port), app.tail());
            for (int stage = 1; stage <= 3; stage++) {
                app.rewrite("Dto", dto(stage));
                String suffix = ":label:42:true:" + (stage == 1 ? "none" : "note" + stage);
                String expected = "code" + suffix + "|null" + suffix
                        + "|code:null:0:false:" + (stage == 1 ? "none" : "null") + "|alias" + suffix;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
                String response;
                do {
                    response = get(client, port);
                    if (response.equals(expected)) break;
                    Thread.sleep(50);
                } while (System.nanoTime() < deadline);
                assertEquals(expected, response, app.tail());
                assertEquals(expected, get(client, port), "a warm strategy must stay current");
            }
        }
    }

    /**
     * SAP Commerce's shape: the loader finds a jar holding the class as it was
     * at startup before the directory a regen writes. Javassist reads class
     * files through that loader to compile the refreshed mapper, so it saw the
     * old source class and refused the added getter: "getNote() not found".
     */
    @Test void addedSourceGetterMapsWhenTheLoaderFindsAnOlderClassFileFirst() throws Exception {
        String sdk = System.getenv("RECLAZZ_ORIKA_TEST_CLASSPATH");
        assumeTrue(sdk != null && !sdk.isBlank(), "local SDK classpath not supplied");
        Path stale = tmp.resolve("stale");
        Path sources = java.nio.file.Files.createDirectories(tmp.resolve("stale-src/app"));
        java.nio.file.Files.writeString(sources.resolve("Data.java"), data(false));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", stale.toString(), sources.resolve("Data.java").toString()));
        Path jar = tmp.resolve("models.jar");
        try (var out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.jar.JarEntry("app/Data.class"));
            out.write(java.nio.file.Files.readAllBytes(stale.resolve("app/Data.class")));
            out.closeEntry();
        }
        try (var app = WatchedApp.in(tmp).classpath(sdk + java.io.File.pathSeparator + jar)
                .jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("App", SHADOWED_APP).with("Data", data(false)).with("WsDto", WS_DTO).start()) {
            app.awaitOrFail("PORT=", "HTTP server did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            var client = HttpClient.newHttpClient();
            assertEquals("code0:null", get(client, port), app.tail());
            app.rewrite("Data", data(true));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
            String response;
            do {
                response = fetch(client, port);
                if (response.equals("code0:note0")) break;
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);
            assertEquals("code0:note0", response, app.tail());
            assertEquals("code0:note0", get(client, port), "a warm strategy must stay current");
        }
    }

    private static String data(boolean note) {
        return """
            package app;
            public class Data {
                public String getCode(){return "code0";}
                %s
            }
            """.formatted(note ? "public String getNote(){return \"note0\";}" : "");
    }

    private static final String WS_DTO = """
            package app;
            public class WsDto {
                private String code;
                private String note;
                public String getCode(){return code;}
                public void setCode(String value){code=value;}
                public String getNote(){return note;}
                public void setNote(String value){note=value;}
            }
            """;

    private static final String SHADOWED_APP = """
            package app;
            import ma.glasnost.orika.impl.DefaultMapperFactory;
            public class App {
                public static void main(String[] args) throws Exception {
                    if (!Data.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith("models.jar"))
                        throw new AssertionError("Data must load from the jar, the way SAP's models.jar shadows a regen");
                    var mapper = new DefaultMapperFactory.Builder().build().getMapperFacade();
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
                    server.createContext("/", exchange -> {
                        int status = 200;String body;
                        try {
                            var dto = mapper.map(new Data(), WsDto.class);
                            body = dto.getCode() + ":" + dto.getNote();
                        } catch(Throwable failure){failure.printStackTrace();status=500;body=failure.toString();}
                        byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(status,bytes.length);
                        try(var out=exchange.getResponseBody()){out.write(bytes);}
                    });
                    server.start();System.out.println("PORT="+server.getAddress().getPort());
                }
            }
            """;

    private static String fetch(HttpClient client, int port) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/"))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? response.body() : response.statusCode() + ": " + response.body();
    }

    private static String get(HttpClient client, int port) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/"))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private static String dto(int stage) {
        return """
            package app;
            public class Dto {
                private String code;
                public String getCode(){return code;}
                public void setCode(String value){code=value;}
                %s
                public String describe(){return %s;}
            }
            """.formatted(stage == 0 ? "" : """
                private String displayName;
                private int count;
                private boolean active;
                public String getDisplayName(){return displayName;}
                public void setDisplayName(String value){displayName=value;}
                public int getCount(){return count;}
                public void setCount(int value){count=value;}
                public boolean isActive(){return active;}
                public void setActive(boolean value){active=value;}
                """ + (stage == 1 ? "" : """
                private String note;
                public String getNote(){return note==null?null:note+"%d";}
                public void setNote(String value){note=value;}
                """.formatted(stage)), stage == 0 ? "code+\":v0\"" :
                    "code+\":\"+getDisplayName()+\":\"+getCount()+\":\"+isActive()+\":\"+" + (stage == 1 ? "\"none\"" : "getNote()"));
    }

    private static final String SOURCE = """
            package app;
            public class Source {
                public String getCode(){return "code";}
                public String getAlias(){return "alias";}
                public String getDisplayName(){return "label";}
                public int getCount(){return 42;}
                public boolean isActive(){return true;}
                public String getNote(){return "note";}
            }
            """;

    private static final String APP = """
            package app;
            import ma.glasnost.orika.impl.DefaultMapperFactory;
            public class App {
                public static void main(String[] args) throws Exception {
                    var normal = new DefaultMapperFactory.Builder().build().getMapperFacade();
                    var excludedFactory = new DefaultMapperFactory.Builder().build();
                    excludedFactory.classMap(Source.class,Dto.class).exclude("code").byDefault().register();
                    var explicitFactory = new DefaultMapperFactory.Builder().build();
                    explicitFactory.classMap(Source.class,Dto.class).field("code","code").register();
                    var renamedFactory = new DefaultMapperFactory.Builder().build();
                    renamedFactory.classMap(Source.class,Dto.class).field("alias","code").byDefault().register();
                    var facades = java.util.List.of(normal, excludedFactory.getMapperFacade(),
                            explicitFactory.getMapperFacade(),renamedFactory.getMapperFacade());
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
                    server.createContext("/", exchange -> {
                        int status = 200;String body;
                        try {
                            var values = new java.util.ArrayList<String>();
                            for (var mapper : facades) values.add(mapper.map(new Source(),Dto.class).describe());
                            body = String.join("|",values);
                        } catch(Throwable failure){failure.printStackTrace();status=500;body=failure.toString();}
                        byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(status,bytes.length);
                        try(var out=exchange.getResponseBody()){out.write(bytes);}
                    });
                    server.start();System.out.println("PORT="+server.getAddress().getPort());
                }
            }
            """;
}
