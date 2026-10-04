/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@org.springframework.stereotype.Service
public class UnrelatedCache {
    private final Cache<String, Integer> guava = CacheBuilder.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS).ticker(new CacheClock()).build();
    private final AtomicReference<long[]> ttl = new AtomicReference<>();
    public UnrelatedCache() { CacheCounters.unrelatedBuilds++; }
    public int guava() throws Exception {
        return guava.get("key", () -> { CacheCounters.unrelatedGuavaLoads++; return 99; });
    }
    public int ttl() {
        long[] entry = ttl.get();
        if (entry == null || CacheClock.now() >= entry[0]) {
            CacheCounters.unrelatedTtlLoads++;
            entry = new long[]{CacheClock.now() + TimeUnit.SECONDS.toNanos(60), 99};
            ttl.set(entry);
        }
        return (int)entry[1];
    }
}
