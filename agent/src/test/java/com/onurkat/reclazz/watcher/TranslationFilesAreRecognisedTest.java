/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.watcher;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A translator saves a file, and something has to happen.
 *
 * <p>Translations are the one kind of file where a save that does nothing is
 * almost invisible: the application keeps serving the previous wording, which
 * is real text in a real language, so nothing looks broken. The person who
 * changed it reloads the page, sees the old string, and assumes they got the
 * key wrong.
 *
 * <p>Which is why the classifier's mistakes matter in both directions. A
 * translation seen as configuration is worse than one seen as nothing: it is
 * pushed into the running property system, so a bundle of Turkish sentences
 * ends up as configuration keys, and the text still does not change.
 *
 * <p>These are the names that actually occur, in the frameworks this agent
 * supports and in the locales its users work in.
 */
class TranslationFilesAreRecognisedTest {

    // ── SAP Commerce ─────────────────────────────────────────────────────

    @Test
    void thePlatformsOwnLocaleBundles() {
        assertEquals(ChangeKind.LOCALIZATION, ChangeKind.of("core-locales_tr.properties"));
        assertEquals(ChangeKind.LOCALIZATION, ChangeKind.of("core-locales_de.properties"));
        assertEquals(ChangeKind.LOCALIZATION,
                ChangeKind.of("generacb2bcore-locales_en.properties"));
    }

    /** Chinese and the other locales that carry a script or a region. */
    @Test
    void localesWithAScriptOrARegion() {
        assertEquals(ChangeKind.LOCALIZATION, ChangeKind.of("core-locales_zh_CN.properties"));
        assertEquals(ChangeKind.LOCALIZATION, ChangeKind.of("core-locales_pt_BR.properties"));
        assertEquals(ChangeKind.LOCALIZATION,
                ChangeKind.of("core-locales_zh_Hans_CN.properties"));
    }

    @Test
    void backofficeLabelsAreKnownByWhereTheyLive() {
        Path labels = Path.of("resources", "generacb2b-backoffice-labels", "labels_tr.properties");
        assertEquals(ChangeKind.LOCALIZATION, ChangeKind.of(labels));

        Path base = Path.of("resources", "generacb2b-backoffice-labels", "labels.properties");
        assertEquals(ChangeKind.LOCALIZATION, ChangeKind.of(base),
                "the untranslated base bundle is a translation file too");
    }

    // ── Spring ───────────────────────────────────────────────────────────

    @Test
    void springsDefaultBundleAndItsSiblings() {
        assertEquals(ChangeKind.LOCALIZATION, ChangeKind.of("messages.properties"));
        assertEquals(ChangeKind.LOCALIZATION, ChangeKind.of("messages_tr.properties"));
        assertEquals(ChangeKind.LOCALIZATION, ChangeKind.of("messages_tr_TR.properties"));
        assertEquals(ChangeKind.LOCALIZATION, ChangeKind.of("messages_zh_Hans_CN.properties"));
    }

    /**
     * The bundle Bean Validation reads, by that exact name, from the classpath
     * root. It is a message bundle by specification rather than by convention:
     * an application that overrides a constraint's wording puts it here, and
     * Hibernate Validator caches it exactly as the message sources do.
     */
    @Test
    void theBundleBeanValidationReads() {
        assertEquals(ChangeKind.LOCALIZATION, ChangeKind.of("ValidationMessages.properties"),
                "a save here changes the text of every validation failure, and treating it "
                        + "as configuration pushes constraint wording into the property system");
        assertEquals(ChangeKind.LOCALIZATION, ChangeKind.of("ValidationMessages_tr.properties"));
    }

    // ── and the ones that are not translations ───────────────────────────

    @Test
    void configurationIsStillConfiguration() {
        assertEquals(ChangeKind.PROPERTIES, ChangeKind.of("local.properties"));
        assertEquals(ChangeKind.PROPERTIES, ChangeKind.of("application.properties"));
        assertEquals(ChangeKind.PROPERTIES, ChangeKind.of("95-local.properties"));
        assertEquals(ChangeKind.PROPERTIES, ChangeKind.of("application-dev.properties"));
    }

    /**
     * The narrow rule earns its keep here. "messagebroker.properties" opens
     * with the same eight letters as the bundle and is configuration; getting
     * that one wrong would stop a real setting from being rebound.
     */
    @Test
    void aNameThatMerelyStartsLikeABundleIsNotOne() {
        assertEquals(ChangeKind.PROPERTIES, ChangeKind.of("messagebroker.properties"));
        assertEquals(ChangeKind.PROPERTIES, ChangeKind.of("messaging.properties"));
        assertEquals(ChangeKind.PROPERTIES, ChangeKind.of("messages-config.properties"),
                "a hyphen is not the separator a locale suffix uses");
    }
}
