/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import java.io.IOException;
import java.util.*;

/** One bounded instance-local registry shared by reload waits and event pages. */
final class ObservationRegistry implements AutoCloseable {
    private final LinkedHashMap<String, Entry> subscriptions = new LinkedHashMap<>(4, .75f, true);
    private final Set<RequestCancellation> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    static final class Entry {
        final EventSubscription stream;
        boolean busy;
        Entry(EventSubscription stream) { this.stream = stream; }
    }

    void begin(RequestCancellation cancel) throws IOException {
        active.add(cancel);
        if (closed) throw new EventSubscription.Failure("session_changed", "MCP observation registry is closed");
    }
    void finish(Entry entry, RequestCancellation cancel) {
        active.remove(cancel);
        if (entry != null) {
            cancel.detach(entry.stream.socket);
            synchronized (this) { entry.busy = false; }
        }
    }
    Entry acquire(Map<String, String> opts, RequestCancellation cancel, long until) throws IOException {
        AgentSocket.Result address = new AgentSocket.Result(); AgentSocket.resolvePort(opts, address);
        if (address.reason != null) throw new EventSubscription.Failure("target_unavailable", "Cannot resolve the selected agent endpoint");
        String key = address.port + "\n" + opts.getOrDefault("baseDir", "") + "\n" + opts.getOrDefault("projectDir", "");
        synchronized (this) {
            Entry e = subscriptions.get(key);
            if (e != null && e.stream.alive()) {
                if (e.busy) throw new EventSubscription.Failure("busy", "Observation subscription already has an active request");
                e.busy = true; return e;
            }
            if (!"checkpoint".equals(opts.get("action")))
                throw new EventSubscription.Failure("session_changed", "No live subscription for this cursor; checkpoint again");
            if (e != null) { e.stream.close(); subscriptions.remove(key); }
            if (subscriptions.size() >= 2) {
                var idle = subscriptions.entrySet().stream().filter(x -> !x.getValue().busy).findFirst();
                if (idle.isEmpty()) throw new EventSubscription.Failure("busy", "All bounded observation subscriptions are active");
                idle.get().getValue().stream.close(); subscriptions.remove(idle.get().getKey());
            }
        }
        EventSubscription created = new EventSubscription(opts, address.port, cancel, until);
        synchronized (this) {
            if (closed || subscriptions.containsKey(key) || subscriptions.size() >= 2) {
                created.close(); throw new EventSubscription.Failure("busy", "Registry changed while opening the subscription");
            }
            Entry e = new Entry(created); e.busy = true; subscriptions.put(key, e); return e;
        }
    }

    @Override public void close() {
        closed = true; active.forEach(RequestCancellation::cancel);
        synchronized (this) { subscriptions.values().forEach(e -> e.stream.close()); subscriptions.clear(); }
    }
}
