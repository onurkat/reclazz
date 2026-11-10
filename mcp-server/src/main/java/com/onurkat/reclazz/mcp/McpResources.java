/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded views of the startup target; URIs are selectors, never locations to fetch. */
final class McpResources {
    static final String TEMPLATE = "reclazz://events/{session}/{subscription}?since={since}{&limit}";
    private static final Pattern EVENTS = Pattern.compile("reclazz://events/([A-Za-z0-9_-]{1,64})/([A-Za-z0-9_-]{1,64})\\?([^#]+)");
    record Selection(String tool, String uri, Map<String, String> options) { }
    static final class Failure extends IllegalArgumentException {
        final int code;
        Failure(int code, String message) { super(message); this.code = code; }
    }
    private McpResources() { }

    static JsonObject parameters(JsonObject request, Set<String> allowed) {
        if (request.has("params") && !request.get("params").isJsonObject())
            throw new IllegalArgumentException("params must be an object");
        JsonObject p = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
        for (String key : p.keySet())
            if (!allowed.contains(key) && !key.equals("_meta")) throw new IllegalArgumentException("Unknown parameter");
        if (p.has("_meta") && !p.get("_meta").isJsonObject()) throw new IllegalArgumentException("_meta must be an object");
        return p;
    }
    static JsonObject list(JsonObject request, boolean templates) {
        parameters(request, Set.of()); // All entries fit one page; no continuation cursor is issued.
        JsonArray rows = new JsonArray();
        if (templates) rows.add(description("events", "uriTemplate", TEMPLATE,
                "Received event pages from an existing await_reload checkpoint on the startup target. since required; limit 1-100, default 50. Observations are not reload proof."));
        else {
            rows.add(description("status", "uri", "reclazz://status", "Agent attachment and health on the startup target."));
            rows.add(description("pending", "uri", "reclazz://pending", "Restart diagnostics on the startup target; complete remains false."));
        }
        JsonObject result = new JsonObject(); result.add(templates ? "resourceTemplates" : "resources", rows); return result;
    }
    private static JsonObject description(String name, String key, String uri, String description) {
        JsonObject r = new JsonObject(); r.addProperty("name", name); r.addProperty(key, uri);
        r.addProperty("description", description); r.addProperty("mimeType", "application/json"); return r;
    }
    static Selection select(JsonObject request) {
        JsonObject p = parameters(request, Set.of("uri"));
        if (!McpServer.isString(p.get("uri"))) throw new IllegalArgumentException("uri must be a string");
        String raw = p.get("uri").getAsString();
        if (raw.length() > 4096 || raw.codePoints().anyMatch(Character::isISOControl) || raw.contains("%"))
            throw new IllegalArgumentException("Invalid resource URI");
        URI uri;
        try { uri = URI.create(raw); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid resource URI"); }
        if (!uri.isAbsolute()) throw new IllegalArgumentException("Resource URI must be absolute");
        if (raw.equals("reclazz://status")) return new Selection("reclazz_status", raw, Map.of());
        if (raw.equals("reclazz://pending")) return new Selection("reclazz_pending", raw, Map.of());
        if ("reclazz".equalsIgnoreCase(uri.getScheme()) && Set.of("status", "pending", "events").contains(uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT))) {
            var match = EVENTS.matcher(raw);
            if (!match.matches()) throw new IllegalArgumentException("Invalid resource selector");
            Map<String, String> opts = new LinkedHashMap<>();
            opts.put("sessionId", match.group(1)); opts.put("subscriptionId", match.group(2));
            for (String part : match.group(3).split("&", -1)) {
                String[] pair = part.split("=", -1);
                if (pair.length != 2 || !Set.of("since", "limit").contains(pair[0]) || pair[1].isEmpty())
                    throw new IllegalArgumentException("Invalid event query");
                String key = pair[0].equals("since") ? "sinceCursor" : "limit";
                if (opts.putIfAbsent(key, pair[1]) != null) throw new IllegalArgumentException("Duplicate event query");
            }
            JsonObject args = new JsonObject(); opts.forEach(args::addProperty);
            if (!RecentEvents.valid(args, opts)) throw new IllegalArgumentException("Invalid event identity, cursor or limit");
            return new Selection("reclazz_events", raw, Map.copyOf(opts));
        }
        throw new Failure(-32002, "Resource not found");
    }
    static boolean isEventRead(JsonObject request) {
        if (!McpServer.isString(request.get("method")) || !request.get("method").getAsString().equals("resources/read")) return false;
        try { return select(request).tool().equals("reclazz_events"); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    static JsonObject contents(String uri, JsonObject data) {
        JsonObject content = new JsonObject(); content.addProperty("uri", uri);
        content.addProperty("mimeType", "application/json"); content.addProperty("text", data.toString());
        JsonArray rows = new JsonArray(); rows.add(content);
        JsonObject result = new JsonObject(); result.add("contents", rows); return result;
    }
}
