/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.hybris

import java.nio.file.Files
import java.nio.file.Path

/**
 * Works out which SAP Commerce properties file has the last word, and
 * what a property currently resolves to.
 *
 * Hybris reads properties in a fixed order, each layer overriding the
 * previous one:
 *
 *   1. `bin/platform/project.properties` (and every extension's)
 *   2. `config/local.properties`
 *   3. every `*.properties` in the optional config directory, sorted by
 *      name, when `hybris.optional.config.dir` points at one
 *
 * Writing to the wrong layer is silent: the value lands in the file, the
 * server ignores it, and nothing explains why. So Reclazz writes into the
 * LAST layer, and reads the effective value from the layers below before
 * it does.
 */
object HybrisPropertyFiles {

    /**
     * File Reclazz writes into.
     *
     * The name is not cosmetic. Files in the optional config directory are
     * only read when they match `<digits>-local.properties`: verified on a
     * real installation, where `20-local.properties` was picked up while
     * `00-reclazz.properties` and `90-reclazz-local.properties` in the same
     * directory were both ignored entirely. A descriptive filename would
     * have produced a settings screen reporting success while the server
     * never saw the agent.
     *
     * The high number matters too: later files win, so this overrides the
     * `10-local.properties` most projects ship with.
     */
    const val RECLAZZ_PROPERTIES = "95-local.properties"

    /** The only filenames Hybris reads from the optional config directory. */
    private val OPTIONAL_PROPERTIES_NAME = Regex("""\d+-local\.properties""")

    /**
     * The optional config directory, when one is configured and exists.
     *
     * Read from `hybris.optional.config.dir` in local.properties, whose
     * value normally contains `${HYBRIS_CONFIG_DIR}`.
     */
    fun optionalConfigDir(configDir: Path): Path? {
        val localProperties = configDir.resolve("local.properties")
        if (!Files.isRegularFile(localProperties)) return null

        val raw = readProperty(localProperties, "hybris.optional.config.dir") ?: return null
        val resolved = raw
            .replace("\${HYBRIS_CONFIG_DIR}", configDir.toString())
            .replace("\${HYBRIS_BIN_DIR}", configDir.parent?.resolve("bin")?.toString() ?: "")
            .trim()
        if (resolved.isEmpty()) return null

        val dir = Path.of(resolved)
        if (Files.isDirectory(dir)) return dir

        // The property names a directory that does not exist yet. Creating
        // it keeps Reclazz inside its own file instead of falling back to
        // the user's local.properties, which is usually version-controlled.
        return try {
            Files.createDirectories(dir)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * True when Reclazz would have to write into a file the user owns,
     * rather than one of its own. Callers say so out loud before doing it.
     */
    fun writesIntoUserFile(configDir: Path): Boolean =
        targetFile(configDir).fileName.toString() != RECLAZZ_PROPERTIES

    /**
     * Where Reclazz should write. The optional directory when there is
     * one, otherwise local.properties itself.
     */
    fun targetFile(configDir: Path): Path {
        val optional = optionalConfigDir(configDir)
        return optional?.resolve(RECLAZZ_PROPERTIES) ?: configDir.resolve("local.properties")
    }

    /**
     * Value a property resolves to WITHOUT Reclazz's own file, so an
     * install can append to what the user configured instead of erasing
     * it. Returns an empty string when nothing defines it.
     */
    fun effectiveValue(platformHome: Path, configDir: Path, key: String): String {
        var value = ""

        readProperty(platformHome.resolve("project.properties"), key)?.let { value = it }
        readProperty(configDir.resolve("local.properties"), key)?.let { value = it }

        optionalConfigDir(configDir)?.let { dir ->
            propertyFilesInLoadOrder(dir)
                .filter { it.fileName.toString() != RECLAZZ_PROPERTIES }
                .forEach { file -> readProperty(file, key)?.let { value = it } }
        }

        return value.trim()
    }

    /**
     * Files Hybris actually reads from the optional directory, in the
     * order it reads them (sorted by name, later ones overriding earlier).
     * Anything not matching the naming convention is ignored, exactly as
     * the platform ignores it.
     */
    fun propertyFilesInLoadOrder(dir: Path): List<Path> =
        try {
            Files.list(dir).use { stream ->
                stream.filter { OPTIONAL_PROPERTIES_NAME.matches(it.fileName.toString()) }
                    .sorted(compareBy { it.fileName.toString() })
                    .toList()
            }
        } catch (e: Exception) {
            emptyList()
        }

    /**
     * Last uncommented definition of [key] in [file], or null.
     *
     * Deliberately simple: Hybris property files are flat `key=value`
     * lines. Continuation lines are not supported here, and the values
     * Reclazz touches never use them.
     */
    fun readProperty(file: Path, key: String): String? {
        if (!Files.isRegularFile(file)) return null
        return try {
            Files.readAllLines(file)
                .asSequence()
                .map { it.trim() }
                .filter { !it.startsWith("#") && !it.startsWith("!") }
                .filter { it.startsWith("$key=") }
                .map { it.substringAfter("=") }
                .lastOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
