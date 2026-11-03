/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Finding what inlined a constant, which the bytecode cannot say and the
 * project can.
 *
 * <p>javac leaves no symbolic reference at a constant's use site, so the
 * dependents are unreachable from the class that changed and the tool used to
 * stop there. The sources are still on disk, and the file that reads
 * {@code MAX_RETRIES} is the file that contains the word. That match is loose
 * on purpose: a file named here that turns out not to inline anything
 * recompiles to identical bytes and the watcher's content hash drops it, so a
 * false positive costs a javac pass, while a miss costs a wrong value in a
 * running server.
 *
 * <p>What these tests hold is the two ways the search can be wrong in a way
 * that matters: naming the declaring class itself, which is current by
 * definition and would recompile on every constant edit forever, and matching
 * a longer name that merely starts the same.
 */
class ConstantDependentsTest {

    private static Path write(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void aSourceThatReadsTheConstantIsFound(@TempDir Path root) throws Exception {
        Path src = root.resolve("src/main/java/com/example");
        write(src, "Limits.java",
                "package com.example;\npublic class Limits { public static final int MAX_RETRIES = 3; }\n");
        Path caller = write(src, "Caller.java",
                "package com.example;\npublic class Caller { int n() { return Limits.MAX_RETRIES; } }\n");
        write(src, "Unrelated.java",
                "package com.example;\npublic class Unrelated { int n() { return 3; } }\n");

        ConstantDependents.Result found = ConstantDependents.search(
                "com.example.Limits", List.of("MAX_RETRIES"),
                Map.of("app", List.of(root.resolve("src/main/java"))));

        assertEquals(List.of(caller), found.byModule().get("app"),
                "the declaring class is current already, and Unrelated inlines nothing");
        assertFalse(found.truncated());
    }

    /**
     * The declaring class is the one that just reloaded. Rebuilding it on
     * every constant edit would be work with no possible effect, and on a
     * watched tree it would loop straight back into another reload.
     */
    @Test
    void theDeclaringClassIsNotItsOwnDependent(@TempDir Path root) throws Exception {
        Path src = root.resolve("src/main/java/com/example");
        write(src, "Limits.java",
                "package com.example;\npublic class Limits {\n"
                + "    public static final int MAX_RETRIES = 3;\n"
                + "    int own() { return MAX_RETRIES; }\n}\n");

        ConstantDependents.Result found = ConstantDependents.search(
                "com.example.Limits", List.of("MAX_RETRIES"),
                Map.of("app", List.of(root.resolve("src/main/java"))));

        assertTrue(found.byModule().isEmpty());
    }

    /** A nested class lives in its outer class's file, and that is the one to skip. */
    @Test
    void aNestedClassResolvesToItsOuterFile() {
        assertEquals("Limits", ConstantDependents.simpleName("com.example.Limits$Inner"));
        assertEquals("Limits", ConstantDependents.simpleName("com.example.Limits"));
        assertEquals("Limits", ConstantDependents.simpleName("Limits"));
    }

    /**
     * A constant whose name is the start of another name is the false positive
     * that would keep recompiling the wrong file on every edit.
     */
    @Test
    void aLongerNameThatStartsTheSameIsNotAMatch(@TempDir Path root) throws Exception {
        Path src = root.resolve("src/main/java/com/example");
        write(src, "Other.java",
                "package com.example;\npublic class Other { int n() { return Limits.MAX_RETRIES_EXTRA; } }\n");

        ConstantDependents.Result found = ConstantDependents.search(
                "com.example.Limits", List.of("MAX_RETRIES"),
                Map.of("app", List.of(root.resolve("src/main/java"))));

        assertTrue(found.byModule().isEmpty(),
                "an underscore is a word character, so the boundary does its job");
    }

    /** Several roots, several modules: each file is reported under its own. */
    @Test
    void filesAreGroupedByTheModuleTheyCameFrom(@TempDir Path root) throws Exception {
        Path core = root.resolve("core/src");
        Path web = root.resolve("web/src");
        write(core, "A.java", "class A { int n() { return Limits.MAX_RETRIES; } }\n");
        write(web, "B.java", "class B { int n() { return Limits.MAX_RETRIES; } }\n");

        ConstantDependents.Result found = ConstantDependents.search(
                "com.example.Limits", List.of("MAX_RETRIES"),
                new java.util.LinkedHashMap<>(Map.of(
                        "core", List.of(core), "web", List.of(web))));

        assertEquals(2, found.count());
        assertEquals(1, found.byModule().get("core").size());
        assertEquals(1, found.byModule().get("web").size());
    }

    /** A source root that is not there contributes nothing and throws nothing. */
    @Test
    void amissingSourceRootIsNotAFailure(@TempDir Path root) {
        ConstantDependents.Result found = ConstantDependents.search(
                "com.example.Limits", List.of("MAX_RETRIES"),
                Map.of("app", List.of(root.resolve("does/not/exist"))));

        assertTrue(found.byModule().isEmpty());
    }

    /**
     * Every SAP Commerce extension declares its own EXTENSIONNAME. Saving one
     * extension's items.xml regenerated its constants class, and the search
     * picked up twenty files in other extensions that read their own
     * EXTENSIONNAME. Those failed to compile with the agent's classpath, and
     * the failure held every later save. A file can only have inlined this
     * class's constant if it can see the class: it names it (qualified use,
     * import, static import, extends), or it shares its package.
     */
    @Test
    void anotherClassesConstantOfTheSameNameIsNotADependent(@TempDir Path root) throws Exception {
        Path src = root.resolve("src");
        write(src, "com/ext/constants/ExtConstants.java",
                "package com.ext.constants;\npublic class ExtConstants { public static final String EXTENSIONNAME = \"ext\"; }\n");
        write(src, "com/other/constants/OtherConstants.java",
                "package com.other.constants;\npublic class OtherConstants { public static final String EXTENSIONNAME = \"other\"; }\n");
        write(src, "com/other/OtherManager.java",
                "package com.other;\nimport com.other.constants.OtherConstants;\n"
                + "public class OtherManager { String n() { return OtherConstants.EXTENSIONNAME; } }\n");
        Path qualified = write(src, "com/use/Reader.java",
                "package com.use;\npublic class Reader { String n() { return com.ext.constants.ExtConstants.EXTENSIONNAME; } }\n");
        Path staticImport = write(src, "com/use/StaticReader.java",
                "package com.use;\nimport static com.ext.constants.ExtConstants.EXTENSIONNAME;\n"
                + "public class StaticReader { String n() { return EXTENSIONNAME; } }\n");
        Path samePackage = write(src, "com/ext/constants/Sibling.java",
                "package com.ext.constants;\npublic class Sibling extends Base { String n() { return EXTENSIONNAME; } }\n");

        ConstantDependents.Result found = ConstantDependents.search(
                "com.ext.constants.ExtConstants", List.of("EXTENSIONNAME"),
                Map.of("app", List.of(src)));

        assertEquals(java.util.Set.of(qualified, staticImport, samePackage),
                java.util.Set.copyOf(found.byModule().get("app")));
    }
}
