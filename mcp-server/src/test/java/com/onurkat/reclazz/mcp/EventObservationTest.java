/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EventObservationTest {
    static JsonObject row(String level, String message) {
        return EventObservation.describe(new EventSubscription.Event(1, level, message, null));
    }
    @Test void everyKnownProducerHasOnlyItsExplicitIdentity() {
        String owner = "com.example.Service$Literal";
        for (String[] input : List.of(
                new String[]{"COMPILE", "Compiled Ünicode$File.java (12ms)", "compile", "fileBasename", "Ünicode$File.java"},
                new String[]{"ERROR", "  Ünicode$File.java:12: missingSymbol", "compiler_diagnostic", "fileBasename", "Ünicode$File.java"},
                new String[]{"RELOAD", "Reloaded " + owner + " (12ms): effects", "reload", "ownerClassName", owner},
                new String[]{"STRUCTURAL_RELOAD", "Structural reload: " + owner + " (+1 field)", "structural_reload", "ownerClassName", owner},
                new String[]{"ERROR", "Hot-swap failed for " + owner + ": refused", "refused", "ownerClassName", owner},
                new String[]{"ERROR", "Failed to refresh Spring bean for " + owner + ": failed", "framework_failure", "ownerClassName", owner},
                new String[]{"WARN", owner + ": superclass change not applied; restart required. Still extends A instead of B", "restart_required", "ownerClassName", owner})) {
            JsonObject r = row(input[0], input[1]); assertEquals(input[2], r.get("type").getAsString());
            assertEquals(input[4], r.get(input[3]).getAsString()); assertFalse(r.has(input[3].equals("fileBasename") ? "ownerClassName" : "fileBasename"));
        }
        JsonObject header = row("ERROR", "Compilation failed:"); assertEquals("compile_error", header.get("type").getAsString());
        assertFalse(header.has("fileBasename")); assertFalse(header.has("ownerClassName"));
    }
    @Test void wrongLevelsInnerDisplayAndMalformedNamesRemainUnknown() {
        for (String[] input : List.of(new String[]{"INFO", "Compiled A.java (1ms)"},new String[]{"WARN", "Hot-swap failed for com.example.A: failed"},
                new String[]{"RELOAD", "Reloaded com.example.A (inner class) (1ms)"},new String[]{"COMPILE", "Compiled ../A.java (1ms)"},
                new String[]{"RELOAD", "Reloaded " + "A".repeat(257)},new String[]{"ERROR", "Hot-swap failed for bad name: failed"},
                new String[]{"ERROR", "  A.java:0: failed"},new String[]{"OK", "ImpEx import complete"})) {
            JsonObject r = row(input[0], input[1]); assertEquals("unknown", r.get("type").getAsString(), Arrays.toString(input));
            assertFalse(r.has("ownerClassName")); assertFalse(r.has("fileBasename"));
        }
    }
    @Test void timestampsAreOptionalBoundedWireInstantsWithoutInventedTime() {
        for (JsonElement invalid : List.of(JsonNull.INSTANCE,new JsonPrimitive(1),new JsonPrimitive(""),new JsonPrimitive("2026-99-99T00:00:00Z"),new JsonPrimitive("x".repeat(65))))
            assertNull(EventSubscription.timestamp(invalid));
        for (String valid : List.of("2026-11-10T10:00:00Z", "2026-11-10T10:00:00.123456789Z", "2026-11-10T13:00:00+03:00"))
            assertEquals(valid, EventSubscription.timestamp(new JsonPrimitive(valid)));
        var first = new EventSubscription.Event(1,"INFO","a","2026-11-10T10:00:00Z");
        var second = new EventSubscription.Event(2,"INFO","b","2026-11-10T09:00:00Z");
        JsonObject page = new JsonObject(); RecentEvents.page(page,new EventSubscription.Snapshot(2,List.of(first,second)),Map.of("sinceCursor","0"));
        assertEquals("1",page.getAsJsonArray("events").get(0).getAsJsonObject().get("sequence").getAsString());
        assertEquals("2",page.getAsJsonArray("events").get(1).getAsJsonObject().get("sequence").getAsString());
    }
    @Test void parseBeforeClippingButNeverPublishAnOversizedIdentity() {
        JsonObject known = row("ERROR", "Hot-swap failed for com.example.Service: " + "x".repeat(1000));
        assertEquals("refused", known.get("type").getAsString()); assertEquals(512,known.get("message").getAsString().length());
        assertTrue(known.get("messageTruncated").getAsBoolean());
        JsonObject unknown = row("COMPILE", "Compiled " + "X".repeat(256) + ".java (1ms)");
        assertEquals("unknown", unknown.get("type").getAsString()); assertFalse(unknown.has("fileBasename"));
    }
}
