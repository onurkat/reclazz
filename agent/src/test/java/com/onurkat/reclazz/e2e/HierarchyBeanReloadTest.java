/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

class HierarchyBeanReloadTest {
    @TempDir Path tmp;
    private static final boolean SPRING_SEVEN = "7.0.10".equals(org.springframework.core.SpringVersion.getVersion());
    private static final String HIERARCHY_REFUSAL = "Spring 7.0.10 configuration hierarchy requires restart for ";
    private static final String PREFIX = "package app; import org.springframework.context.annotation.*; ";
    private static final String CONFIG = "@Configuration(proxyBeanMethods=false) ";
    private static final String FACTORY = "@Bean public Product inherited(){ return new Product(this); }";
    private static final String LOCAL = "@Bean public Product local(){ return new Product(this); }";
    private static String base(String body) { return PREFIX + "public abstract class Base { " + body + " }"; }
    private static String config(String body) { return PREFIX + CONFIG + "public class First extends Base { " + body + " }"; }
    private static final String SECOND = PREFIX + CONFIG + "public class Second extends Base {}";
    private static final String APP = """
            package app;
            import org.springframework.context.annotation.AnnotationConfigApplicationContext;
            import org.springframework.beans.factory.support.RootBeanDefinition;
            public class App {
                static String describe(AnnotationConfigApplicationContext ctx, String name) {
                    if (!ctx.containsBean(name)) return "-";
                    var product = (Product)ctx.getBean(name);
                    var def = (RootBeanDefinition)ctx.getBeanFactory().getMergedBeanDefinition(name);
                    var method = def.getResolvedFactoryMethod();
                    return def.getFactoryBeanName() + "/" + method.getDeclaringClass().getSimpleName()
                            + "/" + (product.owner()==ctx.getBean(def.getFactoryBeanName()))
                            + "/" + (ctx.getBeansOfType(Product.class).get(name)==product) + product.marker();
                }
                public static void main(String[] args) throws Exception {
                    var ctx = new AnnotationConfigApplicationContext();
                    if (Boolean.getBoolean("reverse")) ctx.register(Second.class, First.class);
                    else ctx.register(First.class, Second.class);
                    ctx.refresh();
                    Object held = ctx.getBean(First.class);
                    String last = null;
                    for (;;) {
                        String line;
                        try { line = "STATE " + describe(ctx,"inherited") + " " + describe(ctx,"local")
                                + " held=" + (held==ctx.getBean(First.class)); }
                        catch (Throwable e) { line = "READ " + e; }
                        if (!line.equals(last)) { System.out.println(line); last = line; }
                        if (java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(System.getProperty("probe") + ".registry"))) {
                            var factory = ctx.getBeanFactory();
                            System.out.println("REGISTRY " + factory.containsBeanDefinition("inherited") + "/"
                                    + factory.containsSingleton("inherited") + "/" + factory.containsBeanDefinition("local") + "/"
                                    + factory.containsSingleton("local") + "/" + (held == ctx.getBean(First.class)));
                        }
                        Thread.sleep(40);
                    }
                }
            }
            """;
    private WatchedApp.Builder app(String name, boolean reverse, String b, String c) {
        return app(name,reverse,b,c,APP);
    }
    private WatchedApp.Builder app(String name, boolean reverse, String b, String c, String application) {
        return WatchedApp.in(tmp.resolve(name)).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=400,verbose=true").jvmArgs("-Dreverse=" + reverse, "-Dprobe=" + tmp.resolve(name).resolve("probe"))
                .with("Product","package app; public record Product(Object owner,String marker){ public Product(Object o){this(o,\"\");} }")
                .with("Base",b).with("First",c).with("Second",SECOND).with("App",application);
    }
    private boolean refusedOnSpringSeven(WatchedApp live, boolean startupInherited) throws Exception {
        if (!SPRING_SEVEN) return false;
        live.awaitOrFail(HIERARCHY_REFUSAL, "named refusal for the actual configuration");
        java.nio.file.Files.createFile(live.classesDir().getParent().resolve("probe.registry"));
        live.awaitOrFail("REGISTRY " + startupInherited + "/false/false/false/true", "registry and held configuration after refusal");
        return true;
    }

