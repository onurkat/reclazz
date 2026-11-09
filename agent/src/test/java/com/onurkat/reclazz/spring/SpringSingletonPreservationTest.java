/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpringSingletonPreservationTest {
    public static class Target { }
    public static class Ordinary { }
    public static class Kept { }
    private static PlatformContext platform(GenericApplicationContext c) {
        return (PlatformContext) java.lang.reflect.Proxy.newProxyInstance(c.getClassLoader(), new Class<?>[]{PlatformContext.class},
                (p,m,a) -> switch (m.getName()) { case "getApplicationContext" -> c; case "getAllApplicationContexts" -> List.of(c); default -> null; });
    }
    @Test void initialAndBatchRefreshPreserveAliasBoundaryAndRecreateOrdinary() throws Exception {
        for (boolean batch : List.of(false, true)) try (var c = new GenericApplicationContext()) {
            for (var entry : Map.of("target", Target.class, "ordinary", Ordinary.class, "kept", Kept.class).entrySet())
                c.registerBeanDefinition(entry.getKey(), new RootBeanDefinition(entry.getValue()));
            c.registerAlias("kept", "alias"); c.refresh();
            var f = c.getDefaultListableBeanFactory();
            f.registerDependentBean("target", "ordinary"); f.registerDependentBean("target", "kept");
            Object kept = c.getBean("kept"), ordinary = c.getBean("ordinary"), target = c.getBean("target");
            var reloader = new SpringBeanReloader(platform(c)); reloader.setPreservedSingletons(owner -> Set.of("kept"));
            if (batch) reloader.beginBatch();
            reloader.refreshBean(Target.class.getName(), Target.class);
            if (batch) reloader.endBatch();
            assertSame(kept, c.getBean("alias")); assertNotSame(target, c.getBean("target"));
            assertNotSame(ordinary, c.getBean("ordinary"));
            assertTrue(Arrays.asList(f.getDependentBeans("target")).contains("kept"));
            assertTrue(Arrays.asList(f.getDependenciesForBean("kept")).contains("target"));
        }
    }
    @Test void transitiveBoundaryAndBothDirectionsAreRestoredOnFailure() throws Exception {
        try (var c = new GenericApplicationContext()) {
            c.refresh(); var f = c.getDefaultListableBeanFactory();
            f.registerSingleton("target", new Target()); f.registerSingleton("ordinary", new Ordinary());
            Object kept = new Kept(); f.registerSingleton("kept", kept); f.registerAlias("kept", "alias");
            f.registerDependentBean("target", "ordinary"); f.registerDependentBean("ordinary", "kept");
            assertThrows(IllegalStateException.class, () -> {
                try (var edges = SpringSingletonPreservation.detach(f, "target", Set.of("alias"))) {
                    assertArrayEquals(new String[0], f.getDependentBeans("ordinary"));
                    assertArrayEquals(new String[0], f.getDependenciesForBean("kept"));
                    throw new IllegalStateException("synthetic failure");
                }
            });
            assertSame(kept, c.getBean("kept"));
            assertArrayEquals(new String[]{"kept"}, f.getDependentBeans("ordinary"));
            assertArrayEquals(new String[]{"ordinary"}, f.getDependenciesForBean("kept"));
        }
    }
    @Test void noHookRetainsNativeRecursiveDestruction() {
        try (var c = new GenericApplicationContext()) {
            c.registerBeanDefinition("target", new RootBeanDefinition(Target.class));
            c.registerBeanDefinition("kept", new RootBeanDefinition(Kept.class)); c.refresh();
            c.getDefaultListableBeanFactory().registerDependentBean("target", "kept");
            Object before = c.getBean("kept");
            new SpringBeanReloader(platform(c)).refreshBean(Target.class.getName(), Target.class);
            assertNotSame(before, c.getBean("kept"));
        }
    }
    @Test void nativeDiscoveryFailureIsVisibleAndSkipsOnlyThatOwner() {
        for (boolean batch : List.of(false, true)) try (var broken = new GenericApplicationContext(); var healthy = new GenericApplicationContext()) {
            for (var c : List.of(broken, healthy)) {
                c.registerBeanDefinition("target", new RootBeanDefinition(Target.class)); c.refresh();
            }
            Object failedBefore = broken.getBean("target"), healthyBefore = healthy.getBean("target");
            List<String> errors = new ArrayList<>();
            com.onurkat.reclazz.ui.StatusReporter.StatusListener listener = (level, message) -> {
                if (level.equals("ERROR")) errors.add(message);
            };
            com.onurkat.reclazz.ui.StatusReporter.addListener(listener);
            try {
                var owners = List.of(broken, healthy);
                var platform = (PlatformContext) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlatformContext.class},
                        (p,m,a) -> switch (m.getName()) { case "getApplicationContext" -> broken; case "getAllApplicationContexts" -> owners; default -> null; });
                var reloader = new SpringBeanReloader(platform);
                reloader.setPreservedSingletons(owner -> {
                    if (owner == broken) throw new IllegalStateException("synthetic native discovery failure");
                    return Set.of();
                });
                if (batch) reloader.beginBatch();
                reloader.refreshBean(Target.class.getName(), Target.class);
                if (batch) reloader.endBatch();
                assertSame(failedBefore, broken.getBean("target"), "failed discovery must not destroy the owner singleton");
                assertNotSame(healthyBefore, healthy.getBean("target"), "one owner failure must not skip a later owner");
                assertTrue(errors.stream().anyMatch(s -> s.contains("Failed to refresh Spring bean for " + Target.class.getName() + ":")
                        && s.contains("owner 1/2") && s.contains("synthetic native discovery failure")), errors.toString());
            } finally { com.onurkat.reclazz.ui.StatusReporter.removeListener(listener); }
        }
    }
}
