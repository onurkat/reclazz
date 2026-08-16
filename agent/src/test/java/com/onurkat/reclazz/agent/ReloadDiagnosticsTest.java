package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Why didn't my class reload?" is the question this project gets asked, and
 * for most of the reasons the honest answer is that no reload was ever
 * attempted. None of those reasons produce an error, so the log stays empty
 * and empty reads as broken.
 *
 * What matters in these tests is that each reason produces its own answer. A
 * diagnosis that says something plausible for every case is worse than none:
 * it sends the developer looking in the wrong place with confidence.
 */
class ReloadDiagnosticsTest {

    @Test
    void aClassThatWasNeverBuiltIsNamedAsSuch(@TempDir Path outputDir) {
        ReloadDiagnostics diagnostics = diagnosticsOver(outputDir);

        String report = String.join("\n", diagnostics.explain("com.example.NeverBuilt"));

        assertTrue(report.contains("No compiled .class file"), report);
        assertTrue(report.contains(outputDir.toString()),
                "the watched directories are the point: the developer has to see "
                + "where Reclazz was looking. Was:\n" + report);
    }

    @Test
    void aBuiltButUnloadedClassIsNotAProblem(@TempDir Path outputDir) throws Exception {
        writeClassFile(outputDir, "com/example/NotYetUsed.class");
        ReloadDiagnostics diagnostics = diagnosticsOver(outputDir);

        String report = String.join("\n", diagnostics.explain("com.example.NotYetUsed"));

        assertTrue(report.contains("has not loaded this class yet"), report);
        assertFalse(report.contains("No compiled .class file"), report);
    }

    /**
     * The most misleading case: the file is there, the class is loaded, and
     * nothing happened. Saying so beats silence, because the developer is
     * usually one rebuild away from the answer.
     */
    @Test
    void aBuiltClassWithNoAttemptSaysWhatThatMeans(@TempDir Path outputDir) throws Exception {
        Path classFile = writeClassFile(outputDir, "java/lang/String.class");
        Files.setLastModifiedTime(classFile,
                java.nio.file.attribute.FileTime.from(Instant.now()));

        ReloadDiagnostics diagnostics = diagnosticsOver(outputDir);
        String report = String.join("\n", diagnostics.explain("java.lang.String"));

        assertTrue(report.contains("has not attempted a reload"), report);
        assertTrue(report.contains("identical"),
                "a rebuild that produced the same bytes is the usual reason. Was:\n" + report);
    }

    @Test
    void aStaleBuildIsDistinguishedFromAnIdenticalOne(@TempDir Path outputDir) throws Exception {
        Path classFile = writeClassFile(outputDir, "java/lang/String.class");
        Files.setLastModifiedTime(classFile, java.nio.file.attribute.FileTime.from(
                Instant.now().minusSeconds(3600)));

        ReloadDiagnostics diagnostics = new ReloadDiagnostics(
                null, contextOver(outputDir), null, Instant.now().minusSeconds(600));
        String report = String.join("\n", diagnostics.explain("java.lang.String"));

        assertTrue(report.contains("has not been rebuilt"), report);
        assertFalse(report.contains("identical"),
                "the build never reached this class, which is a different problem "
                + "from a build that produced the same bytes. Was:\n" + report);
    }

    @Test
    void theLastAttemptIsReportedWhenThereWasOne(@TempDir Path outputDir) throws Exception {
        writeClassFile(outputDir, "com/example/Reloaded.class");
        ReloadDiagnostics diagnostics = diagnosticsOver(outputDir);
        diagnostics.record("com.example.Reloaded", false, "attempted to change the schema");

        String report = String.join("\n", diagnostics.explain("com.example.Reloaded"));

        assertTrue(report.contains("failed"), report);
        assertTrue(report.contains("attempted to change the schema"),
                "the JVM's own words are what a developer can search for. Was:\n" + report);
    }

