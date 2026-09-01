/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.agent.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Says which datasource properties a running pool did not take.
 *
 * <p>A connection pool seals its configuration once it is in use, which is
 * correct of it: the connections are already open, against the URL and the
 * credentials it was built with. What was not correct was Reclazz reporting
 * the change as applied anyway. Measured on Spring Boot 3.3.4 with HikariCP 5,
 * editing {@code spring.datasource.url} in a watched properties file:
 *
 * <pre>
 *   [ OK ] Rebound 3 @ConfigurationProperties bean(s): [..., dataSource, ...]
 *   the live pool                                       jdbc:h2:mem:demo  (the old one)
 * </pre>
 *
 * <p>Both halves of that were true and together they were a lie. The
 * properties objects really were rebound; the pool really did keep the old
 * URL; and the only line printed was the one that reads as success.
 *
 * <p>Not every datasource property is like this, which is why this asks the
 * pool rather than assuming. A pool size or a timeout IS applied to a running
 * Hikari pool, through the setters it exposes for exactly that, and measured
 * to work: 5 to 17 with no restart. So the pool is asked what it currently
 * holds and that is compared with what the Environment now says. Only a real
 * disagreement is reported, which keeps the tuning knobs quiet and names the
 * two that a restart is genuinely for.
 *
 * <p>Reflective and shaped by what the object answers to, not by which pool it
 * is: a datasource that will not say what URL it is using produces no claim in
 * either direction.
 */
public class SpringDataSourceCheck {

    private static final String DATA_SOURCE = "javax.sql.DataSource";

    /** What a pool is asked, against the key that should be answering. */
    private static final String[][] PROPERTIES = {
            {"getJdbcUrl", "spring.datasource.url"},
            {"getUrl", "spring.datasource.url"},
            {"getUsername", "spring.datasource.username"},
    };

    private final List<Object> applicationContexts;

    public SpringDataSourceCheck(List<Object> applicationContexts) {
        this.applicationContexts = applicationContexts;
    }

    /**
     * @param changed the keys this save changed, with their new values
     * @return the keys a running pool did not take
     */
    public List<String> report(java.util.Map<String, String> changed) {
        List<String> stale = new ArrayList<>();
        if (changed.isEmpty()) return stale;

        for (Object appContext : applicationContexts) {
            for (String name : SpringBeans.beanNamesForType(appContext, DATA_SOURCE)) {
                Object dataSource = SpringBeans.getBean(appContext, name);
                if (dataSource == null) continue;
                for (String key : disagreeing(dataSource, changed)) {
                    if (!stale.contains(key)) stale.add(key);
                }
            }
        }

        if (!stale.isEmpty()) {
            StatusReporter.warn(stale + " changed, and the connection pool is still using what it "
                    + "was built with: a pool seals its configuration once its connections are "
                    + "open. The properties objects took the new values, so anything reading them "
                    + "from here on sees them, but the pool itself needs a restart. Pool size and "
                    + "the timeouts are different and were applied.");
            RestartLedger.note("the datasource",
                    "connection properties " + stale + " that a running pool cannot take");
        }
        return stale;
    }

    /** The changed keys whose value the pool is not actually using. */
    static List<String> disagreeing(Object dataSource, java.util.Map<String, String> changed) {
        List<String> stale = new ArrayList<>();
        for (String[] pair : PROPERTIES) {
            String wanted = changed.get(pair[1]);
            if (wanted == null || stale.contains(pair[1])) continue;

            Method getter = SpringBeans.findMethod(dataSource.getClass(), pair[0]);
            if (getter == null) continue;
            try {
                Object live = getter.invoke(dataSource);
                // A pool that answers null is not disagreeing, it is not saying.
                if (live == null) continue;
                if (!wanted.equals(String.valueOf(live))) stale.add(pair[1]);
            } catch (Throwable willNotSay) {
                // Same: no claim in either direction.
            }
        }
        return stale;
    }
}
