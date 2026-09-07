/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.compiler;

import com.onurkat.reclazz.hybris.HybrisContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompileAttemptTest {
    @TempDir Path tmp;
    Path module(String name) { return tmp.resolve("bin/custom/" + name); }
    Path source(String module, String root, String name, String body) throws Exception {
        Path path = module(module).resolve(root + "/example/" + name + ".java");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "package example;\npublic class " + name + " { " + body + " }\n");
        return path;
    }
    IncrementalCompiler compiler() throws Exception {
        Files.createDirectories(tmp.resolve("bin/platform/lib"));
        Files.createDirectories(tmp.resolve("config"));
        for (String name : List.of("core", "web")) {
            Files.createDirectories(module(name));
            Files.writeString(module(name).resolve("extensioninfo.xml"),
                    "<extensioninfo><extension name='" + name + "'>"
                    + (name.equals("web") ? "<requires-extension name='core'/>" : "")
                    + "</extension></extensioninfo>");
        }
        Files.writeString(tmp.resolve("config/localextensions.xml"),
                "<hybrisconfig><extensions><extension name='core'/><extension name='web'/></extensions></hybrisconfig>");
        HybrisContext context = new HybrisContext(tmp);
        context.initialize();
        return new IncrementalCompiler(new ClasspathResolver(context).resolve(), context);
    }

    @Test void failedModuleLeavesAllOutputsAndRetriesWithStagedDependencies() throws Exception {
        Path core = source("core", "src", "Core", "public int old() { return 1; }");
        Path web = source("web", "src", "Web", "public int value() { return new Core().old(); }");
        IncrementalCompiler compiler = compiler();
        Map<String, List<Path>> modules = new LinkedHashMap<>();
        modules.put("web", List.of(web)); // caller arrives first; dependency must compile first
        modules.put("core", List.of(core));
        var initial = compiler.compilePackage(modules);
        assertTrue(initial.isSuccess(), initial.getErrors().toString());
        Path coreClass = module("core").resolve("classes/example/Core.class");
        Path webClass = module("web").resolve("classes/example/Web.class");
        byte[] coreBefore = Files.readAllBytes(coreClass), webBefore = Files.readAllBytes(webClass);
        source("core", "src", "Core", "public int added() { return 2; }");
        source("web", "src", "Web", "not Java");
        assertFalse(compiler.compilePackage(modules).isSuccess());
        assertArrayEquals(coreBefore, Files.readAllBytes(coreClass));
        assertArrayEquals(webBefore, Files.readAllBytes(webClass));
        source("web", "src", "Web", "public int value() { return new Core().added(); }");
        var retried = compiler.compilePackage(modules);
        assertTrue(retried.isSuccess(), retried.getErrors().toString());
        assertFalse(Arrays.equals(coreBefore, Files.readAllBytes(coreClass)));
        assertFalse(Arrays.equals(webBefore, Files.readAllBytes(webClass)));
    }

    @Test void stagedCoreIsVisibleToWebAndImplicitOutputsAreCollected() throws Exception {
        Path core = source("core", "src", "Core", "public int added() { return 2; }");
        Path helper = source("core", "src", "Helper", "public int value() { return 3; }");
        Path web = source("core", "web/src", "Web", "public int value() { return new Core().added() + new Helper().value(); }");
        var result = compiler().compileBatch(List.of(web, core), "core");
        assertTrue(result.isSuccess(), result.getErrors().toString());
        assertTrue(result.getCompiledClasses().keySet().containsAll(List.of("example.Core", "example.Helper", "example.Web")),
                result.getCompiledClasses().keySet().toString());
    }

    @Test void publicationFailureReportsNoSuccessAndCanBeRetried() throws Exception {
        Path a = source("core", "src", "A", "public int value() { return 1; }");
        Path b = source("core", "src", "B", "public int value() { return 2; }");
        var compiler = compiler();
        Path blocked = module("core").resolve("classes/example/B.class");
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("blocker"), "cannot replace a nonempty directory");
        var result = compiler.compileBatch(List.of(a, b), "core");
        assertFalse(result.isSuccess());
        assertTrue(result.getCompiledClasses().isEmpty());
        assertTrue(result.getErrors().toString().contains("B.class"), result.getErrors().toString());
        Files.delete(blocked.resolve("blocker")); Files.delete(blocked);
        assertTrue(compiler.compileBatch(List.of(a, b), "core").isSuccess());
    }

    @Test void requeueDoesNotResurrectDeletionOrOverwriteANewerEvent() throws Exception {
        Path missing = tmp.resolve("deleted.java"), exists = Files.writeString(tmp.resolve("current.java"), "source");
        Map<Path, Integer> pending = new LinkedHashMap<>();
        pending.put(exists, 2);
        CompileAttempt.retainExisting(pending, Map.of(missing, 1, exists, 1));
        assertEquals(Map.of(exists, 2), pending);
    }
}
