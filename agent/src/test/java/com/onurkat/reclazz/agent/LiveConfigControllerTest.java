/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.agent;
import com.onurkat.reclazz.config.AgentConfig;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class LiveConfigControllerTest {
    static LiveConfigController immediate(AgentConfig config,boolean sap) {
        return new LiveConfigController(config,sap,(key,target,publish)->{publish.run();return CompletableFuture.completedFuture(null);});
    }
    @Test void configGetReturnsEffective() {
        var config=AgentConfig.parse("autoImpex=true,impexAllowRemove=true");
        try(var controller=immediate(config,true)){
            String reply=controller.reply("CONFIG GET one");assertTrue(reply.startsWith("CONFIG one OK rev=0 "));assertTrue(reply.contains("autoImpex=true"));assertTrue(reply.contains("impexAllowRemove=true"));assertTrue(reply.contains("live=autoCompile,autoImpex"));
        }
    }
    @Test void configSetAppliesAllAndRejectsStaleBase() {
        var config=AgentConfig.parse(null);try(var controller=immediate(config,true)){
            assertTrue(controller.reply("CONFIG SET a base=0 autoImpex=true impexAllowRemove=true autoCompile=true").startsWith("CONFIG a OK rev=1 "));
            assertTrue(config.isAutoCompile());assertTrue(config.isAutoImpex());assertTrue(config.isImpexAllowRemove());
            assertTrue(controller.reply("CONFIG SET b base=0 autoImpex=false").startsWith("CONFIG b CONFLICT rev=1 "));assertTrue(config.isAutoImpex());
        }
    }
    @Test void invalidBatchDoesNotPartiallyPublish() {
        var config=AgentConfig.parse(null);try(var controller=immediate(config,true)){
            for(String changes:new String[]{"autoImpex=true autoImpex=false","autoImpex=true unknown=true","autoImpex=true watchExtensions=other","autoImpex=true autoCompile=maybe"}){
                assertTrue(controller.reply("CONFIG SET a base=0 "+changes).contains(" ERROR "));assertFalse(config.isAutoImpex());assertTrue(controller.reply("CONFIG GET b").contains("rev=0"));
            }
        }
    }
    @Test void sapOptionsUnavailableOutsideSap() {
        var config=AgentConfig.parse(null);try(var controller=immediate(config,false)){
            assertTrue(controller.reply("CONFIG SET a base=0 autoImpex=true").contains("ERROR unavailable"));
            assertTrue(controller.reply("CONFIG GET b").contains("live=autoCompile "));assertFalse(config.isAutoImpex());
        }
    }
    @Test void serialSetsSecondGetsConflictAndGetDoesNotBlock()throws Exception {
        var config=AgentConfig.parse(null);var admitted=new CountDownLatch(1);var finish=new CompletableFuture<Void>();var publish=new java.util.concurrent.atomic.AtomicReference<Runnable>();var calls=new AtomicInteger();
        try(var controller=new LiveConfigController(config,true,(key,target,commit)->{calls.incrementAndGet();publish.set(commit);admitted.countDown();return finish;})){
            var first=CompletableFuture.supplyAsync(()->controller.reply("CONFIG SET first base=0 autoCompile=true",20));assertTrue(admitted.await(2,TimeUnit.SECONDS));
            assertTrue(controller.reply("CONFIG GET state").contains("rev=0"));assertTrue(first.get(2,TimeUnit.SECONDS).contains("PENDING rev=0"));
            var second=CompletableFuture.supplyAsync(()->controller.reply("CONFIG SET second base=0 autoImpex=true",2000));
            publish.get().run();finish.complete(null);
            assertTrue(second.get(2,TimeUnit.SECONDS).contains("CONFLICT rev=1"));assertTrue(controller.reply("CONFIG GET final").contains("autoCompile=true"));assertFalse(config.isAutoImpex());assertEquals(1,calls.get());
        }
    }
    @Test void transitionFailureLeavesOptionsAndRevision() {
        var config=AgentConfig.parse(null);try(var controller=new LiveConfigController(config,true,(key,target,publish)->CompletableFuture.failedFuture(new IllegalStateException("compiler unavailable")))){
            assertTrue(controller.reply("CONFIG SET a base=0 autoCompile=true").contains("ERROR failed compiler unavailable"));assertFalse(config.isAutoCompile());assertTrue(controller.reply("CONFIG GET b").contains("rev=0"));
        }
    }
    @Test void allLiveKeysWithWatchFilterStillRejectAtomicallyAsRestartRequired() {
        var config=AgentConfig.parse(null);try(var controller=immediate(config,true)){
            assertTrue(controller.reply("CONFIG SET a base=0 autoCompile=true autoImpex=true impexAllowRemove=true autoUpdateRunningSystem=true autoSolrReindex=true backofficeConfigReset=false watchExtensions=another").contains("ERROR restart_required"));assertFalse(config.isAutoCompile());assertFalse(config.isAutoImpex());
        }
    }
    @Test void watchHashCanonicalizesEmptyMembersButDistinguishesWatchAll() {
        try(var a=immediate(AgentConfig.parse("watchExtensions=one;;two"),true);var b=immediate(AgentConfig.parse("watchExtensions=two;one"),true);var all=immediate(AgentConfig.parse(null),true);var none=immediate(AgentConfig.parse("watchExtensions="),true)){
            java.util.function.Function<String,String> hash=reply->Arrays.stream(reply.split(" ")).filter(field->field.startsWith("watchExtensions.sha256=")).findFirst().orElseThrow();assertEquals(hash.apply(a.reply("CONFIG GET a")),hash.apply(b.reply("CONFIG GET b")));assertNotEquals(hash.apply(all.reply("CONFIG GET all")),hash.apply(none.reply("CONFIG GET none")));
        }
    }

}
