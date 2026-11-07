/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.ext.NoopStructuralReloadHook;
import com.onurkat.reclazz.hybris.backoffice.BackofficeWidgetReloader;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BackofficeSocketEventAdviceTest {
    private static final int PUBLIC = Opcodes.ACC_PUBLIC;
    private static List<BackofficeSocketEventDetector.Handler> handlers(int access, String descriptor) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, PUBLIC, "com/example/Widget", null, "java/lang/Object", null);
        var method = writer.visitMethod(access, "input", descriptor, null, null);
        method.visitAnnotation("Lcom/hybris/cockpitng/annotations/SocketEvent;", true).visitEnd();
        method.visitEnd(); writer.visitEnd();
        return BackofficeSocketEventDetector.addedHandlers(writer.toByteArray(), Set.of("input:" + descriptor));
    }
    private static List<String> report(List<BackofficeSocketEventDetector.Handler> handlers, boolean premium) {
        RestartLedger.clear();
        var messages = new ArrayList<String>();
        StatusReporter.StatusListener listener = (level, message) -> messages.add(level + ":" + message);
        StatusReporter.addListener(listener);
        try {
            if (premium) new BackofficeWidgetReloader().afterReload(BackofficeSocketEventAdviceTest.class, handlers);
            else new NoopStructuralReloadHook().afterReload(BackofficeSocketEventAdviceTest.class, handlers);
            return messages;
        } finally { StatusReporter.removeListener(listener); }
    }
    @Test void addedSocketEventReportsReopenNotRestart() {
        try {
            var facts = handlers(PUBLIC, "(Ljava/lang/String;)V");
            assertEquals(1, facts.size());
            assertEquals(1, facts.get(0).parameterCount());
            var messages = report(facts, false);
            assertEquals(1, messages.size());
            assertTrue(messages.get(0).startsWith("INFO:"));
            assertTrue(messages.get(0).contains("composed after this reload"));
            assertTrue(messages.get(0).contains("open views keep their old bindings"));
            assertEquals(0, RestartLedger.size());
        } finally { RestartLedger.clear(); }
    }
    @Test void privateHandlerReportsNotBindableWithLedger() { invalid(Opcodes.ACC_PRIVATE, "(Ljava/lang/String;)V"); }
    @Test void twoArgumentHandlerReportsNotBindableWithLedger() { invalid(PUBLIC, "(II)V"); }
    private static void invalid(int access, String descriptor) {
        try {
            var messages = report(handlers(access, descriptor), false);
            assertTrue(messages.get(0).startsWith("WARN:"));
            assertTrue(messages.get(0).contains("this handler is not bound"));
            assertFalse(messages.get(0).contains("restart"));
            assertEquals(1, RestartLedger.size());
        } finally { RestartLedger.clear(); }
    }
    @Test void staticHandlerIsNeverToldItCannotBind() {
        try {
            var messages = report(handlers(PUBLIC | Opcodes.ACC_STATIC, "(Ljava/lang/String;)V"), false);
            assertTrue(messages.get(0).startsWith("INFO:"));
            assertTrue(messages.get(0).contains("native binding accepts this shape"));
            assertTrue(messages.get(0).contains("has not verified it after a reload"));
            assertFalse(messages.get(0).contains("not bound"));
            assertEquals(1, RestartLedger.size());
        } finally { RestartLedger.clear(); }
    }
    @Test void mixedBindableAndUnbindableAdditionsReportEachHandler() {
        try {
            var all = new ArrayList<>(handlers(PUBLIC, "(Ljava/lang/String;)V"));
            all.addAll(handlers(Opcodes.ACC_PRIVATE, "(II)V"));
            all.addAll(handlers(PUBLIC | Opcodes.ACC_STATIC, "()V"));
            var messages = report(all, false);
            assertEquals(3, messages.size());
            assertEquals(1, messages.stream().filter(s -> s.startsWith("WARN:")).count());
            assertEquals(2, RestartLedger.size());
        } finally { RestartLedger.clear(); }
    }
    @Test void freeAndPremiumHooksProduceIdenticalAdvice() {
        try {
            for (var facts : List.of(handlers(PUBLIC, "(Ljava/lang/String;)V"), handlers(PUBLIC | Opcodes.ACC_STATIC, "()V"), handlers(PUBLIC, "(II)V"))) {
                var free = report(facts, false); var ledger = RestartLedger.digest();
                assertEquals(free, report(facts, true));
                assertEquals(ledger, RestartLedger.digest());
            }
        } finally { RestartLedger.clear(); }
    }
    @Test void emptyFactsProduceNoAdvice() {
        try { assertEquals(List.of(), report(List.of(), false)); assertEquals(0, RestartLedger.size()); }
        finally { RestartLedger.clear(); }
    }
}
