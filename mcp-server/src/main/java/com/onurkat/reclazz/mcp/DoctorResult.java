/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import java.io.IOException;
import java.util.Set;

/** Validate evidence before exposing it as a successful tool result. */
final class DoctorResult {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private DoctorResult() { }

    static JsonObject parse(String json, String token) throws IOException {
        JsonObject data = JSON.fromJson(json, JsonObject.class);
        if (data == null || !token.equals(string(data, "requestId"))) throw new IOException("Uncorrelated doctor result");
        String status = string(data, "status");
        string(data, "detail");
        if (status.equals("unavailable")) {
            JsonObject unavailable = new JsonObject();
            for (String name : Set.of("requestId", "status", "detail")) unavailable.addProperty(name, string(data, name));
            return unavailable;
        }
        if (!status.equals("observed")) throw new IOException("Unknown doctor status");
        for (String name : Set.of("sessionId", "agentVersion", "pid", "javaVersion", "vmName", "workingDirectory")) string(data, name);
        if (!string(data,"pid").matches("[1-9][0-9]*")) throw new IOException("Invalid JVM identity");
        if (!Set.of("starting", "watching", "stopped", "unavailable").contains(string(data, "watcherState"))
                || !Set.of("none", "named", "legacy", "unavailable").contains(string(data, "buildHold")))
            throw new IOException("Invalid lifecycle evidence");
        for (String name : Set.of("watchSampleTruncated", "buildOwnershipSupported", "verifySupported", "scanSupported", "reloadConfirmed")) bool(data,name);
        if (bool(data,"reloadConfirmed") || (bool(data,"verifySupported") && string(data,"sessionId").isBlank()))
            throw new IOException("Invalid completion or session evidence");
        int count = count(data,"watchedDirectoryCount"); count(data,"unwatchableDirectoryCount");
        JsonElement paths = data.get("watchedDirectories");
        if (paths == null || !paths.isJsonArray() || paths.getAsJsonArray().size() > 8) throw new IOException("Invalid directory sample");
        for (JsonElement path : paths.getAsJsonArray())
            if (!path.isJsonPrimitive() || !path.getAsJsonPrimitive().isString() || path.getAsString().isBlank()
                    || path.getAsString().length() > 256) throw new IOException("Invalid directory evidence");
        int sample = paths.getAsJsonArray().size();
        if (count < sample || (count > sample) != bool(data,"watchSampleTruncated")) throw new IOException("Inconsistent directory sample");
        return data;
    }

    static JsonObject finish(JsonObject data) {
        data.addProperty("clientWorkingDirectory", System.getProperty("user.dir", ""));
        data.addProperty("reloadConfirmed", false);
        JsonArray actions = new JsonArray();
        if (!"observed".equals(data.get("status").getAsString())) {
            actions.add("Check the port/port file and install matching agent and MCP builds; capability support is unknown.");
        } else {
            actions.add("Compare target PID, workingDirectory and sessionId with the intended application; workingDirectory is not a repository-root assertion.");
            if (!"watching".equals(data.get("watcherState").getAsString())) actions.add("Wait for watcher startup or inspect agent startup/stop diagnostics.");
            if (data.get("watchedDirectoryCount").getAsInt() == 0 || data.get("unwatchableDirectoryCount").getAsInt() > 0)
                actions.add("Check compiled output directories and refused watches before building.");
            if (data.get("watchSampleTruncated").getAsBoolean()) actions.add("Directory sample is incomplete; do not infer coverage of an absent output path.");
            if (!data.get("buildOwnershipSupported").getAsBoolean() || !data.get("verifySupported").getAsBoolean())
                actions.add("Wait for initialization or use a matching agent with BUILD ownership and VERIFY support.");
            if (!"none".equals(data.get("buildHold").getAsString())) actions.add("Inspect the existing build hold; retain the original owner for recovery. Do not force-release it.");
            actions.add("Use exact-byte VERIFY and application checks after a successful owned build; this observation proves no reload.");
        }
        data.add("nextActions", actions);
        return data;
    }

    private static String string(JsonObject data, String name) throws IOException {
        JsonElement e=data.get(name);
        if(e==null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().length()>512)
            throw new IOException("Invalid doctor field: " + name);
        return e.getAsString();
    }
    private static boolean bool(JsonObject data,String name) throws IOException {
        JsonElement e=data.get(name);
        if(e==null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new IOException("Invalid doctor boolean: " + name);
        return e.getAsBoolean();
    }
    private static int count(JsonObject data,String name) throws IOException {
        JsonElement e=data.get(name);
        if(e==null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || !e.getAsString().matches("[0-9]{1,9}"))
            throw new IOException("Invalid doctor count: " + name);
        return e.getAsInt();
    }
}
