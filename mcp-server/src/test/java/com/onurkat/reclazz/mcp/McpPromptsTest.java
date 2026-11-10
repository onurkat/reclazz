/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class McpPromptsTest {
    @Test void discoveryAndGetBothVersions() {
        try (var s = new McpServer()) {
            for (String v : List.of("2024-11-05", "2025-06-18")) {
                McpResourcesTest.result(s, "initialize", McpResourcesTest.params("protocolVersion", v));
                JsonObject list = McpResourcesTest.result(s, "prompts/list", null);
                assertEquals(1, list.getAsJsonArray("prompts").size());
                assertEquals("verify_my_last_change", list.getAsJsonArray("prompts").get(0).getAsJsonObject().get("name").getAsString());
                JsonObject got = McpResourcesTest.result(s, "prompts/get", McpResourcesTest.params("name", "verify_my_last_change"));
                assertEquals(1, got.getAsJsonArray("messages").size());
                assertEquals("user", got.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
            }
        }
    }
    @Test void invalidNamesArgumentsAndMetadataAreRejectedWithoutExecution() {
        try (var s = new McpServer()) {
            for (JsonObject p : List.of(new JsonObject(), McpResourcesTest.params("name", "other"), McpResourcesTest.params("name", "")))
                McpResourcesTest.error(s, McpResourcesTest.rpc("prompts/get", p), -32602);
            for (JsonElement args : List.of(JsonNull.INSTANCE, new JsonPrimitive("x"), new JsonArray(), McpResourcesTest.params("edit", "injected"))) {
                JsonObject p = McpResourcesTest.params("name", "verify_my_last_change"); p.add("arguments", args);
                McpResourcesTest.error(s, McpResourcesTest.rpc("prompts/get", p), -32602);
            }
            JsonObject p = McpResourcesTest.params("name", "verify_my_last_change"); p.add("arguments", new JsonObject());
            String text = McpResourcesTest.result(s, "prompts/get", p).getAsJsonArray("messages").get(0).getAsJsonObject().getAsJsonObject("content").get("text").getAsString();
            assertTrue(text.length() < 8192); assertFalse(text.contains("\u2014"));
            assertTrue(text.contains("Never send BUILD ok just to"));
            assertTrue(text.contains("cannot prove that earlier edit")); assertTrue(text.contains("state failed"));
            assertTrue(text.indexOf("action checkpoint") < text.indexOf("state started"));
            assertTrue(text.indexOf("state started") < text.indexOf("state ok"));
            assertTrue(text.indexOf("action wait") < text.indexOf("5. Call reclazz_verify"));
            assertTrue(text.contains("application HTTP, UI or database check"));
            p.addProperty("_meta", "bad"); McpResourcesTest.error(s, McpResourcesTest.rpc("prompts/get", p), -32602);
            p.remove("_meta"); p.addProperty("extra", "bad"); McpResourcesTest.error(s, McpResourcesTest.rpc("prompts/get", p), -32602);
        }
    }

}
