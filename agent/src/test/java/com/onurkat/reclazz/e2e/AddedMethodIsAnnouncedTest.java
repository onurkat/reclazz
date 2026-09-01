/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adding a getter, and being told what it will and will not do.
 *
 * <p>A method added by a reload lives in the companion class, because a stock
 * JDK will not put a new method on a loaded one. Calls from the developer's own
 * code reach it, since those call sites are rewritten. Reflection does not, and
 * reflection is how frameworks find methods, so an added getter is not
 * serialised, an added {@code @Bean} method is not a bean and an added
 * {@code @Scheduled} method never runs.
 *
 * <p>What made that worth a round of work is not the wall, which is the JDK's,
 * but the silence in front of it: the reload succeeded, the log said so, and
 * the thing the developer had just written did nothing at all.
 *
 * <p>Both halves are measured here, on a running JVM: the wall is real, and it
 * is now announced by name.
 */
class AddedMethodIsAnnouncedTest {

    private static final long TIMEOUT_SEC = 60;

    @TempDir
    Path tmp;

    @Test
    void anAddedGetterIsNamedRatherThanSilentlyIgnored() throws Exception {
        String agentJar = System.getProperty("reclazz.agent.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                agentJar != null && Files.exists(Path.of(agentJar)),
                "agent shadow jar not built — run :agent:shadowJar");

        Path srcDir = Files.createDirectories(tmp.resolve("src/app"));
        Path classesDir = Files.createDirectories(tmp.resolve("classes"));
        Path dto = srcDir.resolve("Dto.java");

        Files.writeString(dto, """
                package app;
                public class Dto {
                    public String getName() { return "before"; }
                }
                """);
        Files.writeString(srcDir.resolve("App.java"), """
                package app;
                import java.lang.reflect.Method;
                public class App {
                    public static void main(String[] args) throws Exception {
                        Dto dto = new Dto();
                        System.out.println("APP_STARTED");
                        while (true) {
                            Thread.sleep(400);
                            String seen;
                            try {
                                Method added = Dto.class.getMethod("getEmail");
                                seen = "VISIBLE:" + added.invoke(dto);
                            } catch (NoSuchMethodException absent) {
                                seen = "INVISIBLE";
                            }
                            System.out.println("STATE name=" + dto.getName()
                                    + " getEmail=" + seen);
                        }
                    }
                }
                """);
        compile(List.of(srcDir.resolve("App.java"), dto), classesDir);

        Process app = new ProcessBuilder(javaBinary(),
                "-javaagent:" + agentJar + "=watchDirs=" + classesDir
                        + ",startupDelaySec=1,debounceMs=200",
                "-cp", classesDir.toString(), "app.App")
                .redirectErrorStream(true).start();

        List<String> output = new CopyOnWriteArrayList<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(app.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) output.add(line);
            } catch (Exception ignored) {
            }
        });
        reader.setDaemon(true);
        reader.start();

        try {
            assertTrue(await(output, "name=before", TIMEOUT_SEC),
                    () -> "app did not start under the agent:\n" + tail(output));

            // One save that does two things: changes a body, which is the
            // control that says the reload landed, and adds a getter, which is
            // the case under test.
            Files.writeString(dto, """
                    package app;
                    public class Dto {
                        public String getName() { return "after"; }
                        public String getEmail() { return "e@x"; }
                    }
                    """);
            compile(List.of(dto), classesDir);

            assertTrue(await(output, "name=after", TIMEOUT_SEC),
                    () -> "the reload never landed, so nothing here is about added methods:\n"
                            + tail(output));

            String state = latest(output, "STATE ");
            System.out.println("[diag] " + state);
            assertTrue(state.contains("getEmail=INVISIBLE"),
                    () -> "reflection found an added method, so this JDK is not the one the "
                            + "warning is for: " + state);

            String warning = latest(output, "getEmail()");
            System.out.println("[diag] " + warning);
            assertNotNull(warning,
                    () -> "the getter was added, did nothing, and nothing was said:\n"
                            + tail(output));
            assertTrue(warning.contains("serialisation"),
                    () -> "warned, but not about what will not happen: " + warning);
        } finally {
            app.destroyForcibly();
            app.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static void compile(List<Path> sources, Path outputDir) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK is required to run this test");
        List<String> args = new ArrayList<>(List.of("-d", outputDir.toString(), "-nowarn"));
        sources.forEach(s -> args.add(s.toString()));
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])),
                "fixture compilation failed");
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String latest(List<String> output, String needle) {
        String found = null;
        for (String line : output) {
            if (line.contains(needle)) found = line;
        }
        return found;
    }

    private static boolean await(List<String> output, String needle, long timeoutSec)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (output.stream().anyMatch(l -> l.contains(needle))) return true;
            Thread.sleep(200);
        }
        return false;
    }

    private static String tail(List<String> output) {
        List<String> lines = new ArrayList<>(output);
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 25), lines.size()));
    }
}
