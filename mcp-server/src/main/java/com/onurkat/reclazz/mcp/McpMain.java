/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * The stdio entry point for the Reclazz MCP server. A coding agent launches it
 * as {@code java -jar reclazz-mcp.jar} and speaks JSON-RPC over stdin/stdout,
 * one message per line. Nothing but JSON-RPC responses is ever written to
 * stdout; anything else would corrupt the stream.
 */
public final class McpMain {
    private static final int MAX_FRAME_CHARS = 65536;

    private McpMain() { }

    public static void main(String[] args) throws IOException {
        McpServer server = new McpServer();
        Gson gson = new GsonBuilder().setStrictness(Strictness.STRICT).serializeNulls().create();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), false);

        java.util.function.Consumer<JsonObject> output = response -> {
            synchronized (out) {
                out.print(gson.toJson(response)); out.print('\n'); out.flush();
            }
        };
        try (BatchDispatcher dispatcher = new BatchDispatcher(server, output)) {
            while (true) {
                // Drain an oversized frame without retaining it, then resume at the next newline.
                StringBuilder line = new StringBuilder();
                boolean oversized = false;
                int c;
                while ((c = in.read()) != -1 && c != '\n') {
                    if (line.length() < MAX_FRAME_CHARS) line.append((char) c);
                    else oversized = true;
                }
                if (c == -1 && line.length() == 0) break;
                if (!oversized && line.toString().isBlank()) continue;
                JsonObject response;
                if (oversized) {
                    response = McpServer.error(null, -32600, "Request exceeds 65536 characters");
                } else {
                    try {
                        JsonElement request = gson.fromJson(line.toString(), JsonElement.class);
                        if (request != null && request.isJsonObject()) {
                            dispatcher.dispatch(request.getAsJsonObject());
                            continue;
                        }
                        response = McpServer.error(null, -32600, "Invalid Request");
                    } catch (JsonParseException malformed) {
                        response = McpServer.error(null, -32700, "Parse error");
                    }
                }
                if (response != null) output.accept(response);
            }
        }
    }
}
