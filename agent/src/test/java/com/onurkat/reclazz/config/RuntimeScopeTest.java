/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.config;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeScopeTest {
    @Test void scopeAndOptionsAreOneCasPublicationAndRejectAStaleExpectedSnapshot() {
        var config=AgentConfig.parse("watchExtensions=a");var before=config.runtime();
        var names=new HashSet<>(Set.of("a","b"));var scope=new AgentConfig.WatchScope(false,names);names.clear();
        var target=new AgentConfig.Runtime(1,before.options().with(Map.of("autoCompile",true)),scope);
        assertEquals(Set.of("a"),config.getWatchExtensions());assertFalse(config.isAutoCompile());
        assertTrue(config.compareAndSetRuntime(before,target));assertFalse(config.compareAndSetRuntime(before,before));
        assertEquals(Set.of("a","b"),config.getWatchExtensions());assertTrue(config.isAutoCompile());
        assertThrows(UnsupportedOperationException.class,()->scope.names().clear());
    }
    @Test void readersCaptureOnlyCompleteScopeOptionRevisions()throws Exception {
        var config=AgentConfig.parse("watchExtensions=a");var initial=config.runtime();
        var reader=CompletableFuture.runAsync(()->{
            for(int i=0;i<20000;i++){
                var value=config.runtime();boolean enabled=value.options().autoCompile();
                assertEquals(enabled?Set.of("a","b"):Set.of("a"),value.scope().names());
                assertEquals(enabled,value.revision()%2==1);
            }
        });
        for(int i=1;i<=20000;i++){
            var before=config.runtime();boolean enabled=i%2==1;
            assertTrue(config.compareAndSetRuntime(before,new AgentConfig.Runtime(i,
                    initial.options().with(Map.of("autoCompile",enabled)),new AgentConfig.WatchScope(false,enabled?Set.of("a","b"):Set.of("a")))));
        }
        reader.get(5,TimeUnit.SECONDS);
    }
}
