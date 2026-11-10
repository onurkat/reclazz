/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import java.util.Map;

/** Canonical query data shared by tools and resources, before presentation. */
final class ReadOnlyQueries {
    record Result(JsonObject data, String legacyText, boolean isError) { }
    private final RecentEvents events;
    ReadOnlyQueries(RecentEvents events) { this.events = events; }
    Result run(String name, Map<String, String> opts, RequestCancellation cancel) {
        if (name.equals("reclazz_events")) {
            JsonObject data = events.run(opts, cancel);
            return new Result(data, data.toString(), !data.get("status").getAsString().equals("observed"));
        }
        if (name.equals("reclazz_status")) {
            JsonObject data = statusJson(AgentSocket.run(opts, "HEALTH"));
            return new Result(data, data.toString(), false);
        }
        if (name.equals("reclazz_pending")) {
            AgentSocket.Result r = AgentSocket.run(opts, "PENDING");
            boolean error = r.reason != null;
            String text = error ? "Pending query failed: " + r.reason : String.join("\n", r.lines);
            return new Result(ToolContracts.diagnostic(r.lines, text, error), text, error);
        }
        throw new IllegalArgumentException("Unknown read-only query");
    }

    private static JsonObject statusJson(AgentSocket.Result r) {
        JsonObject o = new JsonObject();
        o.addProperty("attached", r.connected);
        if (r.connected) {
            if (r.reason != null) o.addProperty("reason", r.reason);
            o.addProperty("agent", r.agent);
            o.addProperty("protocol", r.protocol);
            o.addProperty("port", r.port);
            JsonArray health = new JsonArray();
            for (String line : r.lines) health.add(line);
            o.add("health", health);
        } else {
            o.addProperty("reason", r.reason);
            if (r.port != 0) o.addProperty("port", r.port);
        }
        return o;
    }

}
