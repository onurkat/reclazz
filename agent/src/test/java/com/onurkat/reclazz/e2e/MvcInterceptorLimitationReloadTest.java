/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.io.File;
import java.nio.file.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Characterizes stale constructor state; passing does not mean interceptor lifecycle support. */
class MvcInterceptorLimitationReloadTest {
    @TempDir Path tmp;

    protected String servletNamespace() { return "javax"; }
    private String registration;

    @ParameterizedTest(name = "requestsKeepOldConstructorStateWhileParentIsRecreated [{index}] {arguments}") @CsvSource({"false,lookup", "true,lookup", "false,mapped", "true,mapped", "false,injected", "true,injected"})
    void requestsKeepOldConstructorStateWhileParentIsRecreated(boolean child, String registration) throws Exception {
        this.registration = registration;
        String servlet = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> { String n = Path.of(p).getFileName().toString();
                    return n.startsWith("javax.servlet-api-") || n.startsWith("tomcat-embed-core-")
                            || n.startsWith("micrometer-"); })
                .collect(Collectors.joining(File.pathSeparator));
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath() + File.pathSeparator + servlet)
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .jvmArgs("-Dprobe.dir=" + tmp)
                .with("App", APP).with("Api", API).with("Config", config(registration))
                .with("Primary", primary(1)).with("Stable", stable()).with("Helper", helper(1));
        if (child) builder.childClassLoader();
        try (var app = builder.start()) {
            app.awaitOrFail("READY=true", "MVC parent/web fixture missing");
            app.awaitOrFail("] Watching ", "watcher missing");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child module missing");
            int probe = 0;
            check(app, ++probe, 1, 1);
            // Saving source without publishing class bytes must not update requests.
            Files.writeString(tmp.resolve("src/app/Primary.java"), primary(2));
            check(app, ++probe, 1, 1);
            assertEquals(0, reloads(app, "Primary"), app.tail());
            for (int generation = 2; generation <= 4; generation++) {
                if (generation == 2) app.recompile("Primary");
                else app.rewrite("Primary", primary(generation));
                awaitReload(app, "Primary", generation - 1);
                check(app, ++probe, generation, generation - 1);
                // The parent bean has a constructor-owned final value. A new method
                // body on the old dependency alone cannot satisfy this assertion.
                Files.writeString(tmp.resolve("src/app/Helper.java"), helper(generation));
                check(app, ++probe, generation, generation - 1);
                app.recompile("Helper");
                awaitReload(app, "Helper", generation - 1);
                check(app, ++probe, generation, generation);
            }
            assertFalse(app.output().stream().anyMatch(s -> s.contains("Failed to refresh Spring bean")
                    || s.contains("Dependent cascade skipped") || s.contains("Could not re-wire")), app.tail());
        }
    }

    private long reloads(WatchedApp app, String name) {
        return app.output().stream().filter(s -> s.contains("Reloaded app." + name + " ")
                || s.contains("Structural reload: app." + name + " ")).count();
    }
    private void awaitReload(WatchedApp app, String name, int count) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < until && reloads(app, name) < count) Thread.sleep(25);
        assertEquals(count, reloads(app, name), app.tail());
    }
    private void check(WatchedApp app, int n, int body, int dependency) throws Exception {
        Files.createFile(tmp.resolve("probe" + n));
        app.awaitOrFail("PROBE" + n + "=", "request probe missing");
        String value = app.latest("PROBE" + n + "=");
        System.out.println("[mvc-interceptor] " + value);
        assertEquals("PROBE" + n + "=200:ok:A" + body + "/H" + dependency + ":D"
                + 1
                + ",B,C,Bpost,Apost,Bdone,Adone:true:true:"
                + (!registration.equals("injected") || n < 3)
                + ":true:parent=H" + dependency + ":D" + dependency + ":excluded=200:ok:"
                + (registration.equals("mapped") ? "" : "A" + body + "/H" + dependency + ":D" + 1 + ",")
                + "B,C,Bpost" + (registration.equals("mapped") ? "" : ",Apost") + ",Bdone" + (registration.equals("mapped") ? "" : ",Adone") + ":servletOriginal=true",
                value.substring(value.indexOf("PROBE")), app.tail());
    }
    private static String helper(int generation) {
        return "package app; @org.springframework.stereotype.Component public class Helper { private final String seed = new String(\"D"
                + generation + "\"); public String value() { return \"H"
                + generation + ":\" + seed; } }";
    }
    private String primary(int generation) {
        return ("""
                package app;
                import javax.servlet.http.*;
                @org.springframework.stereotype.Component
                public class Primary implements org.springframework.web.servlet.HandlerInterceptor {
                    @org.springframework.beans.factory.annotation.Autowired private Helper helper;
                    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                        App.trace(req, "A%d/" + helper.value()); return true;
                    }
                    public void postHandle(HttpServletRequest req, HttpServletResponse res, Object handler,
                                           org.springframework.web.servlet.ModelAndView view) { App.trace(req, "Apost"); }
                    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler,
                                                Exception error) { App.trace(req, "Adone"); }
                }
                """).formatted(generation).replace("javax.servlet", servletNamespace() + ".servlet");
    }
    private String stable() {
        return """
                package app;
                import javax.servlet.http.*;
                public class Stable implements org.springframework.web.servlet.HandlerInterceptor {
                    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                        App.trace(req, "B"); return true;
                    }
                    public void postHandle(HttpServletRequest req, HttpServletResponse res, Object handler,
                                           org.springframework.web.servlet.ModelAndView view) { App.trace(req, "Bpost"); }
                    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler,
                                                Exception error) { App.trace(req, "Bdone"); }
                }
                """.replace("javax.servlet", servletNamespace() + ".servlet");
    }
    private static String config(String registration) {
        if (registration.equals("injected")) return INJECTED_CONFIG;
        String src = INJECTED_CONFIG.replace("private Primary primary;", "private org.springframework.context.ApplicationContext context;")
                .replace("@org.springframework.beans.factory.annotation.Autowired private Stable stable;", "")
                .replace("registry.addInterceptor(primary)", "registry.addInterceptor(context.getBean(Primary.class))")
                .replace("registry.addInterceptor(stable)", "registry.addInterceptor(context.getBean(Stable.class))");
        return registration.equals("mapped") ? src.replace(".order(10)", ".addPathPatterns(\"/value\").excludePathPatterns(\"/excluded\").order(10)") : src;
    }
    private static final String INJECTED_CONFIG = """
            package app;
            @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
            @org.springframework.web.servlet.config.annotation.EnableWebMvc
            public class Config implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
                @org.springframework.beans.factory.annotation.Autowired private Primary primary;
                @org.springframework.beans.factory.annotation.Autowired private Stable stable;
                public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
                    registry.addInterceptor(primary).order(10);
                    registry.addInterceptor(stable).order(20);
                }
            }
            """;
    private static final String API = """
            package app;
            @org.springframework.web.bind.annotation.RestController
            public class Api {
                @org.springframework.web.bind.annotation.GetMapping({"/value", "/excluded"})
                public String value(org.springframework.web.context.request.WebRequest request) {
                    Object previous = request.getAttribute("trace", 0);
                    request.setAttribute("trace", previous + ",C", 0); return "ok";
                }
            }
            """;
    private static final String APP = """
            package app;
            import java.nio.file.*;
            public class App {
                public static void trace(Object request, String value) {
                    var req = org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes();
                    Object before = req.getAttribute("trace", 0);
                    req.setAttribute("trace", before == null ? value : before + "," + value, 0);
                }
                public static void main(String[] args) throws Exception {
                    try (var parent = new org.springframework.context.annotation.AnnotationConfigApplicationContext(Helper.class);
                         var web = new org.springframework.web.context.support.AnnotationConfigWebApplicationContext()) {
                        web.setParent(parent); web.setServletContext(new org.springframework.mock.web.MockServletContext());
                        web.register(Config.class, Api.class, Primary.class, Stable.class); web.refresh();
                        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).build();
                        Object stable = web.getBean(Stable.class), api = web.getBean(Api.class);
                        Object mapping = web.getBean("requestMappingHandlerMapping");
                        System.out.println("READY=" + (!web.getBeanFactory().containsLocalBean("helper")
                                && web.getBean(Helper.class) == parent.getBean(Helper.class)));
                        for (int i = 1; i <= 11; i++) {
                            Path probe = Path.of(System.getProperty("probe.dir"), "probe" + i);
                            while (!Files.exists(probe)) Thread.sleep(20);
                            var excluded = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/excluded")).andReturn();
                            var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/value")).andReturn();
                            System.out.println("PROBE" + i + "=" + result.getResponse().getStatus() + ":"
                                + result.getResponse().getContentAsString() + ":" + result.getRequest().getAttribute("trace")
                                + ":" + (stable == web.getBean(Stable.class)) + ":" + (api == web.getBean(Api.class))
                                + ":" + (mapping == web.getBean("requestMappingHandlerMapping"))
                                + ":" + (web.getBean(Helper.class) == parent.getBean(Helper.class))
                                + ":parent=" + parent.getBean(Helper.class).value()
                                + ":excluded=" + excluded.getResponse().getStatus() + ":" + excluded.getResponse().getContentAsString()
                                + ":" + excluded.getRequest().getAttribute("trace")
                                + ":servletOriginal=" + mvc.getDispatcherServlet().getHandlerMappings().stream().anyMatch(m -> m == mapping));
                        }
                    }
                }
            }
            """;
}
