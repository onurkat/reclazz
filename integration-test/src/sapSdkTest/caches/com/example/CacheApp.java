/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.SpringVersion;
import java.util.Scanner;

/** Controlled in-memory cache experiment; no tenant, external service or cluster. */
public class CacheApp {
    private static String snapshot(AnnotationConfigApplicationContext context) throws Exception {
        var consumer = context.getBean(CacheConsumer.class);
        int guava = consumer.guava.value(), ttl = consumer.ttl.value();
        int unrelatedGuava = consumer.unrelated.guava(), unrelatedTtl = consumer.unrelated.ttl();
        if (consumer.guava.value() != guava || consumer.ttl.value() != ttl
                || consumer.unrelated.guava() != unrelatedGuava || consumer.unrelated.ttl() != unrelatedTtl)
            throw new AssertionError("Repeated warm reads changed without a reload or clock advance");
        boolean wired = consumer.guava == context.getBean(GuavaOwner.class)
                && consumer.ttl == context.getBean(TtlOwner.class)
                && consumer.unrelated == context.getBean(UnrelatedCache.class);
        return "guava=" + guava + ";ttl=" + ttl + ";rawGuava=" + consumer.guava.raw()
                + ";rawTtl=" + consumer.ttl.raw() + ";plain=" + PlainHelper.value()
                + ";rules=" + context.getBean(RulesService.class).value()
                + ";unrelatedGuava=" + unrelatedGuava + ";unrelatedTtl=" + unrelatedTtl
                + ";guavaLoads=" + CacheCounters.guavaLoads + ";ttlLoads=" + CacheCounters.ttlLoads
                + ";unrelatedGuavaLoads=" + CacheCounters.unrelatedGuavaLoads
                + ";unrelatedTtlLoads=" + CacheCounters.unrelatedTtlLoads
                + ";guavaBuilds=" + CacheCounters.guavaBuilds + ";ttlBuilds=" + CacheCounters.ttlBuilds
                + ";unrelatedBuilds=" + CacheCounters.unrelatedBuilds
                + ";unrelatedId=" + System.identityHashCode(consumer.unrelated)
                + ";wired=" + wired + ";clock=" + CacheClock.now();
    }
    public static void main(String[] args) throws Exception {
        try (var context = new AnnotationConfigApplicationContext(); var input = new Scanner(System.in)) {
            context.register(GuavaOwner.class, TtlOwner.class, RulesService.class, UnrelatedCache.class, CacheConsumer.class);
            context.refresh();
            System.out.println("CACHE_READY=" + ProcessHandle.current().pid() + ";" + SpringVersion.getVersion());
            while (input.hasNextLine()) {
                String[] command = input.nextLine().split(" ");
                if (command[1].equals("EXPIRE")) CacheClock.expire();
                System.out.println("CACHE_RESULT " + command[0] + " " + snapshot(context));
            }
        }
    }
}
