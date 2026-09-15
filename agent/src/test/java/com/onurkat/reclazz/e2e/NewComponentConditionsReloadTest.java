/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NewComponentConditionsReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void newComponentsAreFilteredBeforeInitializationOnTheApplicationLoader(boolean child, boolean autoCompile) throws Exception {
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        if (autoCompile) builder.mavenLayout().agentArgs("startupDelaySec=1,debounceMs=100,autoCompile=true");
        try (var app = builder.jvmArgs("-Dprobe.dir=" + tmp)
                .with("App", APP).with("FeatureCondition", CONDITION).start()) {
            app.awaitOrFail("READY", "context did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child loader missing");
            save(app, autoCompile, "OffProfile", component("OffProfile", "offProfile", "@Profile(\"test\")"));
            app.awaitOrFail("New component app.OffProfile conditions did not match", "inactive profile was not filtered");
            save(app, autoCompile, "OnProfile", component("OnProfile", "onProfile", "@Profile(\"prod & !test\")"));
            app.awaitOrFail("New bean registered: 'onProfile'", "active profile was not registered");
            save(app, autoCompile, "OffCondition", component("OffCondition", "disabled", "@Conditional(FeatureCondition.class)"));
            app.awaitOrFail("New component app.OffCondition conditions did not match", "false condition was not filtered");
            save(app, autoCompile, "OnCondition", component("OnCondition", "enabled", "@Conditional(FeatureCondition.class)"));
            app.awaitOrFail("New bean registered: 'enabled'", "matching condition was not registered");
            save(app, autoCompile, "ThrowCondition", component("ThrowCondition", "throws", "@Conditional(FeatureCondition.class)"));
            app.awaitOrFail("New component app.ThrowCondition conditions could not be evaluated", "condition error was not reported");
            Files.createFile(tmp.resolve("probe"));
            app.awaitOrFail("RESULT=", "component probe did not finish");
            assertEquals("RESULT=true:true:false:false:false created=2 initialized=2 conditions=3",
                    app.latest("RESULT="), app.tail());
            assertFalse(app.output().stream().anyMatch(line -> line.contains("NoSuchElementException")), app.tail());
            System.out.println("[new-component-conditions] child=" + child + " autoCompile=" + autoCompile + " " + app.latest("RESULT="));
        }
    }

    private void save(WatchedApp app, boolean autoCompile, String type, String source) throws Exception {
        if (autoCompile) Files.writeString(tmp.resolve("src/main/java/app/" + type + ".java"), source);
        else app.rewrite(type, source);
    }

    private static String component(String type, String name, String annotation) {
        return """
                package app;
                import org.springframework.stereotype.Component;
                import org.springframework.context.annotation.*;
                @Component("%s") %s
                public class %s {
                    static { App.initialized++; }
                    public %s() { App.created++; }
                }
                """.formatted(name, annotation, type, type);
    }

    private static final String CONDITION = """
            package app;
            import org.springframework.context.annotation.*;
            import org.springframework.core.type.AnnotatedTypeMetadata;
            public class FeatureCondition implements ConfigurationCondition {
                public ConfigurationPhase getConfigurationPhase() { return ConfigurationPhase.REGISTER_BEAN; }
                public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
                    App.conditions++;
                    if (c.getClassLoader() != App.class.getClassLoader()
                        || c.getBeanFactory() != App.context.getBeanFactory()
                        || !c.getRegistry().containsBeanDefinition("marker")
                        || !c.getResourceLoader().getResource("fixture:feature").exists()) {
                        throw new IllegalStateException("condition received the wrong application context");
                    }
                    String name = (String) m.getAnnotationAttributes("org.springframework.stereotype.Component").get("value");
                    if (name.equals("throws")) throw new IllegalStateException("fixture condition error");
                    return Boolean.parseBoolean(c.getEnvironment().getProperty("feature." + name));
                }
            }
            """;

    private static final String APP = """
            package app;
            import java.nio.file.*;
            import java.util.Map;
            import org.springframework.context.annotation.AnnotationConfigApplicationContext;
            import org.springframework.core.env.MapPropertySource;
            import org.springframework.core.io.ByteArrayResource;
            public class App {
                public static int created, initialized, conditions;
                public static AnnotationConfigApplicationContext context;
                public static void main(String[] args) throws Exception {
                    try (var c = new AnnotationConfigApplicationContext()) {
                        context = c;
                        c.getEnvironment().setActiveProfiles("prod");
                        c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",
                            Map.of("feature.enabled", "true", "feature.disabled", "false")));
                        c.addProtocolResolver((location, loader) -> location.equals("fixture:feature")
                            ? new ByteArrayResource(new byte[]{1}) : null);
                        c.registerBean("marker", String.class, () -> "marker");
                        c.refresh();
                        System.out.println("READY");
                        while (!Files.exists(Path.of(System.getProperty("probe.dir"), "probe"))) Thread.sleep(20);
                        System.out.println("RESULT=" + c.containsBean("onProfile") + ":" + c.containsBean("enabled")
                            + ":" + c.containsBean("offProfile") + ":" + c.containsBean("disabled") + ":" + c.containsBean("throws")
                            + " created=" + created + " initialized=" + initialized + " conditions=" + conditions);
                    }
                }
            }
            """;
}
