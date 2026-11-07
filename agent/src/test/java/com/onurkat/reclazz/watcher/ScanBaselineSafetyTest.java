/* Copyright 2026 Onur Kat; SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.NoopPlatformContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScanBaselineSafetyTest {
    @TempDir Path root;
    FileWatcher watcher;
    @BeforeEach void open() throws Exception { watcher = new FileWatcher(new NoopPlatformContext(), AgentConfig.parse("autoImpex=true")); }
    @AfterEach void close() { watcher.stopWatching(); }
    private Path oldFile(String name) throws Exception {
        Path file=root.resolve(name); Files.createDirectories(file.getParent());
        Files.writeString(file,"INSERT_UPDATE Example;code[unique=true]\n;synthetic\n");
        Files.setLastModifiedTime(file,FileTime.fromMillis(1000)); return file;
    }
    @Test void scanDoesNotReportUnchangedFilesWithoutBaseline() throws Exception {
        Path file=oldFile("data.impex"); watcher.registerRecursive(root,"example","resources");
        var pending=new LinkedHashMap<Path,FileWatcher.PendingEvent>();
        assertEquals(0,watcher.scanWatchedDirectories(pending,0)); assertTrue(pending.isEmpty());
        assertEquals(0,watcher.scanWatchedDirectories(pending,0));
        // A later edit proves the silent inventory really established a baseline.
        Files.setLastModifiedTime(file,FileTime.fromMillis(2000));
        assertEquals(1,watcher.scanWatchedDirectories(pending,0));
        assertEquals(ChangeEvent.Type.MODIFIED,pending.get(file).type());
    }
    @Test void scanReportsFileCreatedAfterRegistration() throws Exception {
        watcher.registerRecursive(root,"example","resources");
        Path file=Files.writeString(root.resolve("new.impex"),"# new");
        Files.setLastModifiedTime(file,FileTime.fromMillis(System.currentTimeMillis()+2000));
        var pending=new LinkedHashMap<Path,FileWatcher.PendingEvent>();
        assertEquals(1,watcher.scanWatchedDirectories(pending,0));
        assertEquals(ChangeEvent.Type.CREATED,pending.get(file).type());
    }
    @Test void scanReportsModifiedFileWithBaseline() throws Exception {
        Path file=oldFile("Known.class"); watcher.registerRecursive(root,"example","classes");
        watcher.prepopulateContentHashes(root,null);
        Files.setLastModifiedTime(file,FileTime.fromMillis(2000));
        var pending=new LinkedHashMap<Path,FileWatcher.PendingEvent>();
        assertEquals(1,watcher.scanWatchedDirectories(pending,0));
        assertEquals(ChangeEvent.Type.MODIFIED,pending.get(file).type());
    }
    @Test void overflowDoesNotApplyOldUnseenImpexAndRecordsItsBaseline() throws Exception {
        Path file=oldFile("data.impex"); watcher.registerRecursive(root,"example","resources");
        var pending=new LinkedHashMap<Path,FileWatcher.PendingEvent>();
        var directory=new FileWatcher.WatchedDirectory(root,"example","resources");
        watcher.handleOverflow(directory,pending); assertTrue(pending.isEmpty());
        Files.setLastModifiedTime(file,FileTime.fromMillis(2000));
        watcher.handleOverflow(directory,pending);
        assertEquals(ChangeEvent.Type.MODIFIED,pending.get(file).type());
        pending.clear(); watcher.handleOverflow(directory,pending); assertTrue(pending.isEmpty());
    }
    @Test void aLateRegisteredChildInheritsTheOriginalRootBaseline() throws Exception {
        watcher.registerRecursive(root,"example","classes");
        long fileTime=System.currentTimeMillis()+1;
        Path child=Files.createDirectories(root.resolve("new/deep"));
        Path file=Files.write(child.resolve("New.class"),new byte[]{1});
        Files.setLastModifiedTime(file,FileTime.fromMillis(fileTime));
        while(System.currentTimeMillis()<=fileTime) Thread.sleep(2);
        watcher.registerRecursive(child,"example","classes");
        var pending=new LinkedHashMap<Path,FileWatcher.PendingEvent>();
        assertEquals(1,watcher.scanWatchedDirectories(pending,0));
        assertEquals(ChangeEvent.Type.CREATED,pending.get(file).type());
    }
}
