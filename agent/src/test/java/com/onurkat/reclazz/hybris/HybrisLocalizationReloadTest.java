package com.onurkat.reclazz.hybris;

import com.onurkat.reclazz.watcher.ChangeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static text was the change that looked like it should be the cheapest and
 * needed a restart anyway: SAP Commerce reads {@code <ext>-locales_<iso>
 * .properties} from disk into one map and answers every lookup from it, and
 * backoffice reads its labels through ZK's cache.
 *
 * Measured on a running server before this was written: an edited type name
 * stayed at the old text across requests, and appeared on the first request
 * after the cache was cleared, with no database write and no system update.
 *
 * The part worth pinning in a test is the routing. A locales file is a
 * .properties file by extension and nothing like one in meaning, and it used
 * to be treated as platform configuration: saving one pushed 802 localization
 * keys into Config and reported them as applied, while the server went on
 * showing the old text. Config and this cache must not be confused again.
 */
class HybrisLocalizationReloadTest {

    @BeforeEach
    void reset() {
        StubTypeLocalization.cleared = 0;
        StubTypeLocalization.instance = new StubTypeLocalization();
        StubLabels.resets = 0;
        SecondStubLabels.resets = 0;
    }

    private HybrisLocalizationReloader reloader() {
        return new HybrisLocalizationReloader(StubTypeLocalization.class, StubLabels.class);
    }

    // ── Routing: what must never reach the configuration ──────────────────

    @Test
    void aLocalesFileIsNotConfiguration() {
        assertEquals(ChangeKind.LOCALIZATION,
                ChangeKind.of("generacb2bcore-locales_en.properties"));
        assertEquals(ChangeKind.LOCALIZATION,
                ChangeKind.of("core-locales_zh_TW.properties"));
    }

    @Test
    void ordinaryPropertyFilesStillGoToTheConfiguration() {
        assertEquals(ChangeKind.PROPERTIES, ChangeKind.of("local.properties"));
        assertEquals(ChangeKind.PROPERTIES, ChangeKind.of("project.properties"));
        assertEquals(ChangeKind.PROPERTIES, ChangeKind.of("application.yml"));
    }

    /**
     * Backoffice label files are called {@code labels_en.properties}, which a
     * Spring application could equally well use for a message bundle. The
     * folder is what tells them apart.
     */
    @Test
    void backofficeLabelsAreRecognisedByTheirFolder() {
        assertEquals(ChangeKind.LOCALIZATION, ChangeKind.of(
                Path.of("/ext/resources/myext-backoffice-labels/labels_en.properties")));
        assertEquals(ChangeKind.PROPERTIES, ChangeKind.of(
                Path.of("/app/src/main/resources/labels_en.properties")),
                "outside a backoffice labels folder this is an ordinary bundle "
                + "and must keep its old handling");
    }

    @Test
    void classifyingByPathAgreesWithClassifyingByName() {
        assertEquals(ChangeKind.CLASS_FILE, ChangeKind.of(Path.of("/out/Demo.class")));
        assertEquals(ChangeKind.SPRING_XML, ChangeKind.of(Path.of("/res/demo-spring.xml")));
        assertEquals(ChangeKind.LOCALIZATION,
                ChangeKind.of(Path.of("/res/localization/demo-locales_en.properties")));
    }

    @Test
    void aNullPathIsNotAnError() {
        assertEquals(ChangeKind.UNKNOWN, ChangeKind.of((Path) null));
    }

    // ── Clearing the two caches ───────────────────────────────────────────

    @Test
    void typeLocalizationsAreClearedThroughTheSingleton() {
        assertTrue(reloader().reloadTypeLocalizations());
        assertEquals(1, StubTypeLocalization.cleared,
                "the platform answers every lookup from this map; clearing it "
                + "is what makes the next read see the file");
    }

    @Test
    void backofficeLabelsAreClearedThroughZk() {
        assertTrue(reloader().reloadBackofficeLabels());
        assertEquals(1, StubLabels.resets);
    }

