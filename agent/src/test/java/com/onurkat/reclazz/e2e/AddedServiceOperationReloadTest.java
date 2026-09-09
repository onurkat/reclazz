/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class AddedServiceOperationReloadTest {
    @TempDir Path tmp;

    @Test void addedOperationsUseRealTransactionsAndCachesThroughOldCglibReference() throws Exception { exercise(true, false); }
    @Test void firstOperationsOnPlainSingletonUseRealTransactionsAndCaches() throws Exception { exercise(false, false); }

    @Test void serviceRecreationSupportsBothOldAndNewBeanReferences() throws Exception { exercise(true, true); }

    private void exercise(boolean proxied, boolean component) throws Exception {
        String h2 = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> Path.of(p).getFileName().toString().startsWith("h2-"))
                .collect(Collectors.joining(File.pathSeparator));
        assertFalse(h2.isEmpty());
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath() + File.pathSeparator + h2)
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("App", APP).with("Config", CONFIG).with("Store", store(0, proxied, component))
                .with("Probe", probe(false)).start()) {
            app.awaitOrFail("PORT=", "application did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            var client = HttpClient.newHttpClient();
            assertEquals("ready", get(client, port, "ready"));
            app.rewriteAll(Map.of("Store", store(1, proxied, component), "Probe", probe(true)));
            awaitReload(app, "Store", 1); awaitReload(app, "Probe", 1);
            assertEquals("1:true", get(client, port, "commit"), app.tail());
            assertEquals("checked failure", get(client, port, "rollback"), app.tail());
            assertEquals("1", get(client, port, "rows"), app.tail());
            assertEquals("1:1", get(client, port, "cached"), app.tail());
            assertEquals("1:1", get(client, port, "cached"), app.tail());
            assertEquals("put", get(client, port, "put"), app.tail());
            assertEquals("put", get(client, port, "cached"), app.tail());
            assertEquals("evicted", get(client, port, "evict"), app.tail());
            assertEquals("1:2", get(client, port, "cached"), app.tail());
            assertEquals("false", get(client, port, "self"), "self invocation must bypass transaction interception");
            assertEquals("true", get(client, port, "identity"));
            app.rewrite("Store", store(2, proxied, component));
            awaitReload(app, "Store", 2);
            assertEquals("2:3", get(client, port, "cached"), app.tail());
            assertEquals("2:3", get(client, port, "cached"), app.tail());
            assertEquals("true", get(client, port, "identity"));
            assertEquals("true", get(client, port, "new-active"), app.tail());
            assertEquals("" + !component, get(client, port, "registered"));
            app.rewrite("Store", store(3, proxied, component));
            awaitReload(app, "Store", 3);
            var removed = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/cached"))
                    .timeout(java.time.Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(500, removed.statusCode());
            assertTrue(removed.body().contains("operation method was removed"), removed.body());
            app.rewrite("Store", store(4, proxied, component));
            awaitReload(app, "Store", 4);
            assertEquals("4:4", get(client, port, "cached"), app.tail());
            assertEquals("4:4", get(client, port, "cached"), app.tail());
            assertEquals("true", get(client, port, "new-active"));
            assertEquals("true", get(client, port, "identity"));
            assertFalse(app.output().stream().anyMatch(s -> s.contains("@Transactional is found by scanning")
                    || s.contains("@Cacheable is found by scanning")), app.tail());
            System.out.println("[added-operations] " + (proxied ? "CGLIB" : "plain")
                    + " component=" + component + " commit=1 rollback-row=0 cache=hit/put/evict self=false state=preserved saves=4");
        }
    }

    private static void awaitReload(WatchedApp app, String name, int count) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (reloads(app, name) < count && System.nanoTime() < until) Thread.sleep(25);
        assertEquals(count, reloads(app, name), app.tail());
    }
    private static long reloads(WatchedApp app, String name) {
        return app.output().stream().filter(s -> s.contains("Reloaded app." + name + " ")
                || s.contains("Structural reload: app." + name + " ")).count();
    }
    private static String get(HttpClient client, int port, String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/" + path))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body()); return response.body();
    }
    private static String store(int version, boolean proxied, boolean component) {
        return """
                package app;
                import org.springframework.transaction.annotation.Transactional;
                import org.springframework.cache.annotation.*;
                %s
                public class Store {
                    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
                    private int calls;
                    public Store(org.springframework.jdbc.core.JdbcTemplate jdbc) { this.jdbc = jdbc; }
                    %s public int original() { return 7; }
                    public String self() { return %s; }
                    public int rows() { return jdbc.queryForObject("select count(*) from entries", Integer.class); }
                    %s
                }
                """.formatted(component ? "@org.springframework.stereotype.Service" : "", proxied ? "@Transactional" : "", version == 0 ? "\"initial\"" : "\"\" + active()",
                version == 0 ? "" : """
                    @Transactional(rollbackFor=Exception.class)
                    public String write(int id, boolean fail) throws Exception {
                        jdbc.update("insert into entries values (?)", id);
                        if (fail) throw new Exception("checked failure");
                        return id + ":" + org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
                    }
                    @Transactional public boolean active() {
                        return org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
                    }
                    %s
                    @CachePut(cacheNames="values", key="'%d:' + #p0")
                    public String put(String key) { return "put"; }
                    @CacheEvict(cacheNames="values", key="'%d:' + #p0")
                    public void evict(String key) { }
                    """.formatted(version == 3 ? "" : "@Cacheable(cacheNames=\"values\", key=\"'" + version
                            + ":' + #p0\") public String cached(String key) { return \"" + version + ":\" + ++calls; }", version, version));
    }
    private static String probe(boolean added) {
        return """
                package app;
                public class Probe {
                    public static String call(Store store, String path) throws Exception {
                        %s
                    }
                }
                """.formatted(added ? """
                    return switch(path) {
                        case "commit" -> store.write(1, false);
                        case "rollback" -> { try { store.write(2, true); yield "missing exception"; }
                            catch(Exception expected) { yield expected.getMessage(); } }
                        case "rows" -> "" + store.rows();
                        case "cached" -> store.cached("a");
                        case "put" -> store.put("a");
                        case "evict" -> { store.evict("a"); yield "evicted"; }
                        case "self" -> store.self();
                        case "active" -> "" + store.active();
                        default -> "ready";
                    };
                    """ : "return \"ready\";");
    }
    private static final String CONFIG = """
            package app;
            @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
            @org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass=true)
            @org.springframework.cache.annotation.EnableCaching(proxyTargetClass=true)
            public class Config {
                @org.springframework.context.annotation.Bean public org.h2.jdbcx.JdbcDataSource dataSource() {
                    var ds = new org.h2.jdbcx.JdbcDataSource(); ds.setURL("jdbc:h2:mem:operations;DB_CLOSE_DELAY=-1"); return ds;
                }
                @org.springframework.context.annotation.Bean public org.springframework.jdbc.core.JdbcTemplate jdbc(javax.sql.DataSource ds) {
                    return new org.springframework.jdbc.core.JdbcTemplate(ds);
                }
                @org.springframework.context.annotation.Bean public org.springframework.transaction.PlatformTransactionManager transactionManager(javax.sql.DataSource ds) {
                    return new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
                }
                @org.springframework.context.annotation.Bean public org.springframework.cache.CacheManager cacheManager() {
                    return new org.springframework.cache.concurrent.ConcurrentMapCacheManager("values");
                }
                @org.springframework.context.annotation.Bean public Store store(org.springframework.jdbc.core.JdbcTemplate jdbc) { return new Store(jdbc); }
            }
            """;
    private static final String APP = """
            package app;
            public class App {
                public static void main(String[] args) throws Exception {
                    var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext(Config.class);
                    context.getBean(org.springframework.jdbc.core.JdbcTemplate.class).execute("create table entries (id int)");
                    Store old = context.getBean(Store.class);
                    Object target = old instanceof org.springframework.aop.framework.Advised
                        ? ((org.springframework.aop.framework.Advised) old).getTargetSource().getTarget() : old;
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                    server.createContext("/", exchange -> {
                        String result; int status = 200;
                        try {
                            String path = exchange.getRequestURI().getPath().substring(1);
                            if (path.equals("identity")) result = "" + ((old instanceof org.springframework.aop.framework.Advised
                                    ? ((org.springframework.aop.framework.Advised) old).getTargetSource().getTarget() : old) == target);
                            else if (path.equals("registered")) result = "" + (old == context.getBean(Store.class));
                            else if (path.equals("new-active")) result = Probe.call(context.getBean(Store.class), "active");
                            else result = Probe.call(old, path);
                        } catch(Throwable failure) { failure.printStackTrace(); result = failure.toString(); status = 500; }
                        byte[] bytes = result.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
                    });
                    server.start(); System.out.println("PORT=" + server.getAddress().getPort()); Thread.sleep(120000);
                }
            }
            """;
}
