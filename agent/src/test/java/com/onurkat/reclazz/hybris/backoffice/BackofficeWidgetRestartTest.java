/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris.backoffice;

import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Set;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

class BackofficeWidgetRestartTest {
    public static class Widget {}

    @Test void bodyOnlyWidgetEditDoesNotInventRegistrationRestart() {
        RestartLedger.clear();
        assertFalse(BackofficeWidgetReloader.hasAddedSocketEvent(bytes("Lcom/hybris/cockpitng/annotations/SocketEvent;"), Set.of()));
        assertEquals(0, RestartLedger.size());
    }

    @Test void addedHelperDoesNotReRegisterExistingSocketAction() {
        assertFalse(BackofficeWidgetReloader.hasAddedSocketEvent(bytes("Lcom/hybris/cockpitng/annotations/SocketEvent;"), Set.of("helper:()V")));
    }

    @Test void exactSavedSocketEventOnAddedMethodIsRecognized() {
        assertTrue(BackofficeWidgetReloader.hasAddedSocketEvent(bytes("Lcom/hybris/cockpitng/annotations/SocketEvent;"), Set.of("input:()V")));
    }

    @Test void unrelatedSocketEventNameIsNotAWidget() {
        assertFalse(BackofficeWidgetReloader.hasAddedSocketEvent(bytes("Lcom/example/NotASocketEvent;"), Set.of("input:()V")));
        assertFalse(BackofficeWidgetReloader.hasAddedSocketEvent(bytes("Lcom/hybris/cockpitng/events/SocketEvent;"), Set.of("input:()V")));
    }

    private static byte[] bytes(String annotation) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Widget", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "input", "()V", null, null);
        method.visitAnnotation(annotation, true).visitEnd(); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }

    @Test void unsupportedWidgetRegistrationCreatesPendingRestartWithoutFalseSuccess() {
        RestartLedger.clear();
        var messages = new ArrayList<String>();
        StatusReporter.StatusListener listener = (level, message) -> messages.add(message);
        StatusReporter.addListener(listener);
        try {
            assertFalse(BackofficeWidgetReloader.reloadWidget(Widget.class));
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains(Widget.class.getName()) && s.contains("SocketEvent")));
            assertTrue(messages.stream().anyMatch(s -> s.contains("restart")));
            assertFalse(messages.stream().anyMatch(s -> s.contains("widget reloaded")));
        } finally { StatusReporter.removeListener(listener); RestartLedger.clear(); }
    }
}
