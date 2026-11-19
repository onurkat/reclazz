/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.compiler;

import com.onurkat.reclazz.platform.HybrisContext;
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

    @Test void platformGeneratedByproductIsNotWrittenIntoExtension() throws Exception {
        Path generated = tmp.resolve("bin/platform/bootstrap/gensrc/example/Dto.java");
        Files.createDirectories(generated.getParent());
        Files.writeString(generated, "package example; public class Dto { public int value() { return 7; } }");
        Path caller = source("core", "src", "Caller", "public int value() { return new Dto().value(); }");
        var result = compiler().compileBatch(List.of(caller), "core");
        assertTrue(result.isSuccess(), result.getErrors().toString());
        assertTrue(result.getCompiledClasses().containsKey("example.Dto"), "still returned for in-memory admission");
        assertFalse(Files.exists(module("core").resolve("classes/example/Dto.class")), "must not shadow models.jar");
        assertTrue(Files.isRegularFile(module("core").resolve("classes/example/Caller.class")));
    }

    @Test void ownExtensionGensrcClassIsStillWritten() throws Exception {
        Path generated = source("core", "gensrc", "Dto", "public int value() { return 3; }");
        Path bootstrap = tmp.resolve("bin/platform/bootstrap/gensrc/example/Dto.java");
        Files.createDirectories(bootstrap.getParent());
        Files.writeString(bootstrap, "package example; public class Dto { public int value() { return 7; } }");
        var result = compiler().compileBatch(List.of(generated), "core");
        assertTrue(result.isSuccess(), result.getErrors().toString());
        assertTrue(result.getCompiledClasses().containsKey("example.Dto"));
        assertTrue(Files.isRegularFile(module("core").resolve("classes/example/Dto.class")));
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
        // Helper was compiled only because Web uses it; it still belongs to the core output.
        assertTrue(Files.isRegularFile(module("core").resolve("classes/example/Helper.class")));
        assertFalse(Files.exists(module("core").resolve("web/webroot/WEB-INF/classes/example/Helper.class")));
        assertFalse(Files.exists(module("core").resolve("web/webroot/WEB-INF/classes/example/Core.class")));
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

    /**
     * Files queued only because they read a changed constant were never saved
     * by anyone. When they fail to compile (on SAP Commerce, other extensions'
     * sources often do with the agent's classpath) holding them meant every
     * later save joined the same failing batch, and nothing reloaded again
     * until a restart. They are dropped; what the developer saved stays held.
     */
    @Test void aFailedBatchDropsSpeculativeFilesAndKeepsSavedOnes() throws Exception {
        Path saved = Files.writeString(tmp.resolve("Saved.java"), "class Saved {}");
        Path dependent = Files.writeString(tmp.resolve("Dependent.java"), "class Dependent {}");
        Path savedLater = Files.writeString(tmp.resolve("Both.java"), "class Both {}");
        Map<Path, String> pending = new LinkedHashMap<>(Map.of(saved, "s", dependent, "d", savedLater, "b"));
        Set<Path> speculative = new HashSet<>(Set.of(dependent));

        assertEquals(List.of(dependent), CompileAttempt.dropSpeculative(pending, speculative));
        assertEquals(Set.of(saved, savedLater), pending.keySet());
        assertTrue(speculative.isEmpty());
    }
    @Test void loadedIdenticalByproductIsSkippedWithoutRewritingItsFile() throws Exception {
        Path helper=source("core","src","Helper","public int value(){return 3;}");
        Path own=source("core","src","Own","public int value(){return new Helper().value();}");
        var compiler=compiler();
        Path published=module("core").resolve("classes/example/Helper.class");
        assertTrue(compiler.compileBatch(List.of(helper),"core").isSuccess());
        byte[] before=Files.readAllBytes(published);
        var originalTime=java.nio.file.attribute.FileTime.fromMillis(10000);Files.setLastModifiedTime(published,originalTime);
        List<String> checked=new ArrayList<>();
        compiler.setByproductAlreadyRunning((name,bytes)->{checked.add(name);return name.equals("example.Helper");});
        var result=compiler.compileBatch(List.of(own),"core");
        assertTrue(result.isSuccess(),result.getErrors().toString());
        assertEquals(Set.of("example.Own"),result.getCompiledClasses().keySet());
        assertEquals(List.of("example.Helper"),checked);assertArrayEquals(before,Files.readAllBytes(published));
        assertEquals(originalTime,Files.getLastModifiedTime(published));
    }
    @Test void unknownUnloadedAndExternallyChangedByproductsAreReturnedEvenIfDiskMatches() throws Exception {
        Path helper=source("core","src","Helper","public static final int VALUE=2;");
        Path own=source("core","src","Own","public int value(){return Helper.VALUE;}");
        var compiler=compiler();
        var initial=compiler.compileBatch(List.of(own),"core");assertTrue(initial.isSuccess());
        // Force javac's implicit-source path again, with already matching disk output.
        Files.setLastModifiedTime(helper,java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()+5000));
        compiler.setByproductAlreadyRunning((name,bytes)->false);
        var result=compiler.compileBatch(List.of(own),"core");assertTrue(result.isSuccess());
        assertTrue(result.getCompiledClasses().containsKey("example.Helper"));
        Files.delete(module("core").resolve("classes/example/Helper.class"));
        compiler.setByproductAlreadyRunning((name,bytes)->{throw new IllegalStateException("proof unavailable");});
        assertTrue(compiler.compileBatch(List.of(own),"core").getCompiledClasses().containsKey("example.Helper"));
    }
    @Test void ownSecondaryTopLevelAndNestedClassesAlwaysReachReload() throws Exception {
        Path own=source("core","src","Own","static class Nested {} } class Secondary {");
        var compiler=compiler();compiler.setByproductAlreadyRunning((name,bytes)->true);
        var result=compiler.compileBatch(List.of(own),"core");assertTrue(result.isSuccess(),result.getErrors().toString());
        assertEquals(Set.of("example.Own","example.Own$Nested","example.Secondary"),result.getCompiledClasses().keySet());
    }
    @Test void byproductSnapshotIsRequestedOnceWithAllNames() throws Exception {
        source("core","src","HelperA","public int value(){return 1;}");
        source("core","src","HelperB","public int value(){return 2;}");
        Path own=source("core","src","Own","public int value(){return new HelperA().value()+new HelperB().value();}");
        var compiler=compiler();var requests=new ArrayList<Set<String>>();var lookups=new ArrayList<String>();
        compiler.setByproductProof(names->{requests.add(names);return (name,bytes)->{lookups.add(name);return true;};});
        var result=compiler.compileBatch(List.of(own),"core");assertTrue(result.isSuccess(),result.getErrors().toString());
        assertEquals(List.of(Set.of("example.HelperA","example.HelperB")),requests);
        assertEquals(Set.of("example.HelperA","example.HelperB"),new HashSet<>(lookups));assertEquals(2,lookups.size());
        assertEquals(Set.of("example.Own"),result.getCompiledClasses().keySet());
    }
    @Test void ownOnlyCompileDoesNotRequestALoadedSnapshot() throws Exception {
        Path own=source("core","src","Own","public int value(){return 1;}");var compiler=compiler();
        compiler.setByproductProof(names->{fail("own-only attempt must not enumerate loaded classes");return null;});
        var result=compiler.compileBatch(List.of(own),"core");assertTrue(result.isSuccess());
        assertEquals(Set.of("example.Own"),result.getCompiledClasses().keySet());
    }
    @Test void unavailableSnapshotNeverDiscardsByproducts() throws Exception {
        source("core","src","Helper","public int value(){return 1;}");
        Path own=source("core","src","Own","public int value(){return new Helper().value();}");var compiler=compiler();
        compiler.setByproductProof(names->{throw new IllegalStateException("snapshot unavailable");});
        var result=compiler.compileBatch(List.of(own),"core");assertTrue(result.isSuccess());
        assertEquals(Set.of("example.Own","example.Helper"),result.getCompiledClasses().keySet());
    }
}
