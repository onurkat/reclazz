/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "reclazz-mcp";

    /** Handle one JSON-RPC request. Returns the response, or null for a notification. */
    public JsonObject handle(JsonObject request) {
        String method = request.has("method") ? request.get("method").getAsString() : "";
        boolean isNotification = !request.has("id") || request.get("id").isJsonNull();

        if (method.startsWith("notifications/")) {
            return null;
        }
        if (isNotification) {
            return null;
        }

        switch (method) {
            case "initialize":
                return success(request, initialize(request));
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

    private JsonObject initialize(JsonObject request) {
        String requested = PROTOCOL_VERSION;
        if (request.has("params") && request.getAsJsonObject("params").has("protocolVersion")) {
            requested = request.getAsJsonObject("params").get("protocolVersion").getAsString();
        }
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", requested);
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
                        + "instead of waiting for its next poll. Use it right after a build.",
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
        JsonArray required = new JsonArray();
        if (needsClassName) {
            props.add("className", stringProp("Fully qualified class name."));
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
        p.addProperty("description", description);
        return p;
    }

    private JsonObject toolsCall(JsonObject request) {
        JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();

        Map<String, String> opts = new LinkedHashMap<>();
        opts.put("baseDir", System.getProperty("user.dir"));
        for (String key : List.of("portFile", "port", "hybrisHome", "timeoutMs", "className", "sha256")) {
            if (key.equals("sha256") && !name.equals("reclazz_verify")) continue;
            if (arguments.has(key) && !arguments.get(key).isJsonNull()) {
                if (name.equals("reclazz_verify") && (!arguments.get(key).isJsonPrimitive() || !arguments.getAsJsonPrimitive(key).isString())) {
                    return error(request, -32602, key + " must be a string");
                }
                opts.put(key, arguments.get(key).getAsString());
            }
        }

        String text;
        boolean isError = false;
        switch (name) {
            case "reclazz_verify": {
                if (!BuildSession.validVerification(opts.get("className"), opts.get("sha256"))) {
                    return error(request, -32602, "reclazz_verify requires className and a lower-case 64-digit sha256");
                }
                try (BuildSession session = BuildSession.open(opts)) {
                    JsonObject receipt = session.verify(opts.get("className"), opts.get("sha256"));
                    text = receipt.toString();
                    isError = !"applied".equals(receipt.get("status").getAsString());
                } catch (java.io.IOException | IllegalArgumentException e) {
                    isError = true;
                    JsonObject failure = new JsonObject();
                    failure.addProperty("status", "unavailable");
                    failure.addProperty("className", opts.get("className"));
                    failure.addProperty("expectedSha256", opts.get("sha256"));
                    failure.addProperty("detail", e.getMessage());
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
                break;
            }
            case "reclazz_status":
                text = statusJson(AgentSocket.run(opts, "HEALTH"));
                break;
            case "reclazz_scan": {
                AgentSocket.Result r = AgentSocket.run(opts, "SCAN");
                text = r.connected
                        ? "Requested a scan; the agent reloads any changed classes as usual."
                        : "Not attached: " + r.reason;
                break;
            }
            case "reclazz_pending": {
                AgentSocket.Result r = AgentSocket.run(opts, "PENDING");
                text = !r.connected ? "Not attached: " + r.reason
                        : r.lines.isEmpty() ? "Nothing pending a restart." : String.join("\n", r.lines);
                break;
            }
            case "reclazz_diagnose": {
                if (!opts.containsKey("className")) {
                    return error(request, -32602, "reclazz_diagnose requires className");
                }
                AgentSocket.Result r = AgentSocket.run(opts, "DIAGNOSE " + opts.get("className"));
                text = !r.connected ? "Not attached: " + r.reason
                        : r.lines.isEmpty() ? "No diagnosis for " + opts.get("className") : String.join("\n", r.lines);
                break;
            }
            default:
                return error(request, -32602, "Unknown tool: " + name);
        }

        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        JsonArray contents = new JsonArray();
        contents.add(content);
        JsonObject result = new JsonObject();
        result.add("content", contents);
        result.addProperty("isError", isError);
        return success(request, result);
    }

    private String statusJson(AgentSocket.Result r) {
        JsonObject o = new JsonObject();
        o.addProperty("attached", r.connected);
        if (r.connected) {
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
        return o.toString();
    }

    private JsonObject success(JsonObject request, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", request.get("id"));
        response.add("result", result);
        return response;
    }

    private JsonObject error(JsonObject request, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", request.has("id") ? request.get("id") : new JsonPrimitive(0));
        response.add("error", error);
        return response;
    }

    private String version() {
        String v = McpServer.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }
}
