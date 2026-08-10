package com.onurkat.reclazz.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end smoke test for the GENERIC (non-Hybris) path — the one the
 * README leads with ("works with any Spring Boot application out of the
 * box"). Everything else in this suite exercises the agent in-process or
 * against SAP Commerce; this launches a real child JVM with
 * {@code -javaagent}, runs a small Spring application in it, edits a bean
 * class on disk and asserts the running app serves the new behaviour.
 *
 * Without it, a change made for the Hybris path could silently break the
 * generic one and nothing would notice until a user reported it.
 *
 * Skipped when the agent jar has not been built (plain {@code :agent:test}
 * without {@code shadowJar}); CI builds it, see agent/build.gradle.kts.
 */
class GenericSpringAgentSmokeTest {

    private static final long BOOT_TIMEOUT_SEC = 60;
    private static final long RELOAD_TIMEOUT_SEC = 45;

    @TempDir
    Path tmp;

    @Test
    void editingABeanClassHotReloadsItInARunningSpringApp() throws Exception {
        String agentJar = System.getProperty("reclazz.agent.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                agentJar != null && Files.exists(Path.of(agentJar)),
                "agent shadow jar not built — run :agent:shadowJar");

        Path srcDir = Files.createDirectories(tmp.resolve("src/app"));
        Path classesDir = Files.createDirectories(tmp.resolve("classes"));

        Path greeter = srcDir.resolve("Greeter.java");
        Files.writeString(greeter, greeterSource("v1"));
        Files.writeString(srcDir.resolve("App.java"), """
                package app;
                import org.springframework.context.annotation.AnnotationConfigApplicationContext;
                import org.springframework.context.annotation.ComponentScan;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                @ComponentScan("app")
                public class App {
                    public static void main(String[] args) throws Exception {
                        var ctx = new AnnotationConfigApplicationContext(App.class);
                        System.out.println("APP_STARTED");
                        while (true) {
                            Thread.sleep(500);
                            System.out.println("GREET=" + ctx.getBean(Greeter.class).greet());
                        }
                    }
                }
                """);

        // Take ONLY the Spring jars from the test classpath. Handing the
        // child the whole worker classpath would also put the agent's own
        // classes on the application classloader — a duplication no real
        // user has, and one that makes the agent load half-unshaded.
        String testClasspath = springOnlyClasspath();
        compile(List.of(srcDir.resolve("App.java"), greeter), classesDir, testClasspath);

        Process app = new ProcessBuilder(
                javaBinary(),
                "-javaagent:" + agentJar + "=watchDirs=" + classesDir + ",startupDelaySec=1,debounceMs=200",
                "-cp", testClasspath + File.pathSeparator + classesDir,
                "app.App")
                .redirectErrorStream(true)
                .start();

        List<String> output = new CopyOnWriteArrayList<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(app.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) output.add(line);
            } catch (Exception ignored) {}
        });
        reader.setDaemon(true);
        reader.start();

        try {
            assertTrue(awaitLine(output, "APP_STARTED", BOOT_TIMEOUT_SEC),
                    () -> "app did not start under the agent:\n" + String.join("\n", output));
            assertTrue(awaitLine(output, "GREET=v1", BOOT_TIMEOUT_SEC),
                    () -> "app did not serve v1:\n" + String.join("\n", output));
            // Deliberately NOT waiting for the watcher to settle: editing
            // right after startup is what a developer does, and it used to
            // fall into the baseline race (the edit was recorded as the
            // baseline while the JVM still ran the old class, so it never
            // reloaded). See BaselineRaceTest.

            // The edit under test: recompile the bean into the watched dir.
            Path classFile = classesDir.resolve("app/Greeter.class");
            long sizeBefore = Files.size(classFile);
            long mtimeBefore = Files.getLastModifiedTime(classFile).toMillis();

            Files.writeString(greeter, greeterSource("v2"));
            compile(List.of(greeter), classesDir, testClasspath);

            System.out.println("[diag] classFile=" + classFile
                    + " size " + sizeBefore + "->" + Files.size(classFile)
                    + " mtime " + mtimeBefore + "->"
                    + Files.getLastModifiedTime(classFile).toMillis());

            assertTrue(awaitLine(output, "GREET=v2", RELOAD_TIMEOUT_SEC),
                    () -> "hot reload did not reach the running app:\n" + String.join("\n", output));
        } finally {
            app.destroyForcibly();
            app.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static String greeterSource(String value) {
        return """
                package app;
                import org.springframework.stereotype.Service;

                @Service
                public class Greeter {
                    public String greet() { return "%s"; }
                }
                """.formatted(value);
    }

    private static void compile(List<Path> sources, Path outputDir, String classpath) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK is required to run this test");
        List<String> args = new ArrayList<>(List.of(
                "-d", outputDir.toString(), "-classpath", classpath, "-nowarn"));
        sources.forEach(s -> args.add(s.toString()));
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])),
                "fixture compilation failed");
    }

    /** Spring (and its logging bridge) entries of the test classpath. */
    private static String springOnlyClasspath() {
        List<String> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            String name = Path.of(entry).getFileName().toString();
            if (name.startsWith("spring-") || name.contains("jcl") || name.contains("commons-logging")) {
                entries.add(entry);
            }
        }
        assertFalse(entries.isEmpty(), "Spring jars must be on the test classpath");
        return String.join(File.pathSeparator, entries);
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static boolean awaitLine(List<String> output, String needle, long timeoutSec)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (output.stream().anyMatch(l -> l.contains(needle))) return true;
            Thread.sleep(200);
        }
        return false;
    }
}
