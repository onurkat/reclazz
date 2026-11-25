/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.watcher.WatcherHealth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WatcherHealthReportingTest {
    @Test void reportsOnlyTheFirstStallAndTheActualRecovery() {
        var server = new StatusServer(0, null);
        var events = new ArrayList<String>();
        StatusReporter.StatusListener listener = (level, message) -> {
            if (message.startsWith("file watcher")) events.add(level + " " + message);
        };
        var watching = new WatcherHealth("watching", 0, null);
        var stalled = new WatcherHealth("stalled", 30_000, null);
        StatusReporter.addListener(listener);
        try {
            for (var sample : List.of(watching, watching, stalled, stalled, watching, watching))
                server.reportWatcherHealth(sample);
        } finally {
            StatusReporter.removeListener(listener);
        }
        assertEquals(List.of("ERROR file watcher has not completed a poll for 30 s",
                "INFO file watcher resumed"), events);
    }
}
