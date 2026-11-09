/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtensionProjectPropertiesWatchTest {
    @TempDir Path temp;
    private FileWatcher watcher(String options, List<Path> baseline) throws Exception {
        var config=AgentConfig.parse(options);
        var context=new HybrisPlatformContext(temp,config);
        var nativeContext=new HybrisContext(temp);
        var platformHome=HybrisContext.class.getDeclaredField("platformHome");platformHome.setAccessible(true);platformHome.set(nativeContext,temp.resolve("bin/platform"));
        var extensions=HybrisContext.class.getDeclaredField("extensions");extensions.setAccessible(true);
        extensions.set(nativeContext,Map.of("example",new ExtensionInfo("example",temp.resolve("extension"),List.of(),true,false,true)));
        var field=HybrisPlatformContext.class.getDeclaredField("hybrisContext");field.setAccessible(true);field.set(context,nativeContext);
        var watcher=new FileWatcher(context,config);watcher.onPropertyFileFound(baseline::add);
        var register=FileWatcher.class.getDeclaredMethod("registerDirectories");register.setAccessible(true);register.invoke(watcher);
        return watcher;
    }
    @Test void exactRootFileBaselinedThenEditDeleteRecreateWithoutNeighbourScan() throws Exception {
        Path root=Files.createDirectories(temp.resolve("extension"));Path file=root.resolve("project.properties");
        Files.writeString(file,"example.value=v1\n");
        var baseline=new ArrayList<Path>();var watcher=watcher("watchExtensions=example",baseline);
        try {
            assertEquals(List.of(file),baseline);
            var pending=new LinkedHashMap<Path,FileWatcher.PendingEvent>();
            watcher.scanWatchedDirectories(pending,0);assertTrue(pending.isEmpty());
            Files.writeString(file,"example.value=v2\n");
            Files.setLastModifiedTime(file,FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis()+5000));
            Files.writeString(root.resolve("other.properties"),"unrelated=value\n");
            Files.writeString(root.resolve("unowned.impex"),"not imported\n");
            watcher.scanWatchedDirectories(pending,0);assertEquals(Set.of(file),pending.keySet());
            assertEquals(ChangeEvent.Type.MODIFIED,pending.get(file).type());
            pending.clear();watcher.scanWatchedDirectories(pending,0);assertTrue(pending.isEmpty());
            Files.delete(file);watcher.scanWatchedDirectories(pending,0);assertEquals(ChangeEvent.Type.DELETED,pending.get(file).type());
            pending.clear();Files.writeString(file,"example.value=v1\n");
            watcher.scanWatchedDirectories(pending,0);assertEquals(ChangeEvent.Type.CREATED,pending.get(file).type());
        } finally {watcher.stopWatching();}
    }
    @Test void missingRootFileCreationIsDetected() throws Exception {
        Path root=Files.createDirectories(temp.resolve("extension"));
        var baseline=new ArrayList<Path>();var watcher=watcher("watchExtensions=example",baseline);
        try {
            assertTrue(baseline.isEmpty());Path file=root.resolve("project.properties");Files.writeString(file,"example.value=v1\n");
            var pending=new LinkedHashMap<Path,FileWatcher.PendingEvent>();watcher.scanWatchedDirectories(pending,0);
            assertEquals(Set.of(file),pending.keySet());assertEquals(ChangeEvent.Type.CREATED,pending.get(file).type());
        } finally {watcher.stopWatching();}
    }
    @Test void excludedFileAndUnselectedModuleAreNotBaselinedOrDispatched() throws Exception {
        Path file=Files.createDirectories(temp.resolve("extension")).resolve("project.properties");Files.writeString(file,"example.value=v1\n");
        for(String options:List.of("watchExtensions=other","watchExtensions=example,excludePatterns=project.properties")) {
            var baseline=new ArrayList<Path>();var watcher=watcher(options,baseline);
            try {
                assertTrue(baseline.isEmpty());Files.setLastModifiedTime(file,FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis()+5000));
                var pending=new LinkedHashMap<Path,FileWatcher.PendingEvent>();watcher.scanWatchedDirectories(pending,0);assertTrue(pending.isEmpty());
            } finally {watcher.stopWatching();}
        }
    }
}
