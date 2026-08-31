/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.watcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A file classified as nothing is dropped without a word, which is the failure
 * mode this classifier exists to make impossible to reach by accident.
 *
 * <p>The line that matters most here is between configuration and text. A
 * properties file that is configuration goes into the running Environment and
 * rebinds beans; one that is a message bundle must never be pushed there, and
 * needs a cache dropped instead. Spring's default basename is the only bundle
 * name that can be recognised from the name alone, so it is the only one
 * claimed: a bundle called something else stays configuration, which is the
 * harmless direction, while a configuration file misread as text would quietly
 * stop being rebound.
 */
class ChangeKindTest {

    @Test
    void springsDefaultMessageBundleIsText() {
        assertEquals(ChangeKind.LOCALIZATION, ChangeKind.of("messages.properties"));
        assertEquals(ChangeKind.LOCALIZATION, ChangeKind.of("messages_tr.properties"));
        assertEquals(ChangeKind.LOCALIZATION, ChangeKind.of("messages_en_GB.properties"));
    }

    @Test
    void sapCommerceLocalesFilesStayText() {
        assertEquals(ChangeKind.LOCALIZATION, ChangeKind.of("mycore-locales_en.properties"));
    }

    @Test
    void configurationIsStillConfiguration() {
        assertEquals(ChangeKind.PROPERTIES, ChangeKind.of("application.properties"));
        assertEquals(ChangeKind.PROPERTIES, ChangeKind.of("local.properties"));
        assertEquals(ChangeKind.PROPERTIES, ChangeKind.of("application.yml"));
        assertEquals(ChangeKind.PROPERTIES, ChangeKind.of("messagesConfig.properties"),
                "a name that merely starts with the word is not the bundle");
    }

    @Test
    void theOtherKindsAreUnchanged() {
        assertEquals(ChangeKind.CLASS_FILE, ChangeKind.of("Foo.class"));
        assertEquals(ChangeKind.JAVA_SOURCE, ChangeKind.of("Foo.java"));
        assertEquals(ChangeKind.SPRING_XML, ChangeKind.of("mycore-spring.xml"));
        assertEquals(ChangeKind.CODEGEN_XML, ChangeKind.of("mycore-items.xml"));
        assertEquals(ChangeKind.LOGGING_CONFIG, ChangeKind.of("logback.xml"));
        assertEquals(ChangeKind.IMPEX, ChangeKind.of("data.impex"));
        assertEquals(ChangeKind.UNKNOWN, ChangeKind.of("notes.txt"));
    }
}
