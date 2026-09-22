/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Tool result contracts for negotiated MCP 2025-06-18 sessions. */
final class ToolContracts {
    private static final JsonObject SCHEMAS = loadSchemas();

    private ToolContracts() { }

    static void describe(JsonObject tool) {
        String name = tool.get("name").getAsString();
        tool.add("outputSchema", SCHEMAS.getAsJsonObject(name).deepCopy());
        boolean mutates = name.equals("reclazz_scan") || name.equals("reclazz_build");
        JsonObject hints = new JsonObject();
        hints.addProperty("readOnlyHint", !mutates);
        // Reload can invoke application callbacks, with effects outside this JVM.
        // Neither repeated scans nor repeated build signals promise idempotence.
        hints.addProperty("openWorldHint", mutates);
        if (mutates) {
            hints.addProperty("destructiveHint", true);
            hints.addProperty("idempotentHint", false);
        }
        tool.add("annotations", hints);
    }

    static JsonObject operation(String status, String detail) {
        JsonObject result = new JsonObject();
        result.addProperty("status", status);
        result.addProperty("detail", detail);
        result.addProperty("reloadConfirmed", false);
        return result;
    }

    static JsonObject diagnostic(List<String> lines, String detail, boolean unavailable) {
        JsonObject result = new JsonObject();
        result.addProperty("status", unavailable ? "unavailable" : "observed");
        JsonArray observations = new JsonArray();
        for (String line : lines) observations.add(line);
        result.add("lines", observations);
        result.addProperty("detail", detail);
        // Protocol 1 INFO diagnostics have neither correlation nor an end marker.
        result.addProperty("complete", false);
        return result;
    }

    private static JsonObject loadSchemas() {
        try (var input = Objects.requireNonNull(ToolContracts.class.getResourceAsStream("/tool-output-schemas.json"));
                var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
