/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Loading watched classes from many threads at once.
 *
 * <p>Class loading is concurrent, and the transformer runs inside it: the JVM
 * calls it while defining a class, on whichever thread got there. Resolving a
 * super call now reads the parent's class file through the same loader that is
 * in the middle of defining the child, which is the newest thing on that path
 * and the one with the least behind it. Reading rather than loading is what
 * keeps it out of the class-loading lock, and this is the test that the
 * distinction holds when the loads overlap.
 *
 * <p>Deep hierarchies on purpose, so every load walks several parents and the
 * resolver's shared cache is written and read by all of the threads at once.
 * What is asserted is what a JVM is entitled to: every class loads, every
 * super call lands where it should, and nothing deadlocks.
 */
class ConcurrentClassLoadingTest {

    private static final int THREADS = 16;

    private static final int CHAINS = 24;

    private static final int DEPTH = 6;

    @TempDir
    Path tmp;

    @Test
    void manyThreadsLoadingDeepHierarchiesAtOnce() throws Exception {
        WatchedApp.Builder builder = WatchedApp.in(tmp);
        for (int chain = 0; chain < CHAINS; chain++) {
            for (int level = 0; level < DEPTH; level++) {
                builder.with(name(chain, level), classSource(chain, level));
            }
        }

        try (WatchedApp app = builder.with("App", driver()).start()) {
            app.awaitOrFail("DONE", "the application did not finish loading its classes");

            String result = app.latest("DONE");
            System.out.println("[diag] " + result);
            assertTrue(result.contains("failures=0"),
                    () -> "a class failed to load or a super call went astray:\n" + app.tail());
            assertTrue(result.contains("wrong=0"),
                    () -> "a super call reached the wrong body:\n" + app.tail());
        }
    }

    private static String name(int chain, int level) {
        return "Chain" + chain + "Level" + level;
    }

    /**
     * Level 0 declares the method. The levels above it mostly do not, which is
     * the shape that has to be resolved: the owner javac names is not the class
     * that has the method.
     */
    private static String classSource(int chain, int level) {
        if (level == 0) {
            return """
                    package app;
                    public class %s {
                        public String describe() { return "chain%d"; }
                    }
                    """.formatted(name(chain, 0), chain);
        }
        // Every other level overrides and calls super; the ones between only
        // extend, so the super call has to be resolved past them.
        if (level % 2 == 0) {
            return """
                    package app;
                    public class %s extends %s {
                    }
                    """.formatted(name(chain, level), name(chain, level - 1));
        }
        return """
                package app;
                public class %s extends %s {
                    @Override public String describe() { return super.describe(); }
                }
                """.formatted(name(chain, level), name(chain, level - 1));
    }

    /** Loads every chain's top class from many threads at the same moment. */
    private static String driver() {
        return """
                package app;
                import java.util.concurrent.*;
                import java.util.concurrent.atomic.*;

                public class App {
                    public static void main(String[] args) throws Exception {
                        System.out.println("APP_STARTED");
                        AtomicInteger failures = new AtomicInteger();
                        AtomicInteger wrong = new AtomicInteger();
                        CountDownLatch go = new CountDownLatch(1);
                        CountDownLatch done = new CountDownLatch(%d);
                        ExecutorService pool = Executors.newFixedThreadPool(%d);

                        for (int c = 0; c < %d; c++) {
                            final int chain = c;
                            pool.submit(() -> {
                                try {
                                    go.await();
                                    Class<?> top = Class.forName(
                                            "app.Chain" + chain + "Level%d");
                                    Object instance = top.getDeclaredConstructor().newInstance();
                                    Object said = top.getMethod("describe").invoke(instance);
                                    if (!("chain" + chain).equals(said)) wrong.incrementAndGet();
                                } catch (Throwable failure) {
                                    failures.incrementAndGet();
                                    System.out.println("LOADFAIL " + failure);
                                } finally {
                                    done.countDown();
                                }
                            });
                        }

                        go.countDown();
                        boolean finished = done.await(90, TimeUnit.SECONDS);
                        pool.shutdownNow();
                        System.out.println("DONE finished=" + finished
                                + " failures=" + failures.get()
                                + " wrong=" + wrong.get());
                        while (true) Thread.sleep(1000);
                    }
                }
                """.formatted(CHAINS, THREADS, CHAINS, DEPTH - 1);
    }
}
