/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Re-processing @EventListener methods ADDS listeners: it re-scans the beans
 * and registers what it finds. So the old adapters have to come out first, and
 * they were not coming out at all. Measured on Boot 3.3: one publish called the
 * method once before a reload and twice after, and it stayed at twice. For a
 * listener whose side effects are the reason it exists, that is a reload
 * quietly doing the work again.
 *
 * <p>The old code looked for a removeAllListeners that does not exist, decided
 * it would be too aggressive anyway, and removed nothing. Too aggressive was
 * the right worry about the wrong method, which is what these tests hold: the
 * adapters for the reloaded class go, and nobody else's do.
 */
class SpringEventReloaderTest {

    /** What the multicaster keeps its listeners in. */
    static class Retriever {
        public final Set<ApplicationListener<?>> applicationListeners = new LinkedHashSet<>();
    }

    /** AbstractApplicationEventMulticaster, as far as this needs it. */
    static class Multicaster {
        private final Retriever defaultRetriever = new Retriever();

        public void removeApplicationListener(ApplicationListener<?> listener) {
            defaultRetriever.applicationListeners.remove(listener);
        }

        Set<ApplicationListener<?>> listeners() {
            return defaultRetriever.applicationListeners;
        }
    }

    /**
     * The name matters: the walk recognises an adapter by its class name
     * ending, the way the real one is named.
     */
    static class FakeApplicationListenerMethodAdapter implements ApplicationListener<ApplicationEvent> {
        @SuppressWarnings("unused")
        private final Method method;

        FakeApplicationListenerMethodAdapter(Method method) {
            this.method = method;
        }

        @Override
        public void onApplicationEvent(ApplicationEvent event) {
        }
    }

    /** A listener somebody registered by hand, which is not ours to remove. */
    static class HandWritten implements ApplicationListener<ApplicationEvent> {
        @Override
        public void onApplicationEvent(ApplicationEvent event) {
        }
    }

    static class Listening {
        public void on() {
        }
    }

    static class AlsoListening {
        public void on() {
        }
    }

    @Test
    void onlyTheReloadedClassesAdaptersAreRemoved() throws Exception {
        Multicaster multicaster = new Multicaster();
        var mine = new FakeApplicationListenerMethodAdapter(Listening.class.getMethod("on"));
        var someoneElses = new FakeApplicationListenerMethodAdapter(
                AlsoListening.class.getMethod("on"));
        var handWritten = new HandWritten();
        multicaster.listeners().add(mine);
        multicaster.listeners().add(someoneElses);
        multicaster.listeners().add(handWritten);

        assertEquals(1, SpringEventReloader.removeAdaptersFor(multicaster, Listening.class));
        assertFalse(multicaster.listeners().contains(mine),
                "re-processing adds one back; without this the class ends up with two");
        assertTrue(multicaster.listeners().contains(someoneElses),
                "another class's listener is not this reload's business");
        assertTrue(multicaster.listeners().contains(handWritten),
                "and neither is one somebody registered by hand");
    }

    @Test
    void aClassWithNoAdaptersRemovesNothing() throws Exception {
        Multicaster multicaster = new Multicaster();
        multicaster.listeners().add(
                new FakeApplicationListenerMethodAdapter(AlsoListening.class.getMethod("on")));

        assertEquals(0, SpringEventReloader.removeAdaptersFor(multicaster, Listening.class));
        assertEquals(1, multicaster.listeners().size());
    }

    /** A multicaster shape this does not know keeps its listeners. */
    @Test
    void anUnknownShapeIsNotAFailure() {
        assertEquals(0, SpringEventReloader.removeAdaptersFor(new Object(), Listening.class));
    }
}
