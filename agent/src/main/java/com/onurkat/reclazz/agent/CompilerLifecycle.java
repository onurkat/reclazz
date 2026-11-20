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
    private final com.onurkat.reclazz.config.ScopeFence fence;
    CompilerLifecycle(ReloadQueue queue, FileWatcher watcher, AtomicReference<IncrementalCompiler> holder,
                      Runnable cleanup, Supplier<IncrementalCompiler> factory, Runnable enabled) {
        this(queue,watcher,holder,cleanup,factory,enabled,new com.onurkat.reclazz.config.ScopeFence());
    }
    CompilerLifecycle(ReloadQueue queue, FileWatcher watcher, AtomicReference<IncrementalCompiler> holder,
                      Runnable cleanup, Supplier<IncrementalCompiler> factory, Runnable enabled,
                      com.onurkat.reclazz.config.ScopeFence fence) {
        this.queue=queue;this.watcher=watcher;this.holder=holder;this.cleanup=cleanup;this.factory=factory;this.enabled=enabled;this.fence=fence;
    }
    private AgentConfig runtimeConfig;
    private com.onurkat.reclazz.platform.PlatformContext platform;
    private com.onurkat.reclazz.transform.TransformContext transforms;
    private java.util.function.Supplier<Class<?>[]> loadedClasses;
    private java.util.function.Function<com.onurkat.reclazz.platform.PlatformContext,IncrementalCompiler> scopedFactory;
    private java.util.function.Consumer<com.onurkat.reclazz.platform.PlatformContext> scopedCleanup;
    private AgentConfig.WatchScope compilerScope;
    CompilerLifecycle(ReloadQueue queue,FileWatcher watcher,AtomicReference<IncrementalCompiler> holder,
                      AgentConfig config,com.onurkat.reclazz.platform.PlatformContext platform,
                      com.onurkat.reclazz.transform.TransformContext transforms,java.util.function.Supplier<Class<?>[]> loadedClasses,
                      java.util.function.Function<com.onurkat.reclazz.platform.PlatformContext,IncrementalCompiler> factory,
                      java.util.function.Consumer<com.onurkat.reclazz.platform.PlatformContext> cleanup,Runnable enabled,
                      com.onurkat.reclazz.config.ScopeFence fence) {
        this(queue,watcher,holder,()->{},()->factory.apply(platform),enabled,fence);
        this.runtimeConfig=config;this.platform=platform;this.transforms=transforms;this.loadedClasses=loadedClasses;
        this.scopedFactory=factory;this.scopedCleanup=cleanup;this.compilerScope=holder.get()==null?null:config.runtime().scope();
    }
    CompletionStage<Void> transitionRuntime(String key,AgentConfig.Runtime target,AgentConfig.Publication publish) {
        var result=new CompletableFuture<Void>();
        class Change implements Runnable {
            void enqueue(){try{queue.submitAutomatic(key,"Applying live watch scope",this,true);}
                catch(Throwable failure){fence.reopen();result.completeExceptionally(failure);}}
            @Override public void run(){
                if(result.isDone())return;
                boolean ownsFence=false;
                try {
                    var stamp=queue.controlStamp();if(stamp==null){enqueue();return;}
                    ownsFence=fence.tryCloseOrRegister(this::enqueue);if(!ownsFence)return;
                    var previous=runtimeConfig.runtime();
                    var view=platform instanceof com.onurkat.reclazz.platform.HybrisPlatformContext h
                            ?new com.onurkat.reclazz.platform.ScopeView(h,target.scope()):platform;
                    IncrementalCompiler prepared=holder.get();
                    if(target.options().autoCompile() && (prepared==null || !target.scope().equals(compilerScope))) {
                        prepared=scopedFactory.apply(view);
                        if(prepared==null)throw new IllegalStateException("Compiler factory returned no compiler");
                    }
                    boolean scopeChanged=!target.scope().equals(previous.scope());
                    Class<?>[] census=scopeChanged?loadedClasses.get():new Class<?>[0];
                    var admission=transforms.stageInventory(view,census);
                    int raw=scopeChanged?0:previous.stats().loadedUninstrumented();
                    if(scopeChanged && platform instanceof com.onurkat.reclazz.platform.HybrisPlatformContext h) {
                        var added=new java.util.HashSet<>(target.scope().names());added.removeAll(previous.scope().names());
                        raw=transforms.countRawDefinitions(new com.onurkat.reclazz.platform.ScopeView(h,new AgentConfig.WatchScope(false,added)),census);
                    }
                    final int loadedRaw=raw;final IncrementalCompiler compiler=prepared;
                    boolean enabling=target.options().autoCompile()&&!previous.options().autoCompile();
                    var addedNames=new java.util.HashSet<>(target.scope().names());addedNames.removeAll(previous.scope().names());
                    boolean needsCleanup=target.options().autoCompile()&&(enabling||!addedNames.isEmpty());
                    var cleanupView=!enabling && platform instanceof com.onurkat.reclazz.platform.HybrisPlatformContext h
                            ?new com.onurkat.reclazz.platform.ScopeView(h,new AgentConfig.WatchScope(false,addedNames)):view;
                    boolean committed=watcher.requestReconfigure(view,target.options(),mutation->queue.guardControl(stamp,mutation),
                        ()->{if(needsCleanup)scopedCleanup.accept(cleanupView);},install->{
                            publish.commit(new AgentConfig.Runtime(target.revision(),target.options(),target.scope(),
                                    new AgentConfig.ScopeStats(loadedRaw,watcher.unwatchableCount()),admission),()->{
                                install.run();
                                if(target.options().autoCompile()){holder.set(compiler);compilerScope=target.scope();}
                            });
                        }).toCompletableFuture().get();
                    if(!committed){enqueue();return;}
                    result.complete(null);
                    if(target.options().autoCompile())try{enabled.run();}
                    catch(RuntimeException failure){com.onurkat.reclazz.ui.StatusReporter.warn("Held source retry could not be queued: "+failure.getClass().getSimpleName());}
                }catch(InterruptedException interrupted){Thread.currentThread().interrupt();result.completeExceptionally(interrupted);}
                catch(Throwable failure){result.completeExceptionally(failure instanceof ExecutionException&&failure.getCause()!=null?failure.getCause():failure);}
                finally{if(ownsFence)fence.reopen();}
            }
        }
        new Change().enqueue();return result;
    }

    CompletionStage<Void> transition(String key, AgentConfig.LiveOptions target, Runnable publish) {
        var result=new CompletableFuture<Void>();
        class Change implements Runnable {
            IncrementalCompiler prepared;
            void enqueue(){
                try { queue.submitAutomatic(key,"Applying live compiler settings",this,true); }
                catch(Throwable failure){fence.reopen();result.completeExceptionally(failure);}
            }
            @Override public void run(){
                if(result.isDone())return;
                boolean ownsFence=false;
                try {
                    var stamp=queue.controlStamp();
                    if(stamp==null){enqueue();return;}
                    ownsFence=fence.tryCloseOrRegister(this::enqueue);
                    if(!ownsFence)return; // The last native permit queues one wake; never spin here.
                    if(target.autoCompile()){
                        prepared=holder.get();if(prepared==null)prepared=factory.get();
                        if(prepared==null)throw new IllegalStateException("Compiler factory returned no compiler");
                    }
                    // The reload executor waits, but never holds buildLock while
                    // the watcher stages keys and enters the guarded commit.
                    boolean committed=watcher.requestReconfigure(target,mutation->queue.guardControl(stamp,mutation),()->{
                        if(target.autoCompile()){cleanup.run();holder.set(prepared);}
                        publish.run();
                    }).toCompletableFuture().get();
                    if(!committed){enqueue();return;}
                    result.complete(null);
                    if(target.autoCompile())try { enabled.run(); }
                    catch(RuntimeException failure){com.onurkat.reclazz.ui.StatusReporter.warn("AutoCompile changed; held source retry could not be queued: "+failure.getClass().getSimpleName());}
                } catch(InterruptedException interrupted){Thread.currentThread().interrupt();result.completeExceptionally(interrupted);}
                catch(Throwable failure){result.completeExceptionally(failure instanceof ExecutionException&&failure.getCause()!=null?failure.getCause():failure);}
                finally {if(ownsFence)fence.reopen();}
            }
        }
        new Change().enqueue();return result;
    }
}
