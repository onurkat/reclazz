/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e.harness;

import java.nio.file.*;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Shared actual-premain Boot fixture; each backend is isolated from the worker classpath. */
public final class BootLoggingStartupFixture {
    private BootLoggingStartupFixture() { }
    public static void run(Path tmp,String backend,String mode,String version)throws Exception {
        String logging=System.getProperty("reclazz.logging."+backend+".classpath");assertNotNull(logging);
        Path appHome=tmp.resolve("application"),classes=Files.createDirectories(appHome.resolve("classes"));
        boolean runtimeGroup=mode.equals("runtimegroup")||mode.equals("runtimecustomgroup");
        boolean group=mode.equals("group")||mode.equals("customgroup")||runtimeGroup;
        String logger=(mode.equals("group")||mode.equals("runtimegroup"))?"org.springframework.jdbc.core":(mode.equals("customgroup")||mode.equals("runtimecustomgroup"))?"fixture.group.member":mode.equals("inherit")?"fixture.inherited":mode.equals("debug")?"org.springframework.boot":mode.equals("runtime")?"fixture.console":"fixture.declared";
        Path config=classes.resolve(backend.equals("logback")?"logback.xml":"log4j2.xml");
        String xml=backend.equals("logback")?"<configuration><logger name=\""+logger+"\" level=\"INFO\"/><root level=\"WARN\"/></configuration>":
                "<Configuration><Appenders/><Loggers><Logger name=\""+logger+"\" level=\"INFO\"/><Root level=\"WARN\"/></Loggers></Configuration>";
        if(mode.equals("inherit"))xml=xml.replace("<logger name=\""+logger+"\" level=\"INFO\"/>","").replace("<Logger name=\""+logger+"\" level=\"INFO\"/>","");
        Files.writeString(config,xml);Path properties=classes.resolve("application.properties");
        String base="spring.main.banner-mode=off\nspring.main.web-application-type=none\n"+(mode.equals("debug")?"debug=true\n":"");
        String levelName=(mode.equals("group")||mode.equals("runtimegroup"))?"sql":(mode.equals("customgroup")||mode.equals("runtimecustomgroup"))?"fixturegroup":logger;
        if(mode.equals("customgroup")||mode.equals("runtimecustomgroup"))base+="logging.group.fixturegroup="+logger+",fixture.group.other\n";
        String initial=base+(mode.equals("runtime")||runtimeGroup?"":"logging.level."+levelName+"="+(mode.equals("debug")?"TRACE":"DEBUG")+"\n")
                +(mode.equals("standard")?"logging.level.root=ERROR\n":"");
        Files.writeString(properties,initial);
        String source="""
                package app;
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
                public class App {
                    public static void main(String[] args)throws Exception {
                        var context=org.springframework.boot.SpringApplication.run(App.class,args);
                        var system=org.springframework.boot.logging.LoggingSystem.get(App.class.getClassLoader());
                        System.out.println("BOOT_VERSION="+org.springframework.boot.SpringBootVersion.getVersion());
                        if(MODE.equals("runtime")) {
                            var env=new org.springframework.core.env.StandardEnvironment();
                            env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("invalid-logging",java.util.Map.of("logging.level.fixture.bad","NOT_A_LEVEL")));
                            var listener=new org.springframework.boot.context.logging.LoggingApplicationListener();
                            var call=listener.getClass().getDeclaredMethod("initializeFinalLoggingLevels",org.springframework.core.env.ConfigurableEnvironment.class,org.springframework.boot.logging.LoggingSystem.class);call.setAccessible(true);
                            try{call.invoke(listener,env,system);throw new AssertionError("invalid startup level did not fail");}
                            catch(java.lang.reflect.InvocationTargetException failure){System.out.println("EXPECTED_STARTUP_FAILURE="+failure.getCause().getClass().getName());}
                            system.setLogLevel(LOGGER,org.springframework.boot.logging.LogLevel.TRACE);
                        }
                        String other=null;
                        if(MODE.endsWith("group")) {
                            var groups=context.getBean(org.springframework.boot.context.logging.LoggingApplicationListener.LOGGER_GROUPS_BEAN_NAME,org.springframework.boot.logging.LoggerGroups.class);
                            other=groups.get((MODE.equals("group")||MODE.equals("runtimegroup"))?"sql":"fixturegroup").getMembers().stream().filter(n->!n.equals(LOGGER)).findFirst().orElseThrow();
                        }
                        String last="";
                        while(true) {
                            String state="STATE named="+READ_NAMED+" root="+READ_ROOT+(other==null?"":" other="+READ_OTHER);
                            if(!state.equals(last)){System.out.println(state);last=state;}
                            Thread.sleep(100);
                        }
                    }
                    private static final String MODE="%s",LOGGER="%s";
                }
                """.formatted(mode,logger);
        source=source.replace("READ_NAMED",backend.equals("log4j")?
                "org.apache.logging.log4j.LogManager.getLogger(LOGGER).getLevel()":
                "((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(LOGGER)).getEffectiveLevel()");
        source=source.replace("READ_ROOT",backend.equals("log4j")?
                "org.apache.logging.log4j.LogManager.getRootLogger().getLevel()":
                "((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)).getEffectiveLevel()");
        source=source.replace("READ_OTHER",backend.equals("log4j")?
                "org.apache.logging.log4j.LogManager.getLogger(other).getLevel()":
                "((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(other)).getEffectiveLevel()");
        Path evidence=Path.of(System.getProperty("reclazz.logging.evidence",tmp.resolve("evidence").toString()))
                .resolve(version+"-"+backend+"-"+mode);Files.createDirectories(evidence);
        boolean complete=false;
        try(var app=WatchedApp.in(appHome).classpath(WatchedApp.springClasspath()+File.pathSeparator+logging+(mode.equals("dual")?File.pathSeparator+Arrays.stream(System.getProperty("reclazz.logging.log4j.classpath").split(File.pathSeparator)).filter(p->!p.contains("slf4j")).collect(java.util.stream.Collectors.joining(File.pathSeparator)):""))
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true").with("App",source).start()) {
            try {
                app.awaitOrFail("BOOT_VERSION="+version,"actual Boot line, not a logging mock");
                String before=runtimeGroup?"STATE named=INFO root=WARN":mode.equals("standard")?"STATE named=DEBUG root=ERROR":mode.equals("runtime")||mode.equals("debug")?"STATE named=TRACE root=WARN":"STATE named=DEBUG root=WARN";
                app.awaitOrFail(before,"Boot startup levels/console control ready");app.awaitOrFail("] Watching 1 director","watcher actually collecting filesystem events");
                if(runtimeGroup) {
                    Files.writeString(properties,base+"logging.level."+levelName+"=DEBUG\n");
                    assertTrue(app.awaits("STATE named=DEBUG root=WARN other=DEBUG",20),app.tail());
                }
                if(mode.equals("runtime")) {
                    app.awaitOrFail("EXPECTED_STARTUP_FAILURE=","implicit exception must clear startup capture scopes");
                    Files.writeString(properties,base+"logging.level."+logger+"=DEBUG\n");
                    app.awaitOrFail("STATE named=DEBUG root=WARN","runtime file edit applied");
                }
                Files.writeString(properties,mode.equals("customgroup")?base.replace("logging.group.fixturegroup="+logger+",fixture.group.other\n",""):base);
                String after="STATE named="+(mode.equals("debug")?"DEBUG":mode.equals("runtime")?"TRACE":mode.equals("inherit")?"WARN":"INFO")+" root=WARN";
                if(group)after+=" other=WARN";
                assertTrue(app.awaits(after,20),"removal restores the precise startup/runtime baseline without restart: "+app.tail());
                assertFalse(app.output().stream().anyMatch(line->line.contains("Could not reset logger level")
                        || line.contains("Could not record Boot startup logger baseline")
                        || line.contains("removed logging level has no lower configured value")),app.tail());
                complete=true;
            }finally {
                Files.write(evidence.resolve("child.log"),app.output());
                var summary=app.output().stream().filter(line->line.startsWith("BOOT_VERSION=")||line.startsWith("STATE ")
                        ||line.startsWith("EXPECTED_STARTUP_FAILURE=")||line.contains("Logger level reset")||line.contains("Could not reset")
                        ||line.contains("startup logger baseline")||line.contains("VerifyError")||line.contains("ClassFormatError")).distinct().toList();
                Files.write(evidence.resolve("summary.txt"),summary);Files.writeString(evidence.resolve("result.txt"),"completed="+complete+"\n");
            }
        }
    }
}
