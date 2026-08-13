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

    /** {@code .impex}, imported only when the user opted in. */
    IMPEX,

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

        if (fileName.endsWith(".properties") || fileName.endsWith(".yml")
                || fileName.endsWith(".yaml")) {
            return PROPERTIES;
        }
        if (fileName.endsWith(".impex")) return IMPEX;

        return UNKNOWN;
    }
}