    @Test void firstRegisteredConfigurationOwnsTheBase() throws Exception { shared(false); }
    @Test void reversingRegistrationChangesTheBaseOwner() throws Exception { shared(true); }
    private void shared(boolean reverse) throws Exception {
        var changed = base(FACTORY);
        var child = config(LOCAL);
        String expected = "STATE " + (reverse ? "second" : "first") + "/Base/true/true first/First/true/true held=true";
        try (var control = app("native",reverse,changed,child).withoutAgent().start()) {
            control.awaitOrFail(expected,"native method, product type, or owner did not match");
        }
        try (var live = app("live",reverse,base(""),config("")).start()) {
            live.awaitOrFail("STATE - - held=true","missing baseline");
            live.awaitOrFail("] Watching ","watcher did not start");
            var sources = new LinkedHashMap<String,String>();
            if (reverse) sources.put("First",child);
            sources.put("Base",changed);
            if (!reverse) sources.put("First",child);
            live.rewriteAll(sources);
            if (refusedOnSpringSeven(live, false)) return;
            assertTrue(live.awaits(expected,20),live::tail);
        }
    }
    @Test void savingOnlyAnUnannotatedBasePublishesItsFactory() throws Exception {
        String expected="STATE first/Base/true/true - held=true";
        try (var nativeApp=app("native",false,base(FACTORY),config("")).withoutAgent().start()) {
            nativeApp.awaitOrFail(expected,"native superclass factory owner");
        }
        try (var live=app("live",false,base(""),config("")).start()) {
            live.awaitOrFail("STATE - - held=true","missing baseline");
            live.awaitOrFail("] Watching ","watcher did not start");
            live.rewrite("Base",base(FACTORY));
            if (refusedOnSpringSeven(live, false)) return;
            assertTrue(live.awaits(expected,20),live::tail);
        }
    }
    @Test void overridingFactoryRetainsTheNativeNamesAndDeclaringMethods() throws Exception {
        String parent = base(FACTORY.replace("Product(this)", "Product(this,\"base\")"));
        String subclass = config("@Bean(\"local\") public Product inherited(){return new Product(this,\"child\");}");
        String expected = SPRING_SEVEN ? "STATE - first/First/true/truechild held=true"
                : "STATE first/Base/true/truechild first/First/true/truechild held=true";
        try (var control = app("control",false,parent,subclass).withoutAgent().start()) {
            control.awaitOrFail(expected,"native override metadata");
        }
        try (var live = app("live",false,base(""),config("")).start()) {
            live.awaitOrFail("STATE - - held=true","missing baseline");
            live.awaitOrFail("] Watching ","watcher did not start");
            live.rewriteAll(java.util.Map.of("Base",parent,"First",subclass));
            if (refusedOnSpringSeven(live, false)) return;
            assertTrue(live.awaits(expected,20),live::tail);
        }
    }

    @Test void editedRemovedAndRepeatedFactoriesKeepNativeArgumentAndSingletonSemantics() throws Exception {
        String first = "@Bean public Product inherited(String label) { System.out.println(\"CREATED-\" + label); return new Product(this,label); }";
        String changed = first.replace("this,label", "this,label + \"2\"");
        String probe = APP.replace("ctx.refresh();", "ctx.registerBean(\"label\", String.class, () -> \"wired\"); ctx.refresh();");
        try (var live = app("cycles",false,base(""),config(""),probe).start()) {
            live.awaitOrFail("STATE - - held=true","missing baseline");
            live.awaitOrFail("] Watching ","watcher ready");
            live.rewrite("Base",base(first));
            if (refusedOnSpringSeven(live, false)) return;
            live.awaitOrFail("STATE first/Base/true/truewired - held=true","argument was not autowired");
            live.recompile("Base");
            live.rewrite("First",config(LOCAL));
            live.awaitOrFail("STATE first/Base/true/truewired first/First/true/true held=true","separate child save");
            assertEquals(1,live.output().stream().filter(s -> s.equals("CREATED-wired")).count(),live::tail);
            live.rewrite("Base",base(changed));
            live.awaitOrFail("STATE first/Base/true/truewired2 first/First/true/true held=true","factory body edit");
            live.rewrite("Base",base(""));
            live.awaitOrFail("STATE - first/First/true/true held=true","removed factory survived");
        }
    }

