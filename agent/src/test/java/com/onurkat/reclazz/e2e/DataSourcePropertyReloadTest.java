/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R37: a Spring Boot HikariCP pool takes a changed URL and changed credentials in the running JVM. The DataSource
 * object stays the same one, a transaction open across the save commits on the old database, new borrows reach the new
 * one, and the old pool is shut down once its last connection is returned. Changes that cannot be proven safe keep the
 * running pool and say why, and a password never reaches the output.
 */
class DataSourcePropertyReloadTest {
    @TempDir Path tmp;

    private static final String SECRET = "s3cr3t-r37-never-printed";

    @Test void bootHikariPoolReconnectsAndRefusesWhatItCannotProve() throws Exception {
        Path file = Files.createDirectories(tmp.resolve("classes")).resolve("application.properties");
        Files.writeString(file, properties("jdbc:h2:mem:alpha;DB_CLOSE_DELAY=-1", ""));
        try (var app = WatchedApp.in(tmp).classpath(hikariClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dconfig.file=" + file, "-Dprobe.dir=" + tmp).with("App", APP).start()) {
            app.awaitOrFail("DS_READY=", "Boot application with a Hikari pool did not start");
            app.awaitWatcherReady();
            assertTrue(app.latest("DS_READY=").startsWith("DS_READY=alpha"), app.tail());

            Files.writeString(file, properties("jdbc:h2:mem:beta;DB_CLOSE_DELAY=-1", ""));
            app.awaitOrFail("Reconnected dataSource", "the pool was not switched to the new URL");
            assertTrue(app.output().stream().anyMatch(s -> s.contains("1 connection already in use finishes on the old pool")), app.tail());
            Files.createFile(tmp.resolve("probe1"));
            app.awaitOrFail("PROBE1=", "first probe missing");
            // new borrow reaches beta : the in-flight transaction committed on alpha : same DataSource object : old pool retired
            assertEquals("PROBE1=beta:2:true:true", app.latest("PROBE1="), app.tail());

            Files.writeString(file, properties("jdbc:hsqldb:mem:other", ""));
            app.awaitOrFail("different database product", "a product change was not refused");
            Files.writeString(file, properties("jdbc:h2:mem:missing;IFEXISTS=TRUE", ""));
            app.awaitOrFail("could not be opened", "an unreachable URL was not refused");
            Files.writeString(file, properties("jdbc:h2:mem:beta;DB_CLOSE_DELAY=-1", SECRET));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (app.output().stream().filter(s -> s.contains("could not be opened")).count() < 2 && System.nanoTime() < deadline) Thread.sleep(50);
            assertEquals(2, app.output().stream().filter(s -> s.contains("could not be opened")).count(), "a wrong password was not refused: " + app.tail());
            Files.createFile(tmp.resolve("probe2"));
            app.awaitOrFail("PROBE2=", "second probe missing");
            assertEquals("PROBE2=beta:2:true:true", app.latest("PROBE2="), app.tail());
            assertTrue(app.output().stream().noneMatch(s -> s.contains(SECRET)), "the password reached the output: " + app.tail());
            // A removed username falls back to what Boot builds the pool with at startup ("sa" for an embedded database):
            // DataSourceProperties is reset and the pool, already on "sa", stays as it is. Restart-equal, no warning.
            int removalStart = app.output().size();
            long warnings = app.output().stream().filter(l -> l.contains("[WARN]")).count();
            Files.writeString(file, "spring.datasource.url=jdbc:h2:mem:beta;DB_CLOSE_DELAY=-1\nspring.datasource.password=\n");
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (app.output().stream().skip(removalStart).noneMatch(l -> l.contains("Applied") && l.contains("spring.datasource.username"))
                    && System.nanoTime() < until) Thread.sleep(100);
            assertTrue(app.output().stream().skip(removalStart).anyMatch(l -> l.contains("Applied") && l.contains("spring.datasource.username")),
                    "a removed username key was not applied: " + app.tail());
            assertEquals(warnings, app.output().stream().filter(l -> l.contains("[WARN]")).count(), "removal warned: " + app.tail());
            Files.createFile(tmp.resolve("probe3"));
            app.awaitOrFail("PROBE3=", "third probe missing");
            assertEquals("PROBE3=beta:2:true:true", app.latest("PROBE3="), app.tail());
            System.out.println("[r37] url switch applied with 1 in-flight transaction kept; product/unreachable/password refused 3/3; removed key reported");
        }
    }

    private static String hikariClasspath() {
        String extra = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> { String n = Path.of(p).getFileName().toString();
                    return n.startsWith("HikariCP-") || n.startsWith("h2-") || n.startsWith("slf4j-api-"); })
                .collect(Collectors.joining(File.pathSeparator));
        return WatchedApp.springClasspath() + File.pathSeparator + extra;
    }

