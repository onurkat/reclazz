/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.compiler;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.config.AgentConfig;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MavenJavacTest {
    @TempDir Path dir;
    private String snapshot(boolean mapper) throws Exception {
        Properties p = new Properties();
        p.setProperty("jdk", System.getProperty("java.specification.version"));
        p.setProperty("classpath", dir.toString());
        p.setProperty("roots", dir.resolve("src").toString());
        p.setProperty("encoding", "ISO-8859-1");
        p.setProperty("option.0", "--release");
        p.setProperty("option.1", "11");
        p.setProperty("option.2", "-Xlint:rawtypes");
        p.setProperty("option.3", "-Werror");
        p.setProperty("mapstruct", Boolean.toString(mapper));
        p.setProperty("generated", dir.resolve("generated").toString());
        Path file = dir.resolve("snapshot.properties");
        try (var out = Files.newBufferedWriter(file)) { p.store(out, null); }
        return Base64.getUrlEncoder().encodeToString(file.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test void compilerUsesReleaseCharsetAndFatalWarnings() throws Exception {
        Path src = Files.createDirectory(dir.resolve("src")).resolve("Example.java");
        Files.writeString(src, "public class Example { public static String value(){return \"café\";} }", StandardCharsets.ISO_8859_1);
        var compiler = new IncrementalCompiler("", (PlatformContext) null);
        compiler.configureMaven(AgentConfig.parse("autoCompile=true,mavenCompiler=" + snapshot(false)));
        Path staging = dir.resolve("first");
        assertEquals(List.of(), compiler.compileStaged("app", List.of(src), staging, List.of()));
        byte[] output = Files.readAllBytes(staging.resolve("Example.class"));
        assertEquals(55, output[7]);
        try (var loader = new URLClassLoader(new URL[]{staging.toUri().toURL()})) {
            assertEquals("café", loader.loadClass("Example").getMethod("value").invoke(null));
        }
        Files.writeString(src, "class Example { java.util.List raw; }");
        assertFalse(compiler.compileStaged("app", List.of(src), dir.resolve("second"), List.of()).isEmpty());
    }

    @Test void unavailableSnapshotKeepsExternalCompilationAtLaunch() {
        String missing = Base64.getUrlEncoder().encodeToString(dir.resolve("missing").toString().getBytes(StandardCharsets.UTF_8));
        AgentConfig config = AgentConfig.parse("autoCompile=true,mavenCompiler=" + missing);
        assertFalse(config.isAutoCompile());
        assertNotNull(config.getMavenCompilerError());
    }

    @Test void aDtoEditIncludesMappersAndIgnoresAnnotationTextInComments() throws Exception {
        Path root = Files.createDirectory(dir.resolve("src"));
        Path dto = Files.writeString(root.resolve("Dto.java"), "class Dto {}");
        Path mapper = Files.writeString(root.resolve("Mapping.java"), "@org.mapstruct.Mapper interface Mapping { Dto map(Dto v); }");
        Files.writeString(root.resolve("Other.java"), "/* @Mapper */ class Other {}");
        var cfg = AgentConfig.parse("mavenCompiler=" + snapshot(true)).getMavenCompiler();
        assertEquals(Set.of(dto, mapper), Set.copyOf(MavenJavac.sources(cfg, ToolProvider.getSystemJavaCompiler(), List.of(dto))));
    }
}
