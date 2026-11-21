/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.Test;
import java.lang.ref.WeakReference;
import static org.junit.jupiter.api.Assertions.*;

class JacksonMapperRegistryTest {
    private static final class SameValue {
        @Override public boolean equals(Object other){return other instanceof SameValue;}
        @Override public int hashCode(){return 1;}
    }
    @Test void identityDedupKeepsDistinctEqualMappers() {
        Object first=new SameValue(),second=new SameValue();
        JacksonMapperRegistry.track(first);JacksonMapperRegistry.track(first);JacksonMapperRegistry.track(second);
        var live=JacksonMapperRegistry.live();
        assertEquals(1,live.stream().filter(value->value==first).count());
        assertEquals(1,live.stream().filter(value->value==second).count());
    }
    @Test void alreadyRegisteredMapperCallsNeverTakeTheGlobalMonitor()throws Exception {
        java.util.List<Object> mappers=new java.util.ArrayList<>();for(int i=0;i<256;i++){Object mapper=new Object();mappers.add(mapper);JacksonMapperRegistry.track(mapper);}
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();java.util.concurrent.Future<?> future;
        try {
            synchronized(JacksonMapperRegistry.class) {
                future=executor.submit(()->{for(int pass=0;pass<50;pass++)for(Object mapper:mappers)JacksonMapperRegistry.track(mapper);});
                assertDoesNotThrow(()->future.get(2,java.util.concurrent.TimeUnit.SECONDS),"hot mapper calls cannot scan or lock the global registry");
            }
        }finally{executor.shutdownNow();executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);}
    }
    @Test void discardedMapperIsNotRetained() throws Exception {
        var reference=registerDisposable();
        for(int i=0;i<80 && reference.get()!=null;i++){System.gc();Thread.sleep(25);}
        assertNull(reference.get(),"registry must not keep mapper or its classloader alive");
        assertTrue(JacksonMapperRegistry.live().stream().noneMatch(value->value==reference.get()));
    }
    private WeakReference<Object> registerDisposable(){
        var mapper=new Object();JacksonMapperRegistry.track(mapper);return new WeakReference<>(mapper);
    }
}
