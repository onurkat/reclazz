/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.*;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import java.lang.invoke.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.context.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FirstAddedFullConfigurationOwnershipTest {
    @TempDir Path tmp;
    @Configuration public static class Owner {
        public Object product() { return new Object(); }
        public Object second() { return new Object(); }
    }
    @Configuration public static class NativeOwner {
        @Bean public Object product() { return new Object(); }
    }
    @AfterEach void clear() {
        AddedBeanBridge.publish(Owner.class, Map.of());
        RestartLedger.clear();
    }

    @Test void firstFactoryServesTheContainerProduct() throws Exception {
        try (var context = context()) {
            var reloader = reloader(context);
            assertTrue(install(reloader), RestartLedger.digest().toString());
            assertNotNull(context.getBean("product"));
            assertSame(context.getBean("product"), context.getBean("productAlias"));
        }
    }

    @Test void heldManagedReceiverResolvesTheSingleton() throws Throwable {
        try (var context = context()) {
            Owner held = context.getBean(Owner.class);
            assertTrue(install(reloader(context)), RestartLedger.digest().toString());
            assertSame(context.getBean("product"), route().invokeWithArguments(held));
        }
    }

    @Test void sameClassInTwoContextsKeepsSeparateOwnership() throws Throwable {
        try (var one = context(); var two = context()) {
            assertTrue(install(reloader(one, two)), RestartLedger.digest().toString());
            Object first = one.getBean("product"), second = two.getBean("product");
            assertNotSame(first, second);
            assertSame(first, route().invokeWithArguments(one.getBean(Owner.class)));
            assertSame(second, route().invokeWithArguments(two.getBean(Owner.class)));
        }
    }

    @Test void manuallyConstructedReceiverKeepsPlainJavaBehavior() throws Throwable {
        SpringConfigurationCalls.publish(Owner.class, AddedBeanAdapter.inspect(annotated(), keys()));
        Owner manual = new Owner();
        Object first = route().invokeWithArguments(manual);
        assertNotNull(first);
        assertNotSame(first, route().invokeWithArguments(manual));
    }

    @Test void extraProxyIsRefusedBeforeProductRegistration() throws Exception {
        try (var context = context()) {
            var factory = context.getDefaultListableBeanFactory();
            var proxy = new org.springframework.aop.framework.ProxyFactory(context.getBean(Owner.class));
            proxy.setProxyTargetClass(true);
            factory.destroySingleton("owner");
            factory.registerSingleton("owner", proxy.getProxy());
            assertFalse(install(reloader(context)));
            assertFalse(context.containsBean("product"));
            assertFalse(context.containsBean("productAlias"));
        }
    }

    @Test void parentOnlyConfigurationIsRefused() throws Exception {
        try (var parent = context(); var child = new AnnotationConfigApplicationContext()) {
            child.setParent(parent); child.refresh();
            assertFalse(install(reloader(child)));
            assertFalse(parent.containsBean("product"));
            assertFalse(child.containsBean("product"));
        }
    }

    @Test void ambiguousLocalSingletonsAreRefused() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            LookupCapture.store(Owner.class, MethodHandles.lookup());
            context.registerBean("one", Owner.class);
            context.registerBean("two", Owner.class);
            context.refresh();
            assertFalse(install(reloader(context)));
            assertFalse(context.containsBean("product"));
        }
    }

    @Test void nameCollisionLeavesForeignProductAndManualReceiverUntouched() throws Throwable {
        try (var context = context()) {
            Object foreign = new Object();
            context.getDefaultListableBeanFactory().registerSingleton("product", foreign);
            assertFalse(install(reloader(context)));
            assertSame(foreign, context.getBean("product"));
            assertFalse(context.containsBean("productAlias"));
            Object manual = route().invokeWithArguments(new Owner());
            assertNotSame(foreign, manual);
        }
    }

    @Test void removalAndReadditionDoNotRetainTheOldProduct() throws Throwable {
        try (var context = context()) {
            var reloader = reloader(context);
            Owner held = context.getBean(Owner.class);
            assertTrue(install(reloader), RestartLedger.digest().toString());
            Object first = route().invokeWithArguments(held);
            assertTrue(reloader.reloadBeanMethods(Owner.class, Set.of(), original()));
            assertFalse(context.containsBean("product"));
            assertFalse(context.containsBean("productAlias"));
            assertNotSame(first, route().invokeWithArguments(held));
            assertTrue(install(reloader), RestartLedger.digest().toString());
            Object next = context.getBean("product");
            assertNotSame(first, next);
            assertSame(next, route().invokeWithArguments(held));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"close", "replace"})
    void enrolledRawReceiverRetiresWithAnExplicitRestartFailure(String mode) throws Throwable {
        try (var context = context()) {
            Owner held = context.getBean(Owner.class);
            assumeTrue(held.getClass() == Owner.class, "Only the new raw receiver enrollment is in scope");
            assertTrue(install(reloader(context)), RestartLedger.digest().toString());
            assertSame(context.getBean("product"), route().invokeWithArguments(held));
            if (mode.equals("close")) context.close();
            else {
                var factory = context.getDefaultListableBeanFactory();
                factory.destroySingleton("owner");
                factory.registerSingleton("owner", new Owner());
            }
            var failure = assertThrows(IllegalStateException.class, () -> route().invokeWithArguments(held));
            assertTrue(failure.getMessage().toLowerCase(Locale.ROOT).contains("restart"), failure.toString());
        }
    }

    @Test void enhancedReceiverAfterCloseRetainsTheNativeControlBehavior() throws Throwable {
        var context = new AnnotationConfigApplicationContext(NativeOwner.class);
        NativeOwner held = context.getBean(NativeOwner.class);
        Object first = held.product();
        context.close();
        Object after = held.product();
        assertNotNull(after);
        assertNotSame(first, after);
    }

    @Test void agentlessRestartHasNativeFullConfigurationSemantics() throws Exception {
        Path log = tmp.resolve("native-restart.log");
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), NativeControl.class.getName());
        System.out.println("[native restart command] " + command);
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "native restart timed out");
            String text = Files.readString(log);
            assertEquals(0, child.exitValue(), text);
            assertTrue(text.contains("NATIVE=enhanced:singleton:manual:no-agent"), text);
            System.out.println(text);
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    public static class NativeControl {
        @Configuration public static class Config {
            @Bean public Object leaf() { return new Object(); }
            @Bean public List<Object> pair() { return List.of(leaf(), leaf()); }
        }
        public static void main(String[] args) {
            if (java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                    .anyMatch(s -> s.contains("-javaagent"))) throw new AssertionError("control has an agent");
            try (var context = new AnnotationConfigApplicationContext(Config.class)) {
                var held = context.getBean(Config.class);
                Object leaf = context.getBean("leaf");
                var pair = (List<?>) context.getBean("pair");
                if (held.getClass().getSuperclass() != Config.class || held.leaf() != leaf
                        || pair.get(0) != leaf || pair.get(1) != leaf
                        || new Config().leaf() == leaf) throw new AssertionError("native identity");
                System.out.println("NATIVE=enhanced:singleton:manual:no-agent java=" + System.getProperty("java.version"));
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void watchedFirstFactoriesKeepHeldAndInterBeanIdentityAcrossContextsAndReaddition(boolean child) throws Exception {
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .jvmArgs("-Dtest.dir=" + tmp).with("App", APP).with("Config", config(0))
                .with("Observer", observer(false)).with("Product", "package app; public record Product(int version) {}")
                .with("Pair", "package app; public record Pair(Product first, Product second) {}");
        if (child) builder.childClassLoader();
        try (var app = builder.start()) {
            app.awaitOrFail("READY", "contexts not ready"); app.awaitOrFail("] Watching ", "watcher not ready");
            for (int stage = 1; stage <= 3; stage++) {
                app.rewrite("Config", config(stage)); awaitReload(app, "Config", stage);
                app.rewrite("Observer", observer(stage != 2)); awaitReload(app, "Observer", stage);
                Files.createFile(tmp.resolve("probe" + stage));
                app.awaitOrFail("FIRST" + stage + "=", "ownership probe not executed");
                assertEquals("FIRST" + stage + "=" + (stage == 2 ? "removed" : "singleton:" + stage),
                        app.latest("FIRST" + stage + "="), app.tail());
                System.out.println("[first-bean child=" + child + "] " + app.latest("FIRST" + stage + "="));
            }
        }
    }

    @Configuration public static class EqualOwner {
        static int hashes;
        public Object product() { return new Object(); }
        @Override public boolean equals(Object other) { return other instanceof EqualOwner; }
        @Override public int hashCode() { hashes++; return 1; }
    }

    @ParameterizedTest @ValueSource(strings = {"fields", "constructor", "lifecycle", "shape"})
    void statefulConfigIsRefusedBeforeMutation(String variant) throws Throwable {
        try (var control = context()) {
            assumeTrue(control.getBean(Owner.class).getClass() == Owner.class);
            assertTrue(install(reloader(control)), "stateless control must be admitted");
            assertSame(control.getBean("product"), route().invokeWithArguments(control.getBean(Owner.class)));
        }
        try (var context = context()) {
            Owner held = context.getBean(Owner.class);
            byte[] bytes = annotated();
            var node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
            switch (variant) {
                case "fields" -> node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "state", "I", null, null));
                case "constructor" -> node.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow().desc = "(I)V";
                case "lifecycle" -> {
                    var method = node.methods.stream().filter(m -> m.name.equals("second")).findFirst().orElseThrow();
                    method.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Ljakarta/annotation/PostConstruct;")));
                }
                case "shape" -> context.getBeanFactory().getBeanDefinition("owner").setRole(1);
                default -> throw new AssertionError(variant);
            }
            var writer = new ClassWriter(0); node.accept(writer);
            RestartLedger.clear();
            assertFalse(reloader(context).reloadBeanMethods(Owner.class, keys(), writer.toByteArray()), variant);
            assertTrue(RestartLedger.digest().toString().toLowerCase(Locale.ROOT).contains("restart"));
            assertSame(held, context.getBean(Owner.class));
            assertFalse(context.containsBean("product"));
            assertFalse(context.containsBean("productAlias"));
        }
    }

    @Test void equalsOverridingConfigInTwoContextsKeepsTwoIdentities() throws Throwable {
        LookupCapture.store(EqualOwner.class, MethodHandles.lookup());
        try (var one = new AnnotationConfigApplicationContext(EqualOwner.class);
             var two = new AnnotationConfigApplicationContext(EqualOwner.class)) {
            var a = one.getBean(EqualOwner.class); var b = two.getBean(EqualOwner.class);
            assumeTrue(a.getClass() == EqualOwner.class);
            assertEquals(a, b); assertNotSame(a, b);
            int beforeHashes = EqualOwner.hashes;
            var node = new ClassNode();
            try (var in = EqualOwner.class.getResourceAsStream("/" + EqualOwner.class.getName().replace('.', '/') + ".class")) {
                new ClassReader(Objects.requireNonNull(in).readAllBytes()).accept(node, 0);
            }
            for (var method : node.methods) if (method.name.equals("product"))
                method.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(AddedBeanAdapter.BEAN)));
            var writer = new ClassWriter(0); node.accept(writer);
            assertTrue(reloader(one, two).reloadBeanMethods(EqualOwner.class, keys(), writer.toByteArray()));
            var mt = MethodType.methodType(Object.class);
            var call = AddedBeanBridge.call(EqualOwner.class, InjectedNames.siteKey("product", InjectedNames.descHash(mt.toMethodDescriptorString())),
                    new ConstantCallSite(MethodHandles.lookup().findVirtual(EqualOwner.class, "product", mt))).dynamicInvoker();
            assertNotSame(one.getBean("product"), two.getBean("product"));
            assertSame(one.getBean("product"), call.invokeWithArguments(a));
            assertSame(two.getBean("product"), call.invokeWithArguments(b));
            assertEquals(beforeHashes, EqualOwner.hashes, "ownership must not invoke receiver hashCode");
        } finally { AddedBeanBridge.publish(EqualOwner.class, Map.of()); }
    }

    @Test void reversibleCollisionKeepsPriorGenerationInEveryScope() throws Throwable {
        rollbackAcrossScopes(false);
    }
    @Test void stagedRegistrationFailureInSecondContextRollsBackFirst() throws Throwable {
        rollbackAcrossScopes(true);
    }
    private static void rollbackAcrossScopes(boolean race) throws Throwable {
        try (var one = context(); var two = context()) {
            assumeTrue(one.getBean(Owner.class).getClass() == Owner.class);
            var reloader = reloader(one, two);
            assertTrue(install(reloader));
            Object a = one.getBean("product"), b = two.getBean("product");
            Object da = one.getBeanFactory().getBeanDefinition("product"), db = two.getBeanFactory().getBeanDefinition("product");
            Object heldA = one.getBean(Owner.class), heldB = two.getBean(Owner.class), foreign = new Object();
            var foreignDefinition = new org.springframework.beans.factory.support.RootBeanDefinition(Object.class);
            foreignDefinition.setInstanceSupplier(() -> foreign);
            Runnable collision = () -> two.getDefaultListableBeanFactory().registerBeanDefinition("second", foreignDefinition);
            if (race) reloader.afterRawPreflight(collision); else collision.run();
            assertFalse(reloader.reloadBeanMethods(Owner.class,
                    Set.of("product:()Ljava/lang/Object;", "second:()Ljava/lang/Object;"), withSecond()));
            assertSame(da, one.getBeanFactory().getBeanDefinition("product"));
            assertSame(db, two.getBeanFactory().getBeanDefinition("product"));
            assertSame(a, one.getBean("productAlias")); assertSame(b, two.getBean("productAlias"));
            assertSame(a, route().invokeWithArguments(heldA)); assertSame(b, route().invokeWithArguments(heldB));
            assertFalse(one.containsBean("second")); assertSame(foreign, two.getBean("second"));
            assertSame(foreignDefinition, two.getBeanFactory().getBeanDefinition("second"));
        }
    }
    private static byte[] withSecond() throws Exception {
        var node = new ClassNode(); new ClassReader(annotated()).accept(node, 0);
        for (var method : node.methods) if (method.name.equals("second"))
            method.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(AddedBeanAdapter.BEAN)));
        var writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }

    @Test void crossContextPublicationWaitsForBothEnrollments() throws Throwable {
        try (var one = context(); var two = context()) {
            assumeTrue(one.getBean(Owner.class).getClass() == Owner.class);
            var checked = new java.util.concurrent.atomic.AtomicBoolean();
            one.getBeanFactory().addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (name.equals("product")) try {
                        assertTrue(two.containsBeanDefinition("product"));
                        assertSame(two.getBean("product"), route().invokeWithArguments(two.getBean(Owner.class)));
                        checked.set(true);
                    } catch (Throwable failure) { throw new AssertionError(failure); }
                    return bean;
                }
            });
            assertTrue(install(reloader(one, two))); assertTrue(checked.get());
        }
    }

    @Test void removalKeepsTheRawRootEligibleForLaterMethodOnlySaves() throws Throwable {
        try (var context = context()) {
            Owner held = context.getBean(Owner.class); assumeTrue(held.getClass() == Owner.class);
            var reloader = reloader(context); assertTrue(install(reloader));
            assertTrue(reloader.reloadBeanMethods(Owner.class, Set.of(), original()));
            var prepared = reloader.prepareRaw(Owner.class, Set.of(), original());
            assertTrue(prepared.preserves(context, "owner", held));
            assertFalse(prepared.preserves(context, "owner", new Owner()));
            assertFalse(context.containsBean("product"));
        }
    }

    @Test void failedInitializationKeepsSuccessfulMethodsActiveAndFailsTheOldRouteClosed() throws Throwable {
        try (var context = context()) {
            Owner held = context.getBean(Owner.class); assumeTrue(held.getClass() == Owner.class);
            var reloader = reloader(context); assertTrue(install(reloader));
            var node = new ClassNode(); new ClassReader(withSecond()).accept(node, 0);
            for (var method : node.methods) if (method.name.equals("product"))
                method.visibleAnnotations.get(0).values.addAll(List.of("initMethod", "missingInitializer"));
            var writer = new ClassWriter(0); node.accept(writer);
            assertFalse(reloader.reloadBeanMethods(Owner.class,
                    Set.of("product:()Ljava/lang/Object;", "second:()Ljava/lang/Object;"), writer.toByteArray()));
            assertFalse(context.containsBean("product")); assertFalse(context.containsBean("productAlias"));
            assertThrows(IllegalStateException.class, () -> route().invokeWithArguments(held));
            var mt = MethodType.methodType(Object.class);
            var second = AddedBeanBridge.call(Owner.class, InjectedNames.siteKey("second", InjectedNames.descHash(mt.toMethodDescriptorString())),
                    new ConstantCallSite(MethodHandles.lookup().findVirtual(Owner.class, "second", mt))).dynamicInvoker();
            assertSame(context.getBean("second"), second.invokeWithArguments(held));
        }
    }

    @Test void aForeignProductReplacementIsNeverAuthorizedOrRemoved() throws Throwable {
        try (var context = context()) {
            Owner held = context.getBean(Owner.class); assumeTrue(held.getClass() == Owner.class);
            var node = new ClassNode(); new ClassReader(annotated()).accept(node, 0);
            for (var method : node.methods) if (method.name.equals("product"))
                method.visibleAnnotations.add(new AnnotationNode("Lorg/springframework/context/annotation/Lazy;"));
            var writer = new ClassWriter(0); node.accept(writer); byte[] lazy = writer.toByteArray();
            var reloader = reloader(context);
            assertTrue(reloader.reloadBeanMethods(Owner.class, keys(), lazy));
            assertSame(context.getBean("product"), route().invokeWithArguments(held));
            var factory = context.getDefaultListableBeanFactory();
            Object definition = factory.getBeanDefinition("product"), foreign = new Object();
            var checked = new java.util.concurrent.atomic.AtomicBoolean();
            reloader.afterRawPreflight(() -> {
                factory.destroySingleton("product"); factory.registerSingleton("product", foreign);
                var failure = assertThrows(IllegalStateException.class, () -> route().invokeWithArguments(held));
                assertTrue(failure.getMessage().contains("restart")); checked.set(true);
            });
            // A lazy product must not turn a failed ownership check into success
            // merely because the initialization loop has no eager work to do.
            assertFalse(reloader.reloadBeanMethods(Owner.class, keys(), lazy));
            assertTrue(checked.get());
            assertSame(foreign, context.getBean("product"));
            assertSame(definition, factory.getBeanDefinition("product"));
        }
    }

    @Test void pendingEnrollmentDoesNotAuthorizeAReference() throws Throwable {
        try (var context = context()) {
            Owner held = context.getBean(Owner.class); assumeTrue(held.getClass() == Owner.class);
            SpringConfigurationCalls.publish(Owner.class, AddedBeanAdapter.inspect(annotated(), keys()));
            var pending = SpringConfigurationCalls.pending(held, Owner.class, context, context.getBeanFactory(),
                    context.getBeanFactory().getBeanDefinition("owner"), "owner");
            try {
                var failure = assertThrows(IllegalStateException.class, () -> route().invokeWithArguments(held));
                assertTrue(failure.getMessage().contains("pending"));
                assertFalse(context.containsBean("product"));
            } finally { SpringConfigurationCalls.abort(held, Owner.class, pending); }
            assertNotSame(route().invokeWithArguments(held), route().invokeWithArguments(held));
        }
    }

    @Test void registryDoesNotRetainContextAfterCloseAndGc() throws Throwable {
        Object[] retained = closedRawContext();
        var context = (java.lang.ref.WeakReference<?>) retained[1];
        var factory = (java.lang.ref.WeakReference<?>) retained[2];
        // Same bounded GC pattern as JacksonMapperRegistryTest (80 x 25ms).
        for (int i = 0; i < 80 && (context.get() != null || factory.get() != null); i++) { System.gc(); Thread.sleep(25); }
        assertNull(context.get(), "weak enrollment must not retain context");
        assertNull(factory.get(), "weak enrollment must not retain factory");
        assertThrows(IllegalStateException.class, () -> route().invokeWithArguments(retained[0]));
    }
    private static Object[] closedRawContext() throws Exception {
        var context = context(); Owner held = context.getBean(Owner.class);
        try {
            assumeTrue(held.getClass() == Owner.class); assertTrue(install(reloader(context)));
            return new Object[]{held, new java.lang.ref.WeakReference<>(context), new java.lang.ref.WeakReference<>(context.getBeanFactory())};
        } finally { context.close(); }
    }

    private static AnnotationConfigApplicationContext context() {
        LookupCapture.store(Owner.class, MethodHandles.lookup());
        var context = new AnnotationConfigApplicationContext();
        context.registerBean("owner", Owner.class);
        context.refresh();
        return context;
    }
    private static SpringAddedBeanReloader reloader(AnnotationConfigApplicationContext... contexts) {
        PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(PlatformContext.class.getClassLoader(),
                new Class<?>[]{PlatformContext.class},
                (p,m,a) -> m.getName().equals("getAllApplicationContexts") ? List.of(contexts) : null);
        return new SpringAddedBeanReloader(platform);
    }
    private static Set<String> keys() { return Set.of("product:()Ljava/lang/Object;"); }
    private static boolean install(SpringAddedBeanReloader reloader) throws Exception {
        return reloader.reloadBeanMethods(Owner.class, keys(), annotated());
    }
    private static MethodHandle route() throws Exception {
        var type = MethodType.methodType(Object.class);
        return AddedBeanBridge.call(Owner.class, InjectedNames.siteKey("product", InjectedNames.descHash(type.toMethodDescriptorString())),
                new ConstantCallSite(MethodHandles.lookup().findVirtual(Owner.class, "product", type))).dynamicInvoker();
    }
    private static byte[] original() throws Exception {
        try (var in = Owner.class.getResourceAsStream("/" + Owner.class.getName().replace('.', '/') + ".class")) {
            return Objects.requireNonNull(in).readAllBytes();
        }
    }
    private static byte[] annotated() throws Exception {
        var node = new ClassNode(); new ClassReader(original()).accept(node, 0);
        for (var method : node.methods) if (method.name.equals("product")) {
            var bean = new AnnotationNode(AddedBeanAdapter.BEAN);
            bean.values = new ArrayList<>(List.of("name", List.of("product", "productAlias")));
            method.visibleAnnotations = new ArrayList<>(List.of(bean));
        }
        var writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
    private static void awaitReload(WatchedApp app, String name, int expected) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < end && reloads(app, name) < expected) Thread.sleep(25);
        assertEquals(expected, reloads(app, name), app.tail());
    }
    private static long reloads(WatchedApp app, String name) {
        return app.output().stream().filter(s -> s.contains("Reloaded app." + name)
                || s.contains("Structural reload: app." + name)).count();
    }
    private static String config(int stage) {
        String factories = stage == 0 || stage == 2 ? "" : """
                @org.springframework.context.annotation.Bean(name={"leaf","leafAlias"})
                public Product leaf(){return new Product(%d);}
                @org.springframework.context.annotation.Bean
                public Pair pair(){return new Pair(leaf(),leaf());}
                """.formatted(stage);
        return "package app; @org.springframework.context.annotation.Configuration public class Config {"
                + "public Product heldReference(){ " + (stage == 0 || stage == 2 ? "return null;" : "return leaf();") + "}"
                + factories + "}";
    }
    private static String observer(boolean added) {
        return "package app; public class Observer { public static boolean same(Config c,Object leaf){return "
                + (added ? "c.leaf()==leaf" : "leaf==null") + ";} public static boolean manual(){return "
                + (added ? "new Config().leaf()!=new Config().leaf()" : "true") + ";}}";
    }
    private static final String APP = """
            package app;
            public class App {
                public static void main(String[] args)throws Exception {
                    try(var one=new org.springframework.context.annotation.AnnotationConfigApplicationContext(Config.class);
                        var two=new org.springframework.context.annotation.AnnotationConfigApplicationContext(Config.class)){
                        Config first=one.getBean(Config.class),second=two.getBean(Config.class);
                        System.out.println("READY");
                        for(int stage=1;stage<=3;stage++){
                            while(!java.nio.file.Files.exists(java.nio.file.Path.of(System.getProperty("test.dir"),"probe"+stage)))Thread.sleep(25);
                            try {
                                if(stage==2){
                                    if(one.containsBean("leaf")||one.containsBean("pair")||one.containsBean("leafAlias")
                                        ||two.containsBean("leaf")||first.heldReference()!=null||second.heldReference()!=null)
                                        throw new AssertionError("removed ownership remains");
                                    System.out.println("FIRST"+stage+"=removed");
                                }else{
                                    Product a=one.getBean("leaf",Product.class),b=two.getBean("leaf",Product.class);
                                    Pair pa=one.getBean("pair",Pair.class),pb=two.getBean("pair",Pair.class);
                                    if(a==b||pa.first()!=a||pa.second()!=a||pb.first()!=b||pb.second()!=b
                                        ||first.heldReference()!=a||second.heldReference()!=b
                                        ||!Observer.same(first,a)||!Observer.same(second,b)||!Observer.manual()
                                        ||a!=one.getBean("leafAlias")||b!=two.getBean("leafAlias")
                                        ||a.version()!=stage||b.version()!=stage)throw new AssertionError("managed/manual/context identity");
                                    System.out.println("FIRST"+stage+"=singleton:"+stage);
                                }
                            }catch(Throwable failure){System.out.println("FIRST"+stage+"=failed:"+failure);throw failure;}
                        }
                    }
                }
            }
            """;
}
