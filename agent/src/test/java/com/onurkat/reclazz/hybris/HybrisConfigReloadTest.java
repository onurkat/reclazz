package com.onurkat.reclazz.hybris;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SAP Commerce reads its property files once, at startup. Editing one
 * afterwards changes a file nothing will read again, so the answer had always
 * been to restart, and Reclazz said as much and stopped there.
 *
 * The platform does support runtime changes: {@code Config.setParameter} is
 * what the HAC console calls when a property is edited there. Applying the
 * keys that differ does the same thing from a file save.
 *
 * The behaviour worth pinning is the comparison. A config directory holds
 * thousands of keys and a save touches one or two; applying all of them would
 * be noise, and would overwrite whatever HAC or another extension had
 * deliberately set at runtime.
 *
 * {@link StubConfig} stands in for the platform class, with the same method
 * signatures. It is handed in directly rather than served under the platform's
 * name by a fake classloader, because Class.forName checks that what a loader
 * returns is actually the class that was asked for.
 */
class HybrisConfigReloadTest {

    @TempDir
    Path dir;

    @BeforeEach
    void reset() {
        StubConfig.reset();
    }

    private HybrisConfigReloader reloader() {
        return new HybrisConfigReloader(StubConfig.class);
    }

    private Path write(String content) throws Exception {
        Path f = dir.resolve("local.properties");
        Files.writeString(f, content);
        return f;
    }

    // ── Only what changed ─────────────────────────────────────────────────

    @Test
    void aChangedValueIsApplied() throws Exception {
        StubConfig.seed("storefront.title", "Old");

        List<String> applied = reloader().apply(write("storefront.title=New\n"));

        assertEquals(List.of("storefront.title"), applied);
        assertEquals("New", StubConfig.getParameter("storefront.title"));
    }

    @Test
    void anUnchangedValueIsLeftAlone() throws Exception {
        StubConfig.seed("storefront.title", "Same");

        assertEquals(List.of(), reloader().apply(write("storefront.title=Same\n")),
                "re-setting thousands of untouched keys on every save would be "
                + "noise, and would overwrite anything set at runtime on purpose");
        assertEquals(0, StubConfig.writes(), "nothing should have been written");
    }

    @Test
    void aNewKeyIsApplied() throws Exception {
        List<String> applied = reloader().apply(write("feature.newFlag=true\n"));

        assertEquals(List.of("feature.newFlag"), applied,
                "a key the server has never seen is a change like any other");
        assertEquals("true", StubConfig.getParameter("feature.newFlag"));
    }

    @Test
    void onlyTheChangedKeysAreTouchedInAFileOfMany() throws Exception {
        StubConfig.seed("a", "1");
        StubConfig.seed("b", "2");
        StubConfig.seed("c", "3");

        List<String> applied = reloader().apply(write("a=1\nb=CHANGED\nc=3\n"));

        assertEquals(List.of("b"), applied);
        assertEquals(1, StubConfig.writes());
        assertEquals("1", StubConfig.getParameter("a"));
        assertEquals("3", StubConfig.getParameter("c"));
    }

    /**
     * A key is only reported as applied if the server actually holds the new
     * value afterwards. The platform is free to ignore, coerce or override a
     * write, and reporting a change that did not take is the one thing this
     * feature must not do.
     */
    @Test
    void aValueThePlatformRefusesIsNotReportedAsApplied() throws Exception {
        StubConfig.seed("readonly.key", "Original");
        StubConfig.refuse("readonly.key");

        assertEquals(List.of(), reloader().apply(write("readonly.key=Attempted\n")),
                "the write was made and did not stick, so it is not an applied change");
        assertEquals("Original", StubConfig.getParameter("readonly.key"));
    }

    // ── Where it must do nothing ──────────────────────────────────────────

    /**
     * The same watcher sees .properties files in Spring Boot and plain Java
     * applications. Pushing those into a platform configuration that is not
     * there has to be a no-op, not an error.
     */
    @Test
    void withoutThePlatformItDoesNothingQuietly() throws Exception {
        HybrisConfigReloader off = new HybrisConfigReloader(
                new java.net.URLClassLoader(new java.net.URL[0], null));

        assertEquals(List.of(), off.apply(write("some.key=value\n")));
    }

    /**
     * Editors save in stages and the watcher is fast, so reading a partial or
     * momentarily missing file is the normal case. The server keeps its
     * previous values and the next save carries the same keys.
     */
    @Test
    void anUnreadableFileIsNotAnError() {
        assertEquals(List.of(), reloader().apply(dir.resolve("never-written.properties")));
        assertEquals(0, StubConfig.writes());
    }

    @Test
    void anEmptyFileChangesNothing() throws Exception {
        StubConfig.seed("a", "1");
        assertEquals(List.of(), reloader().apply(write("")));
        assertEquals("1", StubConfig.getParameter("a"));
    }

    /**
     * An empty result means two different things and they earn different
     * messages: nothing in the file differed, or this is not a platform at all
     * and the edit reached nothing.
     */
    @Test
    void reachabilityIsReportedSeparatelyFromHavingNothingToDo() throws Exception {
        StubConfig.seed("a", "1");
        HybrisConfigReloader on = reloader();
        assertEquals(List.of(), on.apply(write("a=1\n")));
        assertTrue(on.isPlatformReachable(),
                "nothing changed, but the platform is there and was consulted");

        HybrisConfigReloader off = new HybrisConfigReloader(
                new java.net.URLClassLoader(new java.net.URL[0], null));
        assertFalse(off.isPlatformReachable(),
                "no platform: the edit really did reach nothing");
    }

    // ── the stand-in ──────────────────────────────────────────────────────

    /** Same signatures as the platform's Config, and counts its writes. */
    @SuppressWarnings("unused")
    public static final class StubConfig {
        private static final java.util.Map<String, String> values =
                new java.util.concurrent.ConcurrentHashMap<>();
        private static final java.util.concurrent.atomic.AtomicInteger writes =
                new java.util.concurrent.atomic.AtomicInteger();
        private static final java.util.Set<String> refused =
                java.util.concurrent.ConcurrentHashMap.newKeySet();

        public static String getParameter(String key) {
            return values.get(key);
        }

        public static void setParameter(String key, String value) {
            writes.incrementAndGet();
            if (refused.contains(key)) return;
            values.put(key, value);
        }

        static void refuse(String key) {
            refused.add(key);
        }

        static void seed(String key, String value) {
            values.put(key, value);
        }

        static int writes() {
            return writes.get();
        }

        static void reset() {
            values.clear();
            refused.clear();
            writes.set(0);
        }
    }
}