    @ParameterizedTest
    @ValueSource(strings = {"url", "username", "password", "driver-class-name"})
    void removingOneKeyMatchesBootStartupOrNamesTheRestart(String suffix) throws Exception {
        String key = "spring.datasource." + suffix;
        String before = """
                spring.datasource.generate-unique-name=false
                spring.datasource.name=defaultdb
                spring.datasource.url=jdbc:h2:mem:explicitdb
                spring.datasource.username=fixtureuser
                spring.datasource.password=fixturepass
                spring.datasource.driver-class-name=org.h2.Driver
                """;
        String after = before.lines().filter(line -> !line.startsWith(key + "="))
                .collect(Collectors.joining("\n", "", "\n"));
        Path cold = tmp.resolve("cold"), live = tmp.resolve("live");
        Path coldFile = Files.createDirectories(cold.resolve("classes")).resolve("application.properties");
        Path liveFile = Files.createDirectories(live.resolve("classes")).resolve("application.properties");
        Files.writeString(coldFile, after);
        Files.writeString(liveFile, before);
        String expected;
        try (var control = removalFixture(cold, coldFile).withoutAgent().start()) {
            control.awaitOrFail("STATE=", "Boot cold control did not open a connection");
            expected = control.latest("STATE=");
        }
        try (var app = removalFixture(live, liveFile).start()) {
            app.awaitOrFail("STATE=", "Boot live fixture did not open a connection");
            String original = app.latest("STATE=");
            app.awaitWatcherReady();
            int start = app.output().size();
            Files.writeString(liveFile, after);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline && app.output().stream().skip(start)
                    .noneMatch(line -> line.contains("Uncheckable") || line.contains(key)
                            && (line.contains("Applied") || line.toLowerCase(java.util.Locale.ROOT).contains("restart")))) {
                Thread.sleep(50);
            }
            Files.createFile(live.resolve("probe"));
            app.awaitOrFail("AFTER=", "no post-removal connection snapshot");
            var receipts = app.output().stream().skip(start).toList();
            assertFalse(receipts.stream().anyMatch(line -> line.contains("Uncheckable") || line.contains("beanClassLoader")), app::tail);
            boolean restart = receipts.stream().anyMatch(line -> line.contains(key)
                    && line.toLowerCase(java.util.Locale.ROOT).contains("restart"));
            assertTrue(restart || receipts.stream().anyMatch(line -> line.contains(key) && line.contains("Applied")), app::tail);
            assertEquals((restart ? original : expected).substring("STATE=".length()),
                    app.latest("AFTER=").substring("AFTER=".length()), app::tail);
            if (!restart && suffix.equals("driver-class-name")) {
                assertEquals("DRIVER_EXPLICIT=false", app.latest("DRIVER_EXPLICIT="), app::tail);
            }
            System.out.println("Removal " + key + (restart ? " requires named restart; old pool verified"
                    : " matches cold Boot startup"));
        }
    }

    private static WatchedApp.Builder removalFixture(Path dir, Path file) {
        return WatchedApp.in(dir).classpath(hikariClasspath()).agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dconfig.file=" + file, "-Dprobe.dir=" + dir).with("App", REMOVAL_APP);
    }

    private static final String REMOVAL_APP = """
            package app;
            import java.nio.file.*;
            import com.zaxxer.hikari.HikariDataSource;
            @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
            @org.springframework.boot.autoconfigure.ImportAutoConfiguration(
                org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class)
            public class App {
                static String state(HikariDataSource pool) throws Exception {
                    try (var connection = pool.getConnection()) {
                        var metadata = connection.getMetaData();
                        return metadata.getURL() + "|" + metadata.getUserName() + "|" + metadata.getDriverName()
                            + "|" + pool.getDriverClassName() + "|" + "fixturepass".equals(pool.getPassword());
                    }
                }
                public static void main(String[] args) throws Exception {
                    var boot = new org.springframework.boot.SpringApplication(App.class);
                    boot.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
                    try (var context = boot.run("--spring.config.location=file:" + System.getProperty("config.file"))) {
                        var pool = context.getBean(HikariDataSource.class);
                        System.out.println("STATE=" + state(pool));
                        while (!Files.exists(Path.of(System.getProperty("probe.dir"), "probe"))) Thread.sleep(30);
                        var properties = context.getBean(org.springframework.boot.autoconfigure.jdbc.DataSourceProperties.class);
                        System.out.println("DRIVER_EXPLICIT=" + (properties.getDriverClassName() != null));
                        System.out.println("AFTER=" + state(pool));
                    }
                }
            }
            """;

    private static String properties(String url, String password) {
        return "spring.datasource.url=" + url + "\nspring.datasource.username=sa\nspring.datasource.password=" + password + "\n";
    }

    private static final String APP = """
            package app;
            import java.nio.file.*;
            import java.sql.*;
            @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
            @org.springframework.boot.autoconfigure.ImportAutoConfiguration(
                org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class)
            public class App {
                static String marker(javax.sql.DataSource ds) throws Exception {
                    try (Connection c = ds.getConnection(); ResultSet r = c.createStatement().executeQuery("select name from marker")) {
                        r.next(); return r.getString(1);
                    }
                }
                static void seed(String db) throws Exception {
                    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1", "sa", "")) {
                        c.createStatement().execute("create table marker(name varchar(20))");
                        c.createStatement().execute("insert into marker values('" + db + "')");
                        c.createStatement().execute("create table work(n int)");
                    }
                }
                static int alphaWork() throws Exception {
                    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:alpha;DB_CLOSE_DELAY=-1", "sa", "");
                         ResultSet r = c.createStatement().executeQuery("select count(*) from work")) { r.next(); return r.getInt(1); }
                }
                static boolean retired() throws Exception {
                    for (int i = 0; i < 200; i++) {
                        if (Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals("reclazz-hikari-retire"))) return true;
                        Thread.sleep(50);
                    }
                    return false;
                }
                static void await(String name) throws Exception {
                    while (!Files.exists(Path.of(System.getProperty("probe.dir"), name))) Thread.sleep(30);
                }
                public static void main(String[] args) throws Exception {
                    seed("alpha"); seed("beta");
                    var boot = new org.springframework.boot.SpringApplication(App.class);
                    boot.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
                    var context = boot.run("--spring.config.location=file:" + System.getProperty("config.file"));
                    javax.sql.DataSource ds = context.getBean(javax.sql.DataSource.class);
                    String first = marker(ds);
                    Connection inFlight = ds.getConnection();
                    inFlight.setAutoCommit(false);
                    inFlight.createStatement().execute("insert into work values(1)");
                    System.out.println("DS_READY=" + first);
                    await("probe1");
                    String now = marker(ds);
                    inFlight.createStatement().execute("insert into work values(2)");
                    inFlight.commit(); inFlight.close();
                    System.out.println("PROBE1=" + now + ":" + alphaWork() + ":" + (context.getBean(javax.sql.DataSource.class) == ds) + ":" + retired());
                    await("probe2");
                    System.out.println("PROBE2=" + marker(ds) + ":" + alphaWork() + ":" + (context.getBean(javax.sql.DataSource.class) == ds) + ":" + retired());
                    await("probe3");
                    System.out.println("PROBE3=" + marker(ds) + ":" + alphaWork() + ":" + (context.getBean(javax.sql.DataSource.class) == ds) + ":" + retired());
                    while (true) Thread.sleep(1000);
                }
            }
            """;
}
