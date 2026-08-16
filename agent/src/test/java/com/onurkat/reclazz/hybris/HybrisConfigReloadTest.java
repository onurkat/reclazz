/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
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

    private final PropertyFileSnapshots snapshots = new PropertyFileSnapshots();

    /** One store per test, as a running agent has one for its lifetime. */
    private HybrisConfigReloader reloader() {
        return new HybrisConfigReloader(StubConfig.class, snapshots);
    }

    /** The file as the server already has it, which startup records. */
    private void alreadyRunningWith(Path file) {
        snapshots.baseline(file);
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
                new java.net.URLClassLoader(new java.net.URL[0], null),
                new PropertyFileSnapshots());

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
                new java.net.URLClassLoader(new java.net.URL[0], null),
                new PropertyFileSnapshots());
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

    // ── What counts as configuration at all ───────────────────────────────

    /**
     * Everything in a watched extension is a candidate and almost none of it is
     * configuration. Measured on a mid-sized project: 353 property files
     * holding 8,728 keys, 350 of them message bundles for e-mails and OCC
     * responses. Applying those changes what every component reading the
     * platform configuration sees, and it takes no edit to arrive: checking out
     * a branch writes the files.
     */
    @Test
    void onlyThePlatformsOwnConfigurationFilesCount() {
        assertTrue(HybrisConfigReloader.isPlatformConfiguration(
                Path.of("/hybris/config/local.properties")));
        assertTrue(HybrisConfigReloader.isPlatformConfiguration(
                Path.of("/hybris/bin/custom/myext/project.properties")));
        assertTrue(HybrisConfigReloader.isPlatformConfiguration(
                Path.of("/hybris/config/dev/props/10-local.properties")),
                "the platform's own numbered configuration files");
    }

    @Test
    void aMessageBundleIsNotConfiguration() {
        assertFalse(HybrisConfigReloader.isPlatformConfiguration(
                Path.of("/hybris/bin/custom/myext/resources/myext/messages/email-order.properties")));
        assertFalse(HybrisConfigReloader.isPlatformConfiguration(
                Path.of("/hybris/bin/custom/myext/resources/occ/v2/messages/base_de.properties")));
        assertFalse(HybrisConfigReloader.isPlatformConfiguration(
                Path.of("/hybris/bin/custom/myext/resources/generateVariables.properties")),
                "a build helper's file is not the running server's configuration");
    }

    @Test
    void oddInputIsNotMistakenForConfiguration() {
        assertFalse(HybrisConfigReloader.isPlatformConfiguration(null));
        assertFalse(HybrisConfigReloader.isPlatformConfiguration(Path.of("props")));
        assertFalse(HybrisConfigReloader.isPlatformConfiguration(
                Path.of("/hybris/config/dev/props/notes.txt")));
    }

    /**
     * The file a developer edits is config/local.properties, and it lives
     * outside every extension, so nothing in the watch list covered it: the
     * feature could only ever fire on files that are not configuration. The
     * platform's config directory is watched for that reason, and this pins it
     * because losing it again would leave a feature with no way to trigger.
     */
    @Test
    void theWatchListCoversThePlatformsConfigDirectory() throws Exception {
        String source = java.nio.file.Files.readString(sourceOf(
                "agent/src/main/java/com/onurkat/reclazz/platform/HybrisPlatformContext.java"));

        int at = source.indexOf("getResourceDirs");
        assertTrue(at > 0, "getResourceDirs is what the watcher is built from");
        String body = source.substring(at, Math.min(source.length(), at + 2000));
        assertTrue(body.contains("resolve(\"config\")"),
                "config/local.properties and config/<env>/props are where the "
                + "platform keeps the properties a developer edits");
    }

    private static java.nio.file.Path sourceOf(String repoRelative) {
        java.nio.file.Path p = java.nio.file.Path.of(repoRelative);
        if (!java.nio.file.Files.isRegularFile(p)) {
            p = java.nio.file.Path.of(repoRelative.replaceFirst("^agent/", ""));
        }
        if (!java.nio.file.Files.isRegularFile(p)) fail("cannot find " + repoRelative);
        return p;
    }

    /**
     * Hybris expands ${...} when it loads a file, so the server holds
     * {@code file:/opt/hybris/config/security/keystore.jks} where the file
     * still says {@code file:${HYBRIS_CONFIG_DIR}/security/keystore.jks}. The
     * two never compare equal, so every save looked like a change, and writing
     * the raw text back replaced a working path with literal placeholder
     * characters. Found by saving one unrelated line in a real
     * config/dev/props file: nine keys were rewritten, two of them the SSO
     * keystore and metadata locations.
     */
    @Test
    void aValueWithAPlaceholderIsLeftAsTheServerHasIt() throws Exception {
        StubConfig.seed("sso.keystore.location", "file:/opt/hybris/config/security/keystore.jks");

        List<String> applied = reloader().apply(write(
                "sso.keystore.location=file:${HYBRIS_CONFIG_DIR}/security/keystore.jks\n"));

        assertEquals(List.of(), applied);
        assertEquals(0, StubConfig.writes(), "expanding these means reproducing the platform");
        assertEquals("file:/opt/hybris/config/security/keystore.jks",
                StubConfig.getParameter("sso.keystore.location"));
    }

    @Test
    void aPlaceholderDoesNotStopTheRestOfTheFile() throws Exception {
        StubConfig.seed("sso.keystore.location", "file:/opt/hybris/config/security/keystore.jks");
        StubConfig.seed("feature.flag", "false");

        List<String> applied = reloader().apply(write(
                "sso.keystore.location=file:${HYBRIS_CONFIG_DIR}/security/keystore.jks\n"
                + "feature.flag=true\n"));

        assertEquals(List.of("feature.flag"), applied);
        assertEquals("true", StubConfig.getParameter("feature.flag"));
    }

    // ── Measured against the file, not against the server ─────────────────

    /**
     * The platform layers property files and the last one to define a key wins.
     * A key set in 10-local.properties and overridden in 95-local.properties
     * differs from the running value in the first file forever, so comparing
     * that file against the configuration made every save look like a change
     * and quietly undid the override.
     *
     * Seen on a live server: adding one unrelated line to a config/dev/props
     * file reported three keys applied, two of which the developer had not
     * touched, one of them the agent's own options set by a later file.
     */
    @Test
    void aLineTheDeveloperDidNotTouchIsLeftAlone() throws Exception {
        Path f = write("build.development.mode=false\nfeature.flag=off\n");
        alreadyRunningWith(f);
        StubConfig.seed("build.development.mode", "true");   // a later file won
        StubConfig.seed("feature.flag", "off");

        Files.writeString(f, "build.development.mode=false\nfeature.flag=on\n");

        assertEquals(List.of("feature.flag"), reloader().apply(f));
        assertEquals("true", StubConfig.getParameter("build.development.mode"),
                "the override stands; this file never set the running value");
    }

    /**
     * The server read these files at startup, so recording them then is what
     * makes the first save afterwards an edit rather than a whole file.
     */
    @Test
    void aFileThatWasNotEditedSinceStartupAppliesNothing() throws Exception {
        Path f = write("a=1\nb=2\n");
        alreadyRunningWith(f);
        StubConfig.seed("a", "from-a-later-file");

        assertEquals(List.of(), reloader().apply(f));
        assertEquals(0, StubConfig.writes());
        assertEquals("from-a-later-file", StubConfig.getParameter("a"));
    }

    /** A file created after startup has no previous version, so it is all new. */
    @Test
    void aFileThatAppearedAfterStartupIsAppliedInFull() throws Exception {
        List<String> applied = reloader().apply(write("brand.new.key=value\n"));

        assertEquals(List.of("brand.new.key"), applied);
        assertEquals("value", StubConfig.getParameter("brand.new.key"));
    }
}
