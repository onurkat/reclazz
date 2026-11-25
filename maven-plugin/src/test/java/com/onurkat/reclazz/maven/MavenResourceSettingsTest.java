/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.maven;

import org.codehaus.plexus.util.SelectorUtils;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Properties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.model.*;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MavenResourceSettingsTest {
    @TempDir Path dir;

    private MavenProject project() {
        var resource = new Resource();
        resource.setDirectory("custom");
        var build = new Build();
        build.setDirectory(dir.resolve("target").toString());
        build.setOutputDirectory(dir.resolve("target/classes").toString());
        build.addResource(resource);
        var plugin = new Plugin();
        plugin.setArtifactId("maven-resources-plugin");
        plugin.setVersion("3.3.1");
        build.addPlugin(plugin);
        var model = new Model();
        model.setBuild(build);
        var project = new MavenProject(model);
        project.setFile(dir.resolve("pom.xml").toFile());
        return project;
    }

    private Properties settings(MavenProject project) throws Exception {
        Path file = MavenResourceSettings.snapshot(project, null);
        assertEquals(file, MavenResourceSettings.snapshot(project, null));
        var p = new Properties();
        try (var reader = Files.newBufferedReader(file)) { p.load(reader); }
        return p;
    }

    @Test void customExecutionUnknownVersionAndUnresolvedFilteringKeepSourceForAdvice() throws Exception {
        var p = project();
        var plugin = p.getBuildPlugins().get(0);
        plugin.setVersion("unknown");
        assertEquals("advice", settings(p).getProperty("mode"));
        plugin.setVersion("3.3.1");
        var execution = new PluginExecution();
        execution.setId("default-resources");
        execution.addGoal("copy-resources");
        plugin.addExecution(execution);
        assertEquals("advice", settings(p).getProperty("mode"));
        p = project();
        p.getResources().get(0).setFiltering("${unknown}");
        var result = settings(p);
        assertEquals("advice", result.getProperty("mode"));
        assertEquals(dir.resolve("custom").toString(), result.getProperty("root.0.path"));
    }

    @Test void pinnedDefaultExclusionsMatchTheNativeResourcesGoal() throws Exception {
        var result = settings(project());
        assertEquals("3.3.1", result.getProperty("version"));
        int count = Integer.parseInt(result.getProperty("root.0.exclude.count"));
        for (String name : List.of(".DS_Store", "nested/.cvsignore", "nested/backup~")) {
            assertTrue(java.util.stream.IntStream.range(0, count)
                    .anyMatch(i -> name.matches(result.getProperty("root.0.exclude." + i))), name);
        }
    }

    @Test void supportedSelectorsAgreeWithMavenOnRootAndNestedFiles() throws Exception {
        for (String glob : List.of("**", "*.properties", "**/*.html", "a/**/b?.txt", "a/**", "**/x/**/z",
                "a/[x]+.txt", "**/**/x", "**/.DS_Store", "**/*~", "a/b/*.x")) {
            for (String path : List.of("app.properties","one/app.properties","day.html","a","a/n/b1.txt","a/bz.txt","x/z",
                    "m/x/n/z","a/[x]+.txt","x","a/x",".DS_Store","a/.DS_Store","a/file~","a/b/d.x")) {
                assertEquals(SelectorUtils.matchPath(glob, path), path.matches(MavenResourceSettings.regex(glob)),
                        glob + " matches " + path);
            }
        }
    }
}