    @Test void subclassFirstAndBaseSecondCanArriveInSeparateBatches() throws Exception {
        try (var live = app("child-first",false,base(""),config("")).start()) {
            live.awaitOrFail("STATE - - held=true","context ready");
            live.awaitOrFail("] Watching ","watcher ready");
            live.rewrite("First",config(LOCAL));
            if (refusedOnSpringSeven(live, false)) return;
            live.awaitOrFail("STATE - first/First/true/true held=true","first save");
            live.rewrite("Base",base(FACTORY));
            live.awaitOrFail("STATE first/Base/true/true first/First/true/true held=true","second save");
        }
    }

    @Test void aThrowingSecondFactoryRemovesTheWholeStagedGroup() throws Exception {
        String throwing = "@Bean public Product local(){ throw new IllegalStateException(\"r34-test-failure\"); }";
        String inspection = APP.replace("describe(ctx,\"inherited\")","\"-\"")
                .replace("describe(ctx,\"local\")","\"-\"").replace("Thread.sleep(40);", """
                if (java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(System.getProperty("probe")))) {
                    var f = ctx.getBeanFactory();
                    System.out.println("OWNED " + f.containsBeanDefinition("inherited") + "/" + f.containsSingleton("inherited")
                        + "/" + f.containsBeanDefinition("local") + "/" + f.containsSingleton("local"));
                }
                Thread.sleep(40);
                """);
        try (var live = app("rollback",false,base(""),config(""),inspection).start()) {
            live.awaitOrFail("STATE - - held=true","context ready");
            live.awaitOrFail("] Watching ","watcher ready");
            live.rewrite("Base",base(FACTORY.replace("return new", "System.out.println(\"FIRST-RAN\"); return new") + throwing));
            if (refusedOnSpringSeven(live, false)) return;
            live.awaitOrFail("FIRST-RAN","first eager factory never initialized");
            live.awaitOrFail("Added @Bean on app.Base needs a restart","second eager factory did not trigger rollback");
            java.nio.file.Files.createFile(tmp.resolve("rollback/probe"));
            live.awaitOrFail("OWNED false/false/false/false","staged definition or singleton survived");
        }
    }

    @Test void aThrowingRemovalDiscardsTheNewDefinitionAndAllowsALaterRetry() throws Exception {
        String probe = APP.replace("public class App {", """
                public class App {
                    public static class Registry extends org.springframework.beans.factory.support.DefaultListableBeanFactory {
                        private boolean reject = true;
                        @Override public void removeBeanDefinition(String name) {
                            if (reject && name.equals("inherited")) {
                                reject = false;
                                throw new IllegalStateException("r34-removal");
                            }
                            super.removeBeanDefinition(name);
                        }
                    }
                """).replace("new AnnotationConfigApplicationContext()", "new AnnotationConfigApplicationContext(new Registry())")
                .replace("Thread.sleep(40);", """
                    if (java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(System.getProperty("probe"))))
                        System.out.println("AFTER-FAIL " + ctx.containsBeanDefinition("inherited") + "/" + ctx.containsBeanDefinition("local"));
                    Thread.sleep(40);
                    """);
        try (var live = app("remove-failure",false,base(""),config(""),probe).start()) {
            live.awaitOrFail("STATE - - held=true","context ready");
            live.awaitOrFail("] Watching ","watcher ready");
            live.rewrite("Base",base(FACTORY));
            if (refusedOnSpringSeven(live, false)) return;
            live.awaitOrFail("STATE first/Base/true/true - held=true","original product");
            live.rewrite("Base",base(LOCAL));
            live.awaitOrFail("Added @Bean on app.Base needs a restart","missing failed removal receipt");
            java.nio.file.Files.createFile(tmp.resolve("remove-failure/probe"));
            live.awaitOrFail("AFTER-FAIL true/false","new definition escaped rollback");
            live.rewrite("Base",base(LOCAL + "private int version(){return 3;}"));
            live.awaitOrFail("STATE - first/Base/true/true held=true","failed removal could not be retried");
        }
    }

