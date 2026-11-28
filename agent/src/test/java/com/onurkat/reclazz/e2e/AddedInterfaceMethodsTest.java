/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AddedInterfaceMethodsTest {
    @TempDir Path dir;

    private static String owner(String body, String additions) {
        return "package app; public interface Api<T> { int VALUE = 3; T value(); "
                + "default String text() { " + body + " } " + additions + " }";
    }

    private static String withHelpers(String version) {
        return owner("return helper(value());",
                "private String helper(T input) { return label() + input; } "
                + "private static String label() { return \"" + version + "\"; } "
                + "static String call() { return label() + VALUE; }");
    }

    private static final String ORIGINAL = owner("return \"original\";", "");
    private static final String IMPL = "package app; public class Impl implements Api<String> { "
            + "public String value() { return \"X\"; } }";
    private static final String CALLER = """
            package app;
            public class Caller {
                public static String run() {
                    java.util.function.Supplier<String> ref = Api::call;
                    return Api.call() + "/" + ref.get();
                }
            }
            """;
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import java.lang.invoke.*;
            import java.lang.reflect.*;
            public class App {
                public static void main(String[] args) throws Throwable {
                    var instance = new Impl();
                    System.out.println("READY=" + instance.text());
                    MethodHandle held = null;
                    for (int stage=0;stage<4;stage++) {
                        while (!Files.exists(Path.of(System.getProperty("probe.dir"),"go"+stage))) Thread.sleep(10);
                        if (stage >= 2) {
                            try { String value=(String)held.invokeExact(); throw new AssertionError(value); }
                            catch (NoSuchMethodError expected) { }
                            try { Api.class.getDeclaredMethod("call"); throw new AssertionError(); }
                            catch (NoSuchMethodException expected) { }
                            try { Api.class.getDeclaredMethod("helper",Object.class); throw new AssertionError(); }
                            catch (NoSuchMethodException expected) { }
                            if (stage == 2) {
                                MethodHandle replacement = MethodHandles.publicLookup().findStatic(Api.class,
                                        "call", MethodType.methodType(int.class, int.class));
                                System.out.println("SHIFT=" + (int) replacement.invokeExact(3));
                                continue;
                            }
                            try { Api.class.getMethod("call", int.class); throw new AssertionError(); }
                            catch (NoSuchMethodException expected) { }
                            System.out.println("GONE=" + instance.text());
                            return;
                        }
                        var type=MethodType.methodType(String.class);
                        if (held==null) held=MethodHandles.lookup().findStatic(Api.class,"call",type);
                        Method visible=Api.class.getMethod("call");
                        Method hidden=Api.class.getDeclaredMethod("helper",Object.class);
                        try { MethodHandles.lookup().findStatic(Api.class,"label",type); throw new AssertionError(); }
                        catch (IllegalAccessException expected) { }
                        String caller=(String)Class.forName("app.Caller").getMethod("run").invoke(null);
                        System.out.println("RESULT"+stage+"="+instance.text()+"/"+visible.invoke(null)+"/"
                                +(String)held.invokeExact()+"/"+caller+"/"+Modifier.isPrivate(hidden.getModifiers()));
                    }
                }
            }
            """;

    private void signal(Path root,int stage) throws Exception {
        Files.createFile(root.resolve("go"+stage));
    }

    private void save(WatchedApp app,String source) throws Exception {
        long before=reloads(app);
        app.rewrite("Api",source);
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (reloads(app)==before && System.nanoTime()<until) Thread.sleep(25);
        assertTrue(reloads(app)>before,app::tail);
    }

    private long reloads(WatchedApp app) {
        return app.output().stream().filter(line->line.contains("Reloaded app.Api")).count();
    }

    @Test
    void helpersStaticCallsAndHeldHandlesFollowSaves() throws Exception {
        Path nativeDir=dir.resolve("native");
        String nativeResult;
        try (var app=WatchedApp.in(nativeDir).withoutAgent().jvmArgs("-Dprobe.dir="+nativeDir)
                .with("Api",withHelpers("a")).with("Impl",IMPL).with("Caller",CALLER).with("App",APP).start()) {
            signal(nativeDir,0);
            app.awaitOrFail("RESULT0=","fresh JVM invokes original helpers and static references");
            nativeResult=app.latest("RESULT0=");
            assertEquals("RESULT0=aX/a3/a3/a3/a3/true",nativeResult);
        }
        Path live=dir.resolve("live");
        try (var app=WatchedApp.in(live).jvmArgs("-Dprobe.dir="+live)
                .with("Api",ORIGINAL).with("Impl",IMPL).with("App",APP).start()) {
            app.awaitOrFail("READY=original","physical default method");
            app.awaitOrFail("] Watching 1 director","watcher ready");
            save(app,withHelpers("a"));
            app.rewrite("Caller",CALLER);
            signal(live,0);
            app.awaitOrFail("RESULT0=","added helpers, reflection, direct calls and method references");
            assertEquals(nativeResult,app.latest("RESULT0="));
            save(app,withHelpers("b"));
            app.rewrite("Api",withHelpers("rejected").replace("T value();", "T value(int n);")
                    .replace("helper(value())","helper(value(1))"));
            app.awaitOrFail("an original interface method was removed or changed","reject original member retyping");
            signal(live,1);
            app.awaitOrFail("RESULT1=","held handle and previously linked caller see revised body");
            assertEquals("RESULT1=bX/b3/b3/b3/b3/true",app.latest("RESULT1="));
            save(app,owner("return \"shift\";", "static int call(int value) { return value + VALUE; }"));
            signal(live,2);
            app.awaitOrFail("SHIFT=6","retyped static method is accessible through a public lookup");
            save(app,ORIGINAL);
            signal(live,3);
            app.awaitOrFail("GONE=original","removed logical methods disappear and the held handle fails");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusesUnsupportedFunctionalInterfaceBeforePublishing(boolean serializable) throws Exception {
        String step = serializable
                ? "public interface Step extends java.io.Serializable { int f(); }"
                : "interface Step { int f(); }";
        String reason = serializable ? "serializable functional interface" : "non-public functional interface";
        String initial = "package app; public interface Api { default int value() { return 1; } }";
        String edit = "package app; public interface Api { default int value() { Step s = Api::added; return s.f(); }"
                + " private static int added() { return 7; } }";
        String main = """
                package app;
                public class App implements Api {
                    public static void main(String[] args) throws Exception {
                        App receiver = new App();
                        System.out.println("BEFORE=" + receiver.value());
                        while (!java.nio.file.Files.exists(java.nio.file.Path.of(System.getProperty("probe.dir"), "go0")))
                            Thread.sleep(10);
                        System.out.println("AFTER=" + receiver.value());
                    }
                }
                """;
        try (var app = WatchedApp.in(dir).jvmArgs("-Dprobe.dir=" + dir).with("Api", initial)
                .with("Step", "package app; " + step).with("App", main).start()) {
            app.awaitOrFail("BEFORE=1", "original method");
            app.awaitWatcherReady();
            app.rewrite("Api", edit);
            app.awaitOrFail(reason, "named refusal before definition changes");
            signal(dir, 0);
            app.awaitOrFail("AFTER=1", "original receiver still works after refusal");
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void coldStaticInitializesOnFirstInvocation(boolean fail) throws Exception {
        String api="package app; public interface Api { Object STAMP=Counter.init(); %s }";
        String added=api.formatted("static int number() { return 7; }");
        String counter="package app; public class Counter { static int count; static Object init() { "
                + "count++; if(Boolean.getBoolean(\"fail\")) throw new IllegalStateException(); return new Object(); } }";
        String probe="""
                package app;
                import java.nio.file.*;
                import java.lang.invoke.*;
                public class App {
                    public static void main(String[] args) throws Throwable {
                        Class<?> api=Class.forName("app.Api",false,App.class.getClassLoader());
                        System.out.println("COLD="+Counter.count);
                        while (!Files.exists(Path.of(System.getProperty("probe.dir"),"go0"))) Thread.sleep(10);
                        var reflected=api.getMethod("number");
                        var h=MethodHandles.lookup().findStatic(api,"number",MethodType.methodType(int.class));
                        String result="BEFORE="+Counter.count;
                        for (int i=0;i<2;i++) {
                            try {result+="/"+(int)h.invokeExact();}
                            catch (Error failure) {result+="/"+failure.getClass().getSimpleName();}
                        }
                        System.out.println(result+"/AFTER="+Counter.count);
                    }
                }
                """;
        String expected=fail?"BEFORE=0/ExceptionInInitializerError/NoClassDefFoundError/AFTER=1":"BEFORE=0/7/7/AFTER=1";
        for(boolean nativeRun:new boolean[]{true,false}) {
            Path root=dir.resolve(nativeRun?"native":"live");
            var builder=WatchedApp.in(root).jvmArgs("-Dprobe.dir="+root,"-Dfail="+fail)
                    .with("Api",nativeRun?added:api.formatted("")).with("Counter",counter).with("App",probe);
            if(nativeRun) builder.withoutAgent();
            try(var app=builder.start()) {
                app.awaitOrFail("COLD=0","loading an interface does not initialize it");
                if(!nativeRun) {
                    app.awaitOrFail("] Watching 1 director","watcher ready");
                    save(app,added);
                }
                signal(root,0);
                app.awaitOrFail("BEFORE=","static lookup and first two invocations");
                assertEquals(expected,app.latest("BEFORE="));
            }
        }
    }

    @Test void addingAHelperToAPermittedSubinterfaceKeepsItsSealedParent() throws Exception {
        String api = "package app; public non-sealed interface Api<T> extends Parent<T> { "
                + "default String result() { return %s; } %s }";
        String initial = api.formatted("\"before\"", "");
        String next = api.formatted("helper(null)", "private String helper(T value) { return \"after\"; }");
        String main = """
                package app;
                public class App implements Api<String> {
                    public static void main(String[] args) throws Exception {
                        App receiver = new App();
                        System.out.println("ORIGINAL=" + receiver.result());
                        while (!java.nio.file.Files.exists(java.nio.file.Path.of(System.getProperty("probe.dir"), "go0")))
                            Thread.sleep(10);
                        var method = Api.class.getDeclaredMethod("helper", Object.class);
                        System.out.println("APPLIED=" + receiver.result() + "/" + method.getGenericParameterTypes()[0].getTypeName()
                                + "/" + Parent.class.getPermittedSubclasses().length + "/" + Parent.class.getPermittedSubclasses()[0].getName());
                    }
                }
                """;
        try (var app = WatchedApp.in(dir).jvmArgs("-Dprobe.dir=" + dir)
                .with("Parent", "package app; public sealed interface Parent<T> permits Api {}")
                .with("Api", initial).with("App", main).start()) {
            app.awaitOrFail("ORIGINAL=before", "stock sealed hierarchy");
            app.awaitWatcherReady();
            save(app, next);
            signal(dir, 0);
            app.awaitOrFail("APPLIED=after/T/1/app.Api", "method works and the real permits list is intact");
        }
    }

    @Test void togglingVarargsKeepsAnExistingPublicHandleCallable() throws Exception {
        String api = "package app; public interface Api { default int before() { return 1; } %s }";
        String main = """
                package app;
                public class App implements Api {
                    static void await(int n) throws Exception {
                        while (!java.nio.file.Files.exists(java.nio.file.Path.of(System.getProperty("probe.dir"), "go" + n)))
                            Thread.sleep(10);
                    }
                    public static void main(String[] args) throws Throwable {
                        System.out.println("ORIGINAL=" + new App().before());
                        await(0);
                        var type = java.lang.invoke.MethodType.methodType(int.class, String[].class);
                        var old = java.lang.invoke.MethodHandles.publicLookup().findStatic(Api.class, "count", type).asFixedArity();
                        System.out.println("FIRST=" + (int) old.invokeExact(new String[1]));
                        await(1);
                        var current = Api.class.getDeclaredMethod("count", String[].class);
                        System.out.println("CHANGED=" + (int) old.invokeExact(new String[1]) + "/" + current.isVarArgs());
                    }
                }
                """;
        try (var app = WatchedApp.in(dir).jvmArgs("-Dprobe.dir=" + dir)
                .with("Api", api.formatted("")).with("App", main).start()) {
            app.awaitOrFail("ORIGINAL=1", "initial interface");
            app.awaitWatcherReady();
            save(app, api.formatted("static int count(String[] args) { return args.length; }"));
            signal(dir, 0);
            app.awaitOrFail("FIRST=1", "first added static method");
            save(app, api.formatted("static int count(String... args) { return 10 + args.length; }"));
            signal(dir, 1);
            app.awaitOrFail("CHANGED=11/true", "existing handle and new reflective modifiers agree");
        }
    }

    @Test void wideningAnAddedStaticMethodUpdatesItsPublicVisibility() throws Exception {
        String api = "package app; public interface Api { default int value() { return %s; } %s }";
        String main = """
                package app;
                public class App implements Api {
                    static void await(int n) throws Exception {
                        while (!java.nio.file.Files.exists(java.nio.file.Path.of(System.getProperty("probe.dir"), "go" + n)))
                            Thread.sleep(10);
                    }
                    public static void main(String[] args) throws Throwable {
                        App app = new App();
                        System.out.println("ORIGINAL=" + app.value());
                        await(0);
                        System.out.println("PRIVATE=" + app.value() + "/" + java.lang.reflect.Modifier.isPrivate(
                                Api.class.getDeclaredMethod("added").getModifiers()));
                        await(1);
                        var added = java.lang.invoke.MethodHandles.publicLookup().findStatic(Api.class, "added",
                                java.lang.invoke.MethodType.methodType(int.class));
                        System.out.println("PUBLIC=" + app.value() + "/" + (int) added.invokeExact());
                    }
                }
                """;
        try (var app = WatchedApp.in(dir).jvmArgs("-Dprobe.dir=" + dir)
                .with("Api", api.formatted("1", "")).with("App", main).start()) {
            app.awaitOrFail("ORIGINAL=1", "initial receiver");
            app.awaitWatcherReady();
            save(app, api.formatted("added()", "private static int added() { return 2; }"));
            signal(dir, 0);
            app.awaitOrFail("PRIVATE=2/true", "method remains private on the first revision");
            save(app, api.formatted("added()", "public static int added() { return 3; }"));
            signal(dir, 1);
            app.awaitOrFail("PUBLIC=3/3", "public lookup and original receiver follow the widened method");
        }
    }
}
