/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.ui.Plural;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import com.onurkat.reclazz.util.Reflect;

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
 *
 * <p>R37: a HikariCP pool is not left at that. It is matched to the key prefix it
 * was built from (the prefix whose url and username, read before the save, are
 * exactly what the pool uses), and {@link HikariPoolSwap} switches it to the new
 * URL and credentials inside the same object. A pool owned by another prefix is
 * never touched by this one's keys. Only pools that cannot be matched or switched
 * fall back to the warning above.
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
        java.util.Set<Object> handled = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        for (Object appContext : applicationContexts) {
            Before before = consume(appContext);
            for (String name : SpringBeans.beanNamesForType(appContext, DATA_SOURCE)) {
                Object dataSource = SpringBeans.getBean(appContext, name);
                if (dataSource == null) continue;
                Object hikari = HikariPoolSwap.unwrap(dataSource);
                if (hikari != null && before != null) {
                    if (!handled.add(hikari)) continue;
                    Owner owner = before.ownerOf(hikari);
                    if (owner == null) owner = before.byPrefix(knownOwner(hikari));
                    if (owner != null) rememberOwner(hikari, owner.prefix());
                    if (owner != null && reconnect(appContext, name, hikari, owner, changed)) continue;
                }
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

    /**
     * Switches a HikariCP pool that was built from {@code owner}'s keys to their new values. Returns whether the change
     * was dealt with here (applied, or refused with its own reason), so the generic disagreement warning is not added.
     */
    private static boolean reconnect(Object context, String name, Object hikari, Owner owner,
                                     java.util.Map<String, String> changed) {
        Object environment;
        try { environment = PropertyChangeCheck.call(context, "getEnvironment"); }
        catch (Exception unavailable) { return false; }
        String url = resolve(environment, owner.urlKey());
        String username = resolve(environment, owner.prefix() + "username");
        String password = resolve(environment, owner.prefix() + "password");
        String driver = resolve(environment, owner.prefix() + "driver-class-name");
        List<String> keys = new ArrayList<>();
        for (String key : changed.keySet()) if (key.startsWith(owner.prefix())) keys.add(key);

        // A removed key under spring.datasource falls back to what Boot itself would build the pool with at startup
        // (DataSourceProperties.determineUrl/Username/Password, e.g. "sa" for an embedded database): restart-equal.
        Object boot = bootProperties(context, owner.prefix());
        if (boot != null) {
            if (url == null) url = determined(boot, "determineUrl");
            if (username == null) username = determined(boot, "determineUsername");
            if (password == null) password = determined(boot, "determinePassword");
        }
        // A removed url or username key with nothing to fall back to: say so instead of keeping quiet.
        List<String> removedKeys = new ArrayList<>();
        for (String key : keys) {
            String last = key.substring(key.lastIndexOf('.') + 1);
            if (resolve(environment, key) != null) continue;
            if (((last.equals("url") || last.equals("jdbc-url") || last.equals("jdbcUrl")) && url == null)
                    || (last.equals("username") && username == null)) removedKeys.add(key);
        }
        if (!removedKeys.isEmpty()) {
            StatusReporter.warn(removedKeys + " removed, and " + name + " keeps the connection settings it was built with. "
                    + "A restart applies the change.");
            RestartLedger.note(name, "connection properties " + removedKeys + " removed");
            return true;
        }

        String liveUrl = HikariPoolSwap.jdbcUrl(hikari);
        boolean urlChanged = url != null && !url.equals(liveUrl);
        boolean userChanged = username != null && !username.equals(HikariPoolSwap.username(hikari));
        boolean passwordChanged = password != null && !password.equals(HikariPoolSwap.password(hikari));
        if (!urlChanged && !userChanged && !passwordChanged) return true;

        String refused = null;
        if (!java.util.Objects.equals(driver, owner.driver()))
            refused = "the JDBC driver class changed, and the running code was built against the old one";
        else if (urlChanged && !subprotocol(url).equals(subprotocol(liveUrl)))
            refused = "the URL now names a different database product (jdbc:" + subprotocol(liveUrl) + " to jdbc:"
                    + subprotocol(url) + "), and dialects and migrations that ran at startup belong to the old one";
        HikariPoolSwap.Outcome outcome = refused == null
                ? HikariPoolSwap.swap(hikari, urlChanged ? url : null, userChanged ? username : null,
                        passwordChanged ? password : null)
                : HikariPoolSwap.Outcome.refuse(refused);
        if (outcome.refused() != null) {
            StatusReporter.warn(keys + " changed and " + name + " still uses its old connection settings: "
                    + outcome.refused() + ". A restart applies them.");
            RestartLedger.note(name, "connection properties " + keys + ": " + outcome.refused());
            return true;
        }
        StatusReporter.success(outcome.started()
                ? "Reconnected " + name + " with the changed " + keys + ": new connections use them"
                        + (outcome.stillBorrowed() > 0 ? ", and " + Plural.of(outcome.stillBorrowed(), "connection")
                        + " already in use finish" + (outcome.stillBorrowed() == 1 ? "es" : "")
                        + " on the old pool, which closes once they are returned" : ", and the old pool is closed")
                : name + " takes the changed " + keys + " when it opens its first connection");
        return true;
    }

    private static String resolve(Object environment, String key) {
        try {
            Object value = PropertyChangeCheck.call(environment, "getProperty", key);
            return value == null ? null : value.toString();
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** {@code jdbc:postgresql://...} gives {@code postgresql}. */
    static String subprotocol(String url) {
        if (url == null || !url.startsWith("jdbc:")) return "";
        int end = url.indexOf(':', 5);
        return end < 0 ? url.substring(5) : url.substring(5, end);
    }

    // ---- what the pools were built from, read before the new values replace the old ones ----

    /** A key prefix whose url (or jdbc-url) the pool was built from, with the values it had then. */
    record Owner(String prefix, String urlKey, String url, String username, String driver) { }

    /** The datasource-shaped prefixes of one save, with the values they resolved to before it. */
    static final class Before {
        final List<Owner> owners;
        Before(List<Owner> owners) { this.owners = owners; }

        Owner byPrefix(String prefix) {
            if (prefix == null) return null;
            for (Owner owner : owners) if (owner.prefix().equals(prefix)) return owner;
            return null;
        }

        /** The prefix whose old url and username are exactly what this pool is using; null when none or several. */
        Owner ownerOf(Object hikari) {
            String url = HikariPoolSwap.jdbcUrl(hikari), user = HikariPoolSwap.username(hikari);
            Owner found = null;
            for (Owner owner : owners) {
                if (url == null || !url.equals(owner.url())) continue;
                if (user != null && owner.username() != null && !user.equals(owner.username())) continue;
                if (found != null) return null;
                found = owner;
            }
            return found;
        }
    }

    /**
     * The prefix a pool was matched to the first time. After a refused change the Environment holds values the pool does
     * not use, so the next save can no longer match it by its old values; it keeps the prefix it was built from.
     */
    private static final java.util.Map<Object, String> OWNED = new java.util.WeakHashMap<>();

    private static void rememberOwner(Object hikari, String prefix) {
        synchronized (OWNED) { OWNED.put(hikari, prefix); }
    }

    private static String knownOwner(Object hikari) {
        synchronized (OWNED) { return OWNED.get(hikari); }
    }

    /** Boot's own DataSourceProperties bean when {@code prefix} is its prefix; null otherwise. */
    private static Object bootProperties(Object context, String prefix) {
        if (!prefix.equals("spring.datasource.")) return null;
        try {
            Class<?> type = Class.forName("org.springframework.boot.autoconfigure.jdbc.DataSourceProperties", false,
                    context.getClass().getClassLoader());
            java.util.Map<?, ?> beans = (java.util.Map<?, ?>) PropertyChangeCheck.call(context, "getBeansOfType", type);
            return beans.size() == 1 ? beans.values().iterator().next() : null;
        } catch (Throwable noBoot) {
            return null;
        }
    }

    private static String determined(Object boot, String method) {
        try { return (String) PropertyChangeCheck.call(boot, method); }
        catch (Throwable cannotDetermine) { return null; }
    }

    private static final String[] SUFFIXES = {"url", "jdbc-url", "jdbcUrl", "username", "password",
            "driver-class-name", "driverClassName"};
    /** Weak: a save that stops before {@link #report} must not keep its context alive. */
    private static final java.util.Map<Object, Before> PENDING = new java.util.WeakHashMap<>();

    /**
     * Reads, before a save replaces them, the values of the datasource-shaped prefixes it touches. Called by both
     * property paths before the Environment changes; {@link #report} consumes it.
     */
    public static void remember(Object context, java.util.Map<String, String> changed) {
        java.util.Set<String> prefixes = new java.util.LinkedHashSet<>();
        for (String key : changed.keySet()) {
            int dot = key.lastIndexOf('.');
            if (dot < 0) continue;
            String last = key.substring(dot + 1);
            for (String suffix : SUFFIXES) if (suffix.equals(last)) prefixes.add(key.substring(0, dot + 1));
        }
        if (prefixes.isEmpty()) return;
        Object environment;
        try { environment = PropertyChangeCheck.call(context, "getEnvironment"); }
        catch (Exception unavailable) { return; }
        List<Owner> owners = new ArrayList<>();
        for (String prefix : prefixes) {
            for (String urlKey : new String[]{prefix + "url", prefix + "jdbc-url", prefix + "jdbcUrl"}) {
                String url = resolve(environment, urlKey);
                if (url == null) continue;
                owners.add(new Owner(prefix, urlKey, url, resolve(environment, prefix + "username"),
                        resolve(environment, prefix + "driver-class-name")));
                break;
            }
        }
        synchronized (PENDING) {
            // Two file plans of one save touch the same context: keep the first-seen value of every prefix.
            Before earlier = PENDING.get(context);
            if (earlier != null) {
                List<Owner> merged = new ArrayList<>(earlier.owners);
                for (Owner owner : owners)
                    if (merged.stream().noneMatch(o -> o.prefix().equals(owner.prefix()))) merged.add(owner);
                owners = merged;
            }
            PENDING.put(context, new Before(owners));
        }
    }

    private static Before consume(Object context) {
        synchronized (PENDING) { return PENDING.remove(context); }
    }

    /** The changed keys whose value the pool is not actually using. */
    static List<String> disagreeing(Object dataSource, java.util.Map<String, String> changed) {
        List<String> stale = new ArrayList<>();
        for (String[] pair : PROPERTIES) {
            String wanted = changed.get(pair[1]);
            if (wanted == null || stale.contains(pair[1])) continue;

            Method getter = Reflect.findMethod(dataSource.getClass(), pair[0]);
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
