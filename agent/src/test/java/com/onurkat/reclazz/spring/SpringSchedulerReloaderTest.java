/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;

class SpringSchedulerReloaderTest {
    static class Jobs {
        int calls;
        public void first() { calls++; }
        private void second() { }
        public int result() { return 0; }
        public void argument(int ignored) { }
        public static void staticJob() { }
    }

    private static final Set<String> ADDED = Set.of("first:()V", "second:()V");

    @Test
    void aBeanReplacedWithoutASchedulerReloadDoesNotLeaveTheTaskOnTheDestroyedInstance() throws Exception {
        try (Scope scope = new Scope()) {
            assertTrue(reloader(scope).reloadScheduledMethods(Jobs.class, ADDED,
                    scheduled("first", "fixedDelay", 60_000L)));
            Jobs old = scope.context.getBean(Jobs.class);
            Runnable task = scope.processor.getScheduledTasks().iterator().next().getTask().getRunnable();
            task.run();
            assertEquals(1, old.calls);
            scope.context.getDefaultListableBeanFactory().destroySingleton("jobs");
            task.run();
            assertEquals(1, old.calls, "a temporarily absent bean must not run or be recreated by the task");
            Jobs replacement = new Jobs();
            scope.context.getBeanFactory().registerSingleton("jobs", replacement);
            task.run();
            assertEquals(1, replacement.calls, "the existing task must reach the replacement singleton");
            assertEquals(1, old.calls);
        }
    }

    @Test
    void allSingletonsInEachContextHaveOneRegistrationAndRemovalCancelsThem() throws Exception {
        try (Scope one = new Scope(); Scope two = new Scope()) {
            one.context.getBeanFactory().registerSingleton("another", new Jobs());
            var reloader = reloader(one, two);
            byte[] bytes = scheduled("first", "fixedDelay", 60_000L);
            for (int i = 0; i < 3; i++) {
                assertTrue(reloader.reloadScheduledMethods(Jobs.class, ADDED, bytes));
                assertEquals(2, one.processor.getScheduledTasks().size());
                assertEquals(1, two.processor.getScheduledTasks().size());
            }
            assertTrue(reloader.reloadScheduledMethods(Jobs.class, Set.of(), original()));
            assertEquals(0, one.processor.getScheduledTasks().size());
            assertEquals(0, two.processor.getScheduledTasks().size());
        }
    }

    @Test
    void aRepeatableScheduleKeepsBothDeclarationsAndTheirUnits() throws Exception {
        try (Scope scope = new Scope()) {
            ClassNode source = read(original());
            AnnotationNode schedules = new AnnotationNode(AddedScheduledAdapter.SCHEDULES);
            AnnotationNode rate = annotation("fixedRate", 2L);
            rate.values.addAll(List.of("timeUnit", new String[]{"Ljava/util/concurrent/TimeUnit;", "SECONDS"}));
            schedules.values = new ArrayList<>(List.of("value", List.of(rate, annotation("fixedDelay", 60_000L))));
            source.methods.stream().filter(m -> m.name.equals("first")).findFirst().orElseThrow()
                    .visibleAnnotations = new ArrayList<>(List.of(schedules));
            assertTrue(reloader(scope).reloadScheduledMethods(Jobs.class, ADDED, write(source)));
            var tasks = scope.processor.getScheduledTasks();
            assertEquals(2, tasks.size());
            assertTrue(tasks.stream().anyMatch(t -> t.getTask() instanceof org.springframework.scheduling.config.FixedRateTask rateTask
                    && rateTask.getInterval() == 2000));
        }
    }

    @Test
    void partialRegistrationIsCancelledAndTheNextValidSaveRecovers() throws Exception {
        try (Scope scope = new Scope()) {
            var reloader = reloader(scope);
            ClassNode source = read(scheduled("first", "fixedDelay", 60_000L));
            source.methods.stream().filter(m -> m.name.equals("second")).findFirst().orElseThrow()
                    .visibleAnnotations = new ArrayList<>(List.of(annotation("cron", "invalid")));
            assertFalse(reloader.reloadScheduledMethods(Jobs.class, ADDED, write(source)));
            assertEquals(0, scope.processor.getScheduledTasks().size(), "a valid first method must not be left registered");
            assertTrue(reloader.reloadScheduledMethods(Jobs.class, ADDED, scheduled("first", "fixedDelay", 60_000L)));
            assertEquals(1, scope.processor.getScheduledTasks().size());
        }
    }