    @Test void anImportedConfigurationReceivesItsNativeInheritedFactory() throws Exception {
        String bootstrap = APP.replace("if (Boolean.getBoolean(\"reverse\")) ctx.register(Second.class, First.class);",
                "if (Boolean.getBoolean(\"reverse\")) ctx.register(Root.class);")
                .replace("else ctx.register(First.class, Second.class);", "else ctx.register(Root.class);");
        String root = PREFIX + "@Import(First.class) " + CONFIG + "public class Root {}";
        String expected = "STATE app.First/Base/true/true - held=true";
        try (var plain = app("import-control",false,base(FACTORY),config(""),bootstrap)
                .with("Root",root).withoutAgent().start()) {
            plain.awaitOrFail(expected,"native imported configuration name");
        }
        try (var live = app("import-live",false,base(""),config(""),bootstrap).with("Root",root).start()) {
            live.awaitOrFail("STATE - - held=true","context ready");
            live.awaitOrFail("] Watching ","watcher ready");
            live.rewrite("Base",base(FACTORY));
            if (refusedOnSpringSeven(live, false)) return;
            live.awaitOrFail(expected,"native imported owner not preserved");
        }
    }

    @Test void anUnannotatedOverrideExecutesForTheInheritedDefinition() throws Exception {
        String override = "public Product inherited(){return new Product(this,\"child\");}";
        String expected = SPRING_SEVEN ? "STATE first/First/true/truechild - held=true"
                : "STATE first/Base/true/truechild - held=true";
        try (var plain = app("plain-override",false,base(FACTORY),config(override)).withoutAgent().start()) {
            plain.awaitOrFail(expected,"native unannotated override");
        }
        try (var live = app("live-override",false,base(""),config("")).start()) {
            live.awaitOrFail("STATE - - held=true","context ready");
            live.awaitOrFail("] Watching ","watcher ready");
            var sources = new LinkedHashMap<String,String>();
            sources.put("First",config(override));
            sources.put("Base",base(FACTORY));
            live.rewriteAll(sources);
            if (refusedOnSpringSeven(live, false)) return;
            live.awaitOrFail(expected,"unannotated override");
        }
    }

