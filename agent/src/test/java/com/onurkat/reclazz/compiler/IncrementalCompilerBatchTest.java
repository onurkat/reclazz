/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.compiler;

import com.onurkat.reclazz.hybris.HybrisContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link IncrementalCompiler#compileBatch}: a save-all must compile
 * in as few javac invocations as possible (one per output directory) while
 * still returning every produced class, and a broken file holds the complete package without changing its outputs.
 */
class IncrementalCompilerBatchTest {

    @TempDir
    Path tmp;

    private Path hybrisHome;
    private Path extDir;
    private IncrementalCompiler compiler;

    @BeforeEach
    void setUp() throws Exception {
        hybrisHome = tmp.resolve("hybris");
        Path platform = hybrisHome.resolve("bin/platform");
        Files.createDirectories(platform.resolve("lib"));
        Files.createDirectories(hybrisHome.resolve("config"));

        extDir = hybrisHome.resolve("bin/custom/myext");
        Files.createDirectories(extDir.resolve("src/com/example"));
        Files.createDirectories(extDir.resolve("web/src/com/example/web"));
        Files.writeString(extDir.resolve("extensioninfo.xml"), """
                <extensioninfo>
                    <extension classprefix="myext" name="myext"/>
                </extensioninfo>
                """);
        Files.writeString(hybrisHome.resolve("config/localextensions.xml"),
                "<hybrisconfig><extensions><extension name='myext'/></extensions></hybrisconfig>");

        HybrisContext context = new HybrisContext(hybrisHome);
        context.initialize();
        compiler = new IncrementalCompiler(new ClasspathResolver(context).resolve(), context);
    }

    private Path writeCore(String simpleName, String body) throws IOException {
        Path f = extDir.resolve("src/com/example/" + simpleName + ".java");
        Files.writeString(f, "package com.example;\npublic class " + simpleName + " {" + body + "}\n");
        return f;
    }

    @Test
    void compilesEveryFileInOneBatch() throws Exception {
        List<Path> files = List.of(
                writeCore("A", " public String v() { return \"a\"; } "),
                writeCore("B", " public String v() { return \"b\"; } "),
                writeCore("C", " public String v() { return \"c\"; } "));

        IncrementalCompiler.CompileResult result = compiler.compileBatch(files, "myext");

        assertTrue(result.isSuccess(), () -> "errors: " + result.getErrors());
        assertTrue(result.getCompiledClasses().keySet()
                        .containsAll(List.of("com.example.A", "com.example.B", "com.example.C")),
                "every class in the batch must come back: " + result.getCompiledClasses().keySet());
    }

    @Test
    void groupsCoreAndWebSourcesIntoTheirOwnOutputDirs() throws Exception {
        Path core = writeCore("CoreOne", " public int x() { return 1; } ");
        Path web = extDir.resolve("web/src/com/example/web/WebOne.java");
        Files.writeString(web, "package com.example.web;\npublic class WebOne { public int y() { return 2; } }\n");

        IncrementalCompiler.CompileResult result = compiler.compileBatch(List.of(core, web), "myext");

        assertTrue(result.isSuccess(), () -> "errors: " + result.getErrors());
        assertTrue(Files.exists(extDir.resolve("classes/com/example/CoreOne.class")),
                "core sources compile into classes/");
        assertTrue(Files.exists(extDir.resolve("web/webroot/WEB-INF/classes/com/example/web/WebOne.class")),
                "web sources compile into WEB-INF/classes/");
    }

    @Test
    void singleFileBatchStillWorks() throws Exception {
        IncrementalCompiler.CompileResult result =
                compiler.compileBatch(List.of(writeCore("Solo", " public int z() { return 9; } ")), "myext");

        assertTrue(result.isSuccess());
        assertTrue(result.getCompiledClasses().containsKey("com.example.Solo"));
    }

    @Test
    void aBrokenFileSurfacesErrorsInsteadOfFailingSilently() throws Exception {
        Path good = writeCore("Good", " public int ok() { return 1; } ");
        Path broken = extDir.resolve("src/com/example/Broken.java");
        Files.writeString(broken, "package com.example;\npublic class Broken { this is not java }\n");

        IncrementalCompiler.CompileResult result = compiler.compileBatch(List.of(good, broken), "myext");

        assertFalse(result.isSuccess(), "a batch whose only group failed must not report success");
        assertFalse(result.getErrors().isEmpty(), "the compiler diagnostics must be reported");
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Broken.java")),
                "errors must name the offending file: " + result.getErrors());
    }

    @Test
    void aBrokenFileInAnotherOutputGroupFailsTheWholeBatch() throws Exception {
        Path good = writeCore("StillGood", " public int ok() { return 1; } ");
        Path brokenWeb = extDir.resolve("web/src/com/example/web/BrokenWeb.java");
        Files.writeString(brokenWeb, "package com.example.web;\npublic class BrokenWeb { nope }\n");

        IncrementalCompiler.CompileResult result = compiler.compileBatch(List.of(good, brokenWeb), "myext");

        assertFalse(result.isSuccess(), "one broken group rejects the complete package");
        assertFalse(Files.exists(extDir.resolve("classes/com/example/StillGood.class")),
                "the successful group must not publish its output either");
    }

    @Test
    void aFailedPackagePreservesThePreviouslyCompiledBytes() throws Exception {
        Path good = writeCore("Stable", " public int value() { return 1; } ");
        assertTrue(compiler.compileBatch(List.of(good), "myext").isSuccess());
        Path output = extDir.resolve("classes/com/example/Stable.class");
        byte[] before = Files.readAllBytes(output);
        writeCore("Stable", " public int value() { return 2; } ");
        Path brokenWeb = extDir.resolve("web/src/com/example/web/BrokenWeb.java");
        Files.writeString(brokenWeb, "package com.example.web;\npublic class BrokenWeb { nope }\n");
        compiler.compileBatch(List.of(good, brokenWeb), "myext");
        assertArrayEquals(before, Files.readAllBytes(output), "failed builds leave real outputs unchanged");
    }
}
