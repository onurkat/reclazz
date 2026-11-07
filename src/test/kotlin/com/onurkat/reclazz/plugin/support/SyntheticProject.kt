/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.support

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path

/** Isolated on-disk layout; IDE services still belong to the disposable fixture project. */
class SyntheticProject(delegate: Project, sap: Boolean) : AutoCloseable {
    val home: Path = Files.createTempDirectory("reclazz-plugin-fixture-")
    val project: Project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, args ->
        when (method.name) {
            "getBasePath" -> home.toString()
            "getName" -> "Synthetic Reclazz Project"
            else -> method.invoke(delegate, *(args ?: emptyArray()))
        }
    } as Project

    init {
        if (sap) {
            Files.createDirectories(home.resolve("bin/platform"))
            Files.createDirectories(home.resolve("config"))
            Files.writeString(home.resolve("config/localextensions.xml"), "<hybrisconfig><extensions/></hybrisconfig>")
            Files.writeString(home.resolve("config/local.properties"), "")
        }
    }

    override fun close() {
        Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
}
