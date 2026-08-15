/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.watcher;

/**
 * What a changed file is, and therefore which reloader should hear about it.
 *
 * This used to be a chain of {@code endsWith} checks inside the agent's event
 * handler, tangled with the config flags that decide whether a given kind is
 * enabled. That made it untestable, which matters more here than it sounds:
 * every one of these paths fails silently. A file classified as nothing is
 * simply dropped, and the user sees a save that produced no reload and no
 * error, which is the failure mode this project keeps having to hunt down.
 *
 * The naming rules are Hybris conventions. Extensions declare
 * {@code <name>-items.xml}, {@code <name>-beans.xml} and
 * {@code <name>-spring.xml}, with web contexts adding
 * {@code <name>-web-spring.xml}, which still ends the same way.
 */
public enum ChangeKind {

    /** Compiled output: the thing that actually gets hot-swapped. */
    CLASS_FILE,

    /** Java source, which is only interesting when compiling for the user. */
    JAVA_SOURCE,

    /** {@code *-spring.xml}, including {@code *-web-spring.xml}. */
    SPRING_XML,

    /** {@code *-items.xml} and {@code *-beans.xml}, which drive code generation. */
    CODEGEN_XML,

    /** {@code .properties}, {@code .yml}, {@code .yaml}. */
    PROPERTIES,

    /**
     * Static text the platform holds in a cache of its own:
     * {@code <ext>-locales_<iso>.properties} for type and enum names, and the
     * {@code labels_<iso>.properties} files under a backoffice labels folder.
     * These are not platform configuration and must never be pushed into it;
     * the cache is dropped so the next read picks the file up.
     */
    LOCALIZATION,

    /** {@code .impex}, imported only when the user opted in. */
    IMPEX,
    /**
     * A template the engine has already parsed and cached: {@code .html},
     * {@code .ftl}, {@code .ftlh}. Nothing is redefined; the cache is dropped
     * so the next render reads the file again.
     */
    TEMPLATE,

    /** Something we watch the directory for but do nothing with. */
    UNKNOWN;

    /**
     * Classify by file name alone. Deliberately independent of configuration:
     * whether a kind is acted on is a separate decision, and mixing the two
     * is what made this untestable before.
     *
     * @param fileName the file name, not the path
     */
    public static ChangeKind of(String fileName) {
        if (fileName == null) return UNKNOWN;

        if (fileName.endsWith(".class")) return CLASS_FILE;
        if (fileName.endsWith(".java")) return JAVA_SOURCE;

        // Order matters against the codegen suffixes below only in the sense
        // that a name cannot end with two of them; kept explicit anyway.
        if (fileName.endsWith("-spring.xml")) return SPRING_XML;
        if (fileName.endsWith("-items.xml") || fileName.endsWith("-beans.xml")) return CODEGEN_XML;

        // Before the generic properties rule: a locales file is a properties
        // file by extension and nothing like one in meaning.
        if (isLocalesFile(fileName)) return LOCALIZATION;

        if (fileName.endsWith(".properties") || fileName.endsWith(".yml")
                || fileName.endsWith(".yaml")) {
            return PROPERTIES;
        }
        if (fileName.endsWith(".impex")) return IMPEX;
        if (fileName.endsWith(".ftl") || fileName.endsWith(".ftlh")
                || fileName.endsWith(".html") || fileName.endsWith(".htm")) {
            return TEMPLATE;
        }

        return UNKNOWN;
    }

    /**
     * Same decision with the directory in hand.
     *
     * Backoffice label files are called {@code labels_en.properties}, a name a
     * Spring application could equally well use for a message bundle, so the
     * folder is what tells them apart: the platform requires them to sit in
     * {@code <ext>-backoffice-labels}.
     */
    public static ChangeKind of(java.nio.file.Path path) {
        if (path == null) return UNKNOWN;
        String fileName = path.getFileName().toString();

        java.nio.file.Path parent = path.getParent();
        if (parent != null && parent.getFileName() != null
                && parent.getFileName().toString().endsWith("-backoffice-labels")
                && fileName.endsWith(".properties")) {
            return LOCALIZATION;
        }

        return of(fileName);
    }

    /** {@code <ext>-locales_<iso>.properties}, the platform's own convention. */
    private static boolean isLocalesFile(String fileName) {
        return fileName.endsWith(".properties") && fileName.contains("-locales_");
    }
}
