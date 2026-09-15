/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NewComponentScopeReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void newLazyAndPrototypeComponentsKeepTheirNativeLifetime(boolean child) throws Exception {
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.jvmArgs("-Dprobe.dir=" + tmp).with("App", APP).start()) {
            app.awaitOrFail("READY", "context did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child loader missing");
            app.rewrite("LazyComponent", component("Lazy", "@Lazy"));
            app.awaitOrFail("New bean registered: 'lazyComponent'", "lazy definition not registered");
            app.rewrite("PrototypeComponent", component("Prototype", "@Scope(scopeName = \"prototype\")"));
            app.awaitOrFail("New bean registered: 'prototypeComponent'", "prototype definition not registered");
            Files.createFile(tmp.resolve("probe"));
            app.awaitOrFail("CLOSED=", "probe did not finish");
            assertEquals("BEFORE=0:0", app.latest("BEFORE="), app.tail());
            assertEquals("LOOKUPS=true:true:1:2", app.latest("LOOKUPS="), app.tail());
            assertEquals("CLOSED=1:0", app.latest("CLOSED="), app.tail());
            System.out.println("[new-component-scope] child=" + child + " BEFORE=0:0 LOOKUPS=true:true:1:2 CLOSED=1:0");
        }
    }

    private static String component(String kind, String annotations) {
        String counter = kind.equals("Lazy") ? "lazy" : "prototype";
        return """
                package app;
                import org.springframework.stereotype.Component;
                import org.springframework.context.annotation.*;
                @Component %s
                public class %sComponent implements org.springframework.beans.factory.DisposableBean {
                    public %sComponent() { App.%sMade++; }
                    public void destroy() { App.%sClosed++; }
                }
                """.formatted(annotations, kind, kind, counter, counter);
    }

    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.AnnotationConfigApplicationContext;
            public class App {
                public static int lazyMade, prototypeMade, lazyClosed, prototypeClosed;
                public static void main(String[] args) throws Exception {
                    try (var context = new AnnotationConfigApplicationContext()) {
                        context.refresh();
                        System.out.println("READY");
                        while (!Files.exists(Path.of(System.getProperty("probe.dir"), "probe"))) Thread.sleep(20);
                        System.out.println("BEFORE=" + lazyMade + ":" + prototypeMade);
                        Object lazy = context.getBean("lazyComponent");
                        Object prototype = context.getBean("prototypeComponent");
                        System.out.println("LOOKUPS=" + (lazy == context.getBean("lazyComponent")) + ":"
                            + (prototype != context.getBean("prototypeComponent")) + ":" + lazyMade + ":" + prototypeMade);
                    }
                    System.out.println("CLOSED=" + lazyClosed + ":" + prototypeClosed);
                }
            }
            """;
}
