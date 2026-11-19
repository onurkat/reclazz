/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.watcher;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.SpringBootContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class FileWatcherReconfigureTest {
    @TempDir Path root;
    FileWatcher watcher;AgentConfig config;Path classes,source,resources;Thread thread;boolean refuseDirectory;
    @AfterEach void close()throws Exception{if(watcher!=null)watcher.stopWatching();if(thread!=null)thread.join(3000);}
    @SuppressWarnings("unchecked") Map<WatchKey,FileWatcher.WatchedDirectory> keys()throws Exception {
        var field=FileWatcher.class.getDeclaredField("watchKeyMap");field.setAccessible(true);return (Map<WatchKey,FileWatcher.WatchedDirectory>)field.get(watcher);
    }
    void setup(boolean auto,boolean fail)throws Exception {
        classes=Files.createDirectories(root.resolve("target/classes/app/nested"));source=Files.createDirectories(root.resolve("src/main/java/app/nested"));resources=Files.createDirectories(root.resolve("src/main/resources/impex/nested"));
        config=AgentConfig.parse("watchDirs="+root.resolve("target/classes")+",autoCompile="+auto+",startupDelaySec=0,debounceMs=0");var platform=new SpringBootContext(config);platform.initialize();
        watcher=new FileWatcher(platform,config){@Override WatchKey registerReconfiguredDirectory(Path dir)throws java.io.IOException{if(fail&&dir.equals(source))throw new IllegalStateException("injected fatal stage failure");if(refuseDirectory&&dir.equals(source))throw new java.io.IOException("injected watch limit");return super.registerReconfiguredDirectory(dir);}};
        watcher.registerRecursive(root.resolve(auto?"src/main/java":"target/classes"),"app",auto?"src":"classes");watcher.registerRecursive(root.resolve("src/main/resources"),"app","resources");
    }
    boolean change(boolean enabled,Map<Path,FileWatcher.PendingEvent> pending)throws Exception {
        var target=config.liveOptions().with(Map.of("autoCompile",enabled));return watcher.reconfigureNow(target,r->{r.run();return true;},()->config.publishLiveOptions(target),pending);
    }
    void editAndSee(Path file)throws Exception {
        Files.writeString(file,"new-content");Files.setLastModifiedTime(file,FileTime.fromMillis(System.currentTimeMillis()+2000));
        var pending=new LinkedHashMap<Path,FileWatcher.PendingEvent>();watcher.scanWatchedDirectories(pending,0);assertTrue(pending.containsKey(file),"retained nested directory must deliver "+file);
    }
    @Test void nestedImpexEditAfterEnable()throws Exception{setup(false,false);assertTrue(change(true,new LinkedHashMap<>()));editAndSee(resources.resolve("data.impex"));}
    @Test void nestedImpexEditAfterDisable()throws Exception{setup(true,false);assertTrue(change(false,new LinkedHashMap<>()));editAndSee(resources.resolve("data.impex"));}
    @Test void nestedPropertiesEditAfterFailedStage()throws Exception{setup(false,true);assertThrows(IllegalStateException.class,()->change(true,new LinkedHashMap<>()));assertFalse(config.isAutoCompile());editAndSee(resources.resolve("local.properties"));}
    @Test void nestedJavaPackageEditAfterEnable()throws Exception{setup(false,false);assertTrue(change(true,new LinkedHashMap<>()));editAndSee(source.resolve("Changed.java"));}
    @Test void nestedClassPackageEditAfterDisable()throws Exception{setup(true,false);assertTrue(change(false,new LinkedHashMap<>()));editAndSee(classes.resolve("Changed.class"));}
    @Test void fatalStageFailureKeepsOldState()throws Exception {
        setup(false,true);var before=new HashMap<>(keys());assertThrows(IllegalStateException.class,()->change(true,new LinkedHashMap<>()));assertEquals(before,keys());assertTrue(before.keySet().stream().allMatch(WatchKey::isValid));assertFalse(config.isAutoCompile());
    }
    @Test void stageFailureKeepsCommonResourceEvents()throws Exception{setup(false,true);assertThrows(IllegalStateException.class,()->change(true,new LinkedHashMap<>()));editAndSee(resources.resolve("local.properties"));editAndSee(resources.resolve("data.impex"));}
    @Test void commonDirectoryKeepsBaselineAndIdentity()throws Exception {
        setup(false,false);var before=keys().entrySet().stream().filter(e->e.getValue().directory().equals(resources)).findFirst().orElseThrow();var key=before.getKey();var owner=before.getValue();assertTrue(change(true,new LinkedHashMap<>()));assertTrue(key.isValid());assertEquals(owner,keys().get(key));
    }
    @Test void newRootExistingFilesNotReplayed()throws Exception {
        setup(false,false);Files.writeString(source.resolve("Existing.java"),"old");assertTrue(change(true,new LinkedHashMap<>()));var pending=new LinkedHashMap<Path,FileWatcher.PendingEvent>();watcher.scanWatchedDirectories(pending,0);assertTrue(pending.isEmpty(),pending.toString());
    }
    @Test void buildGenerationRejectionCancelsOnlyNewKeys()throws Exception {
        setup(false,false);var before=new HashMap<>(keys());var target=config.liveOptions().with(Map.of("autoCompile",true));assertFalse(watcher.reconfigureNow(target,r->false,()->fail("must not publish"),new LinkedHashMap<>()));assertEquals(before,keys());assertTrue(before.keySet().stream().allMatch(WatchKey::isValid));editAndSee(resources.resolve("still.impex"));
    }
    @Test void editDuringStagingOnNewRootDeliveredAfterCommit()throws Exception {
        setup(false,false);watcher.stopWatching();var platform=new SpringBootContext(config);platform.initialize();Path edited=source.resolve("Changed.java");
        watcher=new FileWatcher(platform,config){@Override WatchKey registerReconfiguredDirectory(Path dir)throws java.io.IOException{WatchKey key=super.registerReconfiguredDirectory(dir);if(dir.equals(source))Files.writeString(edited,"changed while staged");return key;}};
        var events=new CopyOnWriteArrayList<ChangeEvent>();watcher.onFileChange(events::add);thread=new Thread(watcher::startWatching);thread.start();
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!watcher.doctorEvidence().state().equals("watching")&&System.nanoTime()<end)Thread.sleep(10);assertEquals("watching",watcher.doctorEvidence().state());
        var target=config.liveOptions().with(Map.of("autoCompile",true));assertTrue(watcher.requestReconfigure(target,r->{r.run();return true;},()->config.publishLiveOptions(target)).toCompletableFuture().get(5,TimeUnit.SECONDS));
        end=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);while(events.stream().noneMatch(e->e.getPath().equals(edited))&&System.nanoTime()<end)Thread.sleep(20);assertTrue(events.stream().anyMatch(e->e.getPath().equals(edited)));assertTrue(config.isAutoCompile());
    }
    @Test void removedRootPendingEventDelivered()throws Exception {
        setup(false,false);Path file=Files.writeString(classes.resolve("Pending.class"),"old");watcher.prepopulateContentHashes(root.resolve("target/classes"));
        Files.writeString(file,"new");Files.setLastModifiedTime(file,FileTime.fromMillis(System.currentTimeMillis()+2000));var pending=new LinkedHashMap<Path,FileWatcher.PendingEvent>();assertTrue(change(true,pending));assertTrue(pending.containsKey(file),"polling event not yet queued must survive the transition");
        var events=new ArrayList<ChangeEvent>();watcher.onFileChange(events::add);watcher.dispatchEvent(pending.get(file));assertEquals(file,events.get(0).getPath());
    }
    @Test void aliasOfCurrentRootNeverCancelled()throws Exception {
        setup(false,false);var before=new HashMap<>(keys());Files.delete(source);Files.delete(source.getParent());Files.delete(root.resolve("src/main/java"));Files.createSymbolicLink(root.resolve("src/main/java"),root.resolve("src/main/resources"));
        assertTrue(change(true,new LinkedHashMap<>()));for(var entry:before.entrySet())if(entry.getValue().sourceRoot().equals("resources")){assertTrue(entry.getKey().isValid());assertEquals(entry.getValue(),keys().get(entry.getKey()));}
        editAndSee(resources.resolve("alias.impex"));
    }

    void deleteClassOutput()throws Exception {
        try(var paths=Files.walk(root.resolve("target/classes"))){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
    }
    @Test void missingListedOutputRootDoesNotAbortDisable()throws Exception {
        setup(true,false);deleteClassOutput();assertTrue(change(false,new LinkedHashMap<>()));assertFalse(config.isAutoCompile());
        assertTrue(keys().values().stream().noneMatch(owner->owner.sourceRoot().equals("classes")));editAndSee(resources.resolve("after-clean.impex"));
    }
    @Test void deletedDepartingOutputRootDoesNotAbortEnable()throws Exception {
        setup(false,false);deleteClassOutput();assertTrue(change(true,new LinkedHashMap<>()));assertTrue(config.isAutoCompile());
        editAndSee(source.resolve("AfterClean.java"));editAndSee(resources.resolve("after-clean.properties"));
    }
    @Test void unwatchableNewDirectoryIsReportedWhileOthersCommit()throws Exception {
        setup(false,false);var common=keys().entrySet().stream().filter(e->e.getValue().directory().equals(resources)).findFirst().orElseThrow();
        int before=watcher.unwatchableCount();refuseDirectory=true;assertTrue(change(true,new LinkedHashMap<>()));assertTrue(config.isAutoCompile());
        assertEquals(before+1,watcher.unwatchableCount());assertTrue(common.getKey().isValid());assertEquals(common.getValue(),keys().get(common.getKey()));
        assertTrue(keys().values().stream().noneMatch(owner->owner.directory().equals(source)));editAndSee(source.getParent().resolve("Other.java"));editAndSee(resources.resolve("still.impex"));
    }

}