    @Test void aSharedDefaultFactoryRefusesBeforeEitherOwnerPublishes() throws Exception {
        String first = config("").replace("extends Base", "extends Base implements Factories");
        String second = SECOND.replace("extends Base","extends Base implements Factories");
        String iface = PREFIX + "public interface Factories { %s }";
        String saved = iface.formatted("@Bean default Product inherited(){return new Product(this);}");
        String probe = APP.replace("Thread.sleep(40);", """
                if (java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(System.getProperty("probe"))))
                    System.out.println("CHECK-DEFAULT " + ctx.containsBeanDefinition("inherited") + "/" + (held==ctx.getBean(First.class)));
                Thread.sleep(40);
                """);
        String jvm = System.getProperty("reclazz.record.java", System.getenv("RECLAZZ_ENHANCED_JAVA"));
        org.junit.jupiter.api.Assumptions.assumeTrue(jvm != null && !jvm.isBlank(),"configured enhanced child JVM");
        for (boolean control : new boolean[]{true,false}) {
            String name = control ? "native-shared-default" : "live-shared-default";
            var builder = WatchedApp.in(tmp.resolve(name)).classpath(WatchedApp.springClasspath())
                    .agentArgs("startupDelaySec=1,debounceMs=200").jvmArgs("-Dprobe=" + tmp.resolve(name).resolve("probe"))
                    .with("Product","package app; public record Product(Object owner){public String marker(){return \"\";}}")
                    .with("Base",base("")).with("First",first).with("Second",second)
                    .with("Factories",control ? saved : iface.formatted("")).with("App",probe);
            if (control) builder.withoutAgent();
            else builder.javaExecutable(com.onurkat.reclazz.e2e.harness.JavaLauncher.resolve(Path.of(jvm)))
                    .jvmArgs("-XX:+AllowEnhancedClassRedefinition");
            try (var application = builder.start()) {
                if (control) application.awaitOrFail("STATE second/Factories/true/true - held=true","native shared default owner");
                else {
                    application.awaitOrFail("STATE - - held=true","baseline");
                    application.awaitOrFail("] Watching ","watcher ready");
                    application.rewrite("Factories",saved);
                    application.awaitOrFail(SPRING_SEVEN ? HIERARCHY_REFUSAL : "multiple inherited factories claim inherited","shared default must refuse before publication");
                    java.nio.file.Files.createFile(tmp.resolve(name).resolve("probe"));
                    application.awaitOrFail("CHECK-DEFAULT false/true","registry changed during refusal");
                }
            }
        }
    }

    @Test void lazyStartupOverrideStaysUninstantiatedWhenTheBaseAddsItsFactory() throws Exception {
        String child = config("@Bean @Lazy public Product inherited(){System.out.println(\"LAZY-CREATED\");return new Product(this,\"lazy\");}");
        String probe = APP.replace("describe(ctx,\"inherited\")","\"held-lazy\"")
                .replace("ctx.getBeansOfType(Product.class).get(name)==product","ctx.getBean(name)==product")
                .replace("ctx.refresh();", "ctx.refresh(); var original = ctx.getBeanFactory().getBeanDefinition(\"inherited\");")
                .replace("Thread.sleep(40);", """
                    if (java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(System.getProperty("probe"))))
                        System.out.println("LAZY " + (original == ctx.getBeanFactory().getBeanDefinition("inherited"))
                                + "/" + ctx.getBeanFactory().containsSingleton("inherited"));
                    Thread.sleep(40);
                    """);
        try (var live = app("lazy",false,base(""),child,probe).start()) {
            live.awaitOrFail("STATE held-lazy - held=true","lazy context");
            live.awaitOrFail("] Watching ","watcher ready");
            live.rewrite("Base",base(FACTORY + LOCAL));
            if (refusedOnSpringSeven(live, true)) return;
            live.awaitOrFail("STATE held-lazy first/Base/true/true held=true","sibling factory registered");
            java.nio.file.Files.createFile(tmp.resolve("lazy/probe"));
            live.awaitOrFail("LAZY true/false","startup definition or lazy singleton changed");
        }
    }

    @Test void stockKeepsTheOriginalInterfacesOfAHeldConfiguration() throws Exception { interfaces(false,false); }
    @Test void stockRefusesANewMethodOnAnExistingInterface() throws Exception { interfaces(false,true); }
    @Test void enhancedAddsAnInterfaceToAHeldConfiguration() throws Exception { interfaces(true,false); }
    @Test void enhancedAddsADefaultMethodToAnExistingInterface() throws Exception { interfaces(true,true); }

