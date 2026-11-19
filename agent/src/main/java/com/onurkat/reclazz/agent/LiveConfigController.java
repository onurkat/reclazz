/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.config.AgentConfig;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Serial CONFIG transactions; GET observes a complete published revision without queuing. */
final class LiveConfigController implements AutoCloseable {
    @FunctionalInterface interface Transition {
        CompletionStage<Void> apply(String key, AgentConfig.LiveOptions target, Runnable publish);
    }
    private record Published(long revision, AgentConfig.LiveOptions options) { }
    private final AgentConfig config;
    private final boolean sap;
    private final Transition transition;
    private final long acknowledgementMillis;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "Reclazz-LiveConfig"); thread.setDaemon(true); return thread;
    });
    private final AtomicLong serial = new AtomicLong();
    private volatile Published published;

    LiveConfigController(AgentConfig config, boolean sap, Transition transition) { this(config, sap, transition, 10000); }
    LiveConfigController(AgentConfig config, boolean sap, Transition transition, long acknowledgementMillis) {
        this.config = Objects.requireNonNull(config); this.sap = sap; this.transition = Objects.requireNonNull(transition);
        this.acknowledgementMillis = acknowledgementMillis;
        published = new Published(0, config.liveOptions());
    }
    String reply(String command) { return reply(command, acknowledgementMillis); }
    String reply(String command, java.util.function.Consumer<String> pendingAndTerminal) {
        return reply(command, acknowledgementMillis, pendingAndTerminal);
    }
    String reply(String command, long timeoutMillis) { return reply(command, timeoutMillis, null); }
    private String reply(String command, long timeoutMillis, java.util.function.Consumer<String> pendingAndTerminal) {
        String[] parts = command.strip().split("\\s+");
        if (parts.length < 3 || !parts[2].matches("[A-Za-z0-9_-]{1,64}")) return null;
        String id = parts[2];
        if (parts[1].equals("GET")) return parts.length == 3 ? state(id, "OK") : error(id, "bad_request", "GET takes only an id");
        if (!parts[1].equals("SET")) return error(id, "bad_request", "Expected GET or SET");
        var answer = new CompletableFuture<String>();
        try { executor.execute(() -> {
            try { answer.complete(set(id, parts)); }
            catch (Throwable failure) { answer.complete(error(id, "failed", detail(failure))); }
        }); } catch (RejectedExecutionException stopped) { return error(id, "unavailable", "Agent settings are stopping"); }
        try { return answer.get(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (TimeoutException pending) {
            String message = "CONFIG " + id + " PENDING rev=" + published.revision;
            if (pendingAndTerminal == null) return message;
            // Publish PENDING first, then retain the same correlation for its
            // terminal OK/CONFLICT/ERROR. GET alone cannot attribute a revision
            // advanced by an earlier queued transaction to this request.
            pendingAndTerminal.accept(message);
            answer.thenAccept(pendingAndTerminal);
            return null;
        }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return "CONFIG " + id + " PENDING rev=" + published.revision; }
        catch (ExecutionException failure) { return error(id, "failed", detail(failure)); }
    }
    private String set(String id, String[] parts) {
        if (parts.length < 5 || parts.length > 11 || !parts[3].matches("base=[0-9]+")) return error(id, "bad_request", "SET needs base and one to six options");
        long base;
        try { base = Long.parseLong(parts[3].substring(5)); } catch (NumberFormatException overflow) { return error(id, "bad_request", "Invalid revision"); }
        Map<String, Boolean> changes = new LinkedHashMap<>();
        for (int i = 4; i < parts.length; i++) {
            String[] pair = parts[i].split("=", -1);
            if (pair.length != 2 || changes.containsKey(pair[0])) return error(id, "bad_request", "Duplicate or malformed option");
            if (pair[0].equals("watchExtensions")) return error(id, "restart_required", "Watch extensions requires a restart");
            if (!AgentConfig.LiveOptions.KEYS.contains(pair[0])) return error(id, "unknown_key", "Unknown option");
            if (!pair[1].equals("true") && !pair[1].equals("false")) return error(id, "bad_request", "Options must be true or false");
            if (!sap && !pair[0].equals("autoCompile")) return error(id, "unavailable", "SAP option is unavailable on this platform");
            changes.put(pair[0], Boolean.valueOf(pair[1]));
        }
        Published previous = published;
        if (base != previous.revision) return state(id, "CONFLICT");
        var target = previous.options.with(changes);
        Runnable publish = () -> {
            config.publishLiveOptions(target);
            published = new Published(previous.revision + 1, target);
        };
        if (target.autoCompile() != previous.options.autoCompile()) {
            try { transition.apply("config-" + serial.incrementAndGet(), target, publish).toCompletableFuture().get(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CompletionException(interrupted); }
            catch (ExecutionException failure) { throw new CompletionException(failure.getCause()); }
        } else publish.run();
        return state(id, "OK");
    }
    private String state(String id, String status) {
        Published value = published;
        var fields = new StringBuilder("CONFIG ").append(id).append(' ').append(status).append(" rev=").append(value.revision);
        value.options.values().forEach((key, enabled) -> fields.append(' ').append(key).append('=').append(enabled));
        fields.append(" live=").append(sap ? String.join(",", AgentConfig.LiveOptions.KEYS) : "autoCompile");
        fields.append(" watchExtensions.count=").append(watchNames().size());
        fields.append(" watchExtensions.sha256=").append(watchHash());
        return fields.toString();
    }
    private List<String> watchNames() { return config.isWatchAllExtensions() ? List.of("*") : config.getWatchExtensions().stream().filter(name -> !name.isBlank()).sorted().toList(); }
    private String watchHash() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join(";", watchNames()).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    String doctorOptions() {
        Published value = published;
        var fields = new StringBuilder("{\"rev\":").append(value.revision);
        value.options.values().forEach((key, enabled) -> fields.append(",\"").append(key).append("\":").append(enabled));
        List<String> names = watchNames();
        fields.append(",\"watchExtensions\":{\"count\":").append(names.size()).append(",\"sha256\":\"").append(watchHash()).append("\",\"sample\":[");
        for (int i = 0; i < Math.min(5, names.size()); i++) {
            if (i > 0) fields.append(','); String name = names.get(i); if (name.length() > 40) name = name.substring(0, 40);
            fields.append('"').append(StatusServer.escapeJson(name)).append('"');
        }
        return fields.append("],\"truncated\":").append(names.size() > 5 || names.stream().anyMatch(name -> name.length() > 40)).append("}}").toString();
    }
    private static String detail(Throwable failure) {
        while (failure.getCause() != null && (failure instanceof CompletionException || failure instanceof ExecutionException)) failure = failure.getCause();
        String text = String.valueOf(failure.getMessage()).replace('\n', ' ').replace('\r', ' ');
        return text.substring(0, Math.min(200, text.length()));
    }
    private static String error(String id, String code, String reason) { return "CONFIG " + id + " ERROR " + code + " " + reason; }
    @Override public void close() { executor.shutdownNow(); }
}
