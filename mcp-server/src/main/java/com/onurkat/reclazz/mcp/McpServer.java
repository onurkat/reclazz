/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small Model Context Protocol server: it turns the running Reclazz agent's
 * status socket into tools a coding agent can call, so the agent can check
 * whether Reclazz is attached, nudge a scan after a build, ask what still needs
 * a restart, or diagnose why a class did not reload.
 *
 * <p>This class is the protocol core: {@link #handle(JsonObject)} takes one
 * JSON-RPC request and returns one response, or {@code null} for a notification.
 * {@link McpMain} wraps it in a stdio loop.
 */
public final class McpServer {

    private static final String LEGACY_PROTOCOL = "2024-11-05";
    private static final String STRUCTURED_PROTOCOL = "2025-06-18";
    private String protocolVersion = LEGACY_PROTOCOL;
    private static final String SERVER_NAME = "reclazz-mcp";

    /** Handle one JSON-RPC request. Returns the response, or null for a notification. */
    public JsonObject handle(JsonObject request) {
        if (!isString(request.get("jsonrpc")) || !"2.0".equals(request.get("jsonrpc").getAsString())
                || !isString(request.get("method")) || (request.has("id") && !validId(request.get("id")))) {
            return error(request, -32600, "Invalid Request");
        }
        // A notification has no ID, regardless of the method name or its parameters.
        if (!request.has("id")) return null;
        String method = request.get("method").getAsString();
        if (request.has("params") && !request.get("params").isJsonObject()) {
            return error(request, -32602, "params must be an object");
        }
        if (method.equals("initialize")) {
            JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
            if (!isString(params.get("protocolVersion")) || params.get("protocolVersion").getAsString().isBlank()) {
                return error(request, -32602, "initialize requires a nonblank string protocolVersion");
            }
        }

        switch (method) {
            case "initialize":
                return success(request, initialize(request.getAsJsonObject("params").get("protocolVersion").getAsString()));
            case "ping":
                return success(request, new JsonObject());
            case "tools/list":
                return success(request, toolsList());
            case "tools/call":
                return toolsCall(request);
            default:
                return error(request, -32601, "Method not found: " + method);
        }
    }

    private JsonObject initialize(String requestedVersion) {
        protocolVersion = LEGACY_PROTOCOL.equals(requestedVersion) ? LEGACY_PROTOCOL : STRUCTURED_PROTOCOL;
        JsonObject result = new JsonObject();
        // Honor either supported version; otherwise offer our newest implemented version.
        // The client decides whether it can continue with the returned version.
        result.addProperty("protocolVersion", protocolVersion);
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        result.add("capabilities", capabilities);
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", SERVER_NAME);
        serverInfo.addProperty("version", version());
        result.add("serverInfo", serverInfo);
        return result;
    }

    private JsonObject toolsList() {
        JsonArray tools = new JsonArray();
        tools.add(tool("reclazz_status",
                "Report whether the Reclazz hot-reload agent is attached to a running app and how it "
                        + "is doing (reloads, failures, latency, watched directories), as JSON.",
                false));
        tools.add(tool("reclazz_scan",
                "Ask the agent to look at the watched directories now and reload changed classes, "
                        + "instead of waiting for its next poll. Dispatch only, not acceptance or reload completion.",
                false));
        tools.add(tool("reclazz_pending",
                "List what still needs a restart in this session (changes the agent could not apply live).",
                false));
        tools.add(tool("reclazz_diagnose",
                "Explain why a specific class did or did not reload last time. Requires className.",
                true));
        JsonObject build = tool("reclazz_build",
                "Signal compilation state. Wait for acknowledged started BEFORE compiling. Send ok only "
                        + "after exit zero, failed otherwise. SCAN cannot release a failed build. "
                        + "One ordered builder per agent; receipt is not reload completion.", false);
        JsonObject state = stringProp("started, ok or failed");
        JsonArray states = new JsonArray();
        for (String value : List.of("started", "ok", "failed")) states.add(value);
        state.add("enum", states);
        build.getAsJsonObject("inputSchema").getAsJsonObject("properties").add("state", state);
        build.getAsJsonObject("inputSchema").getAsJsonArray("required").add("state");
        tools.add(build);
        JsonObject verify = tool("reclazz_verify",
                "Check completion for exact compiled class bytes. Requires className and lower-case SHA-256 sha256. "
                        + "Only status applied proves a completed reload attempt; other statuses are not success. "
                        + "Point-in-time receipt, not an application behavior test. Poll with a bounded deadline.", true);
        verify.getAsJsonObject("inputSchema").getAsJsonObject("properties")
                .add("sha256", stringProp("Lower-case SHA-256 of the compiled class file to verify."));
        verify.getAsJsonObject("inputSchema").getAsJsonArray("required").add("sha256");
        verify.getAsJsonObject("inputSchema").getAsJsonObject("properties")
                .add("timeoutMs", stringProp("Socket timeout in milliseconds, 1–60000; default 5000."));
        tools.add(verify);
        if (STRUCTURED_PROTOCOL.equals(protocolVersion)) {
            for (JsonElement tool : tools) ToolContracts.describe(tool.getAsJsonObject());
        }
        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    private JsonObject tool(String name, String description, boolean needsClassName) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", description);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("portFile", stringProp("Path to the agent port file (optional; auto-located otherwise)."));
        props.add("port", stringProp("Connect directly to this agent status port (optional)."));
        props.add("hybrisHome", stringProp("SAP Commerce home, to find its port file (optional)."));
        props.add("timeoutMs", stringProp("Socket timeout in milliseconds, 1–60000; default "
                + (name.equals("reclazz_build") || name.equals("reclazz_verify") ? "5000." : "2000.")));
        JsonArray required = new JsonArray();
        if (needsClassName) {
            JsonObject className = stringProp("Binary Java class name, at most 256 characters.");
            className.addProperty("maxLength", 256);
            props.add("className", className);
            required.add("className");
        }
        schema.add("properties", props);
        schema.add("required", required);
        t.add("inputSchema", schema);
        return t;
    }

    private JsonObject stringProp(String description) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "string");
        p.addProperty("maxLength", 4096);
        p.addProperty("description", description);
        return p;
    }

    private JsonObject toolsCall(JsonObject request) {
        JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
        if (!isString(params.get("name"))) return error(request, -32602, "name must be a string");
        String name = params.get("name").getAsString();
        if (params.has("arguments") && !params.get("arguments").isJsonObject()) {
            return error(request, -32602, "arguments must be an object");
        }
        JsonObject arguments = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();

        Map<String, String> opts = new LinkedHashMap<>();
        opts.put("baseDir", System.getProperty("user.dir"));
        for (String key : List.of("portFile", "port", "hybrisHome", "timeoutMs", "className", "sha256", "state")) {
            if (!arguments.has(key)) continue;
            if (!isString(arguments.get(key))) return error(request, -32602, key + " must be a string");
            String value = arguments.get(key).getAsString();
            if (value.length() > 4096 || value.codePoints().anyMatch(Character::isISOControl)) {
                return error(request, -32602, key + " exceeds 4096 characters or contains a control character");
            }
            if (key.equals("portFile") || key.equals("hybrisHome")) {
                try { Path.of(value); }
                catch (InvalidPathException invalid) { return error(request, -32602, key + " is not a valid path"); }
            }
            opts.put(key, value);
        }
        if (opts.containsKey("timeoutMs")) {
            try {
                int timeout = Integer.parseInt(opts.get("timeoutMs"));
                if (timeout < 1 || timeout > 60000) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                return error(request, -32602, "timeoutMs must be an integer string between 1 and 60000");
            }
        }

        String text;
        JsonObject data;
        boolean isError = false;
        switch (name) {
            case "reclazz_verify": {
                if (!BuildSession.validVerification(opts.get("className"), opts.get("sha256"))) {
                    return error(request, -32602, "reclazz_verify requires className and a lower-case 64-digit sha256");
                }
                try (BuildSession session = BuildSession.open(opts)) {
                    JsonObject receipt = session.verify(opts.get("className"), opts.get("sha256"));
                    data = receipt;
                    text = receipt.toString();
                    isError = !"applied".equals(receipt.get("status").getAsString());
                } catch (java.io.IOException | IllegalArgumentException e) {
                    isError = true;
                    JsonObject failure = new JsonObject();
                    failure.addProperty("status", "unavailable");
                    failure.addProperty("className", opts.get("className"));
                    failure.addProperty("expectedSha256", opts.get("sha256"));
                    failure.addProperty("detail", e.getMessage());
                    data = failure;
                    text = failure.toString();
                }
                break;
            }
            case "reclazz_build": {
                if (!arguments.has("state") || !arguments.get("state").isJsonPrimitive()
                        || !arguments.getAsJsonPrimitive("state").isString()
                        || !BuildSession.validState(arguments.get("state").getAsString())) {
                    return error(request, -32602, "reclazz_build requires state: started, ok or failed");
                }
                String state = arguments.get("state").getAsString();
                try (BuildSession session = BuildSession.open(opts)) {
                    session.signal(state);
                    text = "Acknowledged BUILD " + state + ". This is not reload completion.";
                } catch (java.io.IOException | IllegalArgumentException e) {
                    isError = true;
                    text = "Build signal not confirmed: " + e.getMessage();
                }
                data = ToolContracts.operation(isError ? "unavailable" : "acknowledged", text);
                data.addProperty("state", state);
                break;
            }
            case "reclazz_status":
                data = statusJson(AgentSocket.run(opts, "HEALTH"));
                text = data.toString();
                break;
            case "reclazz_scan": {
                AgentSocket.Result r = AgentSocket.run(opts, "SCAN");
                isError = r.reason != null;
                text = isError ? "Scan request failed: " + r.reason
                        : "Sent SCAN to the agent. Acceptance and reload completion are not confirmed.";
                data = ToolContracts.operation(isError ? "unavailable" : "sent", text);
                break;
            }
            case "reclazz_pending": {
                AgentSocket.Result r = AgentSocket.run(opts, "PENDING");
                isError = r.reason != null;
                text = isError ? "Pending query failed: " + r.reason : String.join("\n", r.lines);
                data = ToolContracts.diagnostic(r.lines, text, isError);
                break;
            }
            case "reclazz_diagnose": {
                if (!BuildSession.validClassName(opts.get("className"))) {
                    return error(request, -32602, "reclazz_diagnose requires a valid binary className of at most 256 characters");
                }
                AgentSocket.Result r = AgentSocket.run(opts, "DIAGNOSE " + opts.get("className"));
                isError = r.reason != null;
                text = isError ? "Diagnosis query failed: " + r.reason : String.join("\n", r.lines);
                data = ToolContracts.diagnostic(r.lines, text, isError);
                data.addProperty("className", opts.get("className"));
                break;
            }
            default:
                return error(request, -32602, "Unknown tool: " + name);
        }

        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        boolean structured = STRUCTURED_PROTOCOL.equals(protocolVersion);
        content.addProperty("text", structured ? data.toString() : text);
        JsonArray contents = new JsonArray();
        contents.add(content);
        JsonObject result = new JsonObject();
        result.add("content", contents);
        result.addProperty("isError", isError);
        if (structured) result.add("structuredContent", data);
        return success(request, result);
    }

    private JsonObject statusJson(AgentSocket.Result r) {
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

    private JsonObject success(JsonObject request, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", request.get("id"));
        response.add("result", result);
        return response;
    }

    static JsonObject error(JsonObject request, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", request != null && validId(request.get("id")) ? request.get("id") : JsonNull.INSTANCE);
        response.add("error", error);
        return response;
    }

    private static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static boolean validId(JsonElement value) {
        if (isString(value)) return true;
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
        try {
            // Inspect the scale without expanding exponents into a huge integer.
            return value.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException | ArithmeticException invalid) {
            return false;
        }
    }

    private String version() {
        String v = McpServer.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }
}
