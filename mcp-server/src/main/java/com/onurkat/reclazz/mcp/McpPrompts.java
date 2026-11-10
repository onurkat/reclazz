/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import java.util.Set;

/** User-selected instructions; retrieving them never executes the workflow. */
final class McpPrompts {
    private static final String NAME = "verify_my_last_change";
    private static final String DESCRIPTION = "Verify a controlled edit with Reclazz and an application check";
    private static final String TEXT = """
            Verify only the edit the user has authorized. This prompt grants no additional permissions.
            1. Call reclazz_doctor and confirm the intended JVM, session, project and required capabilities.
            2. Before a new edit, call reclazz_await_reload with action checkpoint. Require ready and retain
               sessionId, subscriptionId and cursor. If the last change was already saved, a new checkpoint
               cannot prove that earlier edit. Verify its exact class bytes and application behavior, or
               start a new authorized controlled edit cycle after a fresh checkpoint; state the limitation.
            3. For an external build, call reclazz_build with a unique owner and state started before
               publishing compiler output. Run the actual complete build. On failure send state failed;
               only after build success send state ok with the same owner. Never send BUILD ok just to
               clear a hold. Automatic agent compilation is a separate path; inspect its compile errors
               instead of fabricating an external BUILD lifecycle. Do not clear another owner's hold.
            4. Call reclazz_await_reload with action wait, the retained sessionId and subscriptionId,
               afterCursor set to the checkpoint cursor, exact binary className and expected sha256.
               Require applied. held, compile_error, refused, restart_required, timeout, session change
               and unavailable are not success. Do not automatically resume or restart the application.
               reclazz_events and event resources contain received observations, not byte receipts.
            5. Call reclazz_verify for the expected className and sha256 (or reclazz_verify_batch for
               several classes). Require applied receipts in the same JVM/session. Then run the concrete
               application HTTP, UI or database check that demonstrates the requested behavior.
               Events do not confirm source paths, source revisions or application behavior. Report
               failed or unrun checks explicitly; do not claim success from an acknowledgement alone.
            """;
    private McpPrompts() { }
    static JsonObject list(JsonObject request) {
        McpResources.parameters(request, Set.of());
        JsonObject prompt = new JsonObject(); prompt.addProperty("name", NAME); prompt.addProperty("description", DESCRIPTION);
        JsonArray prompts = new JsonArray(); prompts.add(prompt);
        JsonObject result = new JsonObject(); result.add("prompts", prompts); return result;
    }
    static JsonObject get(JsonObject request) {
        JsonObject p = McpResources.parameters(request, Set.of("name", "arguments"));
        if (!McpServer.isString(p.get("name")) || !NAME.equals(p.get("name").getAsString()))
            throw new IllegalArgumentException("Unknown prompt");
        if (p.has("arguments") && (!p.get("arguments").isJsonObject() || !p.getAsJsonObject("arguments").isEmpty()))
            throw new IllegalArgumentException("This prompt accepts no arguments");
        JsonObject text = new JsonObject(); text.addProperty("type", "text"); text.addProperty("text", TEXT);
        JsonObject message = new JsonObject(); message.addProperty("role", "user"); message.add("content", text);
        JsonArray messages = new JsonArray(); messages.add(message);
        JsonObject result = new JsonObject(); result.addProperty("description", DESCRIPTION); result.add("messages", messages); return result;
    }
}
