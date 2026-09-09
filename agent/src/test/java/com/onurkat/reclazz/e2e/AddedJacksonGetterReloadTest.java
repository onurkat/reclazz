/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AddedJacksonGetterReloadTest {
    @TempDir Path tmp;

    @Test
    void addedGettersReachHttpWithMapperPoliciesAcrossSaves() throws Exception {
        String jackson = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> Path.of(p).getFileName().toString().startsWith("jackson-"))
                .collect(Collectors.joining(File.pathSeparator));
        assertFalse(jackson.isEmpty(), "real Jackson must be on the test classpath");
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath() + File.pathSeparator + jackson)
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("App", APP).with("Dto", dto(0)).with("Upper", UPPER).start()) {
            app.awaitOrFail("PORT=", "HTTP server did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            {
                var client = HttpClient.newHttpClient();
                assertEquals("{\"displayName\":\"alpha\"}", get(client, port, "plain/old"));
                assertEquals("{\"display_name\":\"alpha\"}", get(client, port, "snake/old"));
                for (int stage = 1; stage <= 4; stage++) {
                    app.rewrite("Dto", dto(stage));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                    while (System.nanoTime() < deadline && reloads(app) < stage) Thread.sleep(25);
                    assertEquals(stage, reloads(app), app.tail());
                    for (String policy : new String[]{"plain", "snake"}) {
                        for (String age : new String[]{"old", "new"}) {
                            String response = get(client, port, policy + "/" + age);
                            System.out.println("[jackson-http] stage=" + stage + " " + policy + "/" + age + " " + response);
                            JsonNode json = new ObjectMapper().readTree(response);
                            assertEquals(7, json.path("count").asInt(), response);
                            assertEquals("alpha", json.path(policy.equals("plain") ? "displayName" : "display_name").asText());
                            assertEquals("LABEL" + stage, json.path("public_label").asText());
                            assertEquals("VALUE" + stage, json.path("formatted").asText());
                            assertEquals(stage, json.path("nested").path("version").asInt());
                            assertEquals("tag", json.path("tags").get(0).asText());
                            assertFalse(json.has("secret"), response);
                            assertFalse(json.has("empty"), response);
                            assertEquals(policy.equals("plain"), json.has("nickname"), response);
                            if (policy.equals("plain")) assertTrue(json.get("nickname").isNull());
                            if (stage == 3) {
                                assertFalse(json.has("email")); assertFalse(json.has("contact_address"));
                            } else {
                                String key = stage == 2 ? "contact_address" : "email";
                                assertEquals("email" + stage, json.path(key).asText(), response + "\n" + app.tail());
                                assertFalse(json.has(stage == 2 ? "email" : "contact_address"));
                            }
                        }
                    }
                    assertEquals("false", get(client, port, "reflection"), "ordinary reflection must stay unchanged");
                    assertEquals("\"custom-alpha\"", get(client, port, "custom/old"), "user's DTO serializer must stay in charge");
                }
            }
            assertFalse(app.output().stream().anyMatch(s -> s.contains("Dto.") && s.contains("was added")), app.tail());
        }
    }

    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Dto") || s.contains("Structural reload: app.Dto")).count();
    }

    private static String get(HttpClient client, int port, String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/" + path))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private static String dto(int stage) {
        return """
                package app;
                import com.fasterxml.jackson.annotation.*;
                import com.fasterxml.jackson.databind.annotation.JsonSerialize;
                public class Dto {
                    public String getDisplayName() { return "alpha"; }
                    %s
                }
                """.formatted(stage == 0 ? "" : """
                    private int count = 7;
                    public int getCount() { return count; }
                    %s
                    @JsonIgnore public String getSecret() { return "hidden"; }
                    @JsonInclude(JsonInclude.Include.NON_EMPTY) public java.util.List<String> getEmpty() { return java.util.List.of(); }
                    public String getNickname() { return null; }
                    @JsonProperty("public_label") private String label() { return "LABEL%d"; }
                    @JsonSerialize(using=Upper.class) public String getFormatted() { return "value%d"; }
                    public java.util.Map<String, Integer> getNested() { return java.util.Map.of("version", %d); }
                    public java.util.List<String> getTags() { return java.util.List.of("tag"); }
                    """.formatted(stage == 3 ? "" : (stage == 2 ? "@JsonProperty(\"contact_address\") " : "")
                        + "public String getEmail() { return \"email" + stage + "\"; }", stage, stage, stage));
    }

    private static final String UPPER = """
            package app;
            public class Upper extends com.fasterxml.jackson.databind.JsonSerializer<String> {
                public void serialize(String value, com.fasterxml.jackson.core.JsonGenerator gen,
                        com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
                    gen.writeString(value.toUpperCase(java.util.Locale.ROOT));
                }
            }
            """;

    private static final String APP = """
            package app;
            import com.fasterxml.jackson.databind.*;
            import com.fasterxml.jackson.annotation.*;
            import org.springframework.context.support.GenericApplicationContext;
            public class App {
                public static void main(String[] args) throws Exception {
                    Dto old = new Dto();
                    ObjectMapper plain = new ObjectMapper();
                    ObjectMapper snake = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
                    var custom = new ObjectMapper();
                    var module = new com.fasterxml.jackson.databind.module.SimpleModule();
                    module.addSerializer(Dto.class, new JsonSerializer<Dto>() {
                        public void serialize(Dto value, com.fasterxml.jackson.core.JsonGenerator gen,
                                SerializerProvider provider) throws java.io.IOException {
                            gen.writeString("custom-" + value.getDisplayName());
                        }
                    });
                    custom.registerModule(module);
                    var context = new GenericApplicationContext();
                    context.registerBean("plain", ObjectMapper.class, () -> plain);
                    context.registerBean("snake", ObjectMapper.class, () -> snake);
                    context.registerBean("custom", ObjectMapper.class, () -> custom);
                    context.refresh();
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                    server.createContext("/", exchange -> {
                        byte[] body; int status = 200;
                        try {
                            String path = exchange.getRequestURI().getPath();
                            String result;
                            if (path.equals("/reflection")) {
                                result = Boolean.toString(java.util.Arrays.stream(Dto.class.getDeclaredMethods())
                                        .anyMatch(m -> m.getName().equals("getEmail")));
                            } else {
                                ObjectMapper mapper = path.startsWith("/snake/") ? snake : path.startsWith("/custom/") ? custom : plain;
                                result = mapper.writeValueAsString(path.endsWith("/new") ? new Dto() : old);
                            }
                            body = result.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        } catch (Throwable failure) {
                            failure.printStackTrace(); status = 500;
                            body = failure.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        }
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(status, body.length);
                        try (var out = exchange.getResponseBody()) { out.write(body); }
                    });
                    server.start();
                    System.out.println("PORT=" + server.getAddress().getPort());
                }
            }
            """;
}
