/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.watcher;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The watcher reports exactly the files some {@link ChangeKind} claims.
 * Every kind here has a sample; a new kind without one fails this test,
 * which is the moment to add the sample and see the watcher accept it
 * with no second list to edit.
 */
class WatchedKindsAgreeTest {

    private static final Map<ChangeKind, String> SAMPLES = new EnumMap<>(ChangeKind.class);

    static {
        SAMPLES.put(ChangeKind.CLASS_FILE, "OrderService.class");
        SAMPLES.put(ChangeKind.JAVA_SOURCE, "OrderService.java");
        SAMPLES.put(ChangeKind.SPRING_XML, "acme-spring.xml");
        SAMPLES.put(ChangeKind.BACKOFFICE_CONFIG, "acme-backoffice-config.xml");
        SAMPLES.put(ChangeKind.CODEGEN_XML, "acme-items.xml");
        SAMPLES.put(ChangeKind.PROPERTIES, "application.properties");
        SAMPLES.put(ChangeKind.LOGGING_CONFIG, "logback-spring.xml");
        SAMPLES.put(ChangeKind.IMPEX, "essential-data.impex");
        SAMPLES.put(ChangeKind.LOCALIZATION, "acme-locales_en.properties");
        SAMPLES.put(ChangeKind.TEMPLATE, "order.html");
    }

    @Test
    void everyKindHasASampleAndTheWatcherAcceptsIt() {
        List<String> problems = new ArrayList<>();
        for (ChangeKind kind : ChangeKind.values()) {
            if (kind == ChangeKind.UNKNOWN) continue;
            String sample = SAMPLES.get(kind);
            if (sample == null) {
                problems.add(kind + " has no sample here; add one, and the watcher watches it");
                continue;
            }
            if (ChangeKind.of(sample) != kind) {
                problems.add(sample + " classifies as " + ChangeKind.of(sample) + ", not " + kind);
            }
            if (!ChangeKind.watched(sample)) {
                problems.add(sample + " (" + kind + ") is not watched");
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void whatNoKindClaimsIsNotWatched() {
        for (String unrelated : List.of("notes.txt", "pom.xml", "web.xml", "diagram.png", "Makefile", ".DS_Store")) {
            assertEquals(ChangeKind.UNKNOWN, ChangeKind.of(unrelated), unrelated);
            assertFalse(ChangeKind.watched(unrelated), unrelated + " would be hashed and queued for nothing");
        }
    }
}
