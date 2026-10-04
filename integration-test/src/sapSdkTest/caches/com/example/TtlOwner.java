/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

@org.springframework.stereotype.Service
public class TtlOwner {
    // Serial fixture operations; immutable [expiresAt,value] entries are never modified after publication.
    private final AtomicReference<long[]> cache = new AtomicReference<>();
    @org.springframework.beans.factory.annotation.Autowired private RulesService rules;
    public TtlOwner() { CacheCounters.ttlBuilds++; }
    public int offset() { return 0; }
    public int raw() { return rules.value() + PlainHelper.value() + offset(); }
    public int value() {
        long[] entry = cache.get();
        if (entry == null || CacheClock.now() >= entry[0]) {
            CacheCounters.ttlLoads++;
            entry = new long[]{CacheClock.now() + TimeUnit.SECONDS.toNanos(60), raw()};
            cache.set(entry);
        }
        return (int)entry[1];
    }
}
