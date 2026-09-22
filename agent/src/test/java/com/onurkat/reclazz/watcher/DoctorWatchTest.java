/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.NoopPlatformContext;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DoctorWatchTest {
    @TempDir Path dir;
    @Test void reportsRegistrationsNotConfigurationAndBoundsItsSample() throws Exception {
        var watcher=new FileWatcher(new NoopPlatformContext(),AgentConfig.parse("watchDirs="+dir));
        try {
            assertEquals("starting",watcher.doctorEvidence().state());
            assertEquals(0,watcher.doctorEvidence().count(),"configuration alone is not evidence");
            Path small=Files.createDirectory(dir.resolve("small"));
            watcher.registerRecursive(small,"app",null);
            assertEquals(java.util.List.of(small.toAbsolutePath().normalize().toString()),watcher.doctorEvidence().directories());
            assertFalse(watcher.doctorEvidence().truncated());
            Files.delete(small);
            assertEquals(0,watcher.doctorEvidence().count(),"deleted output is not a live watch");
            for(int i=0;i<12;i++) watcher.registerRecursive(Files.createDirectory(dir.resolve("d"+i)),"app",null);
            watcher.noteUnwatchable(dir.resolve("denied"),new java.io.IOException("denied"));
            var evidence=watcher.doctorEvidence();
            assertEquals(12,evidence.count());assertEquals(8,evidence.directories().size());
            assertTrue(evidence.truncated());assertEquals(1,evidence.refused());
        } finally {watcher.stopWatching();}
        assertEquals("stopped",watcher.doctorEvidence().state());
    }
}
