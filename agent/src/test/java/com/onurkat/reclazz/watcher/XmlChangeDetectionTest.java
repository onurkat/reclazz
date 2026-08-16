package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.agent.AgentConfig;
import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The XML reload features are only as good as the two steps in front of
 * them: the watcher has to hand the file over, and the agent has to send it
 * to the right reloader. Both were untested, and both fail silently. A file
 * the watcher ignores, or one that lands in no branch, produces a save with
 * no reload and no error, which is the failure this project keeps chasing.
 *
 * The reloaders themselves are covered by SpringXmlReloaderTest and
 * CodegenReloaderTest. This covers getting to them.
 */
class XmlChangeDetectionTest {

    @TempDir
    Path tempDir;

    private FileWatcher watcher;

    @BeforeEach
    void setUp() throws Exception {
        watcher = new FileWatcher(new NoopContext(), AgentConfig.parse(null));
    }

    // ── The watcher hands the file over ──────────────────────────────────

    /**
     * Real names from a Hybris extension. If the watcher's filter ever
     * narrows, these are what stops arriving.
     */
    @Test
    void theWatcherPicksUpEveryXmlKindAnExtensionHas() throws Exception {
        List<String> names = List.of(
                "reclazztest-items.xml",
                "reclazztest-beans.xml",
                "reclazztest-spring.xml",
                "reclazztest-web-spring.xml",
                "test-data.impex",
                "project.properties");
        for (String name : names) {
            Files.writeString(tempDir.resolve(name), "<x/>");
        }
        // Something we should not be woken for.
        Files.writeString(tempDir.resolve("notes.txt"), "ignore me");

        Map<Path, FileWatcher.PendingEvent> queue = new LinkedHashMap<>();
        watcher.enqueueExistingFiles(tempDir, "reclazztest", "resources", queue);

        for (String name : names) {
            assertTrue(queue.keySet().stream().anyMatch(p -> p.endsWith(name)),
                    name + " was not picked up by the watcher");
        }
        assertFalse(queue.keySet().stream().anyMatch(p -> p.endsWith("notes.txt")),
                "the watcher should not wake for unrelated files");
    }

    /** Hybris keeps these in subfolders, so a flat scan would miss them. */
    @Test
    void xmlInASubfolderIsStillPickedUp() throws Exception {
        Path nested = tempDir.resolve("resources").resolve("reclazztest");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("reclazztest-spring.xml"), "<beans/>");

        Map<Path, FileWatcher.PendingEvent> queue = new LinkedHashMap<>();
        watcher.enqueueExistingFiles(tempDir, "reclazztest", "resources", queue);

