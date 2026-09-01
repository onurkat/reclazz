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
 * Application code asking the agent for a capability, in a real JVM.
 *
 * <p>The engine keeps each watched class's own {@code MethodHandles.lookup()},
 * which carries private access to that class, and on a classpath application
 * {@code privateLookupIn} turns one of those into private access to everything
 * else on the classpath. The holder sits on the bootstrap classloader, so the
 * method that returns it is callable from every line of code in the process:
 * an expression language evaluating a submitted string, a deserialization
 * gadget that can reach a static method, ordinary application code.
 *
 * <p>None of those can write a class file into a watched directory, which is
 * the boundary the README draws around what running under Reclazz costs. So
 * handing them the same reach through a public method was quietly drawing it
 * somewhere else, and this is the test that it is no longer drawn there.
 *
 * <p>The unit tests cover the rule. This covers the part they cannot: that the
 * rule is switched on in a real agent start-up, and that switching it on did
 * not cost the engine the access it needs, which the reload in the second half
 * of the run is there to show.
 */
class LookupIsNotHandedToTheAppTest {

    private static final long TIMEOUT_SEC = 60;

    @TempDir
    Path tmp;

    @Test
    void theRunningApplicationCannotAskForAWatchedClassesLookup() throws Exception {
        String agentJar = System.getProperty("reclazz.agent.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                agentJar != null && Files.exists(Path.of(agentJar)),
                "agent shadow jar not built — run :agent:shadowJar");

        Path srcDir = Files.createDirectories(tmp.resolve("src/app"));
        Path classesDir = Files.createDirectories(tmp.resolve("classes"));

        Path work = srcDir.resolve("Work.java");
        Files.writeString(work, source("v1"));
        Files.writeString(srcDir.resolve("App.java"), """
                package app;
                import java.lang.invoke.MethodHandles;
                public class App {
                    public static void main(String[] args) throws Exception {
                        Work work = new Work();
                        System.out.println("APP_STARTED");

                        // Exactly what an injected expression would evaluate:
                        // a public static method on the boot classloader,
                        // called with a Class it can name.
                        try {
                            Class<?> holder = Class.forName(
                                    "com.onurkat.reclazz.bootstrap.LookupCapture");
                            Object lookup = holder.getMethod("get", Class.class)
                                    .invoke(null, Work.class);
                            System.out.println("ASK=" + (lookup == null
                                    ? "NOTHING_CAPTURED" : "GOT_LOOKUP"));
                        } catch (java.lang.reflect.InvocationTargetException refused) {
                            System.out.println("ASK=REFUSED "
                                    + refused.getCause().getClass().getSimpleName());
                        } catch (ClassNotFoundException notThere) {
                            System.out.println("ASK=NO_SUCH_CLASS");
                        }

                        while (true) {
                            Thread.sleep(400);
                            System.out.println("SAW=" + work.tag());
                        }
                    }
                }
                """);
        compile(List.of(srcDir.resolve("App.java"), work), classesDir);

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
            assertTrue(await(output, "ASK=", TIMEOUT_SEC),
                    () -> "the app never got as far as asking:\n" + tail(output));
            String answer = latest(output, "ASK=");
            System.out.println("[diag] " + answer);

            assertTrue(answer.contains("REFUSED"),
                    () -> "application code was handed a watched class's private-access "
                            + "lookup: " + answer);
            assertTrue(answer.contains("SecurityException"),
                    () -> "refused, but not as a refusal: " + answer);

            // And the engine still has the access it needs: a reload after the
            // refusal has to work, or the door was shut on the wrong side.
            Files.writeString(work, source("v2"));
            compile(List.of(work), classesDir);
            assertTrue(await(output, "SAW=v2", TIMEOUT_SEC),
                    () -> "the guard cost the engine its own access:\n" + tail(output));
        } finally {
            app.destroyForcibly();
            app.waitFor(10, TimeUnit.SECONDS);
        }
    }

    /** Structural, so the engine genuinely needs the lookup it captured. */
    private static String source(String value) {
        return """
                package app;
                public class Work {
                    private final String added = "%s";
                    public String tag() { return helper(); }
                    private String helper() { return added == null ? "%s" : added; }
                }
                """.formatted(value, value);
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

    private static String latest(List<String> output, String prefix) {
        String found = null;
        for (String line : output) {
            if (line.contains(prefix)) found = line;
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
