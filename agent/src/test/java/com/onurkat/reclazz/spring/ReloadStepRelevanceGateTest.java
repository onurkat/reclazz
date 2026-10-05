/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.util.Reflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ApplicationListenerMethodAdapter;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two post-refresh steps do work that scales with the whole context: the event
 * listener re-registration scans every registered listener per context, and the
 * transaction/cache metadata step clears every context's attribute cache plus
 * Spring's global annotation caches. On a large application that is a real cost
 * on every save, so both run only when something beyond the body changed.
 *
 * <p>A body-only edit of a PLAIN class must skip them: the companion trampoline
 * applies the new body in place, so an already-registered {@code @EventListener}
 * adapter on an original method keeps dispatching to it and a cached answer (what
 * the annotation says) stays correct. But a class that ever ADDED a member keeps
 * running both steps, because a companion-added listener or transactional method
 * can later be removed by an edit the structural diff cannot see. These tests pin
 * both halves; removing either gate, or the added-members carve-out, reddens one.
 */
class ReloadStepRelevanceGateTest {

    /** The @EventListener is on a method, not the class, so isSpringBean is false
     *  and the orchestrator's inline bean-refresh block does not run: the event
     *  step alone decides whether the adapter is re-registered. */
    public static class Listener {
        @EventListener public void on(ApplicationEvent event) { }
    }

    /** A plain class that is not a bean: the transaction step runs (or not) on
     *  the gate alone, with no bean refresh in the way. */
    public static class Unrelated { }

    /** The minimal shape the transaction step looks up and clears. */
    public static class TxSource implements TransactionAttributeSource {
        final Map<Object, Object> attributeCache = new ConcurrentHashMap<>();
        @Override public TransactionAttribute getTransactionAttribute(Method method, Class<?> targetClass) {
            return null;
        }
    }

    private AnnotationConfigApplicationContext context;
    private SpringReloadOrchestrator orchestrator;

    @AfterEach void close() { if (context != null) context.close(); }

    private Object eventAdapterFor(Class<?> type) {
        for (Object listener : context.getApplicationListeners()) {
            if (listener instanceof ApplicationListenerMethodAdapter adapter
                    && Reflect.readField(adapter, "method") instanceof Method method
                    && method.getDeclaringClass() == type) {
                return adapter;
            }
        }
        return null;
    }

    @Test void eventStepSkipsABodyOnlyEditAndRunsOnAnAnnotationChange() {
        context = new AnnotationConfigApplicationContext();
        context.register(Listener.class);
        context.refresh();
        orchestrator = new SpringReloadOrchestrator(new OneContext(context));
        Object before = eventAdapterFor(Listener.class);
        assertNotNull(before, "the @EventListener adapter must exist at startup");

        orchestrator.onClassReloaded(Listener.class.getName(), Listener.class, false, false);
        assertSame(before, eventAdapterFor(Listener.class),
                "a body-only edit of a plain class must not re-register the listener");

        orchestrator.onClassReloaded(Listener.class.getName(), Listener.class, false, true);
        assertNotSame(before, eventAdapterFor(Listener.class),
                "an annotation change must re-register the listener");
    }

    @Test void eventStepRunsOnABodyOnlyEditAfterAMemberWasAdded() {
        context = new AnnotationConfigApplicationContext();
        context.register(Listener.class);
        context.refresh();
        orchestrator = new SpringReloadOrchestrator(new OneContext(context));

        // A reload that added a member marks the class as carrying a companion.
        orchestrator.onClassReloaded(Listener.class.getName(), Listener.class, false, false, true, Set.of(), null);
        Object afterAdd = eventAdapterFor(Listener.class);
        assertNotNull(afterAdd);

        // A later body-only edit still re-registers: a companion listener could
        // have been removed by an edit the structural diff cannot see.
        orchestrator.onClassReloaded(Listener.class.getName(), Listener.class, false, false, false, Set.of(), null);
        assertNotSame(afterAdd, eventAdapterFor(Listener.class),
                "a class that added a member must keep re-registering on body-only edits");
    }

    @Test void transactionMetadataStepSkipsABodyOnlyEditAndRunsOnAnAnnotationChange() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean("txSource", TxSource.class);
        context.refresh();
        orchestrator = new SpringReloadOrchestrator(new OneContext(context));
        TxSource source = context.getBean(TxSource.class);

        source.attributeCache.put("k", "stale");
        orchestrator.onClassReloaded(Unrelated.class.getName(), Unrelated.class, false, false);
        assertEquals(1, source.attributeCache.size(),
                "a body-only edit of a plain class must not clear the transaction metadata cache");

        orchestrator.onClassReloaded(Unrelated.class.getName(), Unrelated.class, false, true);
        assertTrue(source.attributeCache.isEmpty(),
                "an annotation change must clear the transaction metadata cache");
    }

    @Test void transactionMetadataStepRunsOnABodyOnlyEditAfterAMemberWasAdded() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean("txSource", TxSource.class);
        context.refresh();
        orchestrator = new SpringReloadOrchestrator(new OneContext(context));
        TxSource source = context.getBean(TxSource.class);

        orchestrator.onClassReloaded(Unrelated.class.getName(), Unrelated.class, false, false, true, Set.of(), null);
        source.attributeCache.put("k", "stale");
        orchestrator.onClassReloaded(Unrelated.class.getName(), Unrelated.class, false, false, false, Set.of(), null);
        assertTrue(source.attributeCache.isEmpty(),
                "a class that added a member must keep clearing the metadata cache on body-only edits");
    }

    /** Hands the orchestrator exactly the one context under test. */
    private record OneContext(Object ctx) implements PlatformContext {
        @Override public Platform getPlatformId() { return Platform.GENERIC; }
        @Override public void initialize() { }
        @Override public Map<String, List<Path>> getClassOutputDirs() { return Map.of(); }
        @Override public Map<String, List<Path>> getSourceDirs() { return Map.of(); }
        @Override public Map<String, List<Path>> getResourceDirs() { return Map.of(); }
        @Override public String resolveClasspath() { return ""; }
        @Override public String resolveClassName(Path classFile) { return null; }
        @Override public Path resolveOutputDir(Path classFile) { return null; }
        @Override public Object getApplicationContext() { return ctx; }
    }
}
