/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.compiler.IncrementalCompiler;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.watcher.FileWatcher;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** One compiler holder; a transition owns no BUILD, pause or generated-output hold. */
final class CompilerLifecycle {
    private final ReloadQueue queue;
    private final FileWatcher watcher;
    private final AtomicReference<IncrementalCompiler> holder;
    private final Runnable cleanup;
    private final Supplier<IncrementalCompiler> factory;
    private final Runnable enabled;
    CompilerLifecycle(ReloadQueue queue, FileWatcher watcher, AtomicReference<IncrementalCompiler> holder,
                      Runnable cleanup, Supplier<IncrementalCompiler> factory, Runnable enabled) {
        this.queue=queue;this.watcher=watcher;this.holder=holder;this.cleanup=cleanup;this.factory=factory;this.enabled=enabled;
    }
    CompletionStage<Void> transition(String key, AgentConfig.LiveOptions target, Runnable publish) {
        var result=new CompletableFuture<Void>();
        class Change implements Runnable {
            IncrementalCompiler prepared;
            void enqueue(){queue.submitAutomatic(key,"Applying live compiler settings",this,true);}
            @Override public void run(){
                if(result.isDone())return;
                try {
                    var stamp=queue.controlStamp();
                    if(stamp==null){enqueue();return;}
                    if(target.autoCompile()){
                        if(!queue.guardControl(stamp,cleanup)){enqueue();return;}
                        prepared=holder.get();if(prepared==null)prepared=factory.get();
                        if(prepared==null)throw new IllegalStateException("Compiler factory returned no compiler");
                    }
                    // The reload executor waits, but never holds buildLock while
                    // the watcher stages keys and enters the guarded commit.
                    boolean committed=watcher.requestReconfigure(target,mutation->queue.guardControl(stamp,mutation),()->{
                        if(target.autoCompile())holder.set(prepared);
                        publish.run();
                    }).toCompletableFuture().get();
                    if(!committed){enqueue();return;}
                    result.complete(null);
                    if(target.autoCompile())try { enabled.run(); }
                    catch(RuntimeException failure){com.onurkat.reclazz.ui.StatusReporter.warn("AutoCompile changed; held source retry could not be queued: "+failure.getClass().getSimpleName());}
                } catch(InterruptedException interrupted){Thread.currentThread().interrupt();result.completeExceptionally(interrupted);}
                catch(Throwable failure){result.completeExceptionally(failure instanceof ExecutionException&&failure.getCause()!=null?failure.getCause():failure);}
            }
        }
        new Change().enqueue();return result;
    }
}
