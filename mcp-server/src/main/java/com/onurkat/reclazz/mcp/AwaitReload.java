/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Received-event correlation followed by an exact-byte receipt, never source-revision proof. */
final class AwaitReload implements AutoCloseable {
    private final LinkedHashMap<String, Entry> subscriptions = new LinkedHashMap<>(4, .75f, true);
    private final Set<RequestCancellation> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private static final class Entry {
        final EventSubscription stream;
        boolean busy;
        Entry(EventSubscription stream) { this.stream = stream; }
    }
    record Selection(String status, long sequence, String detail) { }

    static boolean valid(JsonObject args, Map<String, String> opts) {
        Set<String> allowed = Set.of("projectDir", "portFile", "port", "hybrisHome", "timeoutMs", "action",
                "sessionId", "subscriptionId", "afterCursor", "filePath", "className", "ownerClassName", "sha256");
        if (!allowed.containsAll(args.keySet())) return false;
        String action = opts.get("action");
        if ("checkpoint".equals(action)) return Collections.disjoint(args.keySet(),
                Set.of("sessionId", "subscriptionId", "afterCursor", "filePath", "className", "ownerClassName", "sha256"));
        if (!"wait".equals(action) || !token(opts.get("sessionId")) || !token(opts.get("subscriptionId"))) return false;
        String cursor = opts.get("afterCursor");
        if (cursor == null || !cursor.matches("0|[1-9][0-9]{0,18}")) return false;
        try { Long.parseLong(cursor); } catch (NumberFormatException invalid) { return false; }
        if (opts.containsKey("filePath") == opts.containsKey("className")) return false;
        if (opts.containsKey("filePath")) {
            try { Path p = Path.of(opts.get("filePath")); if (!p.isAbsolute() || !p.toString().endsWith(".java") || !p.normalize().equals(p)) return false; }
            catch (RuntimeException invalid) { return false; }
            if (opts.containsKey("ownerClassName") && !BuildSession.validClassName(opts.get("ownerClassName"))) return false;
        } else if (!BuildSession.validClassName(opts.get("className")) || opts.containsKey("ownerClassName")) return false;
        return !opts.containsKey("sha256") || opts.get("sha256").matches("[0-9a-f]{64}");
    }
    private static boolean token(String s) { return s != null && s.matches("[A-Za-z0-9_-]{1,64}"); }

