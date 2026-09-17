/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

    private McpMain() { }

    public static void main(String[] args) throws IOException {
        McpServer server = new McpServer();
        Gson gson = new Gson();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), false);

        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            JsonObject request;
            try {
                request = JsonParser.parseString(line).getAsJsonObject();
            } catch (RuntimeException malformed) {
                // A line that is not a JSON-RPC object is ignored rather than
                // answered, so a stray write never becomes a bogus response.
                continue;
            }
            JsonObject response = server.handle(request);
            if (response != null) {
                out.print(gson.toJson(response));
                out.print('\n');
                out.flush();
            }
        }
    }
}
