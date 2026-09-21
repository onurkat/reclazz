/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.watcher.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ReloadVerificationTest {
    static byte[] bytes(String name, int version) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_STATIC, "v" + version, "I", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
    static ReloadVerification ledger() { return new ReloadVerification(() -> Map.of("A", 1, "B", 1)); }
    static JsonNode result(ReloadVerification v, String name, byte[] bytes) {
        try { return new ObjectMapper().readTree(v.query("test", name, ReloadVerification.sha256(bytes))); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    static String state(ReloadVerification v, String name, byte[] bytes) { return result(v, name, bytes).get("status").asText(); }
    static void success(ReloadVerification v, String name, byte[] bytes) {
        v.apply(Map.of(name, bytes), Map.of(name, name + ".class"), () -> v.outcome(name, true, "body reloaded"));
    }

    @Test void resultWaitsForDeferredBatchEndAndUsesCapturedBytes() {
        for (boolean warn : List.of(false, true)) {
            ReloadVerification v = ledger();
            byte[] a = bytes("A", 1), b = bytes("B", 1);
            Path aPath = Path.of("A.class"), bPath = Path.of("B.class");
            Map<Path, byte[]> disk = new java.util.HashMap<>(Map.of(aPath, a, bPath, b));
            ReloadQueue queue = new ReloadQueue(event -> {
                String name = event.getPath().equals(aPath) ? "A" : "B";
                v.outcome(name, true, "provisional");
                disk.put(aPath, bytes("A", 2));
            }, new ReloadQueue.Bracket() {
                public void begin() { }
                public void end() {
                    assertEquals("running", state(v, "A", a), "success escaped before native batch flush");
                    if (warn) StatusReporter.warn("deferred native redefinition failed");
                }
            }, Runnable::run, new ReloadStall(() -> 0L, 1000), () -> 0L, ignored -> {}, disk::get);
            queue.setVerification(v);
            queue.enqueueClassFile(new ChangeEvent(aPath, ChangeEvent.Type.MODIFIED, "app", null));
            queue.enqueueClassFile(new ChangeEvent(bPath, ChangeEvent.Type.MODIFIED, "app", null));
            queue.reloadClassBatch();
            assertEquals(warn ? "unverified" : "applied", state(v, "A", a));
            assertEquals("mismatch", state(v, "A", bytes("A", 2)), "must never hash a later disk write");
        }
    }

    @Test void runningQueriesDoNotWaitForReloadAndNewerAttemptSupersedesProof() throws Exception {
        ReloadVerification v = ledger();
        byte[] old = bytes("A", 1), next = bytes("A", 2);
        success(v, "A", old);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CompletableFuture<Void> done = CompletableFuture.runAsync(() -> v.apply(Map.of("A", next), Map.of(), () -> {
            v.outcome("A", true, "provisional"); entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        }));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("mismatch", state(v, "A", old));
            assertEquals("running", state(v, "A", next));
            assertEquals("", result(v, "A", next).get("completedAt").asText());
        } finally { release.countDown(); }
        done.get(5, TimeUnit.SECONDS);
        assertEquals("applied", state(v, "A", next));
        assertFalse(result(v, "A", next).get("completedAt").asText().isBlank());
    }

    @Test void exceptionAfterProvisionalSuccessFailsAndListenerIsRemoved() {
        ReloadVerification v = ledger(); byte[] a = bytes("A", 1);
        assertThrows(IllegalStateException.class, () -> v.apply(Map.of("A", a), Map.of(), () -> {
            v.outcome("A", true, "body"); throw new IllegalStateException("framework refresh failed");
        }));
        assertEquals("failed", state(v, "A", a));
        assertTrue(result(v, "A", a).get("detail").asText().contains("framework refresh failed"));
        StatusReporter.warn("outside the batch");
        success(v, "A", a);
        assertEquals("applied", state(v, "A", a));
    }

    @Test void failureCannotBeOverwrittenBySuccessAndBatchErrorsBlockOtherClasses() {
        ReloadVerification v = ledger(); byte[] a = bytes("A", 1), b = bytes("B", 1);
        v.apply(Map.of("A", a, "B", b), Map.of(), () -> {
            v.outcome("A", false, "unsupported superclass");
            v.outcome("A", true, "later callback");
            v.outcome("B", true, "body");
            StatusReporter.error("A needs restart");
        });
        assertEquals("failed", state(v, "A", a));
        assertEquals("unsupported superclass", result(v, "A", a).get("detail").asText());
        assertEquals("unverified", state(v, "B", b));
    }

    @Test void noOutcomeAndUnloadedOrAmbiguousClassesCannotVerify() {
        byte[] a = bytes("A", 1);
        for (int count : List.of(0, 1, 2)) {
            ReloadVerification v = new ReloadVerification(() -> Map.of("A", count));
            v.apply(Map.of("A", a), Map.of(), () -> {});
            assertEquals("unverified", state(v, "A", a));
            success(v, "A", a);
            assertEquals(count == 1 ? "applied" : "unverified", state(v, "A", a));
        }
    }

    @Test void warningOnUnrelatedThreadDoesNotPoisonTheBatch() throws Exception {
        ReloadVerification v = ledger(); byte[] a = bytes("A", 1);
        v.apply(Map.of("A", a), Map.of(), () -> {
            Thread other = new Thread(() -> StatusReporter.warn("unrelated")); other.start();
            try { other.join(2000); } catch (InterruptedException e) { throw new AssertionError(e); }
            assertFalse(other.isAlive()); v.outcome("A", true, "body");
        });
        assertEquals("applied", state(v, "A", a));
    }

    @Test void duplicateInputNamesAndUntrackedMutationsInvalidateProof() {
        ReloadVerification v = ledger(); byte[] a = bytes("A", 1);
        var one = new ChangeEvent(Path.of("one/A.class"), ChangeEvent.Type.MODIFIED, "app", null).withBytes(a);
        var two = new ChangeEvent(Path.of("two/A.class"), ChangeEvent.Type.MODIFIED, "app", null).withBytes(a);
        v.applyFiles(List.of(one, two), () -> v.outcome("A", true, "body"));
        assertEquals("unverified", state(v, "A", a));
        success(v, "A", a); v.invalidateUntracked("A");
        assertEquals("not_observed", state(v, "A", a));
        success(v, "A", a); v.outcome("A", true, "untracked");
        assertEquals("not_observed", state(v, "A", a));
    }

    @Test void boundedHistoryAndNewSessionNeverResurrectEvidence() {
        ReloadVerification v = new ReloadVerification(Map::of);
        for (int i = 0; i <= ReloadVerification.CAPACITY; i++) success(v, "C" + i, bytes("C" + i, 1));
        assertEquals("not_observed", state(v, "C0", bytes("C0", 1)));
        assertEquals("unverified", state(v, "C512", bytes("C512", 1)));
        assertNotEquals(result(v, "A", bytes("A", 1)).get("sessionId"), result(ledger(), "A", bytes("A", 1)).get("sessionId"));
    }

    @Test void structuredResultEscapesAndBoundsFailureAndPathWithoutBreakingJson() throws Exception {
        ReloadVerification v = ledger(); byte[] a = bytes("A", 1);
        String hostile = ("\"\\\n" + (char) 1).repeat(5000);
        v.apply(Map.of("A", a), Map.of("A", hostile), () -> v.outcome("A", false, hostile));
        String json = v.query("token", "A", ReloadVerification.sha256(a));
        assertTrue(json.length() < 4000);
        JsonNode parsed = new ObjectMapper().readTree(json);
        assertEquals("token", parsed.get("requestId").asText());
        assertEquals(hostile.substring(0, 200), parsed.get("detail").asText());
        assertEquals("failed", parsed.get("status").asText());
    }
}
