package com.onurkat.reclazz.inttest.tests

import java.nio.file.Files
import java.nio.file.Path

/** Profile-only files: never deploy or restore these in the ordinary default runner. */
class SolrFixtureBaseline(val root:Path) {
    companion object {
        val relatives=listOf("extensioninfo.xml","project.properties","resources/reclazztestsolr-spring.xml",
            "web/webroot/WEB-INF/web.xml","web/webroot/WEB-INF/config/reclazztestsolr-web-spring.xml",
            "web/webroot/WEB-INF/config/v2-servlet.xml","web/src/com/onurkat/reclazztestsolr/controllers/SolrFactsController.java")+
            listOf("ReclazzChangedValueProvider","ReclazzStableValueProvider","ReclazzControlValueProvider")
                .map {"src/com/onurkat/reclazztestsolr/$it.java"}
        fun original(relative:String):ByteArray=SolrFixtureBaseline::class.java.classLoader
            .getResourceAsStream("templates/solr/$relative")?.use {it.readBytes()} ?: error("Missing immutable Solr fixture: $relative")
    }
    fun checkBytes() {
        for(relative in relatives) check(Files.readAllBytes(root.resolve(relative)).contentEquals(original(relative))) {"Dirty Solr fixture: $relative"}
    }
}
