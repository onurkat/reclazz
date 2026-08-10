package com.onurkat.reclazz.hybris.codegen;

import com.onurkat.reclazz.hybris.ExtensionInfo;
import com.onurkat.reclazz.hybris.HybrisContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Focused unit tests for the pieces of {@link CodegenReloader} that don't
 * require a running Hybris: {@link CodegenReloader.Kind} detection from
 * file suffix, owning-extension resolution from a beans/items.xml path,
 * and the deepest-match tie-breaker for nested extension layouts.
 *
 * The subprocess / ant invocation is deliberately left for live
 * integration tests (proven against real Hybris in 1.0.29 / 1.0.30)
 * because mocking {@code ProcessBuilder} adds no signal over the
 * already-validated live test matrix.
 */
class CodegenReloaderTest {

    @TempDir
    Path tempDir;

    private HybrisContext hybrisContext;
    private CodegenReloader reloader;

    @BeforeEach
    void setUp() throws Exception {
        Path hybrisHome = tempDir;
        Files.createDirectories(hybrisHome.resolve("bin").resolve("platform"));
        hybrisContext = new HybrisContext(hybrisHome);
        setField(hybrisContext, "platformHome", hybrisHome.resolve("bin/platform"));
        setField(hybrisContext, "extensions", new LinkedHashMap<String, ExtensionInfo>());
        reloader = new CodegenReloader(hybrisContext);
    }

    // ─── Kind.fromFileName ────────────────────────────────────────────────────

    @Test
    void kindFromFileName_beansXml_returnsBeans() {
        assertEquals(CodegenReloader.Kind.BEANS,
                CodegenReloader.Kind.fromFileName("examplecore-beans.xml"));
    }

    @Test
    void kindFromFileName_itemsXml_returnsItems() {
        assertEquals(CodegenReloader.Kind.ITEMS,
                CodegenReloader.Kind.fromFileName("examplecore-items.xml"));
    }

    @Test
    void kindFromFileName_unrelatedXml_returnsNull() {
        assertNull(CodegenReloader.Kind.fromFileName("examplecore-spring.xml"));
        assertNull(CodegenReloader.Kind.fromFileName("local.properties"));
        assertNull(CodegenReloader.Kind.fromFileName("beans.xml"),
                "bare beans.xml (no extension prefix) should not match");
    }

    @Test
    void kindFromFileName_suffixTailIsCaseSensitive() {
        // Hybris's own naming convention is always lowercase -beans.xml
        // / -items.xml. We intentionally don't case-fold because an
        // uppercase match would hide real naming mistakes.
        assertNull(CodegenReloader.Kind.fromFileName("MyExt-BEANS.xml"));
        assertNull(CodegenReloader.Kind.fromFileName("MyExt-ITEMS.XML"));
    }

    // ─── findOwningExtension ──────────────────────────────────────────────────

    @Test
    void findOwningExtension_beansFileInsideExtensionResources_returnsExtension() throws Exception {
        Path extDir = tempDir.resolve("bin/custom/examplecore");
        Files.createDirectories(extDir.resolve("resources"));
        ExtensionInfo ext = new ExtensionInfo(
                "examplecore", extDir, List.of(), true, false, true);
        registerExtensions(ext);

        Path beansFile = extDir.resolve("resources").resolve("examplecore-beans.xml");
        Files.writeString(beansFile, "<beans/>");

        ExtensionInfo found = reloader.findOwningExtension(beansFile);
        assertNotNull(found);
        assertEquals("examplecore", found.getName());
    }

    @Test
    void findOwningExtension_itemsFileInsideExtensionResources_returnsExtension() throws Exception {
        Path extDir = tempDir.resolve("bin/custom/myStuff");
        Files.createDirectories(extDir.resolve("resources"));
        ExtensionInfo ext = new ExtensionInfo(
                "myStuff", extDir, List.of(), true, false, true);
        registerExtensions(ext);

        Path itemsFile = extDir.resolve("resources").resolve("myStuff-items.xml");
        Files.writeString(itemsFile, "<items/>");

        ExtensionInfo found = reloader.findOwningExtension(itemsFile);
        assertNotNull(found);
        assertEquals("myStuff", found.getName(),
                "items.xml path lookup is identical to beans.xml path lookup — same owning-extension logic");
    }

    @Test
    void findOwningExtension_fileOutsideAnyExtension_returnsNull() throws Exception {
        ExtensionInfo ext = new ExtensionInfo(
                "foo", tempDir.resolve("bin/custom/foo"),
                List.of(), true, false, true);
        registerExtensions(ext);

        Path stray = tempDir.resolve("somewhere-else").resolve("stray-beans.xml");
        Files.createDirectories(stray.getParent());
        Files.writeString(stray, "<beans/>");

        assertNull(reloader.findOwningExtension(stray));
    }

    @Test
    void findOwningExtension_nestedExtensions_picksDeepestMatch() throws Exception {
        Path parentDir = tempDir.resolve("bin/custom/group");
        Path childDir = parentDir.resolve("group-child");
        Files.createDirectories(childDir.resolve("resources"));

        ExtensionInfo parent = new ExtensionInfo(
                "group", parentDir, List.of(), true, false, true);
        ExtensionInfo child = new ExtensionInfo(
                "groupChild", childDir, List.of(), true, false, true);
        registerExtensions(parent, child);

        Path childItemsFile = childDir.resolve("resources").resolve("groupChild-items.xml");
        Files.writeString(childItemsFile, "<items/>");

        ExtensionInfo found = reloader.findOwningExtension(childItemsFile);
        assertNotNull(found);
        assertEquals("groupChild", found.getName(),
                "deepest-match tie-breaker: child wins over parent");
    }

    @Test
    void findOwningExtension_sameFile_returnsStableResult() throws Exception {
        Path extDir = tempDir.resolve("bin/custom/stable");
        Files.createDirectories(extDir.resolve("resources"));
        ExtensionInfo ext = new ExtensionInfo(
                "stable", extDir, List.of(), true, false, true);
        registerExtensions(ext);

        Path beansFile = extDir.resolve("resources").resolve("stable-beans.xml");
        Files.writeString(beansFile, "<beans/>");

        ExtensionInfo first = reloader.findOwningExtension(beansFile);
        ExtensionInfo second = reloader.findOwningExtension(beansFile);
        assertSame(first, second,
                "lookup is a pure function of the extensions map — same object both calls");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void registerExtensions(ExtensionInfo... exts) throws Exception {
        Map<String, ExtensionInfo> map = new LinkedHashMap<>();
        for (ExtensionInfo e : exts) {
            map.put(e.getName(), e);
        }
        setField(hybrisContext, "extensions", map);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> cls, String fieldName) throws NoSuchFieldException {
        Class<?> current = cls;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
