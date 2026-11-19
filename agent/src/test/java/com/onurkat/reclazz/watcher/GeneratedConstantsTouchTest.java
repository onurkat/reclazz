/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.NoopPlatformContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedConstantsTouchTest {
    @TempDir Path tmp;
    FileWatcher watcher;
    List<ChangeEvent> events=new ArrayList<>();
    @BeforeEach void start() throws Exception {
        watcher=new FileWatcher(new NoopPlatformContext(),AgentConfig.parse(null));
        watcher.onFileChange(events::add);
        watcher.onGeneratedOutputHold(()->true);
    }
    @AfterEach void stop(){watcher.stopWatching();}
    Path source() throws Exception { return Files.writeString(tmp.resolve("ExampleConstants.java"),"class ExampleConstants {} "); }
    void touch(Path p) throws Exception { Files.setLastModifiedTime(p,FileTime.fromMillis(Files.getLastModifiedTime(p).toMillis()+5000)); }
    void dispatch(Path p) { watcher.dispatchEvent(new FileWatcher.PendingEvent(System.currentTimeMillis(),p,ChangeEvent.Type.MODIFIED,"example","src")); }
    @Test void firstUntouchedConstantsTouchDuringHoldIsDropped() throws Exception {
        Path p=source();watcher.baselineConstantsSource(p);touch(p);dispatch(p);assertTrue(events.isEmpty());
    }
    @Test void constantsOutsideHoldRemainARetry() throws Exception {
        Path p=source();watcher.baselineConstantsSource(p);watcher.onGeneratedOutputHold(()->false);touch(p);dispatch(p);assertEquals(1,events.size());
    }
    @Test void actualConstantsEditDuringHoldIsDelivered() throws Exception {
        Path p=source();watcher.baselineConstantsSource(p);Files.writeString(p,"class ExampleConstants { int value; }");dispatch(p);assertEquals(1,events.size());
    }
    @Test void ordinarySourceSameBytesDuringHoldRemainARetry() throws Exception {
        Path p=source();dispatch(p);Thread.sleep(110);touch(p);dispatch(p);assertEquals(2,events.size());
    }
    @Test void equalHashAtSnapshotMtimeIsConservativelyDelivered() throws Exception {
        Path p=source();watcher.baselineConstantsSource(p);dispatch(p);assertEquals(1,events.size());
    }
    @Test void editBetweenSnapshotAndRegistrationIsDelivered() throws Exception {
        Path p=source();watcher.baselineConstantsSource(p);Files.writeString(p,"class ExampleConstants { int newValue; }");touch(p);dispatch(p);assertEquals(1,events.size());
    }
    @Test void concurrentWriteDuringSnapshotLeavesUnknownBaseline() throws Exception {
        Path p=source();watcher.baselineConstantsSource(p,()->{try{touch(p);}catch(Exception e){throw new RuntimeException(e);}return 1;});
        touch(p);dispatch(p);assertEquals(1,events.size());
    }
}
