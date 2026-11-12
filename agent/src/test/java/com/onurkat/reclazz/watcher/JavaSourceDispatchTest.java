/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.NoopPlatformContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JavaSourceDispatchTest {
    @TempDir Path dir;

    @Test void delayedPollingNotificationDoesNotRepeatAHotSourceDispatch() throws Exception {
        exercise(false, false, 1);
    }
    @Test void anExplicitIdenticalResaveStillDispatches() throws Exception {
        exercise(true, false, 2);
    }
    @Test void changedBytesWithTheSameTimestampStillDispatch() throws Exception {
        exercise(false, true, 2);
    }

    @Test void deletionAndRecreationOfTheSameSourceVersionAreDelivered() throws Exception {
        var watcher = new FileWatcher(new NoopPlatformContext(), AgentConfig.parse(null));
        var events = new ArrayList<ChangeEvent>(); watcher.onFileChange(events::add);
        Path file = dir.resolve("Job.java"); byte[] bytes = "class Job {}".getBytes();
        Files.write(file, bytes); FileTime stamp = Files.getLastModifiedTime(file);
        watcher.dispatchEvent(new FileWatcher.PendingEvent(0, file, ChangeEvent.Type.MODIFIED, "app", "src"));
        clearRecent(watcher); Files.delete(file);
        watcher.dispatchEvent(new FileWatcher.PendingEvent(1, file, ChangeEvent.Type.DELETED, "app", "src"));
        clearRecent(watcher); Files.write(file, bytes); Files.setLastModifiedTime(file, stamp);
        watcher.dispatchEvent(new FileWatcher.PendingEvent(2, file, ChangeEvent.Type.CREATED, "app", "src"));
        assertEquals(3, events.size());
        assertEquals(ChangeEvent.Type.CREATED, events.get(2).getType());
    }

    private static void clearRecent(FileWatcher watcher) throws Exception {
        var recent = FileWatcher.class.getDeclaredField("lastModifiedMap"); recent.setAccessible(true);
        ((Map<?,?>) recent.get(watcher)).clear();
    }

    private void exercise(boolean resave, boolean changed, int expected) throws Exception {
        var watcher = new FileWatcher(new NoopPlatformContext(), AgentConfig.parse(null));
        var events = new ArrayList<ChangeEvent>(); watcher.onFileChange(events::add);
        Path file = dir.resolve("Job.java"); Files.writeString(file, "class Job { int delay=1000; }");
        var first = new FileWatcher.PendingEvent(0, file, ChangeEvent.Type.MODIFIED, "app", "src");
        watcher.dispatchEvent(first);
        // Bypass only the unrelated 100ms wall-clock filter: this is the late
        // JDK notification after the hot scan delivered the same disk version.
        clearRecent(watcher);
        FileTime stamp = Files.getLastModifiedTime(file);
        if (changed) { Files.writeString(file, "class Job { int delay=300; }"); Files.setLastModifiedTime(file, stamp); }
        if (resave) Files.setLastModifiedTime(file, FileTime.fromMillis(stamp.toMillis()+2000));
        watcher.dispatchEvent(new FileWatcher.PendingEvent(1, file, ChangeEvent.Type.MODIFIED, "app", "src"));
        assertEquals(expected, events.size());
    }
}
