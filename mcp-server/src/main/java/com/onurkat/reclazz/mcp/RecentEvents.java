/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Snapshot pagination over the same received-event history used by await_reload. */
final class RecentEvents {
    static final Set<String> LEVELS = Set.of("INFO", "OK", "WARN", "ERROR", "COMPILE", "RELOAD", "STRUCTURAL_RELOAD");
    private final ObservationRegistry registry;
    RecentEvents(ObservationRegistry registry) { this.registry = registry; }

    static boolean valid(JsonObject args, Map<String, String> opts) {
        if (!Set.of("projectDir", "portFile", "port", "hybrisHome", "timeoutMs", "sessionId", "subscriptionId",
                "sinceCursor", "limit", "level", "ownerClassName", "fileBasename").containsAll(args.keySet())) return false;
        for (String key : List.of("sessionId", "subscriptionId"))
            if (!opts.getOrDefault(key, "").matches("[A-Za-z0-9_-]{1,64}")) return false;
        String cursor = opts.getOrDefault("sinceCursor", "");
        if (!cursor.matches("0|[1-9][0-9]{0,18}")) return false;
        try { Long.parseLong(cursor); } catch (NumberFormatException invalid) { return false; }
        String limit = opts.getOrDefault("limit", "50");
        if (!limit.matches("[1-9][0-9]{0,2}") || Integer.parseInt(limit) > 100) return false;
        return (!opts.containsKey("level") || LEVELS.contains(opts.get("level")))
                && (!opts.containsKey("ownerClassName") || BuildSession.validClassName(opts.get("ownerClassName")))
                && (!opts.containsKey("fileBasename") || EventObservation.fileBasename(opts.get("fileBasename")));
    }

    JsonObject run(Map<String, String> opts, RequestCancellation cancel) {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Integer.parseInt(opts.getOrDefault("timeoutMs", "5000")));
        JsonObject out = base(opts);
        ObservationRegistry.Entry entry = null;
        long after = Long.parseLong(opts.get("sinceCursor"));
        try {
            registry.begin(cancel);
            entry = registry.acquire(opts, cancel, until);
            EventSubscription stream = entry.stream;
            cancel.attach(stream.socket);
            stream.doctor(opts, until);
            if (!stream.id.equals(opts.get("subscriptionId")) || !stream.sessionId().equals(opts.get("sessionId")))
                throw new EventSubscription.Failure("session_changed", "Cursor belongs to a different observation subscription or JVM");
            EventSubscription.Snapshot snapshot = stream.snapshot(after);
            out.addProperty("snapshotHead", Long.toString(snapshot.head()));
            page(out, snapshot, opts);
            stream.recheck(after);
            EventSubscription.remaining(until);
            if (cancel.isCancelled()) throw new EventSubscription.Failure("cancelled", "Observation cancelled");
            out.addProperty("status", "observed"); out.addProperty("reasonCode", "snapshot");
            out.addProperty("detail", "Received observations only; event types confirm no byte receipt, source revision or application behavior");
            return out;
        } catch (IOException | RuntimeException failure) {
            String code = failure instanceof EventSubscription.Failure f ? f.code : "observation_unavailable";
            if (entry != null && Set.of("session_changed", "malformed_response", "response_timeout", "target_mismatch").contains(code)) entry.stream.close();
            out.addProperty("status", "unavailable");
            out.addProperty("reasonCode", code.equals("cursor_gap") ? "cursor_evicted" : code);
            String detail = failure.getMessage() == null ? "Observation unavailable" : failure.getMessage();
            out.addProperty("detail", detail.substring(0, Math.min(512, detail.length())));
            out.add("events", new JsonArray()); out.addProperty("nextCursor", opts.get("sinceCursor"));
            out.addProperty("hasMore", false);
            return out;
        } finally { registry.finish(entry, cancel); }
    }

    static void page(JsonObject out, EventSubscription.Snapshot snapshot, Map<String, String> opts) {
        long scanned = Long.parseLong(opts.get("sinceCursor"));
        int limit = Integer.parseInt(opts.getOrDefault("limit", "50"));
        JsonArray rows = new JsonArray();
        for (EventSubscription.Event event : snapshot.events()) {
            scanned = event.sequence();
            JsonObject row = EventObservation.describe(event);
            boolean matches = true;
            for (String key : List.of("level", "ownerClassName", "fileBasename"))
                if (opts.containsKey(key) && (!row.has(key) || !opts.get(key).equals(row.get(key).getAsString()))) matches = false;
            if (matches) {
                rows.add(row);
                if (rows.size() == limit) break;
            }
        }
        out.add("events", rows);
        out.addProperty("nextCursor", Long.toString(scanned));
        out.addProperty("hasMore", scanned < snapshot.head());
    }
    private static JsonObject base(Map<String, String> opts) {
        JsonObject out = new JsonObject();
        for (String key : List.of("sessionId", "subscriptionId", "sinceCursor", "level", "ownerClassName", "fileBasename"))
            if (opts.containsKey(key)) out.addProperty(key, opts.get(key));
        out.addProperty("limit", Integer.parseInt(opts.getOrDefault("limit", "50")));
        out.addProperty("nextCursor", opts.get("sinceCursor")); out.addProperty("snapshotHead", opts.get("sinceCursor"));
        out.addProperty("hasMore", false); out.add("events", new JsonArray());
        out.addProperty("correlationScope", "received_events");
        out.addProperty("sourcePathConfirmed", false); out.addProperty("editRevisionConfirmed", false); out.addProperty("reloadConfirmed", false);
        return out;
    }
}
