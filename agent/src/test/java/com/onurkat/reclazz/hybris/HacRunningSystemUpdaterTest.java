/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The facade call itself needs a running SAP Commerce and is verified live; what is
 * unit-testable is how its result map is read. A completed update reports no failure and,
 * on the versions that set one, a success flag; a flagged failure or a non-map result is
 * not a success. Reading this wrong would either claim an update that did not happen or
 * keep nagging after one that did.
 */
class HacRunningSystemUpdaterTest {

    private static boolean succeeded(Object result) {
        return HacRunningSystemUpdater.succeeded(result);
    }

    @Test void aNonMapResultIsNotSuccess() {
        assertFalse(succeeded(null));
        assertFalse(succeeded("done"));
    }

    @Test void anExplicitFailureIsNotSuccess() {
        assertFalse(succeeded(Map.of("Failed", Boolean.TRUE)));
        assertFalse(succeeded(Map.of("Failed", "true", "success", Boolean.TRUE)),
                "a flagged failure wins even if a success flag is also present");
    }

    @Test void anExplicitSuccessFlagIsHonoured() {
        assertTrue(succeeded(Map.of("success", Boolean.TRUE)));
        assertTrue(succeeded(Map.of("success", "true")));
        assertFalse(succeeded(Map.of("success", Boolean.FALSE)));
    }

    /**
     * HAC builds the facade as a Spring bean and injects its tray notifier, and
     * executeInitUpdate calls that notifier before anything else. Constructed
     * reflectively the field stayed null, so on a real installation every
     * automatic update ended in a NullPointerException wrapped as
     * InvocationTargetException. The fixture keeps exactly that shape. Past
     * that, the update runs on the codegen thread, which has no tenant, and
     * the platform refuses with "no tenant active". And the audit step looks
     * JAXB up through the context classloader, which on that thread is not the
     * platform's.
     */
    @Test void runsTheFacadeThatExpectsItsInjectedNotifier(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path notifier = dir.resolve("de/hybris/bootstrap/osnotify/SystemTrayNotifier.java");
        java.nio.file.Path data = dir.resolve("de/hybris/platform/hac/data/dto/BeautifulInitializationData.java");
        java.nio.file.Path facade = dir.resolve("de/hybris/platform/hac/facade/HacInitUpdateFacade.java");
        java.nio.file.Path registry = dir.resolve("de/hybris/platform/core/Registry.java");
        for (java.nio.file.Path p : new java.nio.file.Path[]{notifier, data, facade, registry}) {
            java.nio.file.Files.createDirectories(p.getParent());
        }
        java.nio.file.Files.writeString(notifier, """
                package de.hybris.bootstrap.osnotify;
                public interface SystemTrayNotifier {
                    enum NotificationLevel { INFO, WARNING, ERROR }
                    void notify(String title, String message, NotificationLevel level);
                }
                """);
        java.nio.file.Files.writeString(data, """
                package de.hybris.platform.hac.data.dto;
                public class BeautifulInitializationData {
                    public enum InitMethod { INIT, UPDATE }
                    public InitMethod initMethod;
                    public Boolean dropTables, clearHMC, essential, project, localize;
                    public void setInitMethod(InitMethod m) { initMethod = m; }
                    public void setDropTables(Boolean b) { dropTables = b; }
                    public void setClearHMC(Boolean b) { clearHMC = b; }
                    public void setCreateEssentialData(Boolean b) { essential = b; }
                    public void setCreateProjectData(Boolean b) { project = b; }
                    public void setLocalizeTypes(Boolean b) { localize = b; }
                }
                """);
        java.nio.file.Files.writeString(registry, """
                package de.hybris.platform.core;
                public class Registry {
                    private static final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);
                    public static boolean hasCurrentTenant() { return active.get(); }
                    public static void activateMasterTenant() { active.set(true); }
                }
                """);
        java.nio.file.Files.writeString(facade, """
                package de.hybris.platform.hac.facade;
                import de.hybris.bootstrap.osnotify.SystemTrayNotifier;
                import de.hybris.platform.hac.data.dto.BeautifulInitializationData;
                public class HacInitUpdateFacade {
                    public static BeautifulInitializationData ran;
                    private SystemTrayNotifier notifier;
                    public void setNotifier(SystemTrayNotifier n) { notifier = n; }
                    public boolean isLocked() { return false; }
                    public synchronized java.util.Map<String, Object> executeInitUpdate(BeautifulInitializationData d) {
                        notifier.notify("Init/Update", "started", SystemTrayNotifier.NotificationLevel.INFO);
                        if (!de.hybris.platform.core.Registry.hasCurrentTenant()) {
                            throw new IllegalStateException("no tenant active");
                        }
                        if (Thread.currentThread().getContextClassLoader() != HacInitUpdateFacade.class.getClassLoader()) {
                            throw new IllegalStateException("Implementation of Jakarta XML Binding-API has not been found");
                        }
                        ran = d;
                        notifier.notify("Init/Update", "finished", SystemTrayNotifier.NotificationLevel.INFO);
                        return java.util.Map.of("success", Boolean.TRUE);
                    }
                }
                """);
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-d", dir.toString(), notifier.toString(), data.toString(), facade.toString(),
                registry.toString()));
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{dir.toUri().toURL()}, null)) {
            ClassLoader before = Thread.currentThread().getContextClassLoader();
            assertTrue(new HacRunningSystemUpdater(() -> loader).updateSchema());
            assertSame(before, Thread.currentThread().getContextClassLoader(), "the thread gets its loader back");
            Object ran = loader.loadClass("de.hybris.platform.hac.facade.HacInitUpdateFacade").getField("ran").get(null);
            assertNotNull(ran);
            Class<?> dataClass = ran.getClass();
            assertEquals("UPDATE", dataClass.getField("initMethod").get(ran).toString());
            for (String flag : new String[]{"dropTables", "clearHMC", "essential", "project", "localize"}) {
                assertEquals(Boolean.FALSE, dataClass.getField(flag).get(ran), flag + " stays off: schema only");
            }
        }
    }

    @Test void aMapWithNoFailureFlagIsTreatedAsComplete() {
        assertTrue(succeeded(Map.of("log", "updated types")));
        Map<String, Object> blankFailure = new HashMap<>();
        blankFailure.put("Failed", "false");
        assertTrue(succeeded(blankFailure), "a Failed=false string is not a failure");
    }
}
