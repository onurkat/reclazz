/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.support.GenericApplicationContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * After a mapped helper reloads, the opt-in owner refresh recreates only the selected
 * Spring singleton owners, through the normal guarded bean lifecycle, and leaves
 * unmapped owners and rejected (event-listener) owners alone.
 */
class HelperOwnerCacheRefreshTest {

    /** A plain non-bean helper; its reload is what triggers the opt-in refresh. */
    public static class Helper { }

    /** A singleton owner of a custom cache; recreation gives a fresh instance. */
    public static class CacheOwner { }

    /** Another singleton owner, used to prove only mapped owners are touched. */
    public static class OtherOwner { }

    /** A singleton owner that is also an event listener: declined, not recreated. */
    public static class ListenerOwner implements ApplicationListener<ApplicationEvent> {
        @Override public void onApplicationEvent(ApplicationEvent event) { }
    }

    private GenericApplicationContext context;

    private SpringReloadOrchestrator orchestrator(Map<String, List<String>> mapping) {
        context = new GenericApplicationContext();
        context.registerBean("cacheOwner", CacheOwner.class);
        context.registerBean("otherOwner", OtherOwner.class);
        context.registerBean("listenerOwner", ListenerOwner.class);
        context.refresh();
        var orchestrator = new SpringReloadOrchestrator(new TestPlatformContext(context));
        orchestrator.setRefreshOwnersOnHelper(mapping);
        return orchestrator;
    }

    @AfterEach void close() { if (context != null) context.close(); }

    @Test void mappedOwnerIsRecreatedAfterHelperReload() {
        var orchestrator = orchestrator(Map.of(Helper.class.getName(), List.of(CacheOwner.class.getName())));
        Object before = context.getBean(CacheOwner.class);
        Object otherBefore = context.getBean(OtherOwner.class);

        orchestrator.onHelperReloaded(Helper.class.getName(), Helper.class);

        assertNotSame(before, context.getBean(CacheOwner.class), "mapped owner should be recreated");
        assertSame(otherBefore, context.getBean(OtherOwner.class), "unmapped owner must be untouched");
    }

    @Test void unmappedHelperRecreatesNothing() {
        var orchestrator = orchestrator(Map.of(Helper.class.getName(), List.of(CacheOwner.class.getName())));
        Object before = context.getBean(CacheOwner.class);

        orchestrator.onHelperReloaded("some.other.Helper", OtherOwner.class);

        assertSame(before, context.getBean(CacheOwner.class), "an unmapped helper must recreate nothing");
    }

    @Test void eventListenerOwnerIsDeclinedNotRecreated() {
        var orchestrator = orchestrator(Map.of(Helper.class.getName(), List.of(ListenerOwner.class.getName())));
        Object before = context.getBean(ListenerOwner.class);

        orchestrator.onHelperReloaded(Helper.class.getName(), Helper.class);

        assertSame(before, context.getBean(ListenerOwner.class), "an event-listener owner must not be recreated");
    }

    @Test void emptyMappingIsANoOp() {
        var orchestrator = orchestrator(Map.of());
        Object before = context.getBean(CacheOwner.class);

        orchestrator.onHelperReloaded(Helper.class.getName(), Helper.class);

        assertSame(before, context.getBean(CacheOwner.class));
    }

    private static final class TestPlatformContext implements PlatformContext {
        private final Object ctx;
        TestPlatformContext(Object ctx) { this.ctx = ctx; }
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