    JsonObject run(Map<String, String> opts, RequestCancellation cancel) {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Integer.parseInt(opts.getOrDefault("timeoutMs", "5000")));
        JsonObject out = base(opts);
        Entry entry = null;
        active.add(cancel);
        try {
            if (closed) throw new EventSubscription.Failure("session_changed", "MCP observation registry is closed");
            entry = acquire(opts, cancel, until);
            EventSubscription stream = entry.stream;
            cancel.attach(stream.socket);
            stream.doctor(opts, until);
            out.addProperty("sessionId", stream.sessionId()); out.addProperty("subscriptionId", stream.id);
            if (opts.get("action").equals("checkpoint")) {
                out.addProperty("cursor", Long.toString(stream.cursor()));
                return outcome(out, "ready", "checkpoint", "Received-event checkpoint; save only after this response");
            }
            if (!stream.id.equals(opts.get("subscriptionId")) || !stream.sessionId().equals(opts.get("sessionId")))
                throw new EventSubscription.Failure("session_changed", "Cursor belongs to a different observation subscription or JVM");
            long after = Long.parseLong(opts.get("afterCursor"));
            String owner = opts.getOrDefault("className", opts.get("ownerClassName"));
            if (owner == null) throw new EventSubscription.Failure("owner_required", "Source mode requires ownerClassName; basenames cannot identify a binary owner");
            out.addProperty("ownerClassName", owner);
            String file = opts.containsKey("filePath") ? Path.of(opts.get("filePath")).getFileName().toString() : null;
            while (System.nanoTime() < until) {
                if (cancel.isCancelled()) throw new EventSubscription.Failure("cancelled", "Observation cancelled");
                List<EventSubscription.Event> events = stream.since(after);
                out.addProperty("cursor", Long.toString(stream.cursor()));
                Selection selected = select(events, file, owner);
                if (selected != null) {
                    diagnostics(out, events, selected);
                    if (selected.status().equals("compile_error") || selected.status().equals("unavailable"))
                        return outcome(out, selected.status(), selected.status().equals("compile_error") ? "compiler_diagnostic"
                                : selected.detail().startsWith("Multiple same-basename") ? "ambiguous_source" : "framework_failure", selected.detail());
                    JsonObject receipt = receipt(stream, owner, opts.get("sha256"), until);
                    Selection latest = select(stream.since(after), file, owner);
                    if (!Objects.equals(latest, selected)) continue;
                    out.addProperty("cursor", Long.toString(stream.cursor()));
                    String state = receipt.get("status").getAsString();
                    if (state.equals("running")) { stream.awaitChange(stream.cursor(), until); continue; }
                    if (selected.status().equals("refused") && state.equals("failed")) {
                        out.add("receipt", receipt); return outcome(out, "refused", "reload_failed", selected.detail());
                    }
                    if (selected.status().equals("restart_required") && !state.equals("applied") && !state.equals("mismatch") && !state.equals("not_observed")) {
                        out.add("receipt", receipt); return outcome(out, "restart_required", "hierarchy_not_applied", selected.detail());
                    }
                    if (selected.status().equals("reload") && state.equals("applied")) {
                        out.add("receipt", receipt);
                        out.addProperty("observedSha256", receipt.get("observedSha256").getAsString());
                        out.addProperty("hashSource", opts.containsKey("sha256") ? "caller" : "receipt");
                        return outcome(out, "applied", "owner_receipt", "Exact owner event and completed byte receipt observed; source revision and application behavior are unconfirmed");
                    }
                    if (selected.status().equals("reload") && state.equals("not_observed")) {
                        stream.awaitChange(stream.cursor(), until); continue;
                    }
                    out.add("receipt", receipt);
                    return outcome(out, "unavailable", "receipt_" + state, "Observed event has no compatible completed exact-byte receipt");
                }
                JsonObject admission = stream.state(until);
                // A terminal event may have arrived while the state command was in flight.
                if (select(stream.since(after), file, owner) != null) continue;
                if (admission.get("paused").getAsBoolean() || !admission.get("buildHold").getAsString().equals("none")) {
                    out.add("admission", admission);
                    return outcome(out, "held", "admission_blocked", "Agent-wide admission state only; target observation/pending is unconfirmed and already admitted work may finish");
                }
                long head = stream.cursor();
                if (System.nanoTime() >= until) break;
                stream.awaitChange(head, until);
            }
            return outcome(out, "timeout", "no_matching_outcome", "No qualifying received-event and receipt evidence before the total deadline");
        } catch (IOException | RuntimeException failure) {
            String code = failure instanceof EventSubscription.Failure f ? f.code : "observation_unavailable";
            if (entry != null && Set.of("session_changed", "malformed_response", "response_timeout", "target_mismatch").contains(code))
                entry.stream.close();
            if (opts.get("action").equals("wait") && code.equals("deadline") && entry != null && entry.stream.alive())
                return outcome(out, "timeout", "deadline", "Total observation deadline exceeded; no reload completion confirmed");
            return outcome(out, "unavailable", code, failure.getMessage());
        } finally {
            active.remove(cancel);
            if (entry != null) {
                cancel.detach(entry.stream.socket);
                synchronized (this) { entry.busy = false; }
            }
        }
    }

    private Entry acquire(Map<String, String> opts, RequestCancellation cancel, long until) throws IOException {
        AgentSocket.Result address = new AgentSocket.Result(); AgentSocket.resolvePort(opts, address);
        if (address.reason != null) throw new EventSubscription.Failure("target_unavailable", "Cannot resolve the selected agent endpoint");
        String key = address.port + "\n" + opts.getOrDefault("baseDir", "") + "\n" + opts.getOrDefault("projectDir", "");
        synchronized (this) {
            Entry e = subscriptions.get(key);
            if (e != null && e.stream.alive()) {
                if (e.busy) throw new EventSubscription.Failure("busy", "Observation subscription already has an active request");
                e.busy = true; return e;
            }
            if (!opts.get("action").equals("checkpoint"))
                throw new EventSubscription.Failure("session_changed", "No live subscription for this cursor; checkpoint again");
            if (e != null) { e.stream.close(); subscriptions.remove(key); }
            if (subscriptions.size() >= 2) {
                var idle = subscriptions.entrySet().stream().filter(x -> !x.getValue().busy).findFirst();
                if (idle.isEmpty()) throw new EventSubscription.Failure("busy", "All bounded observation subscriptions are active");
                idle.get().getValue().stream.close(); subscriptions.remove(idle.get().getKey());
            }
        }
        EventSubscription created = new EventSubscription(opts, address.port, cancel, until);
        synchronized (this) {
            if (closed || subscriptions.containsKey(key) || subscriptions.size() >= 2) {
                created.close(); throw new EventSubscription.Failure("busy", "Registry changed while opening the subscription");
            }
            Entry e = new Entry(created); e.busy = true; subscriptions.put(key, e); return e;
        }
    }

    private static JsonObject receipt(EventSubscription stream, String owner, String expected, long until) throws IOException {
        if (expected != null) return stream.verify(owner, expected, until);
        JsonObject discovered = stream.verify(owner, "0".repeat(64), until);
        String hash = EventSubscription.string(discovered, "observedSha256");
        if (!hash.matches("[0-9a-f]{64}")) return discovered;
        return stream.verify(owner, hash, until);
    }

    static Selection select(List<EventSubscription.Event> events, String file, String owner) {
        Selection selected = null;
        boolean compilationErrors = false;
        long compiled = 0;
        int pendingCompiles = 0;
        for (EventSubscription.Event e : events) {
            String message = e.message();
            if (file != null && e.level().equals("COMPILE") && compile(message, file)) {
                compiled = e.sequence(); pendingCompiles++; selected = null; compilationErrors = false;
            }
            if (e.level().equals("ERROR") && message.equals("Compilation failed:")) compilationErrors = true;
            else if (compilationErrors && e.level().equals("ERROR") && file != null
                    && Pattern.compile("^  " + Pattern.quote(file) + ":[0-9]+: .+", Pattern.DOTALL).matcher(message).matches())
                selected = new Selection("compile_error", e.sequence(), message.strip());
            else if (!e.level().equals("ERROR")) compilationErrors = false;
            if (file != null && compiled == 0) continue;
            if (e.level().equals("ERROR") && message.startsWith("Hot-swap failed for " + owner + ":"))
                selected = new Selection("refused", e.sequence(), message);
            else if (e.level().equals("ERROR") && message.startsWith("Failed to refresh Spring bean for " + owner + ":"))
                selected = new Selection("unavailable", e.sequence(), message);
            else if (e.level().equals("WARN") && message.startsWith(owner + ": superclass change not applied; restart required. Still extends "))
                selected = new Selection("restart_required", e.sequence(), message);
            else if (reload(e.level(), message, owner)) {
                if (pendingCompiles > 1) selected = new Selection("unavailable", e.sequence(), "Multiple same-basename compile observations cannot be attributed to one source");
                else if (selected == null || !Set.of("refused", "restart_required", "unavailable", "compile_error").contains(selected.status()))
                    selected = new Selection("reload", e.sequence(), message);
                pendingCompiles = 0;
            }
        }
        return selected;
    }
    static boolean compile(String message, String file) {
        return message.equals("Compiled " + file) || message.startsWith("Compiled " + file + " (");
    }
    static boolean reload(String level, String message, String owner) {
        String prefix = switch (level) { case "RELOAD" -> "Reloaded "; case "STRUCTURAL_RELOAD" -> "Structural reload: "; default -> null; };
        if (prefix == null || !message.startsWith(prefix)) return false;
        String tail = message.substring(prefix.length());
        int end = 0; while (end < tail.length() && tail.charAt(end) != ' ' && tail.charAt(end) != ':') end++;
        return tail.substring(0, end).equals(owner) && !tail.substring(end).stripLeading().startsWith("(inner class)");
    }
    private static JsonObject base(Map<String, String> opts) {
        JsonObject r = new JsonObject();
        r.addProperty("action", opts.get("action"));
        r.addProperty("sessionId", ""); r.addProperty("subscriptionId", ""); r.addProperty("cursor", opts.getOrDefault("afterCursor", "0"));
        r.addProperty("correlationScope", "received_events");
        r.addProperty("sourcePathConfirmed", false); r.addProperty("editRevisionConfirmed", false);
        r.add("diagnostics", new JsonArray()); r.addProperty("diagnosticsTruncated", false);
        for (String k : List.of("filePath", "className", "ownerClassName", "sha256")) if (opts.containsKey(k)) r.addProperty(k, opts.get(k));
        return r;
    }
    private static JsonObject outcome(JsonObject out, String status, String code, String detail) {
        out.addProperty("status", status); out.addProperty("reasonCode", code);
        out.addProperty("detail", detail == null ? "Observation unavailable" : detail.substring(0, Math.min(512, detail.length())));
        out.addProperty("reloadConfirmed", status.equals("applied"));
        return out;
    }
    private static void diagnostics(JsonObject out, List<EventSubscription.Event> events, Selection selection) {
        List<String> lines = events.stream().filter(e -> e.sequence() <= selection.sequence()
                && Set.of("ERROR", "WARN", "COMPILE", "RELOAD", "STRUCTURAL_RELOAD").contains(e.level())).map(EventSubscription.Event::message).toList();
        JsonArray a = new JsonArray(); boolean truncated = lines.size() > 16;
        for (String line : lines.subList(Math.max(0, lines.size() - 16), lines.size())) {
            a.add(line.substring(0, Math.min(512, line.length()))); truncated |= line.length() > 512;
        }
        out.add("diagnostics", a); out.addProperty("diagnosticsTruncated", truncated);
    }
    @Override public void close() {
        closed = true; active.forEach(RequestCancellation::cancel);
        synchronized (this) { subscriptions.values().forEach(e -> e.stream.close()); subscriptions.clear(); }
    }
}
