/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class AddedBeanRichArgumentsReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans={false,true})
    void richArgumentsFollowSavesAndKeepProviderLazy(boolean child) throws Exception {
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dtest.dir=" + tmp).with("App", APP).with("Config", config(0))
                .with("Wire", WIRE).with("Product", PRODUCT).with("Missing", "package app; public class Missing {}")
                .with("Late", "package app; public class Late { public static int made; public final int id=++made; }").start()) {
            app.awaitOrFail("READY", "Spring did not start"); app.awaitOrFail("] Watching ", "watcher did not start");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child loader missing");
            reload(app, 1); probe(app, 1, "1:[red, blue]:[blue, red]:false:blue:7:0:9:2.5:blue");
            Files.createFile(tmp.resolve("provider")); app.awaitOrFail("PROVIDER=1:2:true", "provider did not resolve fresh targets lazily");
            reload(app, 2); probe(app, 2, "2:[red, blue]:[blue, red]:true:red:7:2:9:2.5:blue");
            reload(app, 3); probe(app, 3, "missing");
            reload(app, 4); probe(app, 4, "4:[red, blue]:[blue, red]:true:blue:7:2:9:2.5:blue");
            reload(app, 5); probe(app, 5, "missing");
            reload(app, 6); probe(app, 6, "6:[red, blue]:[blue, red]:true:blue:7:2:9:2.5:blue");
            Files.createFile(tmp.resolve("close")); app.awaitOrFail("CLOSED=4", "each created product must close once");
        }
    }
    private void reload(WatchedApp app, int stage) throws Exception {
        app.rewrite("Config", config(stage));
        long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while (System.nanoTime()<deadline && reloads(app)<stage) Thread.sleep(25);
        assertEquals(stage,reloads(app),app.tail());
    }
    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s->s.contains("Reloaded app.Config")||s.contains("Structural reload: app.Config")).count();
    }
    private void probe(WatchedApp app,int stage,String expected) throws Exception {
        String prefix="RICH"+stage+"=";
        Files.createFile(tmp.resolve("probe"+stage)); app.awaitOrFail(prefix,"probe missing");
        String line=app.latest(prefix);
        assertEquals(prefix+expected,line.substring(line.indexOf(prefix)),app.tail());
        System.out.println("[rich-bean] "+line);
    }
    private static String config(int stage) {
        String methods=stage==0 ? "" : """
                @org.springframework.context.annotation.Bean
                public Wire blue() { return new Wire("blue", 20); }
                @org.springframework.context.annotation.Bean
                public Wire red() { return new Wire("red", 10); }
                @org.springframework.context.annotation.Bean
                private static String numbers(@org.springframework.beans.factory.annotation.Value("9") long wide,
                        @org.springframework.beans.factory.annotation.Value("2.5") double decimal,
                        @org.springframework.beans.factory.annotation.Qualifier("blue") Wire wire) {
                    return wide + ":" + decimal + ":" + wire.name;
                }
                """;
        if(stage>=2) methods+="@org.springframework.context.annotation.Bean public Missing maybe() { return new Missing(); }";
        String client=stage==0||stage==5 ? "" : """
                @org.springframework.context.annotation.Bean
                private Product client(java.util.List<Wire> wires, java.util.Map<String,Wire> named,
                        java.util.Optional<Missing> optional, org.springframework.beans.factory.ObjectProvider<Late> late,
                        @org.springframework.beans.factory.annotation.Qualifier("%s") Wire selected,
                        @org.springframework.beans.factory.annotation.Value("%s") int limit) {
                    return new Product(%d, wires, named, optional, late, selected, limit);
                }
                """.formatted(stage==2 ? "red" : "blue",stage==3 ? "invalid" : "${limit:7}",stage);
        return "package app; @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) public class Config {"
                + "public int version() { return "+stage+"; }"+client+methods+"}";
    }
    private static final String WIRE="""
            package app;
            public class Wire implements org.springframework.core.Ordered {
                public final String name; private final int order;
                public Wire(String name,int order) { this.name=name; this.order=order; }
                public int getOrder() { return order; }
                public String toString() { return name; }
            }
            """;
    private static final String PRODUCT="""
            package app;
            public class Product implements AutoCloseable {
                public static int closed;
                public final org.springframework.beans.factory.ObjectProvider<Late> late;
                private final String description;
                public Product(int version,java.util.List<Wire> wires,java.util.Map<String,Wire> named,
                        java.util.Optional<Missing> optional,org.springframework.beans.factory.ObjectProvider<Late> late,
                        Wire selected,int limit) {
                    this.late=late; description=version+":"+wires+":"+new java.util.TreeSet<>(named.keySet())
                            +":"+optional.isPresent()+":"+selected.name+":"+limit;
                }
                public String toString() { return description+":"+Late.made; }
                public void close() { closed++; }
            }
            """;
    private static final String APP="""
            package app;
            public class App {
                public static void main(String[] args) throws Exception {
                    var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext();
                    context.register(Config.class);
                    context.registerBean("late",Late.class,()->new Late(),d->d.setLazyInit(true));
                    context.refresh(); System.out.println("READY");
                    java.nio.file.Path dir=java.nio.file.Path.of(System.getProperty("test.dir"));
                    for(int stage=1;stage<=6;stage++) {
                        while(!java.nio.file.Files.exists(dir.resolve("probe"+stage))) Thread.sleep(25);
                        String value=context.containsBean("client") ? context.getBean("client")+":"+context.getBean("numbers") : "missing";
                        System.out.println("RICH"+stage+"="+value);
                        if(stage==1) {
                            while(!java.nio.file.Files.exists(dir.resolve("provider"))) Thread.sleep(25);
                            Product held=context.getBean("client",Product.class);
                            int first=held.late.getObject().id;
                            context.getDefaultListableBeanFactory().destroySingleton("late");
                            int second=held.late.getObject().id;
                            System.out.println("PROVIDER="+first+":"+second+":"+(held==context.getBean("client")));
                        }
                    }
                    while(!java.nio.file.Files.exists(dir.resolve("close"))) Thread.sleep(25);
                    context.close(); System.out.println("CLOSED="+Product.closed);
                }
            }
            """;
}
