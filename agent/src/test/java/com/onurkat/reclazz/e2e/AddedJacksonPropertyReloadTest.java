/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AddedJacksonPropertyReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void addedSettersReadJsonAcrossSaves(boolean childLoader) throws Exception { exercise(false, childLoader); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void addedFieldsReadAndWriteJsonAcrossSaves(boolean childLoader) throws Exception { exercise(true, childLoader); }

    private void exercise(boolean fields, boolean childLoader) throws Exception {
        String jackson = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> Path.of(p).getFileName().toString().startsWith("jackson-"))
                .collect(Collectors.joining(File.pathSeparator));
        assertFalse(jackson.isEmpty());
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath() + File.pathSeparator + jackson);
        if (childLoader) builder.childClassLoader();
        try (var app = builder.agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("App", APP).with("Dto", dto(0, fields)).with("Upper", UPPER).start()) {
            app.awaitOrFail("PORT=", "HTTP server did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            if (childLoader) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child module was not used");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            var client = HttpClient.newHttpClient();
            var json = new ObjectMapper();
            for (String age : new String[]{"new", "old"})
                assertEquals("warm", json.readTree(post(client, port, age, "{\"existing\":\"warm\"}", 200))
                        .path("json").path("existing").asText());
            for (int stage = 1; stage <= 4; stage++) {
                app.rewrite("Dto", dto(stage, fields));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                while (System.nanoTime() < deadline && reloads(app) < stage) Thread.sleep(25);
                assertEquals(stage, reloads(app), app.tail());
                String input = "{\"existing\":\"kept\",\"score\":\"12\",\"tags\":[\"a\",\"b\"],"
                        + "\"note\":null,\"secret\":\"must-not-leak\""
                        + (stage == 3 ? "" : ",\"label\":\"name" + stage + "\"") + "}";
                // The reload log line can precede the Jackson mapper picking up
                // the new shape under load, so wait for it before the strict
                // checks instead of racing the first request.
                long settle = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < settle) {
                    var probe = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/new"))
                            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(input)).build(), HttpResponse.BodyHandlers.ofString());
                    if (probe.statusCode() == 200) {
                        var b = json.readTree(probe.body());
                        boolean ready = stage == 3
                                ? !b.path("json").has("display_name") && !b.path("json").has("renamed")
                                : b.path("json").path(stage == 2 ? "renamed" : "display_name").asText().equals("NAME" + stage);
                        if (ready) break;
                    }
                    Thread.sleep(50);
                }
                for (String age : new String[]{"new", "old"}) {
                    String response = post(client, port, age, input, 200);
                    System.out.println("[jackson-input] fields=" + fields + " child=" + childLoader
                            + " stage=" + stage + " " + age + " " + response);
                    var body = json.readTree(response);
                    assertEquals("kept", body.path("json").path("existing").asText());
                    assertEquals(12, body.path("json").path("score").asInt());
                    assertEquals(12, body.path("state").path("score").asInt(), "same storage as application field reads");
                    assertEquals("b", body.path("state").path("tags").get(1).asText());
                    assertEquals("initial", body.path("json").path("note").asText(), "nulls=SKIP preserves initializer");
                    assertFalse(body.path("json").has("secret"), response);
                    assertEquals("hidden", body.path("state").path("secret").asText());
                    assertFalse(body.path("reflected").asBoolean(), "original reflection stays unchanged");
                    if (stage == 3) {
                        assertFalse(body.path("json").has("display_name"));
                        assertFalse(body.path("json").has("renamed"));
                    } else {
                        String key = stage == 2 ? "renamed" : "display_name";
                        assertEquals("NAME" + stage, body.path("json").path(key).asText(), response);
                        assertEquals("NAME" + stage, body.path("state").path("display").asText());
                        assertFalse(body.path("json").has(stage == 2 ? "display_name" : "renamed"));
                    }
                }
                post(client, port, "new", "{\"score\":\"invalid-number\"}", 400);
                if (stage == 3) post(client, port, "new", "{\"label\":\"removed\"}", 400);
                assertEquals("custom", json.readTree(post(client, port, "custom", "{\"unknown\":1}", 200))
                        .path("json").path("existing").asText(), "custom DTO deserializer stays in charge");
            }
        }
    }

    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Dto") || s.contains("Structural reload: app.Dto")).count();
    }

    private static String post(HttpClient client, int port, String path, String input, int status) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/" + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(input)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), response.body());
        return response.body();
    }

    private static String dto(int stage, boolean fields) {
        String display = stage == 3 ? "" : "@JsonProperty(\"" + (stage == 2 ? "renamed" : "display_name")
                + "\") @JsonAlias(\"label\") @JsonDeserialize(using=Upper.class) ";
        String additions = "";
        if (stage > 0) {
            if (fields) additions = """
                    public int score = 7;
                    public java.util.List<String> tags;
                    @JsonSetter(nulls=Nulls.SKIP) public String note = "initial";
                    @JsonIgnore public String secret = "hidden";
                    """ + (stage == 3 ? "" : display + (stage == 4 ? "private" : "public") + " String displayName;");
            else additions = """
                    private int score = 7;
                    private java.util.List<String> tags;
                    private String note = "initial";
                    private String secret = "hidden";
                    public int getScore() { return score; }
                    public void setScore(int value) { score = value; }
                    public java.util.List<String> getTags() { return tags; }
                    public void setTags(java.util.List<String> value) { tags = value; }
                    public String getNote() { return note; }
                    @JsonSetter(nulls=Nulls.SKIP) public void setNote(String value) { note = value; }
                    @JsonIgnore public String getSecret() { return secret; }
                    @JsonIgnore public void setSecret(String value) { secret = value; }
                    """ + (stage == 3 ? "" : "private String displayName; "
                    + display + (stage == 4 ? "private" : "public") + " void acceptName(String value) { displayName = value; }"
                    + " @JsonProperty(\"" + (stage == 2 ? "renamed" : "display_name")
                    + "\") public String getDisplayName() { return displayName; }");
        }
        return """
                package app;
                import com.fasterxml.jackson.annotation.*;
                import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
                public class Dto {
                    private String existing;
                    public String getExisting() { return existing; }
                    public void setExisting(String value) { existing = value; }
                    %s
                    public java.util.Map<String,Object> snapshot() {
                        var values = new java.util.LinkedHashMap<String,Object>();
                        %s
                        return values;
                    }
                }
                """.formatted(additions, stage == 0 ? "" : "values.put(\"score\", score); values.put(\"tags\", tags); values.put(\"secret\", secret);"
                + (stage == 3 ? "" : "values.put(\"display\", displayName);"));
    }

    private static final String UPPER = """
            package app;
            public class Upper extends com.fasterxml.jackson.databind.JsonDeserializer<String> {
                public String deserialize(com.fasterxml.jackson.core.JsonParser parser,
                        com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
                    return parser.getValueAsString().toUpperCase(java.util.Locale.ROOT);
                }
            }
            """;

    private static final String APP = """
            package app;
            import com.fasterxml.jackson.databind.*;
            import org.springframework.context.support.GenericApplicationContext;
            public class App {
                public static void main(String[] args) throws Exception {
                    Dto old = new Dto();
                    var mapper = new ObjectMapper();
                    var custom = new ObjectMapper();
                    var module = new com.fasterxml.jackson.databind.module.SimpleModule();
                    module.addDeserializer(Dto.class, new JsonDeserializer<Dto>() {
                        public Dto deserialize(com.fasterxml.jackson.core.JsonParser parser,
                                DeserializationContext ignored) throws java.io.IOException {
                            parser.skipChildren(); var value = new Dto(); value.setExisting("custom"); return value;
                        }
                    });
                    custom.registerModule(module);
                    var context = new GenericApplicationContext();
                    context.registerBean("mapper", ObjectMapper.class, () -> mapper);
                    context.registerBean("custom", ObjectMapper.class, () -> custom);
                    context.refresh();
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                    server.createContext("/", exchange -> {
                        byte[] response; int status = 200;
                        try {
                            String input = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                            String path = exchange.getRequestURI().getPath();
                            Dto value = path.equals("/old") ? mapper.readerForUpdating(old).readValue(input)
                                    : (path.equals("/custom") ? custom : mapper).readValue(input, Dto.class);
                            var body = new java.util.LinkedHashMap<String,Object>();
                            body.put("json", value); body.put("state", value.snapshot());
                            body.put("reflected", java.util.Arrays.stream(Dto.class.getDeclaredFields()).anyMatch(f -> f.getName().equals("score"))
                                    || java.util.Arrays.stream(Dto.class.getDeclaredMethods()).anyMatch(m -> m.getName().equals("setScore")));
                            response = mapper.writeValueAsBytes(body);
                        } catch (Throwable failure) {
                            failure.printStackTrace(); status = 400;
                            response = failure.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        }
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(status, response.length);
                        try (var out = exchange.getResponseBody()) { out.write(response); }
                    });
                    server.start();
                    System.out.println("PORT=" + server.getAddress().getPort());
                }
            }
            """;
}
