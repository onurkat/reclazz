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

    /**
     * {@code *-backoffice-config.xml}, the cockpitng view configuration. The
     * running backoffice merges these once and answers from a cache; the
     * reload resets that cache through cockpitng's own {@code Resettable}
     * contract so the next view open re-reads the configuration.
     */
    BACKOFFICE_CONFIG,

    /** {@code *-items.xml} and {@code *-beans.xml}, which drive code generation. */
    CODEGEN_XML,

    /** {@code .properties}, {@code .yml}, {@code .yaml}. */
    PROPERTIES,

    /**
     * Static text a framework holds in a cache of its own:
     * {@code <ext>-locales_<iso>.properties} for SAP Commerce type and enum
     * names, the {@code labels_<iso>.properties} files under a backoffice
     * labels folder, and Spring's own {@code messages[_<iso>].properties}
     * bundles. These are not configuration and must never be pushed into it;
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

    /**
     * {@code log4j2.xml} or {@code logback.xml}, in any of the spellings the
     * frameworks accept. The file is a configuration the running context can be
     * pointed at again, so a level or an appender changes without a restart.
     */
    LOGGING_CONFIG,

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

        // Ahead of the generic .xml rules: these names are configuration for a
        // framework rather than for the application.
        if (isLoggingConfig(fileName)) return LOGGING_CONFIG;

        // Order matters against the codegen suffixes below only in the sense
        // that a name cannot end with two of them; kept explicit anyway.
        if (fileName.endsWith("-spring.xml")) return SPRING_XML;
        if (fileName.endsWith("-backoffice-config.xml")) return BACKOFFICE_CONFIG;
        if (fileName.endsWith("-items.xml") || fileName.endsWith("-beans.xml")) return CODEGEN_XML;

        // Before the generic properties rule: a locales file is a properties
        // file by extension and nothing like one in meaning.
        if (isLocalesFile(fileName) || isMessageBundle(fileName)) return LOCALIZATION;

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
     * The names Log4j2 and Logback look for, plus the {@code -test} and
     * {@code -spring} variants both frameworks accept.
     */
    private static boolean isLoggingConfig(String fileName) {
        if (!fileName.endsWith(".xml")) return false;
        String stem = fileName.substring(0, fileName.length() - ".xml".length());
        return stem.equals("log4j2") || stem.startsWith("log4j2-") || stem.startsWith("log4j2.")
                || stem.equals("logback") || stem.startsWith("logback-") || stem.startsWith("logback.");
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

    /**
     * {@code messages.properties} and its per-locale siblings, which is what
     * {@code spring.messages.basename} defaults to.
     *
     * <p>Deliberately only that default. A message bundle can be called
     * anything, and the way to know which is to ask the running
     * {@code MessageSource} for its basenames, which the classifier has no
     * access to and should not: it decides by name alone so it stays
     * testable. Getting this wrong in the other direction is the one that
     * costs something, because a configuration file classified as text would
     * stop being rebound, so the rule is narrow on purpose.
     */
    private static boolean isMessageBundle(String fileName) {
        if (!fileName.endsWith(".properties")) return false;
        String stem = fileName.substring(0, fileName.length() - ".properties".length());
        return stem.equals("messages") || stem.startsWith("messages_")
                || isValidationBundle(stem);
    }

    /**
     * {@code ValidationMessages.properties} and its per-locale siblings.
     *
     * <p>Not a convention like the one above: Bean Validation fixes this name,
     * and an application that rewords a constraint puts it exactly there. It
     * was classified as configuration, which is the mistake that costs
     * something in both directions at once. The new wording did not reach the
     * validator, so the developer saw the old sentence and assumed they had the
     * key wrong; and the keys, which are things like
     * {@code jakarta.validation.constraints.NotBlank.message}, were pushed into
     * the running property system as though somebody had set them there.
     */
    private static boolean isValidationBundle(String stem) {
        return stem.equals("ValidationMessages") || stem.startsWith("ValidationMessages_");
    }
}