        assertTrue(queue.keySet().stream().anyMatch(p -> p.endsWith("reclazztest-spring.xml")),
                "nested XML must be found; extensions do not keep these at the top level");
    }

    // ── The agent sends it to the right place ────────────────────────────

    @Test
    void itemsAndBeansGoToCodeGeneration() {
        assertEquals(ChangeKind.CODEGEN_XML, ChangeKind.of("reclazztest-items.xml"));
        assertEquals(ChangeKind.CODEGEN_XML, ChangeKind.of("reclazztest-beans.xml"));
    }

    @Test
    void springXmlGoesToTheSpringReloader() {
        assertEquals(ChangeKind.SPRING_XML, ChangeKind.of("reclazztest-spring.xml"));
        assertEquals(ChangeKind.SPRING_XML, ChangeKind.of("reclazztest-web-spring.xml"),
                "web contexts use this name and must not fall through");
    }

    /**
     * The three XML kinds share a suffix and must not be confused: a
     * beans.xml sent to the Spring reloader would be parsed as a bean
     * definition file and quietly do nothing useful.
     */
    @Test
    void theThreeXmlKindsDoNotBleedIntoEachOther() {
        assertNotEquals(ChangeKind.of("a-items.xml"), ChangeKind.of("a-spring.xml"));
        assertNotEquals(ChangeKind.of("a-beans.xml"), ChangeKind.of("a-spring.xml"));
        assertEquals(ChangeKind.of("a-items.xml"), ChangeKind.of("a-beans.xml"),
                "both drive the same code generation run");
    }

    /**
     * A logging configuration is an XML the running framework can be pointed
     * at again, which is worth telling apart from the rest: sending it to the
     * Spring reloader would parse it as bean definitions and fail, and leaving
     * it unclassified would mean a restart for a change to one level.
     */
    @Test
    void loggingConfigurationIsItsOwnKind() {
        assertEquals(ChangeKind.LOGGING_CONFIG, ChangeKind.of("log4j2.xml"));
        assertEquals(ChangeKind.LOGGING_CONFIG, ChangeKind.of("logback.xml"));
        assertEquals(ChangeKind.LOGGING_CONFIG, ChangeKind.of("logback-spring.xml"),
                "the name Spring Boot looks for first");
        assertEquals(ChangeKind.LOGGING_CONFIG, ChangeKind.of("log4j2-test.xml"));
    }

    /**
     * An extension called {@code logbackutils} would otherwise have every XML
     * in it treated as a logging configuration.
     */
    @Test
    void aNameThatMerelyStartsWithTheFrameworkIsNotAConfiguration() {
        assertNotEquals(ChangeKind.LOGGING_CONFIG, ChangeKind.of("logbackutils-spring.xml"));
        assertNotEquals(ChangeKind.LOGGING_CONFIG, ChangeKind.of("log4j2utils-items.xml"));
    }

    @Test
    void otherKindsStillClassify() {
        assertEquals(ChangeKind.CLASS_FILE, ChangeKind.of("Foo.class"));
        assertEquals(ChangeKind.JAVA_SOURCE, ChangeKind.of("Foo.java"));
        assertEquals(ChangeKind.IMPEX, ChangeKind.of("test-data.impex"));
        assertEquals(ChangeKind.PROPERTIES, ChangeKind.of("local.properties"));
        assertEquals(ChangeKind.PROPERTIES, ChangeKind.of("application.yml"));
        assertEquals(ChangeKind.PROPERTIES, ChangeKind.of("application.yaml"));
    }

    /**
     * An XML that is none of the three known kinds must not be mistaken for
     * one. Hybris installations are full of them, and treating, say,
     * extensioninfo.xml as a Spring context would be noise at best.
     */
    @Test
    void unrelatedXmlIsNotClaimedByAnyReloader() {
        assertEquals(ChangeKind.UNKNOWN, ChangeKind.of("extensioninfo.xml"));
        assertEquals(ChangeKind.UNKNOWN, ChangeKind.of("localextensions.xml"));
        assertEquals(ChangeKind.UNKNOWN, ChangeKind.of("web.xml"));
        assertEquals(ChangeKind.UNKNOWN, ChangeKind.of("pom.xml"));
    }

    @Test
    void nothingBlowsUpOnOddInput() {
        assertEquals(ChangeKind.UNKNOWN, ChangeKind.of((String) null));
        assertEquals(ChangeKind.UNKNOWN, ChangeKind.of(""));
        assertEquals(ChangeKind.UNKNOWN, ChangeKind.of(".xml"));
    }

    private static final class NoopContext implements PlatformContext {
        @Override public Platform getPlatformId() { return Platform.GENERIC; }
        @Override public void initialize() { }
        @Override public Map<String, List<Path>> getClassOutputDirs() { return Map.of(); }
        @Override public Map<String, List<Path>> getSourceDirs() { return Map.of(); }
        @Override public Map<String, List<Path>> getResourceDirs() { return Map.of(); }
        @Override public String resolveClasspath() { return ""; }
        @Override public String resolveClassName(Path classFile) { return null; }
        @Override public Path resolveOutputDir(Path classFile) { return null; }
        @Override public Object getApplicationContext() { return null; }
    }
}
