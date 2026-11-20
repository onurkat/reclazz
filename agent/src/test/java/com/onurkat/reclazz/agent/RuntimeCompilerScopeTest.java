/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.agent;
import com.onurkat.reclazz.compiler.IncrementalCompiler;
import com.onurkat.reclazz.config.*;
import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.transform.*;
import com.onurkat.reclazz.watcher.FileWatcher;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeCompilerScopeTest {
    final List<FileWatcher> watchers=new ArrayList<>();
    @AfterEach void close(){watchers.forEach(FileWatcher::stopWatching);}
    class Fixture {
        final ReloadBuildTest.Fixture work=new ReloadBuildTest.Fixture();
        final AgentConfig config=AgentConfig.parse("watchExtensions=a");final PlatformContext platform;
        final AtomicReference<IncrementalCompiler> holder=new AtomicReference<>();
        final AtomicInteger factories=new AtomicInteger(),cleanups=new AtomicInteger(),inventories=new AtomicInteger(),censuses=new AtomicInteger();
        final List<PlatformContext> cleanupViews=new ArrayList<>();
        final ScopeFence fence=new ScopeFence();boolean failFactory,failInventory;
        final CompilerLifecycle lifecycle;
        Fixture()throws Exception {this(new NoopPlatformContext());}
        Fixture(PlatformContext platform)throws Exception {
            this.platform=platform;
            var transforms=new TransformContext(){@Override public WatchInventory stageInventory(PlatformContext view,Class<?>[] loaded)throws java.io.IOException {
                inventories.incrementAndGet();if(failInventory)throw new java.io.IOException("failed pure inventory");return super.stageInventory(view,loaded);
            }};
            var watcher=new FileWatcher(platform,config){@Override public CompletionStage<Boolean> requestReconfigure(PlatformContext view,AgentConfig.LiveOptions target,java.util.function.Predicate<Runnable> guard,Runnable cleanup,java.util.function.Consumer<Runnable> publish){
                return CompletableFuture.completedFuture(guard.test(()->publish.accept(cleanup)));
            }};watchers.add(watcher);
            lifecycle=new CompilerLifecycle(work.queue,watcher,holder,config,platform,transforms,()->{censuses.incrementAndGet();return new Class<?>[0];},view->{
                factories.incrementAndGet();if(failFactory)throw new IllegalStateException("failed pure factory");return new IncrementalCompiler("",platform);
            },view->{cleanups.incrementAndGet();cleanupViews.add(view);},()->{},fence);
        }
        CompletableFuture<Void> change(Set<String> names,boolean auto){
            var old=config.runtime();var target=new AgentConfig.Runtime(old.revision()+1,old.options().with(Map.of("autoCompile",auto)),new AgentConfig.WatchScope(false,names));
            return lifecycle.transitionRuntime("test",target,(next,install)->config.publishRuntime(old,next,install)).toCompletableFuture();
        }
        void complete(CompletableFuture<Void> done){work.run();done.join();}
    }
    @Test void scopeChangeWhileDisabledRebuildsCompilerOnNextEnableButSameScopeReusesIt()throws Exception {
        var f=new Fixture();f.complete(f.change(Set.of("a"),true));var a=f.holder.get();assertEquals(1,f.factories.get());
        f.complete(f.change(Set.of("a"),false));f.complete(f.change(Set.of("a"),true));assertSame(a,f.holder.get());assertEquals(1,f.factories.get());
        f.complete(f.change(Set.of("b"),false));assertSame(a,f.holder.get());assertEquals(1,f.factories.get());
        f.complete(f.change(Set.of("b"),true));assertNotSame(a,f.holder.get());assertEquals(2,f.factories.get());assertEquals(1,f.censuses.get());
    }
    @Test void mixedScopeFactoryFailureCannotClearEvidenceOrPublish()throws Exception {
        var f=new Fixture();f.failFactory=true;var old=f.config.runtime();var done=f.change(Set.of("a","b"),true);f.work.run();assertThrows(CompletionException.class,done::join);
        assertSame(old,f.config.runtime());assertNull(f.holder.get());assertEquals(0,f.cleanups.get());assertEquals(0,f.inventories.get());
        try(var permit=f.fence.tryEnterRun(()->fail("fence leaked"))){assertNotNull(permit);}
    }
    @Test void mixedScopeInventoryFailureCannotInstallCompilerOrRunCleanup()throws Exception {
        var f=new Fixture();f.failInventory=true;var old=f.config.runtime();var done=f.change(Set.of("a","b"),true);f.work.run();assertThrows(CompletionException.class,done::join);
        assertSame(old,f.config.runtime());assertNull(f.holder.get());assertEquals(1,f.factories.get());assertEquals(1,f.inventories.get());assertEquals(0,f.cleanups.get());
    }
    @Test void codegenPermitKeepsMixedTransitionDormantUntilOneWake()throws Exception {
        var f=new Fixture();var permit=f.fence.tryEnterRun(()->fail("already admitted"));var done=f.change(Set.of("a","b"),true);
        for(int i=0;i<10;i++)f.work.run();assertFalse(done.isDone());assertEquals(0,f.factories.get());assertEquals(0,f.inventories.get());assertEquals(0,f.censuses.get());
        permit.close();f.complete(done);assertEquals(1,f.factories.get());assertEquals(1,f.inventories.get());assertEquals(1,f.cleanups.get());assertEquals(Set.of("a","b"),f.config.getWatchExtensions());
        f.work.run();assertEquals(1,f.inventories.get());
    }
    @Test void unchangedScopeCompileToggleKeepsRawDefinitionEvidence()throws Exception {
        var f=new Fixture();var old=f.config.runtime();assertTrue(f.config.compareAndSetRuntime(old,new AgentConfig.Runtime(old.revision(),old.options(),old.scope(),new AgentConfig.ScopeStats(9,0))));
        f.complete(f.change(Set.of("a"),true));assertEquals(9,f.config.runtime().stats().loadedUninstrumented());
    }
    @Test void growingAnEnabledScopeRunsCleanupButRemovalDoesNot()throws Exception {
        var f=new Fixture();f.complete(f.change(Set.of("a"),true));assertEquals(1,f.cleanups.get());
        f.complete(f.change(Set.of("a","b"),true));assertEquals(2,f.cleanups.get());
        f.complete(f.change(Set.of("b"),true));assertEquals(2,f.cleanups.get());
    }

    @Test void failedPublicationDoesNotInstallPreparedCompilerOrClean()throws Exception {
        var f=new Fixture();var previous=f.config.runtime();
        var target=new AgentConfig.Runtime(previous.revision()+1,previous.options().with(Map.of("autoCompile",true)),new AgentConfig.WatchScope(false,Set.of("a","b")));
        var done=f.lifecycle.transitionRuntime("refused",target,(next,install)->{throw new IllegalStateException("publication refused");}).toCompletableFuture();
        f.work.run();assertThrows(CompletionException.class,done::join);
        assertSame(previous,f.config.runtime());assertNull(f.holder.get());assertEquals(0,f.cleanups.get());
        try(var permit=f.fence.tryEnterRun(()->fail("fence leaked"))){assertNotNull(permit);}
    }

    @Test void growingScopeCleansOnlyAddedExtensions(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home)throws Exception {
        var nativeContext=new HybrisContext(home);
        var rootField=HybrisContext.class.getDeclaredField("platformHome");rootField.setAccessible(true);rootField.set(nativeContext,home.resolve("bin/platform"));
        rootField=HybrisContext.class.getDeclaredField("configDir");rootField.setAccessible(true);rootField.set(nativeContext,home.resolve("config"));
        var extensions=new LinkedHashMap<String,ExtensionInfo>();
        for(String name:List.of("a","b","c"))extensions.put(name,new ExtensionInfo(name,home.resolve(name),List.of(),true,false,true));
        var field=HybrisContext.class.getDeclaredField("extensions");field.setAccessible(true);field.set(nativeContext,extensions);
        var platform=new HybrisPlatformContext(home,AgentConfig.parse("watchExtensions=a"));
        field=HybrisPlatformContext.class.getDeclaredField("hybrisContext");field.setAccessible(true);field.set(platform,nativeContext);
        var f=new Fixture(platform);f.complete(f.change(Set.of("a"),true));f.complete(f.change(Set.of("a","b"),true));
        assertEquals(2,f.cleanupViews.size());var added=f.cleanupViews.get(1);
        assertFalse(added.shouldWatch("a"));assertTrue(added.shouldWatch("b"));assertFalse(added.shouldWatch("c"));
    }

}
