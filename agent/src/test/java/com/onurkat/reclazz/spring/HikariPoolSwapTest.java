/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** R37: HikariPoolSwap against a real HikariCP 4.0.3 pool (Boot 2.7's) and in-memory H2 databases. */
class HikariPoolSwapTest {
    private final List<HikariDataSource> pools = new ArrayList<>();
    private final String run = UUID.randomUUID().toString().substring(0, 8);

    @AfterEach void close() { pools.forEach(HikariDataSource::close); }

    private String db(String name) throws Exception {
        String url = "jdbc:h2:mem:" + name + run + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.createStatement().execute("create table marker(name varchar(20))");
            c.createStatement().execute("insert into marker values('" + name + "')");
            c.createStatement().execute("create table work(n int)");
        }
        return url;
    }

    private HikariDataSource pool(String url) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url); ds.setUsername("sa"); ds.setPassword("");
        ds.setPoolName("swap-" + run); ds.setMaximumPoolSize(3);
        pools.add(ds);
        return ds;
    }

    private static String marker(javax.sql.DataSource ds) throws Exception {
        try (Connection c = ds.getConnection(); ResultSet r = c.createStatement().executeQuery("select name from marker")) {
            r.next(); return r.getString(1);
        }
    }

    private static Object livePool(HikariDataSource ds) throws Exception {
        var field = HikariDataSource.class.getDeclaredField("pool");
        field.setAccessible(true);
        return field.get(ds);
    }

    private static boolean retiredWithin(long millis) throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals("reclazz-hikari-retire")
                    && t.isAlive())) return true;
            Thread.sleep(20);
        }
        return false;
    }

    @Test void borrowedConnectionFinishesOnTheOldPoolWhileNewBorrowsReachTheNewDatabase() throws Exception {
        String alpha = db("alpha"), beta = db("beta");
        HikariDataSource ds = pool(alpha);
        assertEquals("alpha", marker(ds));
        Object oldPool = livePool(ds);
        Connection inFlight = ds.getConnection();
        inFlight.setAutoCommit(false);
        inFlight.createStatement().execute("insert into work values(1)");

        var outcome = HikariPoolSwap.swap(ds, beta, null, null);

        assertNull(outcome.refused());
        assertTrue(outcome.started());
        assertEquals(1, outcome.stillBorrowed());
        assertEquals("beta", marker(ds), "a new borrow reaches the new database");
        assertNotSame(oldPool, livePool(ds));
        assertEquals(beta, ds.getJdbcUrl());
        assertThrows(IllegalStateException.class, () -> ds.setJdbcUrl("jdbc:h2:mem:x"), "the pool is sealed again");
        assertFalse(retiredWithin(500), "the old pool waits for its borrowed connection");
        inFlight.createStatement().execute("insert into work values(2)");
        inFlight.commit();
        inFlight.close();
        assertTrue(retiredWithin(5_000), "the old pool is shut down once the connection is returned");
        try (Connection c = DriverManager.getConnection(alpha, "sa", "");
             ResultSet r = c.createStatement().executeQuery("select count(*) from work")) {
            r.next(); assertEquals(2, r.getInt(1), "the transaction open across the swap committed on the old database");
        }
        assertEquals(0, ((com.zaxxer.hikari.pool.HikariPool) oldPool).getTotalConnections());
    }

    @Test void anUnusedPoolTakesTheSettingsForItsFirstConnection() throws Exception {
        String alpha = db("alpha"), beta = db("beta");
        HikariDataSource ds = pool(alpha);
        var outcome = HikariPoolSwap.swap(ds, beta, null, null);
        assertNull(outcome.refused());
        assertFalse(outcome.started());
        assertEquals("beta", marker(ds));
    }

    @Test void aConnectionThatCannotBeOpenedChangesNothingAndNeverNamesThePassword() throws Exception {
        String alpha = db("alpha");
        HikariDataSource ds = pool(alpha);
        assertEquals("alpha", marker(ds));
        Object before = livePool(ds);

        var missing = HikariPoolSwap.swap(ds, "jdbc:h2:mem:missing" + run + ";IFEXISTS=TRUE", null, null);
        assertNotNull(missing.refused());
        assertEquals(alpha, ds.getJdbcUrl(), "the URL is restored");
        assertSame(before, livePool(ds));

        String secret = "r37-secret-" + run;
        var wrong = HikariPoolSwap.swap(ds, null, null, secret);
        assertNotNull(wrong.refused());
        assertFalse(wrong.refused().contains(secret));
        assertEquals("", ds.getPassword(), "the password is restored");
        assertSame(before, livePool(ds));
        assertEquals("alpha", marker(ds));
        assertThrows(IllegalStateException.class, () -> ds.setJdbcUrl("jdbc:h2:mem:x"), "still sealed");
    }

    @Test void jmxRegistrationMovesToTheNewPoolOnceTheOldOneIsGone() throws Exception {
        String alpha = db("alpha"), beta = db("beta");
        HikariDataSource ds = pool(alpha);
        ds.setRegisterMbeans(true);
        assertEquals("alpha", marker(ds));
        var name = new javax.management.ObjectName("com.zaxxer.hikari:type=Pool (swap-" + run + ")");
        var server = ManagementFactory.getPlatformMBeanServer();
        assertTrue(server.isRegistered(name));

        assertNull(HikariPoolSwap.swap(ds, beta, null, null).refused());
        assertTrue(retiredWithin(5_000));
        assertTrue(server.isRegistered(name), "the new pool is registered under the shared name");
        assertEquals(livePool(ds), ds.getHikariPoolMXBean());
        assertTrue(ds.isRegisterMbeans());
    }

    @Test void aWrappedPoolIsFoundAndAForeignOneIsNot() throws Exception {
        HikariDataSource ds = pool(db("alpha"));
        var wrapper = new org.springframework.jdbc.datasource.DelegatingDataSource(ds);
        assertSame(ds, HikariPoolSwap.unwrap(wrapper));
        assertNull(HikariPoolSwap.unwrap(new org.springframework.jdbc.datasource.DriverManagerDataSource()));
    }

    @Test void ownershipNeedsOneExactMatchAndProductsAreCompared() {
        var before = new SpringDataSourceCheck.Before(List.of(
                new SpringDataSourceCheck.Owner("spring.datasource.", "spring.datasource.url", "jdbc:h2:mem:a", "sa", null),
                new SpringDataSourceCheck.Owner("app.second.", "app.second.url", "jdbc:h2:mem:b", "sa", null)));
        HikariDataSource a = pool("jdbc:h2:mem:a"), b = pool("jdbc:h2:mem:b"), other = pool("jdbc:h2:mem:c");
        assertEquals("spring.datasource.", before.ownerOf(a).prefix());
        assertEquals("app.second.", before.ownerOf(b).prefix(), "a second pool is matched to its own keys");
        assertNull(before.ownerOf(other));
        var ambiguous = new SpringDataSourceCheck.Before(List.of(
                new SpringDataSourceCheck.Owner("x.", "x.url", "jdbc:h2:mem:a", null, null),
                new SpringDataSourceCheck.Owner("y.", "y.url", "jdbc:h2:mem:a", null, null)));
        assertNull(ambiguous.ownerOf(a), "two prefixes that both match prove nothing");
        assertEquals("postgresql", SpringDataSourceCheck.subprotocol("jdbc:postgresql://h/db"));
        assertEquals("h2", SpringDataSourceCheck.subprotocol("jdbc:h2:mem:a"));
        assertEquals("", SpringDataSourceCheck.subprotocol(null));
    }

    @Test void aRefusedCredentialChangeNeverReachesTheOldPoolsNewConnections() throws Exception {
        String url = "jdbc:h2:mem:creds" + run + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "right")) { c.createStatement().execute("create table marker(name varchar(20))"); c.createStatement().execute("insert into marker values('creds')"); }
        HikariDataSource ds = pool(url);
        ds.setPassword("right"); ds.setConnectionTimeout(2_000);
        assertEquals("creds", marker(ds));
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        Thread traffic = new Thread(() -> {
            while (!stop.get()) {
                try {
                    // Every round opens a new physical connection, which reads the pool's credentials again.
                    ds.getHikariPoolMXBean().softEvictConnections();
                    marker(ds);
                } catch (Exception failed) { failures.incrementAndGet(); }
            }
        });
        traffic.start();
        var refused = HikariPoolSwap.swap(ds, null, null, "wrong-" + run);
        stop.set(true); traffic.join();
        assertNotNull(refused.refused());
        assertEquals(0, failures.get(), "the old pool kept opening connections with the old password while the new one was tried");
        assertEquals("right", ds.getPassword());
    }

    @Test void aCallerThatReadTheOldPoolJustBeforeTheSwitchCanStillBorrowFromIt() throws Exception {
        String alpha = db("alpha"), beta = db("beta");
        HikariDataSource ds = pool(alpha);
        assertEquals("alpha", marker(ds));
        var old = (com.zaxxer.hikari.pool.HikariPool) livePool(ds);
        assertNull(HikariPoolSwap.swap(ds, beta, null, null).refused());
        try (Connection late = old.getConnection(1_000)) {
            assertTrue(late.isValid(1), "the retired pool still serves a caller that held it across the switch");
        }
        assertTrue(retiredWithin(5_000));
    }

    @Test void overlappingSwapsLeaveOnlyTheLivePoolRegisteredWithItsSettings() throws Exception {
        String alpha = db("alpha"), beta = db("beta"), gamma = db("gamma");
        HikariDataSource ds = pool(alpha);
        ds.setRegisterMbeans(true); ds.setMinimumIdle(2);
        assertEquals("alpha", marker(ds));
        Connection held = ds.getConnection();
        assertNull(HikariPoolSwap.swap(ds, beta, null, null).refused());
        assertNull(HikariPoolSwap.swap(ds, gamma, null, null).refused());
        assertEquals("gamma", marker(ds));
        held.close();
        assertTrue(retiredWithin(10_000));
        var name = new javax.management.ObjectName("com.zaxxer.hikari:type=Pool (swap-" + run + ")");
        assertTrue(ManagementFactory.getPlatformMBeanServer().isRegistered(name));
        assertSame(livePool(ds), ds.getHikariPoolMXBean(), "the registered pool is the live one");
        assertEquals(2, ds.getMinimumIdle(), "the live pool's minimum idle is back once nothing drains");
        assertEquals("gamma", marker(ds));
    }
}
