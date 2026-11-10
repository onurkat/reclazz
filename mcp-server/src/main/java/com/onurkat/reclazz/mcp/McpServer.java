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
public final class McpServer implements AutoCloseable {

    private static final String LEGACY_PROTOCOL = "2024-11-05";
    private static final String STRUCTURED_PROTOCOL = "2025-06-18";
    private String protocolVersion = LEGACY_PROTOCOL;
    private static final String SERVER_NAME = "reclazz-mcp";
    private final McpTarget target;
    private final ObservationRegistry observations = new ObservationRegistry();
    private final AwaitReload awaitReload = new AwaitReload(observations);
    private final RecentEvents recentEvents = new RecentEvents(observations);
    private final ReadOnlyQueries queries = new ReadOnlyQueries(recentEvents);

    @Override public void close() { awaitReload.close(); }

    public McpServer() { this(McpTarget.parse(new String[0], Path.of(System.getProperty("user.dir")))); }
    McpServer(McpTarget target) { this.target = target; }

    /** Handle one JSON-RPC request. Returns the response, or null for a notification. */
    public JsonObject handle(JsonObject request) {
        return handle(request, new RequestCancellation());
    }

    JsonObject handle(JsonObject request, RequestCancellation cancellation) {
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
                return toolsCall(request, cancellation);
            case "resources/list", "resources/templates/list", "resources/read", "prompts/list", "prompts/get":
                return contextRequest(request, cancellation);
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
        capabilities.add("resources", new JsonObject());
        capabilities.add("prompts", new JsonObject());
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
        tools.add(tool("reclazz_doctor",
                "Read correlated JVM/session/watch and BUILD/VERIFY capability evidence. Compare the target before acting. "
                        + "A bounded directory sample and working directory do not prove output coverage, project root or reload success.", false));
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
        JsonObject control = tool("reclazz_reload_control",
                "Pause, resume or inspect automatic reload admission. Pending changes are retained; admitted work may finish. "
                        + "Resume never releases BUILD holds. Acknowledgement is not reload completion. Agent-wide manual switch, not client ownership.", false);
        JsonObject action = stringProp("pause, resume or status");
        JsonArray actions = new JsonArray();
        for (String value : List.of("pause", "resume", "status")) actions.add(value);
        action.add("enum", actions);
        control.getAsJsonObject("inputSchema").getAsJsonObject("properties").add("action", action);
        control.getAsJsonObject("inputSchema").getAsJsonArray("required").add("action");
        tools.add(control);
        JsonObject build = tool("reclazz_build",
                "Signal compilation state. Wait for acknowledged started BEFORE compiling. Send ok only "
                        + "after exit zero, failed otherwise. SCAN cannot release a failed build. "
                        + "Use a unique owner token and retain it for recovery; receipt is not reload completion.", false);
        JsonObject state = stringProp("started, ok or failed");
        JsonArray states = new JsonArray();
        for (String value : List.of("started", "ok", "failed")) states.add(value);
        state.add("enum", states);
        build.getAsJsonObject("inputSchema").getAsJsonObject("properties").add("state", state);
        build.getAsJsonObject("inputSchema").getAsJsonArray("required").add("state");
        JsonObject owner = stringProp("Unique builder token; retain across started/ok/failed and recovery. Not authentication.");
        owner.addProperty("maxLength", 64);
        owner.addProperty("pattern", "^[A-Za-z0-9_-]{1,64}$");
        build.getAsJsonObject("inputSchema").getAsJsonObject("properties").add("owner", owner);
        build.getAsJsonObject("inputSchema").getAsJsonArray("required").add("owner");
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
        JsonObject batch = tool("reclazz_verify_batch",
                "Observe 1–32 unique class/hash pairs on one agent connection with one total timeout. "
                        + "Only allApplied confirms individual applied receipts; never atomic reload or application behavior. "
                        + "Cancellable via notifications/cancelled in stdio; no automatic retry.", false);
        JsonObject entries = new JsonObject(); entries.addProperty("type", "array");
        entries.addProperty("minItems", 1); entries.addProperty("maxItems", BatchVerification.MAX_ITEMS);
        JsonObject pair = new JsonObject(); pair.addProperty("type", "object"); pair.addProperty("additionalProperties", false);
        JsonObject pairProps = new JsonObject();
        JsonObject className = stringProp("Unique binary Java class name"); className.addProperty("maxLength", 256);
        JsonObject hash = stringProp("Exact compiled bytes SHA-256"); hash.addProperty("pattern", "^[0-9a-f]{64}$"); hash.addProperty("maxLength", 64);
        pairProps.add("className", className); pairProps.add("sha256", hash); pair.add("properties", pairProps);
        JsonArray pairRequired = new JsonArray(); pairRequired.add("className"); pairRequired.add("sha256"); pair.add("required", pairRequired);
        entries.add("items", pair);
        batch.getAsJsonObject("inputSchema").getAsJsonObject("properties").add("items", entries);
        batch.getAsJsonObject("inputSchema").getAsJsonObject("properties").add("timeoutMs", stringProp("Total batch budget including connect/handshake/all receipts, 1–60000 ms; default 5000."));
        batch.getAsJsonObject("inputSchema").getAsJsonArray("required").add("items");
        tools.add(batch);
        JsonObject await = tool("reclazz_await_reload",
                "Checkpoint a persistent received-event stream before saving, then wait for exact-owner events and a same-session byte receipt. "
                        + "Source mode requires ownerClassName; basename correlation proves no full source path, source revision or application behavior. "
                        + "Bounded sapOutcomes describe agent messages only and never change Java await acceptance. "
                        + "Read-only; cancellation closes the subscription. Total timeout default 5000, max 60000 ms.", false);
        JsonObject ap = await.getAsJsonObject("inputSchema").getAsJsonObject("properties");
        for (String key : List.of("action", "sessionId", "subscriptionId", "afterCursor", "filePath", "className", "ownerClassName", "sha256"))
            ap.add(key, stringProp(switch (key) {
                case "action" -> "checkpoint before editing; wait with the returned session, subscription and cursor";
                case "filePath" -> "Absolute normalized .java path; only basename is present in agent compile events";
                case "ownerClassName" -> "Exact binary source owner; required for a successful source-mode observation";
                case "sha256" -> "Optional expected compiled-owner SHA-256; otherwise discover and reverify receipt bytes";
                default -> "Observation identity returned by checkpoint, or an exact binary class selector";
            }));
        JsonArray aa = new JsonArray(); aa.add("checkpoint"); aa.add("wait"); ap.getAsJsonObject("action").add("enum", aa);
        ap.getAsJsonObject("afterCursor").addProperty("pattern", "^(0|[1-9][0-9]{0,18})$");
        ap.getAsJsonObject("sha256").addProperty("pattern", "^[0-9a-f]{64}$");
        for (String key : List.of("className", "ownerClassName")) ap.getAsJsonObject(key).addProperty("maxLength", 256);
        for (String key : List.of("sessionId", "subscriptionId")) {
            ap.getAsJsonObject(key).addProperty("pattern", "^[A-Za-z0-9_-]{1,64}$");
            ap.getAsJsonObject(key).addProperty("maxLength", 64);
        }
        JsonObject as = await.getAsJsonObject("inputSchema"); as.addProperty("additionalProperties", false);
        as.getAsJsonArray("required").add("action");
        as.add("oneOf", com.google.gson.JsonParser.parseString("""
            [{"properties":{"action":{"const":"checkpoint"}},"not":{"anyOf":[
              {"required":["filePath"]},{"required":["className"]},{"required":["ownerClassName"]},
              {"required":["sha256"]},{"required":["sessionId"]},{"required":["subscriptionId"]},{"required":["afterCursor"]}]}},
             {"properties":{"action":{"const":"wait"}},"required":["sessionId","subscriptionId","afterCursor"],
              "oneOf":[{"required":["filePath"],"not":{"required":["className"]}},
                       {"required":["className"],"not":{"anyOf":[{"required":["filePath"]},{"required":["ownerClassName"]}]}}]}]
            """).getAsJsonArray());
        tools.add(await);
        JsonObject events = tool("reclazz_events",
                "List bounded received-event pages from an await_reload checkpoint. Reuse nextCursor as sinceCursor. "
                        + "Exact filters are AND on one event; event types confirm no reload, source revision or behavior. "
                        + "Typed SAP outcomes describe explicit agent messages, not correlated edits or native application proof. "
                        + "No implicit reconnect; cancellation closes the shared observation subscription.", false);
        JsonObject ep = events.getAsJsonObject("inputSchema").getAsJsonObject("properties");
        for (String key : List.of("sessionId", "subscriptionId", "sinceCursor", "limit", "level", "ownerClassName", "fileBasename"))
            ep.add(key, stringProp(switch (key) {
                case "sinceCursor" -> "Checkpoint cursor initially, then previous nextCursor; decimal sequence string";
                case "limit" -> "Maximum matching events, integer string 1..100, default 50";
                case "fileBasename" -> "Literal .java basename, not a path or a source attribution claim";
                default -> "Exact per-event filter or identity from await_reload checkpoint";
            }));
        for (String key : List.of("sessionId", "subscriptionId")) {
            ep.getAsJsonObject(key).addProperty("pattern", "^[A-Za-z0-9_-]{1,64}$");
            ep.getAsJsonObject(key).addProperty("maxLength", 64);
        }
        ep.getAsJsonObject("sinceCursor").addProperty("pattern", "^(0|[1-9][0-9]{0,18})$");
        ep.getAsJsonObject("limit").addProperty("pattern", "^([1-9]|[1-9][0-9]|100)$");
        ep.getAsJsonObject("ownerClassName").addProperty("maxLength", 256);
        ep.getAsJsonObject("fileBasename").addProperty("maxLength", 255);
        JsonArray levels = new JsonArray(); RecentEvents.LEVELS.stream().sorted().forEach(levels::add);
        ep.getAsJsonObject("level").add("enum", levels);
        JsonObject es = events.getAsJsonObject("inputSchema"); es.addProperty("additionalProperties", false);
        for (String key : List.of("sessionId", "subscriptionId", "sinceCursor")) es.getAsJsonArray("required").add(key);
        tools.add(events);

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
        props.add("projectDir", stringProp("Explicit project directory; requires matching target working-directory evidence. Relative to startup project/cwd."));
        props.add("portFile", stringProp("Path to the agent port file (optional; auto-located otherwise)."));
        props.add("port", stringProp("Connect directly to this agent status port (optional)."));
        props.add("hybrisHome", stringProp("SAP Commerce home, to find its port file (optional)."));
        props.add("timeoutMs", stringProp("Socket timeout in milliseconds, 1–60000; default "
                + (name.equals("reclazz_build") || name.equals("reclazz_verify") || name.equals("reclazz_doctor")
                || name.equals("reclazz_reload_control") || name.equals("reclazz_await_reload") || name.equals("reclazz_events") ? "5000." : "2000.")));
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

    private JsonObject toolsCall(JsonObject request, RequestCancellation cancellation) {
        JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
        if (!isString(params.get("name"))) return error(request, -32602, "name must be a string");
        String name = params.get("name").getAsString();
        if (params.has("arguments") && !params.get("arguments").isJsonObject()) {
            return error(request, -32602, "arguments must be an object");
        }
        JsonObject arguments = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();

        Map<String, String> opts = new LinkedHashMap<>();
        for (String key : List.of("projectDir", "portFile", "port", "hybrisHome", "timeoutMs", "className", "sha256", "state", "owner", "action", "sessionId", "subscriptionId", "afterCursor", "filePath", "ownerClassName", "sinceCursor", "limit", "level", "fileBasename")) {
            if (!arguments.has(key)) continue;
            if (!isString(arguments.get(key))) return error(request, -32602, key + " must be a string");
            String value = arguments.get(key).getAsString();
            if (value.length() > 4096 || value.codePoints().anyMatch(Character::isISOControl)) {
                return error(request, -32602, key + " exceeds 4096 characters or contains a control character");
            }
            if (key.equals("projectDir") || key.equals("portFile") || key.equals("hybrisHome")) {
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

        if (name.equals("reclazz_await_reload") && !AwaitReload.valid(arguments, opts))
            return error(request, -32602, "Invalid await_reload action, cursor, selector or arguments");

        if (name.equals("reclazz_events") && !RecentEvents.valid(arguments, opts))
            return error(request, -32602, "Invalid events identity, cursor, limit, filters or arguments");

        try { opts = target.options(opts); }
        catch (IllegalArgumentException invalid) { return error(request, -32602, invalid.getMessage()); }

        String text;
        JsonObject data;
        boolean isError = false;
        switch (name) {
            case "reclazz_events", "reclazz_status", "reclazz_pending": {
                ReadOnlyQueries.Result query = queries.run(name, opts, cancellation);
                data = query.data(); text = query.legacyText(); isError = query.isError();
                break;
            }
            case "reclazz_await_reload": {
                data = awaitReload.run(opts, cancellation);
                isError = !List.of("ready", "applied").contains(data.get("status").getAsString());
                text = data.toString();
                break;
            }
            case "reclazz_verify_batch": {
                if (!BatchVerification.valid(arguments.get("items")))
                    return error(request, -32602, "items must contain 1–32 unique className/lower-case sha256 pairs, with no extra fields");
                data = BatchVerification.run(arguments.getAsJsonArray("items"), opts, cancellation);
                isError = !data.get("allApplied").getAsBoolean();
                text = data.toString();
                break;
            }
            case "reclazz_doctor": {
                try (BuildSession session = BuildSession.open(opts)) {
                    data = session.doctor();
                } catch (java.io.IOException | IllegalArgumentException e) {
                    data = ToolContracts.operation("unavailable", "Doctor evidence unavailable; check the endpoint and matching agent version.");
                }
                isError = !"observed".equals(data.get("status").getAsString());
                data = DoctorResult.finish(data);
                text = data.toString();
                break;
            }
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
            case "reclazz_reload_control": {
                String action = opts.get("action");
                if (!BuildSession.validReloadAction(action))
                    return error(request, -32602, "reclazz_reload_control requires action: pause, resume or status");
                try (BuildSession session = BuildSession.open(opts)) {
                    data = session.reloadControl(action);
                } catch (java.io.IOException | IllegalArgumentException e) {
                    data = ToolContracts.operation("unavailable", e.getMessage());
                    data.addProperty("action", action);
                    isError = true;
                }
                text = data.toString();
                break;
            }
            case "reclazz_build": {
                if (!BuildSession.validOwner(opts.get("owner"))) {
                    return error(request, -32602, "reclazz_build requires owner: 1-64 ASCII letters, digits, _ or -");
                }
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
                data.addProperty("owner", opts.get("owner"));
                break;
            }
            case "reclazz_scan": {
                AgentSocket.Result r = AgentSocket.run(opts, "SCAN");
                isError = r.reason != null;
                text = isError ? "Scan request failed: " + r.reason
                        : "Sent SCAN to the agent. Acceptance and reload completion are not confirmed.";
                data = ToolContracts.operation(isError ? "unavailable" : "sent", text);
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

    private JsonObject contextRequest(JsonObject request, RequestCancellation cancellation) {
        try {
            JsonObject result;
            switch (request.get("method").getAsString()) {
                case "resources/list" -> result = McpResources.list(request, false);
                case "resources/templates/list" -> result = McpResources.list(request, true);
                case "prompts/list" -> result = McpPrompts.list(request);
                case "prompts/get" -> result = McpPrompts.get(request);
                case "resources/read" -> {
                    McpResources.Selection selected = McpResources.select(request);
                    JsonObject data = queries.run(selected.tool(), target.options(selected.options()), cancellation).data();
                    result = McpResources.contents(selected.uri(), data);
                }
                default -> throw new IllegalArgumentException("Unknown context method");
            }
            return success(request, result);
        } catch (McpResources.Failure missing) {
            return error(request, missing.code, missing.getMessage());
        } catch (IllegalArgumentException invalid) {
            return error(request, -32602, invalid.getMessage());
        } catch (RuntimeException failure) {
            return error(request, -32603, "Context request failed");
        }
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

    static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    static boolean validId(JsonElement value) {
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