    /**
     * The same watcher sees .properties files in Spring Boot and plain Java
     * applications. Without a platform there is nothing to clear, and that has
     * to be a quiet no, not an error and not a claim that text was refreshed.
     */
    @Test
    void withoutThePlatformItDoesNothingQuietly() {
        HybrisLocalizationReloader off = new HybrisLocalizationReloader(
                new java.net.URLClassLoader(new java.net.URL[0], null), null);

        assertFalse(off.reloadTypeLocalizations());
        assertFalse(off.reloadBackofficeLabels(),
                "ZK is found among the loaded classes, and with no "
                + "Instrumentation there is no list to search");
    }

    /**
     * Backoffice loads ZK the first time someone opens it. Until then the
     * class is not in the process at all, and saying labels were refreshed
     * would be a claim about a cache that does not exist yet.
     */
    @Test
    void backofficeThatWasNeverOpenedIsReportedAsNotRefreshed() {
        HybrisLocalizationReloader noZk =
                new HybrisLocalizationReloader(StubTypeLocalization.class);

        assertFalse(noZk.reloadBackofficeLabels());
        assertTrue(noZk.reloadTypeLocalizations(),
                "the platform cache is a separate store and is still there");
    }

    /**
     * A server can hold more than one copy of ZK's Labels class, one per web
     * application classloader and each with its own cache, and a copy left
     * behind by a redeployed application is indistinguishable from the live
     * one. Resetting only the first found clears a cache nobody reads while
     * backoffice goes on serving the old text, which is what a run against a
     * live server looked like: the call reported success and the label in the
     * navigation tree did not change.
     */
    @Test
    void everyCopyOfTheLabelCacheIsReset() {
        HybrisLocalizationReloader many = new HybrisLocalizationReloader(
                StubTypeLocalization.class, StubLabels.class, SecondStubLabels.class);

        assertTrue(many.reloadBackofficeLabels());
        assertEquals(1, StubLabels.resets);
        assertEquals(1, SecondStubLabels.resets,
                "the copy that is not first in the list is the one backoffice "
                + "may actually be reading");
    }

    /** One broken copy must not stop the others from being cleared. */
    @Test
    void oneUnusableCopyDoesNotStopTheRest() {
        HybrisLocalizationReloader mixed = new HybrisLocalizationReloader(
                StubTypeLocalization.class, StubBrokenLabels.class, StubLabels.class);

        assertTrue(mixed.reloadBackofficeLabels());
        assertEquals(1, StubLabels.resets);
    }

    @Test
    void aCacheThatRefusesToClearIsNotReportedAsSuccess() {
        HybrisLocalizationReloader broken =
                new HybrisLocalizationReloader(StubBrokenLocalization.class, StubBrokenLabels.class);

        assertFalse(broken.reloadTypeLocalizations());
        assertFalse(broken.reloadBackofficeLabels());
    }

    // ── the stand-ins ─────────────────────────────────────────────────────

    /** Same shape as TypeLocalization: a singleton with a clear method. */
    @SuppressWarnings("unused")
    public static final class StubTypeLocalization {
        static StubTypeLocalization instance = new StubTypeLocalization();
        static int cleared;

        public static StubTypeLocalization getInstance() {
            return instance;
        }

        public void clearLocalizationCache() {
            cleared++;
        }
    }

    /** Same shape as ZK's Labels: a static reset. */
    @SuppressWarnings("unused")
    public static final class StubLabels {
        static int resets;

        public static void reset() {
            resets++;
        }
    }

    /** A second copy, as a second web application classloader would hold. */
    @SuppressWarnings("unused")
    public static final class SecondStubLabels {
        static int resets;

        public static void reset() {
            resets++;
        }
    }

    @SuppressWarnings("unused")
    public static final class StubBrokenLocalization {
        public static StubBrokenLocalization getInstance() {
            return new StubBrokenLocalization();
        }

        public void clearLocalizationCache() {
            throw new IllegalStateException("cache is held elsewhere");
        }
    }

    @SuppressWarnings("unused")
    public static final class StubBrokenLabels {
        public static void reset() {
            throw new IllegalStateException("no label loader registered");
        }
    }
}
