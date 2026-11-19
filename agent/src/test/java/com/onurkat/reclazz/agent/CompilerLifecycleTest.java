/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.agent;
import com.onurkat.reclazz.compiler.IncrementalCompiler;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.NoopPlatformContext;
import com.onurkat.reclazz.watcher.FileWatcher;
import org.junit.jupiter.api.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
class CompilerLifecycleTest {
    final List<FileWatcher> watchers=new ArrayList<>();
    @AfterEach void close(){watchers.forEach(FileWatcher::stopWatching);}
    class Fixture {
        final ReloadBuildTest.Fixture work=new ReloadBuildTest.Fixture();
        final AgentConfig config=AgentConfig.parse(null);
        final AtomicReference<IncrementalCompiler> holder=new AtomicReference<>();
        final AtomicInteger cleanups=new AtomicInteger(), builds=new AtomicInteger(), commits=new AtomicInteger(), retries=new AtomicInteger();
        Runnable beforeCommit=()->{};Supplier<IncrementalCompiler> factory=()->{builds.incrementAndGet();return new IncrementalCompiler("",new NoopPlatformContext());};
        final FileWatcher watcher;
        final CompilerLifecycle lifecycle;
        Fixture()throws Exception {
            watcher=new FileWatcher(new NoopPlatformContext(),config){
                @Override public CompletionStage<Boolean> requestReconfigure(AgentConfig.LiveOptions target,Predicate<Runnable> guard,Runnable publish){
                    beforeCommit.run();return CompletableFuture.completedFuture(guard.test(()->{commits.incrementAndGet();publish.run();}));
                }
            };watchers.add(watcher);
            lifecycle=new CompilerLifecycle(work.queue,watcher,holder,cleanups::incrementAndGet,()->factory.get(),retries::incrementAndGet);
        }
        CompletableFuture<Void> enable(){var target=config.liveOptions().with(Map.of("autoCompile",true));return lifecycle.transition("config-test",target,()->config.publishLiveOptions(target)).toCompletableFuture();}
    }
    @Test void enableWaitsForBuildOkThenApplies()throws Exception {
        var f=new Fixture();assertTrue(f.work.queue.build("owner","started",()->{}));var done=f.enable();f.work.run();assertFalse(done.isDone());assertEquals(0,f.cleanups.get());assertEquals(0,f.commits.get());assertFalse(f.config.isAutoCompile());
        assertTrue(f.work.queue.build("owner","ok",()->{}));f.work.run();done.join();assertTrue(f.config.isAutoCompile());assertNotNull(f.holder.get());assertEquals(1,f.cleanups.get());assertEquals(1,f.commits.get());assertEquals(1,f.retries.get());
    }
    @Test void buildFailedKeepsEnableHeld()throws Exception {
        var f=new Fixture();f.work.queue.build("owner","started",()->{});var done=f.enable();f.work.queue.build("owner","failed",()->{});f.work.run();assertFalse(done.isDone());assertEquals(0,f.cleanups.get());assertEquals("named",f.work.queue.buildHoldKind());
    }
    @Test void otherOwnerBuildOkDoesNotRelease()throws Exception {
        var f=new Fixture();f.work.queue.build("owner","started",()->{});var done=f.enable();assertFalse(f.work.queue.build("other","ok",()->{}));f.work.run();assertFalse(done.isDone());assertEquals(0,f.cleanups.get());
    }
    @Test void newerBuildGenerationDuringEnableRequeues()throws Exception {
        var f=new Fixture();var once=new AtomicBoolean();f.beforeCommit=()->{if(once.compareAndSet(false,true))f.work.queue.build("owner","started",()->{});};
        var done=f.enable();f.work.run();assertFalse(done.isDone());assertEquals(0,f.commits.get());assertNull(f.holder.get());assertFalse(f.config.isAutoCompile());
        f.work.queue.build("owner","ok",()->{});f.work.run();done.join();assertEquals(1,f.commits.get());assertTrue(f.config.isAutoCompile());assertEquals(2,f.cleanups.get());
    }
    @Test void enableWaitsDuringManualPause()throws Exception {
        var f=new Fixture();f.work.queue.control("pause",()->{});var done=f.enable();f.work.run();assertFalse(done.isDone());assertEquals(0,f.cleanups.get());f.work.queue.control("resume",()->{});f.work.run();done.join();assertTrue(f.config.isAutoCompile());
    }
    @Test void ensureFailureLeavesOptionsUnchanged()throws Exception {
        var f=new Fixture();f.factory=()->{throw new IllegalStateException("factory unavailable");};var done=f.enable();f.work.run();assertThrows(CompletionException.class,done::join);assertNull(f.holder.get());assertFalse(f.config.isAutoCompile());assertEquals(0,f.commits.get());
    }
    @Test void falseStartEnableCompilesThenHeldRetryRuns()throws Exception {
        var f=new Fixture();var generated=f.work.queue.holdGeneratedClasses(List.of(Path.of("/generated")),()->{});var done=f.enable();f.work.run();assertFalse(done.isDone());assertEquals(0,f.cleanups.get());assertTrue(f.work.queue.generatedOutputHeld());
        generated.compiled(()->{});f.work.run();done.join();assertFalse(f.work.queue.generatedOutputHeld());assertTrue(f.config.isAutoCompile());assertEquals(1,f.retries.get());
    }
    @Test void disableKeepsCompilerForNextEnable()throws Exception {
        var f=new Fixture();var first=f.enable();f.work.run();first.join();var compiler=f.holder.get();
        var target=f.config.liveOptions().with(Map.of("autoCompile",false));var off=f.lifecycle.transition("off",target,()->f.config.publishLiveOptions(target));f.work.run();off.toCompletableFuture().join();assertFalse(f.config.isAutoCompile());assertSame(compiler,f.holder.get());
        var on=f.enable();f.work.run();on.join();assertSame(compiler,f.holder.get());assertEquals(1,f.builds.get());
    }
    @Test void stoppedQueueCannotRunDestructivePreparation()throws Exception {
        var f=new Fixture();f.work.queue.holdGeneratedClasses(List.of(Path.of("/generated")),()->{});var done=f.enable();f.work.run();assertFalse(done.isDone());f.work.queue.shutdown();f.work.run();assertTrue(done.isCompletedExceptionally());assertEquals(0,f.cleanups.get());assertEquals(0,f.commits.get());
    }

}
