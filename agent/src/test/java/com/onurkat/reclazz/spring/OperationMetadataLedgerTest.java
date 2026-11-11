/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.interceptor.CacheableOperation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static com.onurkat.reclazz.spring.OperationMetadataSchedulingTest.*;

class OperationMetadataLedgerTest {
    @BeforeEach @AfterEach void clearLedger() { RestartLedger.clear(); }
    private static String digest() { return String.join("\n",RestartLedger.digest()); }
    private static void poison(Rig r) throws Exception {
        r.cache.getCacheOperations(A.class.getMethod("read"),A.class);
        Map<Object,Object> old=map(r.cache);Object key=old.keySet().iterator().next();
        var b=new CacheableOperation.Builder();b.setCacheName("items");b.setCondition("true");b.setUnless("");
        Map<Object,Object> bad=new ConcurrentHashMap<>(old) {@Override public void clear(){}};
        bad.put(key,List.of(b.build()));replaceMap(r.cache,bad);
    }
    @Test void warnAlsoNotesRestartLedger() throws Exception {
        try(Rig r=new Rig()) {poison(r);r.reload(A.class);
            assertTrue(digest().contains("cache metadata may still be stale (annotation/source mismatch at read)"),digest());}
    }
    @Test void laterMatchResolvesMismatchNote() throws Exception {
        for(boolean delayed:List.of(false,true)) {
            RestartLedger.clear();
            try(Rig r=new Rig()) {
                poison(r);r.reload(A.class);assertTrue(digest().contains("mismatch"),digest());
                RestartLedger.note(A.class.getName(),"unrelated reason");
                replaceMap(r.cache,new ConcurrentHashMap<>());
                if(delayed)r.fire();else r.reload(A.class);
                assertFalse(digest().contains("mismatch"),digest());
                assertTrue(digest().contains("unrelated reason"),digest());
            }
        }
    }
    @Test void unsupportedRepeatDoesNotAddLedgerNotes() {
        try(Rig r=new Rig()) {
            r.reloader.reloadOperationSources(A.class,true,Set.of(),null,true);r.fire();
            r.reloader.reloadOperationSources(A.class,true,Set.of(),null,true);r.fire();
            assertTrue(digest().contains("companion operation metadata"),digest());
            assertEquals(1,RestartLedger.size(),digest());assertFalse(digest().contains("times"),digest());
            r.reload(A.class);assertFalse(digest().contains("companion operation metadata"),digest());
        }
    }
    @Test void repairQueueFailureIsRememberedAndLaterMatchResolvesIt() {
        try(Rig r=new Rig()) {
            r.reloader.setScheduler((work,delay)->{r.timers.add(work);return ()->{};},work->{throw new IllegalStateException("closed queue");});
            r.reload(A.class);r.timers.getLast().run();assertTrue(digest().contains("repair queue unavailable"),digest());
            r.reloader.setScheduler((work,delay)->{r.timers.add(work);return ()->{};},r.queue::add);
            r.reload(A.class);r.fire();assertFalse(digest().contains("repair queue unavailable"),digest());
        }
    }
    @Test void matchingOneSourceDoesNotResolveAnotherUnsupportedSource() throws Exception {
        try(Rig r=new Rig()) {
            poison(r);r.reload(A.class);assertTrue(digest().contains("mismatch"),digest());
            replaceMap(r.cache,new ConcurrentHashMap<>());
            r.context.getBeanFactory().registerSingleton("custom",new org.springframework.cache.annotation.AnnotationCacheOperationSource(){});
            r.reload(A.class);assertTrue(digest().contains("mismatch"),digest());
        }
    }
}
