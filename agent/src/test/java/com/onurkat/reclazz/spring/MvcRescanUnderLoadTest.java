/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Requests keep arriving while a controller is re-scanned. Taking its
 * mappings out and scanning them back in leaves a moment with no mapping
 * for the path, and a request in that moment is a 404 for having saved a
 * file. The registry's own lock, held across the swap, turns that moment
 * into a wait.
 */
class MvcRescanUnderLoadTest {

    @RestController
    static class PingController {
        @GetMapping("/ping")
        public String ping() {
            return "pong";
        }
    }

    private static final int THREADS = 8;
    private static final int RESCANS = 200;

    @Test
    void noRequestMissesTheMappingWhileItIsReScanned() throws Exception {
        GenericWebApplicationContext ctx = new GenericWebApplicationContext(new MockServletContext());
        ctx.registerBean("pingController", PingController.class);
        ctx.refresh();
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(ctx);
        mapping.afterPropertiesSet();
        assertNotNull(mapping.getHandler(new MockHttpServletRequest("GET", "/ping")), "mapped before anything happens");
        assertNotNull(SpringMvcReloader.registryWriteLock(mapping), "the registry's lock is reachable on this Spring");

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong lookups = new AtomicLong();
        AtomicLong misses = new AtomicLong();
        List<String> failures = new CopyOnWriteArrayList<>();
        CountDownLatch started = new CountDownLatch(THREADS);
        Thread[] callers = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            callers[i] = new Thread(() -> {
                started.countDown();
                while (running.get()) {
                    try {
                        Object handler = mapping.getHandler(new MockHttpServletRequest("GET", "/ping"));
                        if (handler == null) misses.incrementAndGet();
                        lookups.incrementAndGet();
                    } catch (Throwable t) {
                        if (failures.size() < 5) failures.add(t.toString());
                    }
                }
            }, "request-" + i);
            callers[i].setDaemon(true);
            callers[i].start();
        }
        started.await();

        SpringMvcReloader reloader = new SpringMvcReloader(null);
        for (int i = 0; i < RESCANS; i++) {
            assertTrue(reloader.rescan(mapping, "pingController", PingController.class));
        }
        running.set(false);
        for (Thread t : callers) t.join(5000);

        System.out.println("[diag] lookups=" + lookups.get() + " misses=" + misses.get()
                + " rescans=" + RESCANS + " failures=" + failures);
        assertTrue(lookups.get() > RESCANS, "the load overlapped the re-scans, saw " + lookups.get());
        assertTrue(failures.isEmpty(), "a lookup threw while the controller was re-scanned: " + failures);
        assertEquals(0, misses.get(), "requests that found no handler for /ping during a re-scan");
        assertNotNull(mapping.getHandler(new MockHttpServletRequest("GET", "/ping")), "mapped afterwards");
        assertEquals(1, mapping.getHandlerMethods().size(), "one mapping, not one per re-scan");
        ctx.close();
    }
}
