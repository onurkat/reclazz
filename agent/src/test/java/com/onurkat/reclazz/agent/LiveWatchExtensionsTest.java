/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.config.AgentConfig;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class LiveWatchExtensionsTest {
    @Test void namedScopeAndBooleansPublishTogetherWithOneRevisionAndReceipt(){
        var config=AgentConfig.parse("watchExtensions=a");var calls=new AtomicInteger();
        try(var controller=new LiveConfigController(config,Set.of("a","b"),(key,target,publish)->{
            calls.incrementAndGet();assertEquals(Set.of("a"),config.getWatchExtensions());assertFalse(config.isAutoCompile());
            assertEquals(Set.of("a","b"),target.scope().names());assertTrue(target.options().autoCompile());
            publish.accept(new AgentConfig.Runtime(target.revision(),target.options(),target.scope(),new AgentConfig.ScopeStats(2,1),target.inventory()));return CompletableFuture.completedFuture(null);
        })){
            String result=controller.reply("CONFIG SET one base=0 watchExtensions=b;a;b autoCompile=true");
            assertTrue(result.startsWith("CONFIG one OK rev=1 "));assertTrue(result.contains("loadedUninstrumented=2 unwatchable=1"));
            assertTrue(result.contains("watchExtensions.count=2"));assertTrue(result.contains(",watchExtensions"));
            assertEquals(1,calls.get());assertEquals(1,config.runtime().revision());assertEquals(Set.of("a","b"),config.getWatchExtensions());
            assertTrue(controller.doctorOptions().contains("\"loadedUninstrumented\":2,\"unwatchable\":1"));
        }
    }
    @Test void invalidOrStaleScopeDoesNotStageOrPartiallyChangeBooleans(){
        var config=AgentConfig.parse("watchExtensions=a");var calls=new AtomicInteger();
        try(var controller=new LiveConfigController(config,Set.of("a","b"),(key,target,publish)->{calls.incrementAndGet();fail("invalid transaction staged");return CompletableFuture.completedFuture(null);})){
            for(String options:List.of("autoCompile=true watchExtensions=unknown","autoCompile=true watchExtensions=*","watchExtensions=b watchExtensions=a","autoCompile=maybe watchExtensions=b")){
                assertTrue(controller.reply("CONFIG SET bad base=0 "+options).contains(" ERROR "));
                assertFalse(config.isAutoCompile());assertEquals(Set.of("a"),config.getWatchExtensions());assertEquals(0,config.runtime().revision());
            }
            assertTrue(controller.reply("CONFIG SET stale base=4 watchExtensions=b autoCompile=true").contains(" CONFLICT rev=0 "));assertEquals(0,calls.get());
        }
    }
    @Test void watchAllTransitionStillRequiresRestart(){
        var config=AgentConfig.parse(null);
        try(var controller=new LiveConfigController(config,Set.of("a"),(key,target,publish)->{fail("all-mode transition");return CompletableFuture.completedFuture(null);})){
            assertTrue(controller.reply("CONFIG SET one base=0 watchExtensions=a autoCompile=true").contains("ERROR restart_required"));assertFalse(config.isAutoCompile());assertTrue(config.isWatchAllExtensions());
        }
    }
    @Test void rejectedStageKeepsScopeOptionsRevisionAndEvidence(){
        var config=AgentConfig.parse("watchExtensions=a");
        try(var controller=new LiveConfigController(config,Set.of("a","b"),(key,target,publish)->CompletableFuture.failedFuture(new IllegalStateException("stage unavailable")))){
            assertTrue(controller.reply("CONFIG SET one base=0 watchExtensions=b autoCompile=true").contains("ERROR failed stage unavailable"));
            assertEquals(Set.of("a"),config.getWatchExtensions());assertFalse(config.isAutoCompile());assertEquals(0,config.runtime().revision());assertEquals(AgentConfig.ScopeStats.EMPTY,config.runtime().stats());
        }
    }
    @Test void invalidStagedRevisionCannotInvokeInstaller(){
        var config=AgentConfig.parse("watchExtensions=a");var installed=new AtomicBoolean();
        try(var controller=new LiveConfigController(config,Set.of("a","b"),(key,target,publish)->{
            publish.commit(new AgentConfig.Runtime(target.revision()+1,target.options(),target.scope()),()->installed.set(true));
            return CompletableFuture.completedFuture(null);
        })){
            assertTrue(controller.reply("CONFIG SET invalid base=0 watchExtensions=b autoCompile=true").contains(" ERROR failed "));
            assertFalse(installed.get());assertFalse(config.isAutoCompile());assertEquals(Set.of("a"),config.getWatchExtensions());assertEquals(0,config.runtime().revision());
        }
    }

}
