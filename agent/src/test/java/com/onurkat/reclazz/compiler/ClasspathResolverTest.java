/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.compiler;

import com.onurkat.reclazz.hybris.HybrisContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test: the internal compiler's classpath must include jars
 * nested under platform/ext/&lt;name&gt;/lib. In SAP Commerce 2211 the Spring
 * jars live in ext/core/lib — a flat scan of ext/ found nothing, so every
 * autoCompile attempt failed with
 * "package org.springframework.stereotype does not exist".
 */
class ClasspathResolverTest {

    @TempDir
    Path tmp;

    @Test
    void includesNestedPlatformExtLibsAndTomcatLib() throws IOException {
        Path platform = tmp.resolve("hybris/bin/platform");
        Files.createDirectories(platform.resolve("lib"));
        Files.createDirectories(tmp.resolve("hybris/config"));

        // Nested platform extension jar (the 2211 Spring location)
        Path coreLib = platform.resolve("ext/core/lib");
        Files.createDirectories(coreLib);
        Files.createFile(coreLib.resolve("spring-beans-6.2.12.jar"));

        // Platform extension classes dir
        Path hacClasses = platform.resolve("ext/hac/classes");
        Files.createDirectories(hacClasses);

        // Servlet API in tomcat/lib
        Path tomcatLib = platform.resolve("tomcat/lib");
        Files.createDirectories(tomcatLib);
        Files.createFile(tomcatLib.resolve("servlet-api.jar"));

        // Compiled platform code shipped as a jar in ext/<name>/bin
        // (2211: ext/core/bin/coreserver.jar holds the servicelayer)
        Path coreBin = platform.resolve("ext/core/bin");
        Files.createDirectories(coreBin);
        Files.createFile(coreBin.resolve("coreserver.jar"));

        Files.writeString(tmp.resolve("hybris/config/localextensions.xml"),
                "<hybrisconfig><extensions/></hybrisconfig>");

        HybrisContext context = new HybrisContext(tmp.resolve("hybris"));
        context.initialize();

        String classpath = new ClasspathResolver(context).resolve();

        assertTrue(classpath.contains("spring-beans-6.2.12.jar"),
                "ext/core/lib jars must be on the compile classpath");
        assertTrue(classpath.contains(hacClasses.toString()),
                "ext/<name>/classes dirs must be on the compile classpath");
        assertTrue(classpath.contains("servlet-api.jar"),
                "tomcat/lib jars must be on the compile classpath");
        assertTrue(classpath.contains("coreserver.jar"),
                "jars inside ext/<name>/bin must be on the compile classpath");
    }
}
