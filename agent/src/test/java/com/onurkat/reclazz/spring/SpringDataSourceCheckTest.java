/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A connection pool takes a size and refuses a URL, and Reclazz used to report
 * both as applied. Measured on Boot 3.3 with HikariCP 5: editing
 * {@code spring.datasource.url} printed "Rebound 3 @ConfigurationProperties
 * bean(s)" while the pool went on using the URL it was built with, and nothing
 * else was said. Both halves of that were true and together they read as
 * success.
 *
 * <p>So the pool is asked what it is actually using, rather than assumed either
 * way. That distinction is the whole point and is what these tests hold: a pool
 * size really is applied to a running Hikari pool, measured at 5 to 17 without
 * a restart, so a check that warned about every datasource property would be
 * noise on the ones that work.
 */
class SpringDataSourceCheckTest {

    /** HikariDataSource answers getJdbcUrl; a plain one answers getUrl. */
    static class HikariShape {
        private final String url;
        private final String username;

        HikariShape(String url, String username) {
            this.url = url;
            this.username = username;
        }

        public String getJdbcUrl() {
            return url;
        }

        public String getUsername() {
            return username;
        }
    }

    /** A datasource that will not say: no claim in either direction. */
    static class Silent {
    }

    /** One that answers null, which is not a disagreement either. */
    static class SaysNothing {
        public String getJdbcUrl() {
            return null;
        }
    }

    @Test
    void aUrlThePoolIsNotUsingIsReported() {
        var pool = new HikariShape("jdbc:h2:mem:demo", "sa");

        assertEquals(List.of("spring.datasource.url"),
                SpringDataSourceCheck.disagreeing(pool,
                        Map.of("spring.datasource.url", "jdbc:h2:mem:other")),
                "the pool kept what it was built with, and saying nothing reads as success");
    }

    @Test
    void aUrlThePoolIsAlreadyUsingIsNotReported() {
        var pool = new HikariShape("jdbc:h2:mem:demo", "sa");

        assertTrue(SpringDataSourceCheck.disagreeing(pool,
                Map.of("spring.datasource.url", "jdbc:h2:mem:demo")).isEmpty());
    }

    /**
     * The one that keeps this honest in the other direction: a pool size IS
     * applied to a running pool, so a check that fired on every datasource key
     * would be crying wolf on the keys that work.
     */
    @Test
    void aPoolPropertyThatIsAppliedIsNeverReported() {
        var pool = new HikariShape("jdbc:h2:mem:demo", "sa");

        assertTrue(SpringDataSourceCheck.disagreeing(pool,
                Map.of("spring.datasource.hikari.maximum-pool-size", "17",
                       "spring.datasource.hikari.connection-timeout", "45000")).isEmpty());
    }

    @Test
    void theUsernameIsCheckedTheSameWay() {
        var pool = new HikariShape("jdbc:h2:mem:demo", "sa");

        assertEquals(List.of("spring.datasource.username"),
                SpringDataSourceCheck.disagreeing(pool,
                        Map.of("spring.datasource.username", "someone-else")));
    }

    @Test
    void aDataSourceThatWillNotSayProducesNoClaim() {
        Map<String, String> changed = Map.of("spring.datasource.url", "jdbc:h2:mem:other");

        assertTrue(SpringDataSourceCheck.disagreeing(new Silent(), changed).isEmpty());
        assertTrue(SpringDataSourceCheck.disagreeing(new SaysNothing(), changed).isEmpty(),
                "answering null is not disagreeing, it is not answering");
    }

    @Test
    void aSaveThatTouchesNoDatasourceKeyIsSilent() {
        var pool = new HikariShape("jdbc:h2:mem:demo", "sa");

        assertTrue(SpringDataSourceCheck.disagreeing(pool,
                Map.of("demo.greeting", "selam")).isEmpty());
    }
}
