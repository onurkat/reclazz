package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.support.GenericApplicationContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the stale-reference healing path against a real Spring context:
 * after a singleton is destroyed and recreated, holders that captured the
 * old instance must be re-pointed at the new one, and a batch must do that
 * work once rather than per reloaded class.
 */
class SpringBeanReloaderHealingTest {

    public static class Service {
        public String version() { return "v1"; }
    }

    public static class Holder {
        @Autowired
        Service service; // wired by Spring — Spring records a dependency edge
    }

    /**
     * Captures the service WITHOUT Spring recording a dependency edge
     * (wired manually after refresh). Only stale-reference healing can fix
     * this holder — the dependent cascade cannot see it.
     */
    public static class ManualHolder {
        Service service;
    }

    /** A bean with no reference to Service; its fields must be skipped. */
    public static class Unrelated {
        String name = "x";
        int count = 3;
    }

    private GenericApplicationContext context;
    private SpringBeanReloader reloader;

    @BeforeEach
    void setUp() {
        context = new GenericApplicationContext();
        // GenericApplicationContext does not register annotation processors
        // on its own, so @Autowired would never be honoured.
        org.springframework.context.annotation.AnnotationConfigUtils
                .registerAnnotationConfigProcessors(context);
        context.registerBean("service", Service.class);
        context.registerBean("holder", Holder.class);
        context.registerBean("manualHolder", ManualHolder.class);
        context.registerBean("unrelated", Unrelated.class);
        context.refresh();
        // Manual wiring: no dependency edge is recorded for this one.
        context.getBean(ManualHolder.class).service = context.getBean(Service.class);
        reloader = new SpringBeanReloader(new TestPlatformContext(context));
    }

    @AfterEach
    void tearDown() {
        if (context != null && context.isActive()) context.close();
    }

    @Test
    void manuallyWiredHolderIsHealedInPlace() {
        Service original = context.getBean(Service.class);
        ManualHolder manual = context.getBean(ManualHolder.class);
        assertSame(original, manual.service, "precondition: holder captured the original");

        reloader.refreshBean(Service.class.getName(), Service.class);

        Service refreshed = context.getBean(Service.class);
        assertNotSame(original, refreshed, "bean must be destroyed and recreated");
        assertSame(refreshed, manual.service,
                "the SAME holder instance must be re-pointed at the refreshed bean");
    }

    @Test
    void autowiredDependentIsRewiredByCascade() {
        Service original = context.getBean(Service.class);
        assertSame(original, context.getBean(Holder.class).service);

        reloader.refreshBean(Service.class.getName(), Service.class);

        Service refreshed = context.getBean(Service.class);
        assertSame(refreshed, context.getBean(Holder.class).service,
                "the dependent bean must end up wired to the refreshed instance");
    }

    @Test
    void batchDefersHealingUntilEndBatch() {
        Service original = context.getBean(Service.class);
        ManualHolder manual = context.getBean(ManualHolder.class);

        reloader.beginBatch();
        reloader.refreshBean(Service.class.getName(), Service.class);

        // Inside the batch the bean is already recreated, but the sweep
        // that re-points holders has not run yet.
        assertNotSame(original, context.getBean(Service.class));
        assertSame(original, manual.service, "healing must be deferred inside a batch");

        reloader.endBatch();
        assertSame(context.getBean(Service.class), manual.service,
                "endBatch must run the deferred healing");
    }

    @Test
    void unrelatedBeansAreUntouched() {
        Unrelated before = context.getBean(Unrelated.class);
        String name = before.name;
        int count = before.count;

        reloader.refreshBean(Service.class.getName(), Service.class);

        Unrelated after = context.getBean(Unrelated.class);
        assertSame(before, after, "unrelated singleton must not be recreated");
        assertEquals(name, after.name);
        assertEquals(count, after.count);
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
