/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.function.Consumer;

/** At most one batch worker. No queued mutations; the reader remains available for cancel/ping. */
final class BatchDispatcher implements AutoCloseable {
    private final McpServer server;
    private final Consumer<JsonObject> output;
    private Active active;
    private record Active(JsonElement id, RequestCancellation cancellation, Thread worker) { }

    BatchDispatcher(McpServer server, Consumer<JsonObject> output) { this.server = server; this.output = output; }

    synchronized void dispatch(JsonObject request) {
        boolean envelope = McpServer.isString(request.get("jsonrpc")) && "2.0".equals(request.get("jsonrpc").getAsString())
                && McpServer.isString(request.get("method"));
        String method = envelope ? request.get("method").getAsString() : "";
        if (envelope && !request.has("id") && "notifications/cancelled".equals(method)) {
            JsonElement params = request.get("params");
            if (params != null && params.isJsonObject()) {
                JsonObject p = params.getAsJsonObject();
                if (McpServer.validId(p.get("requestId")) && (!p.has("reason") || McpServer.isString(p.get("reason")))
                        && active != null && sameId(active.id(), p.get("requestId"))) active.cancellation().cancel();
            }
            return;
        }
        if (!envelope || !request.has("id") || !McpServer.validId(request.get("id"))) {
            emit(server.handle(request)); return;
        }
        if (active != null) {
            if (sameId(active.id(), request.get("id"))) { emit(McpServer.error(request, -32600, "Request ID is already active")); return; }
            if (!method.equals("ping") && !method.equals("tools/list")) {
                emit(McpServer.error(request, -32000, "Verification batch active; retry after completion or cancellation")); return;
            }
        }
        JsonElement params = request.get("params");
        boolean batch = method.equals("tools/call") && params != null && params.isJsonObject()
                && McpServer.isString(params.getAsJsonObject().get("name"))
                && "reclazz_verify_batch".equals(params.getAsJsonObject().get("name").getAsString());
        if (!batch) { emit(server.handle(request)); return; }
        RequestCancellation cancel = new RequestCancellation();
        Thread worker = new Thread(() -> {
            JsonObject response;
            try { response = server.handle(request, cancel); }
            catch (RuntimeException failed) { response = McpServer.error(request, -32603, "Batch processing failed"); }
            synchronized (BatchDispatcher.this) {
                if (!cancel.isCancelled()) emit(response);
                active = null;
                BatchDispatcher.this.notifyAll();
            }
        }, "reclazz-batch-verification");
        worker.setDaemon(true);
        active = new Active(request.get("id"), cancel, worker);
        worker.start();
    }

    private void emit(JsonObject response) { if (response != null) output.accept(response); }
    private static boolean sameId(JsonElement left, JsonElement right) {
        if (McpServer.isString(left) || McpServer.isString(right))
            return McpServer.isString(left) && McpServer.isString(right) && left.getAsString().equals(right.getAsString());
        return left.getAsBigDecimal().compareTo(right.getAsBigDecimal()) == 0;
    }

    @Override public void close() {
        Thread worker;
        synchronized (this) {
            if (active == null) return;
            active.cancellation().cancel(); worker = active.worker();
        }
        try { worker.join(2000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
