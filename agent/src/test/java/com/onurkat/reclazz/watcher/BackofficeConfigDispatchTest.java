/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.NoopPlatformContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackofficeConfigDispatchTest {
    @TempDir Path dir;
    private FileWatcher watcher;
    private final ArrayList<ChangeEvent> events = new ArrayList<>();

    @BeforeEach void startWatcher() throws Exception {
        watcher = new FileWatcher(new NoopPlatformContext(), AgentConfig.parse(null));
        watcher.onFileChange(events::add);
    }

    @AfterEach void stopWatcher() {
        if (watcher != null) watcher.stopWatching();
    }

    @Test void backofficeConfigLateDuplicateDropped() throws Exception {
        Path file = config("sample-backoffice-config.xml", "<config id='A'/>");
        dispatch(file, ChangeEvent.Type.MODIFIED);
        dispatch(file, ChangeEvent.Type.MODIFIED);
        assertEquals(1, events.size());
        // The same bytes at another path are a first sighting, not a duplicate.
        Path other = config("other-backoffice-config.xml", "<config id='A'/>");
        Files.setLastModifiedTime(other, Files.getLastModifiedTime(file));
        dispatch(other, ChangeEvent.Type.MODIFIED);
        assertEquals(2, events.size());
        assertEquals(other, events.get(1).getPath());
    }

    @Test void backofficeConfigRevertABAApplied() throws Exception {
        Path file = config("sample-backoffice-config.xml", "<config id='A'/>");
        FileTime stamp = Files.getLastModifiedTime(file);
        dispatch(file, ChangeEvent.Type.MODIFIED);
        Files.writeString(file, "<config id='B'/>");
        Files.setLastModifiedTime(file, FileTime.fromMillis(stamp.toMillis() + 2000));
        dispatch(file, ChangeEvent.Type.MODIFIED);
        Files.writeString(file, "<config id='A'/>");
        Files.setLastModifiedTime(file, FileTime.fromMillis(stamp.toMillis() + 4000));
        dispatch(file, ChangeEvent.Type.MODIFIED);
        assertEquals(3, events.size(), "returning to A is a new version, not a globally seen hash");
    }

    @Test void backofficeConfigSameMtimeDifferentBytesApplied() throws Exception {
        Path file = config("sample-backoffice-config.xml", "<config id='A'/>");
        FileTime stamp = Files.getLastModifiedTime(file);
        dispatch(file, ChangeEvent.Type.MODIFIED);
        Files.writeString(file, "<config id='B'/>");
        Files.setLastModifiedTime(file, stamp);
        dispatch(file, ChangeEvent.Type.MODIFIED);
        assertEquals(2, events.size());
    }

    @Test void backofficeConfigResaveNewMtimeApplied() throws Exception {
        Path file = config("sample-backoffice-config.xml", "<config id='A'/>");
        FileTime stamp = Files.getLastModifiedTime(file);
        dispatch(file, ChangeEvent.Type.MODIFIED);
        Files.setLastModifiedTime(file, FileTime.fromMillis(stamp.toMillis() + 2000));
        dispatch(file, ChangeEvent.Type.MODIFIED);
        assertEquals(2, events.size(), "an explicit identical resave may request another reset");
    }

    @Test void otherXmlUnaffected() throws Exception {
        Path file = config("sample-spring.xml", "<beans/>");
        dispatch(file, ChangeEvent.Type.MODIFIED);
        dispatch(file, ChangeEvent.Type.MODIFIED);
        assertEquals(2, events.size(), "this rule must not suppress another XML family");
    }

    @Test void backofficeConfigDeletionAndRecreationAreDelivered() throws Exception {
        Path file = config("sample-backoffice-config.xml", "<config id='A'/>");
        FileTime stamp = Files.getLastModifiedTime(file);
        dispatch(file, ChangeEvent.Type.MODIFIED);
        Files.delete(file);
        dispatch(file, ChangeEvent.Type.DELETED);
        Files.writeString(file, "<config id='A'/>");
        Files.setLastModifiedTime(file, stamp);
        dispatch(file, ChangeEvent.Type.CREATED);
        assertEquals(3, events.size());
        assertEquals(ChangeEvent.Type.DELETED, events.get(1).getType());
        assertEquals(ChangeEvent.Type.CREATED, events.get(2).getType());
    }

    private Path config(String name, String text) throws Exception {
        return Files.writeString(dir.resolve(name), text);
    }

    private void dispatch(Path file, ChangeEvent.Type type) throws Exception {
        // Match JavaSourceDispatchTest: remove only the unrelated 100ms filter
        // so the second dispatch models a late JDK notification deterministically.
        var recent = FileWatcher.class.getDeclaredField("lastModifiedMap");
        recent.setAccessible(true);
        ((Map<?, ?>) recent.get(watcher)).clear();
        watcher.dispatchEvent(new FileWatcher.PendingEvent(0, file, type, "sample", "resources"));
    }
}
