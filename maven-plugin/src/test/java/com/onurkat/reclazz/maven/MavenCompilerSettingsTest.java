/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MavenCompilerSettingsTest {
    @TempDir Path dir;

    private MavenProject project() {
        Build build = new Build();
        build.setDirectory(dir.resolve("target").toString());
        build.setOutputDirectory(dir.resolve("target/classes").toString());
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-compiler-plugin");
        plugin.setVersion("3.13.0");
        build.addPlugin(plugin);
        Model model = new Model();
        model.setBuild(build);
        MavenProject project = new MavenProject(model);
        project.setFile(dir.resolve("pom.xml").toFile());
        project.addCompileSourceRoot(dir.resolve("src/main/java").toString());
        return project;
    }

    private Properties read(String encoded) throws IOException {
        var result = new Properties();
        Path file = Path.of(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
        try (var in = Files.newBufferedReader(file)) { result.load(in); }
        return result;
    }

    @Test void settingsAreStableAndCarryTheEffectiveCompilerProperties() throws Exception {
        var p = project();
        p.getProperties().setProperty("maven.compiler.release", "11");
        p.getProperties().setProperty("project.build.sourceEncoding", "ISO-8859-1");
        p.getProperties().setProperty("maven.compiler.failOnWarning", "true");
        String key = new MavenCompilerSettings(p, null, null).prepare(false);
        assertEquals(key, new MavenCompilerSettings(p, null, null).prepare(false));
        var settings = read(key);
        assertEquals("ISO-8859-1", settings.getProperty("encoding"));
        assertEquals("--release", settings.getProperty("option.0"));
        assertEquals("11", settings.getProperty("option.1"));
        assertTrue(settings.containsValue("-Werror"));
    }

    @Test void usesTheDefaultsOfTheSelectedCompilerPlugin() throws Exception {
        for (String version : List.of("3.10.1", "3.11.0", "3.13.0", "3.14.0", "3.14.1", "3.15.0")) {
            var project = project();
            project.getBuildPlugins().get(0).setVersion(version);
            var settings = read(new MavenCompilerSettings(project, null, null).prepare(false));
            String level = version.equals("3.10.1") ? "1.7" : "1.8";
            assertEquals("-source", settings.getProperty("option.0"), version);
            assertEquals(level, settings.getProperty("option.1"), version);
            assertEquals("-target", settings.getProperty("option.2"), version);
            assertEquals(level, settings.getProperty("option.3"), version);
            assertEquals(version.equals("3.10.1"), settings.containsValue("-nowarn"), version);
        }
    }

    @Test void olderCompileMojosReadProcAndCompilerArgumentFromXml() throws Exception {
        for (String version : List.of("3.10.1", "3.11.0")) {
            var p = project();
            var plugin = p.getBuildPlugins().get(0);
            plugin.setVersion(version);
            p.getProperties().setProperty("maven.compiler.proc", "none");
            p.getProperties().setProperty("maven.compiler.compilerArgument", "-Werror");
            p.getProperties().setProperty("maven.compiler.encoding", "UTF-16");
            p.getProperties().setProperty("encoding", "ISO-8859-1");
            var settings = read(new MavenCompilerSettings(p, null, null).prepare(false));
            assertFalse(settings.containsValue("-proc:none"), version);
            assertFalse(settings.containsValue("-Werror"), version);
            assertEquals("ISO-8859-1", settings.getProperty("encoding"), version);
            var config = new Xpp3Dom("configuration");
            var proc = new Xpp3Dom("proc");
            proc.setValue("none");
            config.addChild(proc);
            var argument = new Xpp3Dom("compilerArgument");
            argument.setValue("-Werror");
            config.addChild(argument);
            plugin.setConfiguration(config);
            settings = read(new MavenCompilerSettings(p, null, null).prepare(false));
            assertTrue(settings.containsValue("-proc:none"), version);
            assertTrue(settings.containsValue("-Werror"), version);
        }
    }

    @Test void fallbackNamesTheUnverifiedPluginVersion() {
        var project = project();
        project.getBuildPlugins().get(0).setVersion("3.12.1");
        var reason = assertThrows(IOException.class, () -> new MavenCompilerSettings(project, null, null).prepare(false));
        assertTrue(reason.getMessage().contains("3.12.1"), reason::getMessage);
    }

    @Test void processorOnCompileClasspathRequiresExplicitMapperCoverage() throws Exception {
        var p = project();
        Path provider = Path.of(p.getBuild().getOutputDirectory(), "META-INF/services/javax.annotation.processing.Processor");
        Files.createDirectories(provider.getParent());
        Files.writeString(provider, "org.mapstruct.ap.MappingProcessor\n");
        assertTrue(assertThrows(IOException.class, () -> new MavenCompilerSettings(p, null, null).prepare(false))
                .getMessage().contains("org.mapstruct.ap.MappingProcessor"));
        assertEquals("true", read(new MavenCompilerSettings(p, null, null).prepare(true)).getProperty("mapstruct"));
        Files.writeString(provider, "com.example.UnknownProcessor\n");
        assertThrows(IOException.class, () -> new MavenCompilerSettings(p, null, null).prepare(true));
    }
}
