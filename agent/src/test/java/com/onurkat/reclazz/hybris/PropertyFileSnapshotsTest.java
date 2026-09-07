/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PropertyFileSnapshotsTest {
    @TempDir Path tmp;

    @Test void rejectingThenFixingRetriesEveryPendingKey() throws Exception {
        var snapshots = new PropertyFileSnapshots();
        Path file = tmp.resolve("application.properties");
        Files.writeString(file, "svc.url=old\nsvc.timeout=10\n");
        snapshots.baseline(file);
        Files.writeString(file, "svc.url=new\nsvc.timeout=abc\n");
        assertEquals(2, snapshots.pending(file).changed().size());
        assertEquals(2, snapshots.pending(file).changed().size());
        Files.writeString(file, "svc.url=new\nsvc.timeout=20\n");
        var fixed = snapshots.pending(file);
        assertEquals(Map.of("svc.url", "new", "svc.timeout", "20"), fixed.changed());
        snapshots.accept(fixed);
        assertTrue(snapshots.pending(file).changed().isEmpty());
    }

    @Test void acceptingARecordsANeverTheNewerDiskVersion() throws Exception {
        var snapshots = new PropertyFileSnapshots();
        Path file = tmp.resolve("application.properties");
        Files.writeString(file, "svc.timeout=10\n");
        snapshots.baseline(file);
        Files.writeString(file, "svc.timeout=20\n");
        var a = snapshots.pending(file);
        Files.writeString(file, "svc.timeout=abc\n");
        snapshots.accept(a);
        assertEquals(Map.of("svc.timeout", "20"), snapshots.current(file));
        assertEquals(Map.of("svc.timeout", "abc"), snapshots.pending(file).changed());
        assertThrows(UnsupportedOperationException.class, () -> a.content().put("svc.timeout", "999"));
    }

    @Test void unreadableOrMalformedFileDoesNotAdvanceTheBaseline() throws Exception {
        var snapshots = new PropertyFileSnapshots();
        Path file = tmp.resolve("application.properties");
        Files.writeString(file, "svc.timeout=10\n");
        snapshots.baseline(file);
        Files.writeString(file, "broken=" + '\\' + "uZZZZ\n");
        assertNull(snapshots.pending(file));
        assertEquals(Map.of("svc.timeout", "10"), snapshots.current(file));
        Files.delete(file);
        assertNull(snapshots.pending(file));
        assertEquals(Map.of("svc.timeout", "10"), snapshots.current(file));
    }
}
