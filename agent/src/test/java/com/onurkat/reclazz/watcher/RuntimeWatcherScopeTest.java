/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.config.*;
import com.onurkat.reclazz.platform.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeWatcherScopeTest {
    @TempDir Path home;
    AgentConfig config;HybrisPlatformContext platform;FileWatcher watcher;PropertyFileSnapshots snapshots;
    private static void field(Object target,String name,Object value)throws Exception {
        var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    @SuppressWarnings("unchecked") private <T>T value(String name)throws Exception {
        var f=FileWatcher.class.getDeclaredField(name);f.setAccessible(true);return (T)f.get(watcher);
    }
    void setup()throws Exception {
        Path nativePlatform=Files.createDirectories(home.resolve("bin/platform"));Files.createDirectories(home.resolve("config"));
        Map<String,ExtensionInfo> extensions=new LinkedHashMap<>();
        for(String name:List.of("a","b")){
            Path dir=home.resolve("bin/custom").resolve(name);
            for(String root:List.of("classes","src/com/example/constants","resources"))Files.createDirectories(dir.resolve(root));
            Files.writeString(dir.resolve("project.properties"),"example.value=v1\n");
            extensions.put(name,new ExtensionInfo(name,dir,List.of(),true,false,true,false,"Example","com.example"));
            Files.writeString(dir.resolve("src/com/example/constants/ExampleConstants.java"),"old");
        }
        var nativeContext=new HybrisContext(home);field(nativeContext,"platformHome",nativePlatform);field(nativeContext,"configDir",home.resolve("config"));field(nativeContext,"extensions",extensions);
        config=AgentConfig.parse("watchExtensions=a,startupDelaySec=0");platform=new HybrisPlatformContext(home,config);field(platform,"hybrisContext",nativeContext);
        snapshots=new PropertyFileSnapshots();watcher=new FileWatcher(platform,config);watcher.onPropertySnapshots(snapshots);watcher.onPropertyFileFound(snapshots::baseline);
        var register=FileWatcher.class.getDeclaredMethod("registerDirectories");register.setAccessible(true);register.invoke(watcher);
    }
    Path file(String module,String name){return home.resolve("bin/custom").resolve(module).resolve(name);}
    boolean change(Set<String> names,boolean auto,java.util.function.Predicate<Runnable> guard,Runnable cleanup)throws Exception {
        var old=config.runtime();var scope=new AgentConfig.WatchScope(false,names);var options=old.options().with(Map.of("autoCompile",auto));
        return watcher.reconfigureNow(new ScopeView(platform,scope),options,guard,cleanup,install->config.publishRuntime(old,new AgentConfig.Runtime(old.revision()+1,options,scope),install),new LinkedHashMap<>());
    }
    @AfterEach void close(){if(watcher!=null)watcher.stopWatching();ApplicationContextHolder.clear();}
    @Test void newlySubscribedPropertiesAreBaselinedAtCommitAndFirstEditIsADiff()throws Exception {
        setup();Path b=file("b","project.properties");assertNull(snapshots.current(b));
        assertTrue(change(Set.of("a","b"),false,mutation->{assertNull(snapshots.current(b));assertEquals(Set.of("a"),config.getWatchExtensions());mutation.run();return true;},()->{}));
        assertEquals(Map.of("example.value","v1"),snapshots.current(b));
        Files.writeString(b,"example.value=v2\n");Files.setLastModifiedTime(b,FileTime.fromMillis(System.currentTimeMillis()+5000));
        var pending=new LinkedHashMap<Path,FileWatcher.PendingEvent>();assertEquals(1,watcher.scanConfigurationFiles(pending));assertTrue(pending.containsKey(b));
        assertEquals(Map.of("example.value","v2"),snapshots.pending(b).changed());
    }
    @Test void rejectedStageKeepsKeysSnapshotsAndConstantsAndNeverCleans()throws Exception {
        setup();Map<WatchKey,FileWatcher.WatchedDirectory> keys=new HashMap<>(value("watchKeyMap"));
        assertFalse(change(Set.of("a","b"),true,mutation->false,()->fail("cleanup on rejected stage")));
        assertEquals(keys,value("watchKeyMap"));assertTrue(keys.keySet().stream().allMatch(WatchKey::isValid));
        assertNull(snapshots.current(file("b","project.properties")));assertEquals(Set.of(),value("constantsSources"));assertEquals(Set.of("a"),config.getWatchExtensions());
    }
    @Test void cleanupRunsAfterDepartingCancellationAndBeforeNewKeysAndPublication()throws Exception {
        setup();Map<WatchKey,FileWatcher.WatchedDirectory> old=new HashMap<>(value("watchKeyMap"));
        assertTrue(change(Set.of("b"),false,mutation->{mutation.run();return true;},()->{
            assertTrue(old.keySet().stream().noneMatch(WatchKey::isValid));assertEquals(Set.of("a"),config.getWatchExtensions());
            try{Map<WatchKey,FileWatcher.WatchedDirectory> installed=value("watchKeyMap");assertTrue(installed.isEmpty());}catch(Exception failure){throw new AssertionError(failure);}
        }));
        assertNull(snapshots.current(file("a","project.properties")));assertNotNull(snapshots.current(file("b","project.properties")));
    }
    @Test void addConstantsPreservesCommonBaselineAndRemovalDropsPendingEvents()throws Exception {
        setup();assertTrue(change(Set.of("a"),true,mutation->{mutation.run();return true;},()->{}));
        Map<Path,?> before=value("fileStates");Path a=file("a","src/com/example/constants/ExampleConstants.java");Object state=before.get(a);assertNotNull(state);
        assertTrue(change(Set.of("a","b"),true,mutation->{mutation.run();return true;},()->{}));
        assertSame(state,((Map<?,?>)value("fileStates")).get(a));assertEquals(Set.of(a,file("b","src/com/example/constants/ExampleConstants.java")),value("constantsSources"));
        assertTrue(change(Set.of("b"),true,mutation->{mutation.run();return true;},()->{}));
        var events=new ArrayList<ChangeEvent>();watcher.onFileChange(events::add);
        watcher.dispatchEvent(new FileWatcher.PendingEvent(System.currentTimeMillis(),a,ChangeEvent.Type.MODIFIED,"a","src"));assertTrue(events.isEmpty());
    }
    @Test void readdingAPropertyFileBaselinesDisabledPeriodEdits()throws Exception {
        setup();Path a=file("a","project.properties");assertTrue(change(Set.of("b"),false,mutation->{mutation.run();return true;},()->{}));
        Files.writeString(a,"example.value=disabled\n");assertTrue(change(Set.of("a","b"),false,mutation->{mutation.run();return true;},()->{}));
        assertTrue(snapshots.pending(a).changed().isEmpty());assertEquals("disabled",snapshots.current(a).get("example.value"));
        Files.writeString(a,"example.value=enabled\n");assertEquals(Map.of("example.value","enabled"),snapshots.pending(a).changed());
    }
    @Test void physicalAliasPreservesUnacceptedCommonPropertyEdit()throws Exception {
        setup();Path a=file("a","project.properties"),b=file("b","project.properties");Files.delete(b);Files.createSymbolicLink(b,a);
        Files.writeString(a,"example.value=pending\n");Files.setLastModifiedTime(a,FileTime.fromMillis(System.currentTimeMillis()+5000));
        assertTrue(change(Set.of("b"),false,mutation->{mutation.run();return true;},()->{}));
        assertEquals(Map.of("example.value","v1"),snapshots.current(b));assertEquals(Map.of("example.value","pending"),snapshots.pending(b).changed());
        var pending=new LinkedHashMap<Path,FileWatcher.PendingEvent>();assertEquals(1,watcher.scanConfigurationFiles(pending));assertTrue(pending.containsKey(b));
    }
    @Test void loadedExternalXmlReaddDoesNotReplayDisabledEditAndNextEditIsDelivered()throws Exception {
        setup();Path xml=file("b","external/loaded.xml");Files.createDirectories(xml.getParent());
        Files.writeString(xml,"<beans xmlns='http://www.springframework.org/schema/beans'/>");
        var context=new org.springframework.context.support.GenericApplicationContext();
        try {
            var reader=new org.springframework.beans.factory.xml.XmlBeanDefinitionReader(context);reader.setValidating(false);
            var resource=new org.springframework.core.io.FileSystemResource(xml);reader.loadBeanDefinitions(resource);SpringXmlResources.record(reader,resource);context.refresh();ApplicationContextHolder.register(context);
            assertTrue(change(Set.of("a","b"),false,mutation->{mutation.run();return true;},()->{}));
            long first=Files.getLastModifiedTime(xml).toMillis();Files.setLastModifiedTime(xml,FileTime.fromMillis(first+5000));
            var pending=new LinkedHashMap<Path,FileWatcher.PendingEvent>();assertEquals(1,watcher.scanLoadedSpringXml(pending));assertTrue(pending.containsKey(xml.toAbsolutePath().normalize()));
            assertTrue(change(Set.of("a"),false,mutation->{mutation.run();return true;},()->{}));
            Files.setLastModifiedTime(xml,FileTime.fromMillis(first+10000));assertEquals(0,watcher.scanLoadedSpringXml(new LinkedHashMap<>()));
            assertTrue(change(Set.of("a","b"),false,mutation->{mutation.run();return true;},()->{}));assertEquals(0,watcher.scanLoadedSpringXml(new LinkedHashMap<>()));
            Files.setLastModifiedTime(xml,FileTime.fromMillis(first+15000));assertEquals(1,watcher.scanLoadedSpringXml(new LinkedHashMap<>()));
        }finally{context.close();}
    }
    @Test void unreadableNewPropertySnapshotRejectsBeforeAnyCleanup()throws Exception {
        setup();Files.writeString(file("b","project.properties"),"invalid=\\uZZZZ\n");var old=config.runtime();Map<WatchKey,FileWatcher.WatchedDirectory> keys=new HashMap<>(value("watchKeyMap"));
        assertThrows(java.io.IOException.class,()->change(Set.of("a","b"),true,mutation->{mutation.run();return true;},()->fail("cleanup before readable baseline")));
        assertSame(old,config.runtime());assertEquals(keys,value("watchKeyMap"));assertNull(snapshots.current(file("b","project.properties")));
    }

    @Test void staleRuntimePublicationPreservesKeysSnapshotsAndDelivery()throws Exception {
        setup();Map<WatchKey,FileWatcher.WatchedDirectory> old=new HashMap<>(value("watchKeyMap"));
        var expected=config.runtime();var scope=new AgentConfig.WatchScope(false,Set.of("b"));
        var next=new AgentConfig.Runtime(expected.revision()+1,expected.options(),scope);
        config.publishLiveOptions(expected.options().with(Map.of("autoImpex",true)));
        var current=config.runtime();
        assertThrows(IllegalStateException.class,()->watcher.reconfigureNow(new ScopeView(platform,scope),next.options(),
                mutation->{mutation.run();return true;},()->fail("cleanup after refused revision"),
                install->config.publishRuntime(expected,next,install),new LinkedHashMap<>()));
        assertSame(current,config.runtime());assertEquals(old,value("watchKeyMap"));assertTrue(old.keySet().stream().allMatch(WatchKey::isValid));
        assertNull(snapshots.current(file("b","project.properties")));assertNotNull(snapshots.current(file("a","project.properties")));
        Path source=file("a","classes/StillWatched.class");Files.write(source,new byte[]{1});
        WatchKey key=old.entrySet().stream().filter(e->e.getValue().directory().equals(source.getParent())).findFirst().orElseThrow().getKey();
        boolean delivered=false;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while(!delivered&&System.nanoTime()<deadline){delivered=key.pollEvents().stream().anyMatch(e->e.context().equals(source.getFileName()));if(!delivered)Thread.sleep(10);}
        assertTrue(delivered,"old native key must still deliver after revision refusal");
    }

}
