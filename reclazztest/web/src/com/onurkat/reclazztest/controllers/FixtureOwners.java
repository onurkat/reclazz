package com.onurkat.reclazztest.controllers;

import org.springframework.context.ConfigurableApplicationContext;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Uses existing agent observations and exact-owner tenant bridge, never guesses a tenant. */
final class FixtureOwners {
    private FixtureOwners() {}
    static List<ConfigurableApplicationContext> of(String filename) {
        try {
            ClassLoader loader = ClassLoader.getSystemClassLoader();
            Class<?> holder = Class.forName("com.onurkat.reclazz.platform.ApplicationContextHolder", false, loader);
            Class<?> resources = Class.forName("com.onurkat.reclazz.platform.SpringXmlResources", false, loader);
            List<?> contexts = (List<?>) holder.getMethod("getAllContexts").invoke(null);
            java.util.Set<Path> files = (java.util.Set<Path>) resources.getMethod("loadedFiles").invoke(null);
            Path source = files.stream().filter(p -> p.getFileName().toString().equals(filename)).findFirst().orElseThrow();
            return (List<ConfigurableApplicationContext>) resources.getMethod("owners", Path.class, List.class)
                    .invoke(null, source, contexts);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    static <T> T execute(ConfigurableApplicationContext owner, Callable<T> action) {
        try {
            Class<?> scope = Class.forName("com.onurkat.reclazz.hybris.SapOwnerScope", false, ClassLoader.getSystemClassLoader());
            var execute = scope.getDeclaredMethod("execute", Object.class, Callable.class);
            execute.setAccessible(true);
            FutureTask<T> task = new FutureTask<>(() -> (T) execute.invoke(null, owner, action));
            Thread thread = new Thread(task, "reclazz-test-owned-scope");
            thread.setDaemon(true); thread.start();
            return task.get(20, TimeUnit.SECONDS);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Synthetic owner observation/cleanup failed", failure);
        }
    }
}
