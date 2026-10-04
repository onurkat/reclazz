/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.concurrent.TimeUnit;

@org.springframework.stereotype.Service
public class GuavaOwner {
    private final Cache<String, Integer> cache = CacheBuilder.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS).ticker(new CacheClock()).build();
    @org.springframework.beans.factory.annotation.Autowired private RulesService rules;
    public GuavaOwner() { CacheCounters.guavaBuilds++; }
    public int offset() { return 0; }
    public int raw() { return rules.value() + PlainHelper.value() + offset(); }
    public int value() throws Exception {
        return cache.get("key", () -> { CacheCounters.guavaLoads++; return raw(); });
    }
}
