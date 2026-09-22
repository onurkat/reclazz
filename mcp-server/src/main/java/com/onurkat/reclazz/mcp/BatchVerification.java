/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Sequential observations on one connection, never a transactional reload receipt. */
final class BatchVerification {
    static final int MAX_ITEMS = 32;
    private BatchVerification() { }

    static boolean valid(JsonElement value) {
        if (value == null || !value.isJsonArray()) return false;
        JsonArray items = value.getAsJsonArray();
        if (items.isEmpty() || items.size() > MAX_ITEMS) return false;
        Set<String> names = new HashSet<>();
        for (JsonElement item : items) {
            if (!item.isJsonObject()) return false;
            JsonObject pair = item.getAsJsonObject();
            if (!pair.keySet().equals(Set.of("className", "sha256"))
                    || !McpServer.isString(pair.get("className")) || !McpServer.isString(pair.get("sha256"))) return false;
            String name = pair.get("className").getAsString();
            if (!BuildSession.validVerification(name, pair.get("sha256").getAsString()) || !names.add(name)) return false;
        }
        return true;
    }

    static JsonObject run(JsonArray items, Map<String, String> opts, RequestCancellation cancel) {
        int timeout = Integer.parseInt(opts.getOrDefault("timeoutMs", "5000"));
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        JsonArray results = new JsonArray();
        String sessionId = "";
        boolean allApplied = true;
        try (BuildSession session = BuildSession.open(opts, cancel, until)) {
            for (JsonElement item : items) {
                if (cancel.isCancelled()) throw new IOException("Request cancelled");
                JsonObject pair = item.getAsJsonObject();
                JsonObject receipt = session.verify(pair.get("className").getAsString(), pair.get("sha256").getAsString(), until);
                String actualSession = receipt.get("sessionId").getAsString();
                if (!sessionId.isEmpty() && !sessionId.equals(actualSession)) throw new IOException("Inconsistent agent session");
                sessionId = actualSession;
                results.add(receipt);
                allApplied &= "applied".equals(receipt.get("status").getAsString());
            }
        } catch (IOException | IllegalArgumentException unavailable) {
            allApplied = false;
        }
        boolean unavailable = results.size() != items.size();
        while (results.size() < items.size()) {
            JsonObject pair = items.get(results.size()).getAsJsonObject();
            JsonObject missing = new JsonObject();
            missing.addProperty("className", pair.get("className").getAsString());
            missing.addProperty("expectedSha256", pair.get("sha256").getAsString());
            missing.addProperty("status", "unavailable");
            missing.addProperty("detail", "No validated receipt: connection, deadline, cancellation or session consistency failed; subsequent items were not queried.");
            results.add(missing);
        }
        JsonObject data = new JsonObject();
        data.addProperty("status", unavailable ? "unavailable" : allApplied ? "applied" : "incomplete");
        data.addProperty("detail", "Ordered point-in-time class receipts; no atomic reload or application behavior proof.");
        data.addProperty("sessionId", sessionId);
        data.addProperty("requestedCount", items.size());
        data.addProperty("allApplied", allApplied);
        data.addProperty("atomic", false);
        data.addProperty("reloadConfirmed", false);
        data.add("results", results);
        return data;
    }
}