    @Test
    void subclassProxyIsNamedInsteadOfBypassingItsAdvice() throws Exception {
        try (Scope scope = new Scope()) {
            var reloader = reloader(scope);
            byte[] bytes = scheduled("first", "fixedDelay", 60_000L);
            assertTrue(reloader.reloadScheduledMethods(Jobs.class, ADDED, bytes));
            scope.context.getDefaultListableBeanFactory().destroySingleton("jobs");
            var proxy = new org.springframework.aop.framework.ProxyFactory(new Jobs());
            proxy.setProxyTargetClass(true);
            scope.context.getBeanFactory().registerSingleton("jobs", proxy.getProxy());
            RestartLedger.clear();
            assertFalse(reloader.reloadScheduledMethods(Jobs.class, ADDED, bytes));
            assertEquals(0, scope.processor.getScheduledTasks().size(), "the old adapter must be cancelled too");
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("proxies")));
        } finally { RestartLedger.clear(); }
    }

    @Test
    void prototypeIsNotCreatedToScheduleAnAddedMethod() throws Exception {
        try (Scope scope = new Scope()) {
            scope.context.getDefaultListableBeanFactory().destroySingleton("jobs");
            var definition = new org.springframework.beans.factory.support.RootBeanDefinition(Jobs.class);
            definition.setScope("prototype");
            scope.context.registerBeanDefinition("jobs", definition);
            RestartLedger.clear();
            assertFalse(reloader(scope).reloadScheduledMethods(Jobs.class, ADDED,
                    scheduled("first", "fixedDelay", 60_000L)));
            assertEquals(0, scope.processor.getScheduledTasks().size());
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("singleton")));
        } finally { RestartLedger.clear(); }
    }

    @Test
    void unsupportedSignaturesAndAdviceAreExplained() throws Exception {
        for (String name : List.of("result", "argument", "staticJob")) {
            byte[] bytes = scheduled(name, "fixedDelay", 1000L);
            var method = read(bytes).methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
            var plan = AddedScheduledAdapter.inspect(bytes, Set.of(method.name + ":" + method.desc));
            assertTrue(plan.methods().isEmpty());
            assertEquals(1, plan.refused().size());
        }
        ClassNode source = read(scheduled("first", "fixedDelay", 1000L));
        source.methods.stream().filter(m -> m.name.equals("first")).findFirst().orElseThrow().visibleAnnotations
                .add(new AnnotationNode("Lorg/springframework/transaction/annotation/Transactional;"));
        var plan = AddedScheduledAdapter.inspect(write(source), ADDED);
        assertTrue(plan.methods().isEmpty());
        assertTrue(plan.refused().get(0).contains("additional method annotations"));
    }

    @Test
    void adapterIsHiddenAndDoesNotExposeItsTargetAsAnApplicationField() throws Throwable {
        LookupCapture.store(Jobs.class, MethodHandles.privateLookupIn(Jobs.class, MethodHandles.lookup()));
        Object adapter = AddedScheduledAdapter.create(Jobs.class, Jobs::new,
                AddedScheduledAdapter.inspect(scheduled("first", "fixedDelay", 60_000L), ADDED));
        assertTrue(adapter.getClass().isHidden());
        assertTrue(java.util.Arrays.stream(adapter.getClass().getDeclaredFields())
                .allMatch(f -> f.getName().startsWith("__reclazz$")));
    }

    private static SpringSchedulerReloader reloader(Scope... scopes) {
        List<Object> contexts = java.util.Arrays.stream(scopes).map(s -> (Object) s.context).toList();
        PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(
                SpringSchedulerReloaderTest.class.getClassLoader(), new Class<?>[]{PlatformContext.class},
                (p, m, args) -> m.getName().equals("getAllApplicationContexts") ? contexts : null);
        return new SpringSchedulerReloader(platform);
    }

    private static final class Scope implements AutoCloseable {
        final GenericApplicationContext context = new GenericApplicationContext();
        final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        final ScheduledAnnotationBeanPostProcessor processor = new ScheduledAnnotationBeanPostProcessor();
        Scope() throws Exception {
            LookupCapture.store(Jobs.class, MethodHandles.privateLookupIn(Jobs.class, MethodHandles.lookup()));
            executor.setRemoveOnCancelPolicy(true);
            context.getBeanFactory().registerSingleton("jobs", new Jobs());
            context.getBeanFactory().registerSingleton("schedulerProcessor", processor);
            context.refresh();
            processor.setScheduler(executor);
            processor.setBeanFactory(context.getBeanFactory());
            processor.afterSingletonsInstantiated();
        }
        @Override public void close() { processor.destroy(); context.close(); executor.shutdownNow(); }
    }

    private static byte[] original() throws Exception {
        try (var stream = Jobs.class.getResourceAsStream("/" + Jobs.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(stream);
            return stream.readAllBytes();
        }
    }
    private static byte[] scheduled(String name, String attribute, Object value) throws Exception {
        ClassNode source = read(original());
        source.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow()
                .visibleAnnotations = new ArrayList<>(List.of(annotation(attribute, value)));
        return write(source);
    }
    private static AnnotationNode annotation(String attribute, Object value) {
        var annotation = new AnnotationNode(AddedScheduledAdapter.SCHEDULED);
        annotation.values = new ArrayList<>(List.of(attribute, value, "initialDelay", 60_000L));
        // Cron cannot carry an initial delay.
        if (attribute.equals("cron")) annotation.values = new ArrayList<>(List.of(attribute, value));
        return annotation;
    }
    private static ClassNode read(byte[] bytes) {
        ClassNode source = new ClassNode();
        new ClassReader(bytes).accept(source, 0);
        return source;
    }
    private static byte[] write(ClassNode source) {
        ClassWriter writer = new ClassWriter(0);
        source.accept(writer);
        return writer.toByteArray();
    }
}
