/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.watcher.ChangeEvent;
import org.objectweb.asm.ClassReader;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Exact input-byte receipts, published only after the whole reload bracket returns. */
final class ReloadVerification {
    static final int CAPACITY = 512;
    private final String session = UUID.randomUUID().toString();
    private final Supplier<Map<String, Integer>> loadedCounts;
    private final LinkedHashMap<String, Receipt> latest = new LinkedHashMap<>();
    private final ThreadLocal<Batch> active = new ThreadLocal<>();

    ReloadVerification(Supplier<Map<String, Integer>> loadedCounts) {
        this.loadedCounts = loadedCounts;
    }

    String sessionId() { return session; }

    record Receipt(String sha256, String source, String status, String detail, String completedAt) { }
    private static final class Attempt {
        final Receipt running;
        final boolean unique;
        Boolean success;
        String detail = "No reload outcome was recorded";
        Attempt(Receipt running, boolean unique) { this.running = running; this.unique = unique; }
    }
    private static final class Batch {
        final Map<String, Attempt> attempts = new LinkedHashMap<>();
        String warning;
    }

    void applyFiles(List<ChangeEvent> events, Runnable work) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        boolean ambiguous = false;
        for (ChangeEvent event : events) {
            // Capture already happened in ReloadQueue. Never read a compiler's current file here.
            byte[] captured = event.getBytes();
            String source = event.getPath().toString();
            synchronized (this) {
                latest.entrySet().removeIf(e -> e.getValue().source().equals(shortText(source)));
            }
            try {
                String name = new ClassReader(captured).getClassName().replace('/', '.');
                if (bytes.put(name, captured) != null) ambiguous = true;
                sources.put(name, source);
            } catch (RuntimeException invalidClass) {
                // The normal handler owns diagnostics for invalid bytes. No receipt is evidence.
                ambiguous = true;
            }
        }
        apply(bytes, sources, ambiguous, work);
    }

    void apply(Map<String, byte[]> bytes, Map<String, String> sources, Runnable work) {
        apply(bytes, sources, false, work);
    }

    private void apply(Map<String, byte[]> bytes, Map<String, String> sources, boolean ambiguous, Runnable work) {
        Batch batch = new Batch();
        Map<String, Integer> counts = loadedCounts.get();
        synchronized (this) {
            for (var entry : bytes.entrySet()) {
                String name = entry.getKey();
                Receipt running = new Receipt(sha256(entry.getValue()), shortText(sources.get(name)),
                        "running", "Reload batch has not completed", "");
                batch.attempts.put(name, new Attempt(running, !ambiguous && counts.getOrDefault(name, 0) == 1));
                put(name, running);
            }
        }
        Batch parent = active.get();
        active.set(batch);
        Thread applyingThread = Thread.currentThread();
        StatusReporter.StatusListener listener = (level, message) -> {
            if (Thread.currentThread() == applyingThread && (level.equals("WARN") || level.equals("ERROR"))) {
                if (batch.warning == null) batch.warning = shortText(level + ": " + message);
            }
        };
        StatusReporter.addListener(listener);
        Throwable failure = null;
        try {
            work.run();
        } catch (Throwable thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            StatusReporter.removeListener(listener);
            if (parent == null) active.remove(); else active.set(parent);
            finish(batch, failure);
        }
    }

    private synchronized void finish(Batch batch, Throwable failure) {
        String completed = Instant.now().toString();
        for (var entry : batch.attempts.entrySet()) {
            Attempt attempt = entry.getValue();
            // Eviction or another attempt must never resurrect an older receipt.
            if (latest.get(entry.getKey()) != attempt.running) continue;
            String state, detail;
            if (failure != null || Boolean.FALSE.equals(attempt.success)) {
                state = "failed";
                detail = failure != null ? Failures.describe(failure) : attempt.detail;
            } else if (!attempt.unique || attempt.success == null || batch.warning != null) {
                state = "unverified";
                detail = batch.warning != null ? batch.warning : !attempt.unique
                        ? "Class was not uniquely loaded before the batch, or input class names were ambiguous"
                        : attempt.detail;
            } else {
                state = "applied";
                detail = attempt.detail;
            }
            latest.put(entry.getKey(), new Receipt(attempt.running.sha256(), attempt.running.source(),
                    state, shortText(detail), completed));
        }
    }

    /** A successful handler result is provisional until apply's finally block. Failures stick. */
    void outcome(String className, boolean success, String detail) {
        Batch batch = active.get();
        Attempt attempt = batch == null ? null : batch.attempts.get(className);
        if (attempt == null) {
            invalidateUntracked(className);
        } else if (!Boolean.FALSE.equals(attempt.success)) {
            attempt.success = success;
            attempt.detail = shortText(detail);
        }
    }

    /** Called before a mutation outside a tracked batch, so an earlier receipt cannot survive it. */
    synchronized void invalidateUntracked(String className) {
        Batch batch = active.get();
        if (batch == null || !batch.attempts.containsKey(className)) latest.remove(className);
    }

    private void put(String name, Receipt receipt) {
        latest.remove(name);
        latest.put(name, receipt);
        while (latest.size() > CAPACITY) latest.remove(latest.keySet().iterator().next());
    }

    synchronized String query(String token, String name, String expected) {
        Receipt r = latest.get(name);
        String status = r == null ? "not_observed" : !r.sha256().equals(expected) ? "mismatch" : r.status();
        String detail = r == null ? "No retained reload receipt in this agent session"
                : status.equals("mismatch") ? "The latest attempted bytes differ from the requested bytes" : r.detail();
        // Kept inside INFO.message for compatibility with existing event readers.
        return "{" + field("requestId", token) + "," + field("sessionId", session) + ","
                + field("className", name) + "," + field("expectedSha256", expected) + ","
                + field("observedSha256", r == null ? "" : r.sha256()) + "," + field("status", status) + ","
                + field("source", r == null ? "" : r.source()) + "," + field("detail", detail) + ","
                + field("completedAt", r == null ? "" : r.completedAt()) + "}";
    }

    static boolean validQuery(String token, String name, String hash) {
        return token.matches("[A-Za-z0-9_-]{1,64}") && name.length() <= 256
                && name.codePoints().noneMatch(Character::isIdentifierIgnorable)
                && name.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*")
                && hash.matches("[0-9a-f]{64}");
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String field(String name, String value) {
        return "\"" + name + "\":\"" + StatusServer.escapeJson(value) + "\"";
    }
    private static String shortText(String value) {
        if (value == null) return "";
        return value.length() > 200 ? value.substring(0, 200) : value;
    }
}
