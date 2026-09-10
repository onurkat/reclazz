package com.onurkat.reclazz.hybris;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class HybrisTenantRetryTest {
    @TempDir Path dir;

    @Test void failedTenantActivationDoesNotAcknowledgeSave() throws Exception {
        // Protocol fault injection only: these two fixtures are not a SAP runtime simulation.
        Path registry = dir.resolve("de/hybris/platform/core/Registry.java");
        Path config = dir.resolve("de/hybris/platform/util/Config.java");
        Files.createDirectories(registry.getParent());
        Files.createDirectories(config.getParent());
        Files.writeString(registry, """
                package de.hybris.platform.core;
                public class Registry {
                    public static boolean ready, active;
                    public static boolean hasCurrentTenant() { return active; }
                    public static void activateMasterTenant() {
                        if (!ready) throw new IllegalStateException("not ready");
                        active = true;
                    }
                }
                """);
        Files.writeString(config, """
                package de.hybris.platform.util;
                public class Config {
                    public static String value = "old";
                    public static String getParameter(String key) { return value; }
                    public static void setParameter(String key, String next) { value = next; }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-d", dir.toString(), registry.toString(), config.toString()));
        try (var loader = new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()}, null)) {
            Path file = dir.resolve("local.properties");
            Files.writeString(file, "feature=old\n");
            var snapshots = new PropertyFileSnapshots();
            snapshots.baseline(file);
            Files.writeString(file, "feature=new\n");
            var reloader = new HybrisConfigReloader(loader, snapshots);
            var failed = reloader.applyResult(snapshots.pending(file));
            assertTrue(failed.reachable());
            assertEquals(java.util.List.of("feature"), failed.pending());
            assertEquals("old", snapshots.current(file).get("feature"));
            loader.loadClass("de.hybris.platform.core.Registry").getField("ready").set(null, true);
            var retried = reloader.applyResult(snapshots.pending(file));
            assertEquals(java.util.List.of("feature"), retried.applied());
            assertTrue(retried.pending().isEmpty());
            assertEquals("new", loader.loadClass("de.hybris.platform.util.Config").getField("value").get(null));
        }
    }
}