    private static final String IFACE = PREFIX + "public interface Factories { %s }";
    private static final String DEFAULT = "@Bean default Product via(){ return Product.from(this); }";
    private static final String IFACE_CONFIG = PREFIX + CONFIG + "public class First %s {}";
    private static final String IFACE_APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.AnnotationConfigApplicationContext;
            import org.springframework.beans.factory.support.RootBeanDefinition;
            public class App {
                static String state(AnnotationConfigApplicationContext ctx, Object held) throws Exception {
                    boolean assignable = Factories.class.isInstance(held);
                    boolean present = ctx.containsBean("via");
                    String owner = "-";
                    if (present) {
                        var product = ctx.getBean(Product.class);
                        var def = (RootBeanDefinition)ctx.getBeanFactory().getMergedBeanDefinition("via");
                        var method = def.getResolvedFactoryMethod();
                        owner = method.getDeclaringClass().getSimpleName() + "/" + method.isDefault()
                            + "/" + (product == ctx.getBean("via")) + "/" + (product.owner()==held)
                            + "/" + (((Product)method.invoke(held)).owner()==held);
                    }
                    return assignable + "/" + (held==ctx.getBean(First.class)) + "/" + owner;
                }
                public static void main(String[] args) throws Exception {
                    var ctx = new AnnotationConfigApplicationContext(First.class);
                    Object held = ctx.getBean(First.class);
                    String last = null;
                    for (;;) {
                        String current;
                        try { current = state(ctx,held); } catch(Throwable e) { current = "ERROR " + e; }
                        if (!current.equals(last)) { System.out.println("CURRENT " + current); last=current; }
                        if (Files.deleteIfExists(Path.of(System.getProperty("probe")))) System.out.println("CHECK " + current);
                        Thread.sleep(30);
                    }
                }
            }
            """;
    private WatchedApp.Builder interfaceApp(Path dir, boolean implementsInterface, boolean hasMethod) {
        return WatchedApp.in(dir).classpath(WatchedApp.springClasspath()).agentArgs("startupDelaySec=1,debounceMs=200,verbose=true")
                .jvmArgs("-Dprobe=" + dir.resolve("probe"))
                .with("Product","package app; public record Product(Factories owner) { public static Product from(Factories f){return new Product(f);} }")
                .with("First",IFACE_CONFIG.formatted(implementsInterface ? "implements Factories" : ""))
                .with("Factories",IFACE.formatted(hasMethod ? DEFAULT : "")).with("App",IFACE_APP);
    }
    private void interfaces(boolean enhanced, boolean alreadyImplemented) throws Exception {
        String expected = "true/true/Factories/true/true/true/true";
        Path dir=tmp.resolve("live");
        var builder=interfaceApp(dir,alreadyImplemented,!alreadyImplemented);
        if (enhanced) {
            String jvm = System.getProperty("reclazz.record.java", System.getenv("RECLAZZ_ENHANCED_JAVA"));
            org.junit.jupiter.api.Assumptions.assumeTrue(jvm!=null && !jvm.isBlank(),"configured enhanced child JVM");
            builder.javaExecutable(com.onurkat.reclazz.e2e.harness.JavaLauncher.resolve(Path.of(jvm)))
                    .jvmArgs("-XX:+AllowEnhancedClassRedefinition");
        }
        try (var control=interfaceApp(tmp.resolve("native"),true,true).withoutAgent().start()) {
            control.awaitOrFail("CURRENT " + expected,"native default factory and receiver");
        }
        try (var live=builder.start()) {
            String original = alreadyImplemented + "/true/-";
            live.awaitOrFail("CURRENT " + original,"initial hierarchy");
            live.awaitOrFail("] Watching ","watcher did not start");
            if (alreadyImplemented) live.rewrite("Factories",IFACE.formatted(DEFAULT));
            else live.rewrite("First",IFACE_CONFIG.formatted("implements Factories"));
            if (enhanced && SPRING_SEVEN) {
                live.awaitOrFail(HIERARCHY_REFUSAL, "enhanced VM preserves the Spring registry refusal");
                java.nio.file.Files.createFile(dir.resolve("probe"));
                live.awaitOrFail("CHECK true/true/-", "same owner and no product after refusal");
            } else if (enhanced) assertTrue(live.awaits("CURRENT " + expected,20),live::tail);
            else {
                String reason=alreadyImplemented ? "stock JVM applies only" : "interfaces of a loaded class";
                assertTrue(live.awaits(reason,20),live::tail);
                java.nio.file.Files.createFile(dir.resolve("probe"));
                live.awaitOrFail("CHECK " + original,"held instance and factory after refusal");
            }
        }
    }

    @Test void aSharedBaseOwnedByAProxiedConfigurationRefusesBeforePublishing() throws Exception {
        String full = config("").replace("@Configuration(proxyBeanMethods=false)", "@Configuration");
        try (var live=app("live",false,base(""),full).start()) {
            live.awaitOrFail("STATE - - held=true","missing baseline");
            live.awaitOrFail("] Watching ","watcher did not start");
            live.rewrite("Base",base(FACTORY));
            if (refusedOnSpringSeven(live, false)) return;
            assertTrue(live.awaits("native configuration owner is not a live raw singleton",20),live::tail);
            assertEquals("STATE - - held=true",live.latest("STATE "),live::tail);
        }
    }

    @Test void libraryAncestorRequiresRestart() throws Exception {
        String libraryBase = base("").replace("class Base {","class Base extends org.springframework.core.AttributeAccessorSupport {");
        try (var live=app("live",false,libraryBase,config("")).start()) {
            live.awaitOrFail("STATE - - held=true","missing baseline");
            live.awaitOrFail("] Watching ","watcher did not start");
            live.rewrite("First",config(LOCAL));
            if (refusedOnSpringSeven(live, false)) return;
            assertTrue(live.awaits("library or unavailable configuration ancestor",20),live::tail);
            assertEquals("STATE - - held=true",live.latest("STATE "),live::tail);
        }
    }

    @Test void genericSuperclassFactoryRequiresNativeTypeResolution() throws Exception {
        String original = base("").replace("class Base","class Base<T>");
        String first = config("").replace("extends Base","extends Base<Product>");
        String saved = base("@Bean public T inherited(){ return null; }").replace("class Base","class Base<T>");
        try (var live=app("live",false,original,first).start()) {
            live.awaitOrFail("STATE - - held=true","missing baseline");
            live.awaitOrFail("] Watching ","watcher did not start");
            live.rewrite("Base",saved);
            if (refusedOnSpringSeven(live, false)) return;
            assertTrue(live.awaits("unresolved factory type variable",20),live::tail);
            assertEquals("STATE - - held=true",live.latest("STATE "),live::tail);
        }
    }

    @Test void newAncestorFieldRefusesBothFactories() throws Exception {
        try (var live=app("live",false,base(""),config("")).start()) {
            live.awaitOrFail("STATE - - held=true","missing baseline");
            live.awaitOrFail("] Watching ","watcher did not start");
            live.rewriteAll(java.util.Map.of("First",config(LOCAL),"Base",base("public int generation;" + FACTORY)));
            if (refusedOnSpringSeven(live, false)) return;
            assertTrue(live.awaits("configuration creation state changed since startup",20),live::tail);
            assertEquals("STATE - - held=true",live.latest("STATE "),live::tail);
        }
    }

    @Test void changedAncestorConstructorRefusesTheEntireBatch() throws Exception {
        String initial="public Base(){System.setProperty(\"hierarchy.fixture\",\"old\");}";
        try (var live=app("live",false,base(initial),config("")).start()) {
            live.awaitOrFail("STATE - - held=true","missing baseline");
            live.awaitOrFail("] Watching ","watcher did not start");
            live.rewriteAll(java.util.Map.of("Base",base(initial.replace("old","new") + FACTORY),"First",config(LOCAL)));
            if (refusedOnSpringSeven(live, false)) return;
            assertTrue(live.awaits("configuration creation state changed since startup",20),live::tail);
            assertEquals("STATE - - held=true",live.latest("STATE "),live::tail);
        }
    }
}
