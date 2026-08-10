/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.hybris

import com.intellij.openapi.project.Project
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

object ExtensionResolver {

    fun resolveExtensions(project: Project): List<String> {
        val hybrisHome = HybrisProjectDetector.findHybrisHome(project) ?: return emptyList()

        val localExtensionsXml = findLocalExtensionsXml(hybrisHome)
        if (localExtensionsXml == null || !localExtensionsXml.toFile().exists()) {
            return emptyList()
        }

        return parseExtensionNames(localExtensionsXml)
    }

    private fun findLocalExtensionsXml(hybrisHome: Path): Path? {
        val candidates = listOf(
            hybrisHome.resolve("config/localextensions.xml"),
            hybrisHome.resolve("hybris/config/localextensions.xml")
        )
        return candidates.firstOrNull { it.toFile().exists() }
    }

    private fun parseExtensionNames(xmlPath: Path): List<String> {
        val names = mutableListOf<String>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
            val doc = factory.newDocumentBuilder().parse(xmlPath.toFile())
            val extensions = doc.getElementsByTagName("extension")
            for (i in 0 until extensions.length) {
                val element = extensions.item(i)
                val name = element.attributes?.getNamedItem("name")?.nodeValue
                if (!name.isNullOrBlank()) {
                    names.add(name)
                }
            }
        } catch (_: Exception) {}
        return names
    }
}