    /**
     * The name in front of the developer is what is in the editor tab, not a
     * fully qualified one.
     */
    @Test
    void aSimpleNameIsEnoughToAsk(@TempDir Path outputDir) throws Exception {
        writeClassFile(outputDir, "com/example/deep/OrderService.class");
        ReloadDiagnostics diagnostics = diagnosticsOver(outputDir);

        String report = String.join("\n", diagnostics.explain("OrderService"));

        assertFalse(report.contains("No compiled .class file"),
                "the class is there, under a package the question did not name. Was:\n" + report);
        assertTrue(report.contains("OrderService.class"), report);
    }

    @Test
    void anEmptyQuestionIsAnsweredWithHowToAsk(@TempDir Path outputDir) {
        String report = String.join("\n", diagnosticsOver(outputDir).explain("  "));

        assertTrue(report.contains("Give a class name"), report);
    }

    private static ReloadDiagnostics diagnosticsOver(Path outputDir) {
        // A real Instrumentation is what the JVM hands an agent, so the loaded
        // classes come from this JVM instead: java.lang.String stands in for a
        // class that is loaded, com.example.* for classes that are not.
        return new ReloadDiagnostics(new LoadedClassesOnly(), contextOver(outputDir), null,
                Instant.now().minusSeconds(60));
    }

    private static Path writeClassFile(Path outputDir, String relative) throws Exception {
        Path file = outputDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        return file;
    }

    private static PlatformContext contextOver(Path outputDir) {
        return new PlatformContext() {
            @Override public Platform getPlatformId() { return Platform.GENERIC; }
            @Override public void initialize() {}
            @Override public Map<String, List<Path>> getClassOutputDirs() {
                return Map.of("main", List.of(outputDir));
            }
            @Override public Map<String, List<Path>> getSourceDirs() { return Map.of(); }
            @Override public Map<String, List<Path>> getResourceDirs() { return Map.of(); }
            @Override public String resolveClasspath() { return ""; }
            @Override public String resolveClassName(Path classFile) {
                String relative = outputDir.relativize(classFile).toString();
                return relative.replace(java.io.File.separatorChar, '.')
                        .replaceAll("\\.class$", "");
            }
            @Override public Path resolveOutputDir(Path classFile) { return outputDir; }
            @Override public Object getApplicationContext() { return null; }
        };
    }

    /** Only the two calls the diagnosis makes are real; the rest is unused. */
    private static class LoadedClassesOnly
            implements java.lang.instrument.Instrumentation {
        @Override public Class<?>[] getAllLoadedClasses() {
            return new Class<?>[] {String.class, Integer.class};
        }
        @Override public void addTransformer(java.lang.instrument.ClassFileTransformer t, boolean canRetransform) {}
        @Override public void addTransformer(java.lang.instrument.ClassFileTransformer t) {}
        @Override public boolean removeTransformer(java.lang.instrument.ClassFileTransformer t) { return false; }
        @Override public boolean isRetransformClassesSupported() { return false; }
        @Override public void retransformClasses(Class<?>... classes) {}
        @Override public boolean isRedefineClassesSupported() { return false; }
        @Override public void redefineClasses(java.lang.instrument.ClassDefinition... definitions) {}
        @Override public boolean isModifiableClass(Class<?> theClass) { return false; }
        @Override public Class<?>[] getInitiatedClasses(ClassLoader loader) { return new Class<?>[0]; }
        @Override public long getObjectSize(Object objectToSize) { return 0; }
        @Override public void appendToBootstrapClassLoaderSearch(java.util.jar.JarFile jarfile) {}
        @Override public void appendToSystemClassLoaderSearch(java.util.jar.JarFile jarfile) {}
        @Override public boolean isNativeMethodPrefixSupported() { return false; }
        @Override public void setNativeMethodPrefix(java.lang.instrument.ClassFileTransformer transformer, String prefix) {}
        @Override public void redefineModule(Module module, java.util.Set<Module> extraReads,
                                             Map<String, java.util.Set<Module>> extraExports,
                                             Map<String, java.util.Set<Module>> extraOpens,
                                             java.util.Set<Class<?>> extraUses,
                                             Map<Class<?>, List<Class<?>>> extraProvides) {}
        @Override public boolean isModifiableModule(Module module) { return false; }
    }
}
