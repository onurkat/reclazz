/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

class SpringConstructorExtensionReloadTest {
    @TempDir Path tmp;

    private static final String OWNER = """
            package app;
            @org.springframework.stereotype.Service
            public class Owner {
                public Owner() {}
                public String read(){return "old";}
            }
            """;
    private static final String CALLER = """
            package app;
            @org.springframework.stereotype.Service
            public class Caller {
                final Owner owner;
                public Caller(Owner owner){this.owner=owner;}
                public String read(){return "old:"+owner.read();}
            }
            """;
    private static final String APP = """
            package app;
            import org.springframework.context.annotation.AnnotationConfigApplicationContext;
            import java.nio.file.*;
            public class App {
                public static void main(String[] args) throws Exception {
                    var context = new AnnotationConfigApplicationContext("app");
                    var original = context.getBean(Owner.class);
                    var held = context.getBean(Caller.class);
                    System.out.println("ORIGINAL="+held.read());
                    while(!Files.exists(Path.of(System.getProperty("probe")))) Thread.sleep(20);
                    while(true){
                        System.out.println("RESULT="+held.read()+":"+(original==context.getBean(Owner.class))+":"
                                +context.containsBean("second")+":"+context.containsBean("third"));
                        Thread.sleep(200);
                    }
                }
            }
            """;

    @ParameterizedTest @ValueSource(strings={"unique","qualifier","ambiguous","logic","creation","ownerFailure"})
    void savePublishesCompleteDependenciesOrPreservesOriginalCaller(String scenario) throws Exception {
        Path probe = tmp.resolve("check");
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath()).jvmArgs("-Dprobe="+probe)
                .with("Parent","package app; public class Parent { public Parent(){if(java.nio.file.Files.exists("
                        +"java.nio.file.Path.of(System.getProperty(\"probe\")+\".fail\")))throw new IllegalStateException(\"owner creation failed\");}}")
                .with("App",APP).with("Owner",OWNER.replace("class Owner {","class Owner extends Parent {")).with("Caller",CALLER)
                .with("Choice","package app; public interface Choice { String read(); }")
                .with("First","package app; @org.springframework.stereotype.Service @org.springframework.context.annotation.Primary "
                        +"public class First implements Choice { public String read(){return \"first\";} }").start()) {
            app.awaitOrFail("ORIGINAL=old:old","baseline injection missing");
            app.awaitWatcherReady();
            boolean qualified = scenario.equals("qualifier");
            boolean unique = scenario.equals("unique");
            var changed = new LinkedHashMap<String,String>();
            String type = unique || scenario.equals("creation") || scenario.equals("ownerFailure") ? "Second" : "Choice";
            String annotation = qualified ? "@org.springframework.beans.factory.annotation.Qualifier(\"second\") " : "";
            changed.put("Owner", OWNER.replace("class Owner {","class Owner extends Parent {").replace("public Owner() {}", "final "+type+" component; public Owner("
                    +annotation+type+" value){"+(scenario.equals("logic") ? "value.read();" : "")+"this.component=value;}")
                    .replace("return \"old\"","return component.read()"));
            changed.put("Caller",CALLER.replace("\"old:\"","\"new:\""));
            changed.put("Second", "package app; @org.springframework.stereotype.Service public class Second implements Choice {"
                    +(scenario.equals("creation") ? "public Second(){throw new IllegalStateException(\"creation refused\");}" : "")
                    +"public String read(){return \"second\";}}");
            if (scenario.equals("creation")) {
                changed.put("Third","package app; @org.springframework.stereotype.Service public class Third {}");
                changed.put("Owner",changed.get("Owner").replace("public Owner(Second value){",
                        "final Third third; public Owner(Third other, Second value){this.third=other;"));
            }
            if (scenario.equals("ownerFailure")) Files.createFile(Path.of(probe+".fail"));
            app.rewriteAll(changed);
            boolean positive = qualified || unique;
            if (!positive) app.awaitOrFail("constructor dependency change on app.Owner requires restart", "missing named refusal");
            else app.awaitOrFail("Structural reload: app.Owner", "owner class was not reloaded");
            Files.createFile(probe);
            app.awaitOrFail(positive ? "RESULT=new:second:false:true:false" : "RESULT=old:old:true:false:false",
                    "consumer body, owner identity or bean registry diverged");
        }
    }
}
