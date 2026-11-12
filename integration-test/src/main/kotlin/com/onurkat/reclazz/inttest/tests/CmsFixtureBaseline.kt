package com.onurkat.reclazz.inttest.tests

import java.nio.file.Files
import java.nio.file.Path

/** Owned CMS files only; native commonweb sources/jars are ignored build outputs. */
class CmsFixtureBaseline(val root: Path) {
    companion object {
        val relatives = listOf("web/webroot/WEB-INF/views/reclazz-page-slot.jsp", "web/webroot/WEB-INF/views/reclazz-uid-paragraph.jsp", "extensioninfo.xml", "project.properties", "buildcallbacks.xml",
            "web/webroot/WEB-INF/web.xml", "web/webroot/WEB-INF/config/reclazztestcms-web-spring.xml",
            "web/webroot/WEB-INF/config/v2-servlet.xml", "web/webroot/WEB-INF/views/reclazz-paragraph.jsp",
            "web/src/com/onurkat/reclazztestcms/controllers/ProbeController.java",
            "web/src/com/onurkat/reclazztestcms/controllers/ReclazzParagraphComponentController.java")
        fun original(relative: String): ByteArray = CmsFixtureBaseline::class.java.classLoader
            .getResourceAsStream("templates/cms/$relative")?.use { it.readBytes() } ?: error("Missing immutable CMS fixture: $relative")
    }
    fun checkBytes() {
        for (relative in relatives) check(Files.readAllBytes(root.resolve(relative)).contentEquals(original(relative))) { "Dirty CMS fixture: $relative" }
    }
}
