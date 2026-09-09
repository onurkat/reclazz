/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.e2e.harness.RabbitBroker;
import com.onurkat.reclazz.bootstrap.RabbitConsumerBridge;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SpringRabbitLifecycleTest {
    @BeforeAll static void tracking() throws Exception { SpringRabbitReloaderTest.installTracking(); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void shutdownDoesNotMeanBlockedOriginalCallbackHasFinished(boolean closeConnection) throws Exception {
        SpringRabbitReloaderTest.entered = new CountDownLatch(1);
        SpringRabbitReloaderTest.release = new CountDownLatch(1);
        try (var broker = RabbitBroker.start();
             var s = new SpringRabbitReloaderTest.Scope(false, broker.connectionFactory(), true)) {
            new RabbitAdmin(broker.connectionFactory()).declareQueue(new Queue("unused", true));
            SpringRabbitReloaderTest.register(s, "original", s.owner(), "blocked");
            var c = s.container("original"); c.setShutdownTimeout(25); c.start();
            new RabbitTemplate(broker.connectionFactory()).convertAndSend("unused", "held");
            try {
                assertTrue(SpringRabbitReloaderTest.entered.await(10, TimeUnit.SECONDS));
                if (closeConnection) broker.connectionFactory().createConnection().getDelegate().close();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                if (closeConnection) {
                    while (c.getActiveConsumerCount() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
                    assertEquals(0, c.getActiveConsumerCount(), "real connection shutdown released Spring's counter");
                }
                assertFalse(s.reloader.beforeBeanRefresh(SpringRabbitReloaderTest.Owner.class));
                assertTrue(s.owner().calls.isEmpty(), "the real callback is still blocked");
                assertSame(c, s.container("original"));
                assertFalse(s.reloader.beforeBeanRefresh(SpringRabbitReloaderTest.Owner.class), "repeat must wait for work, not the zero Spring counter");
                assertSame(c, s.container("original"));
                Thread.currentThread().interrupt();
                assertFalse(s.reloader.beforeBeanRefresh(SpringRabbitReloaderTest.Owner.class));
                assertTrue(Thread.currentThread().isInterrupted());
                assertSame(c, s.container("original"));
            } finally { Thread.interrupted(); SpringRabbitReloaderTest.release.countDown(); }
            assertTrue(RabbitConsumerBridge.awaitStopped(c, System.nanoTime() + TimeUnit.SECONDS.toNanos(10)));
            assertEquals(java.util.List.of("held"), s.owner().calls);
            assertTrue(s.reloader.beforeBeanRefresh(SpringRabbitReloaderTest.Owner.class));
            assertNull(s.container("original")); assertFalse(c.isActive()); assertFalse(c.isRunning());
        } finally { SpringRabbitReloaderTest.release.countDown(); }
    }
}
