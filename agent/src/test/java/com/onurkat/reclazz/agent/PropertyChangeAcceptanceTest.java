/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.bootstrap.RequestGate;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.PropertyFileSnapshots;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.spring.SpringPropertyRebinder;
import com.onurkat.reclazz.spring.PropertyChangeOutcome;
import com.onurkat.reclazz.spring.PropertyChangeCheckTest.ServiceProperties;
import com.onurkat.reclazz.watcher.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PropertyChangeAcceptanceTest {
    @TempDir Path tmp;
    @Configuration(proxyBeanMethods = false) @EnableConfigurationProperties(ServiceProperties.class)
    static class Config { }

    @Test void aWaitingCandidateKeepsItsOwnLoggerAndBaseline() throws Exception {
        try (var context = context(); var logger = logger()) {
            Path file = tmp.resolve("application.properties");
            Files.writeString(file, content("10", "INFO"));
            ReclazzAgent.baselinePropertyFile(file);
            var read = new CountDownLatch(1);
            var platform = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlatformContext.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getAllApplicationContexts")) {
                            read.countDown(); return List.of(context);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            Object previousPlatform = swap("platformContext", platform);
            Object previousConfig = swap("agentConfig", AgentConfig.parse("reloadBoundary=request"));
            Object previousInstrumentation = swap("instrumentation", logger.instrumentation());
            try {
                RequestGate.global().installed();
                RequestGate.global().enter();
                var failure = new AtomicReference<Throwable>();
                Thread writer = new Thread(() -> {
                    try { handle(file); } catch (Throwable t) { failure.set(t); }
                });
                try {
                    Files.writeString(file, content("20", "DEBUG"));
                    writer.start();
                    assertTrue(read.await(10, TimeUnit.SECONDS));
                    Files.writeString(file, content("abc", "TRACE"));
                    assertEquals("INFO", logger.level());
                    assertEquals("10", snapshots().current(file).get("svc.timeout"));
                } finally {
                    RequestGate.global().exit();
                    writer.join(10_000);
                }
                assertFalse(writer.isAlive());
                assertNull(failure.get());
                assertEquals(20, context.getBean(ServiceProperties.class).getTimeout());
                assertEquals("20", snapshots().current(file).get("svc.timeout"));
                assertEquals("DEBUG", logger.level(), "A must not reread B's TRACE level");
                handle(file);
                assertEquals("20", snapshots().current(file).get("svc.timeout"));
                assertEquals("DEBUG", logger.level());
                assertEquals(2, snapshots().pending(file).changed().size());
                Files.writeString(file, content("30", "TRACE"));
                handle(file);
                assertEquals(30, context.getBean(ServiceProperties.class).getTimeout());
                assertEquals("TRACE", logger.level());
                assertTrue(snapshots().pending(file).changed().isEmpty());
            } finally {
                swap("platformContext", previousPlatform);
                swap("agentConfig", previousConfig);
                swap("instrumentation", previousInstrumentation);
            }
        }
    }

    @Test void interruptedBoundaryDoesNotAcceptOrApplyLoggerChanges() throws Exception {
        try (var context = context(); var logger = logger()) {
            Path file = tmp.resolve("application.properties");
            var snapshots = new PropertyFileSnapshots();
            Files.writeString(file, content("10", "INFO"));
            snapshots.baseline(file);
            Files.writeString(file, content("20", "DEBUG"));
            var candidate = snapshots.pending(file);
            RequestGate.global().installed();
            RequestGate.global().enter();
            var entered = new CountDownLatch(1);
            var outcome = new AtomicReference<PropertyChangeOutcome>();
            var failure = new AtomicReference<Throwable>();
            Thread writer = new Thread(() -> {
                try {
                    outcome.set(new SpringPropertyRebinder(List.of(context)).apply(candidate.changed(), work -> {
                        entered.countDown(); RequestReloadBoundary.run(work);
                    }, () -> {
                        snapshots.accept(candidate);
                        new com.onurkat.reclazz.reload.LoggingReloader(logger.instrumentation())
                                .applyLevels(Map.of("app", "DEBUG"));
                    }));
                } catch (Throwable t) { failure.set(t); }
            });
            try {
                writer.start();
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                writer.interrupt();
                writer.join(10_000);
                assertFalse(writer.isAlive());
                assertNull(failure.get());
                assertEquals(PropertyChangeOutcome.State.NOT_RUN, outcome.get().state());
                assertEquals("10", snapshots.current(file).get("svc.timeout"));
                assertEquals("10", context.getEnvironment().getProperty("svc.timeout"));
                assertEquals("INFO", logger.level());
                assertEquals(2, snapshots.pending(file).changed().size());
            } finally { RequestGate.global().exit(); }
        }
    }

    @Test void sapWithoutConfigStillAppliesItsLoggerLevels() throws Exception {
        try (var logger = logger()) {
            Path file = Files.createDirectories(tmp.resolve("hybris/config")).resolve("local.properties");
            Files.writeString(file, "logging.level.app=INFO\n");
            ReclazzAgent.baselinePropertyFile(file);
            var platform = new com.onurkat.reclazz.platform.HybrisPlatformContext(tmp, AgentConfig.parse("")) {
                @Override public Object getApplicationContext() { return null; }
            };
            Object previousPlatform = swap("platformContext", platform);
            Object previousInstrumentation = swap("instrumentation", logger.instrumentation());
            try {
                var modules = new com.onurkat.reclazz.ext.PremiumModules(new com.onurkat.reclazz.ext.AlwaysAdmit());
                Method register = ReclazzAgent.class.getDeclaredMethod("registerPremiumAutomation",
                        com.onurkat.reclazz.ext.PremiumModules.class, AgentConfig.class, PlatformContext.class);
                register.setAccessible(true); register.invoke(null, modules, AgentConfig.parse(null), platform);
                assertTrue(modules.has(com.onurkat.reclazz.ext.Capability.CONFIG_APPLY));
                Files.writeString(file, "logging.level.app=DEBUG\n");
                modules.handle(new com.onurkat.reclazz.ext.PlatformChangeHandler.Change(
                        com.onurkat.reclazz.ext.Capability.CONFIG_APPLY, file, "MODIFIED", "app", "classes"));
                assertEquals("DEBUG", logger.level());
                assertTrue(snapshots().pending(file).changed().isEmpty());
            } finally {
                swap("platformContext", previousPlatform);
                swap("instrumentation", previousInstrumentation);
            }
        }
    }

    @Test void pureLoggerDeletionUndoesTheEarlierFileEdit() throws Exception {
        try(var context=context();var logger=logger()) {
            Path file=tmp.resolve("logging.properties");Files.writeString(file,"");ReclazzAgent.baselinePropertyFile(file);
            Object platform=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                    (proxy,method,args)->{if(method.getName().equals("getAllApplicationContexts"))return List.of(context);throw new UnsupportedOperationException(method.getName());});
            Object previousPlatform=swap("platformContext",platform),previousInstrumentation=swap("instrumentation",logger.instrumentation()),previousConfig=swap("agentConfig",AgentConfig.parse(null));
            try {
                Files.writeString(file,"logging.level.app=DEBUG\n");handle(file);assertEquals("DEBUG",logger.level());
                Files.writeString(file,"");handle(file);assertEquals("INFO",logger.level(),"pure deletion must undo the earlier DEBUG edit");
                assertTrue(snapshots().removed(snapshots().pending(file)).isEmpty());
            } finally {swap("platformContext",previousPlatform);swap("instrumentation",previousInstrumentation);swap("agentConfig",previousConfig);}
        }
    }

    @Test void loggerRenameResetsOldNameAndKeepsNewName() throws Exception {
        try(var context=context();var logger=logger()) {
            Path file=tmp.resolve("renamed.properties");Files.writeString(file,"");ReclazzAgent.baselinePropertyFile(file);
            Object platform=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                    (proxy,method,args)->{if(method.getName().equals("getAllApplicationContexts"))return List.of(context);throw new UnsupportedOperationException(method.getName());});
            Object previousPlatform=swap("platformContext",platform),previousInstrumentation=swap("instrumentation",logger.instrumentation()),previousConfig=swap("agentConfig",AgentConfig.parse(null));
            try {
                Files.writeString(file,"log4j2.logger.fixture.name=app.one\nlog4j2.logger.fixture.level=DEBUG\n");handle(file);assertEquals("DEBUG",logger.level("app.one"));
                Files.writeString(file,"log4j2.logger.fixture.name=app.two\nlog4j2.logger.fixture.level=DEBUG\n");handle(file);
                assertEquals("INFO",logger.level("app.one"));assertEquals("DEBUG",logger.level("app.two"));
                assertTrue(snapshots().pending(file).changed().isEmpty());
            }finally{swap("platformContext",previousPlatform);swap("instrumentation",previousInstrumentation);swap("agentConfig",previousConfig);}
        }
    }

    @Test void failedLegacyResetStaysPendingAndAnIdenticalSaveRetriesIt() throws Exception {
        try(var context=context();var logger=logger()) {
            Path file=tmp.resolve("retry.properties");Files.writeString(file,"");ReclazzAgent.baselinePropertyFile(file);
            Object platform=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                    (proxy,method,args)->{if(method.getName().equals("getAllApplicationContexts"))return List.of(context);throw new UnsupportedOperationException(method.getName());});
            Object oldPlatform=swap("platformContext",platform),oldInstrumentation=swap("instrumentation",logger.instrumentation()),oldConfig=swap("agentConfig",AgentConfig.parse(null));
            try {
                Files.writeString(file,"logging.level.app=DEBUG\n");handle(file);
                logger.config.getField("failRemoval").setBoolean(null,true);Files.writeString(file,"");handle(file);
                assertEquals("DEBUG",logger.level("app"));assertEquals(Set.of("logging.level.app"),snapshots().removed(snapshots().pending(file)));
                logger.config.getField("failRemoval").setBoolean(null,false);handle(file);
                assertEquals("INFO",logger.level("app"));assertTrue(snapshots().removed(snapshots().pending(file)).isEmpty());
            } finally {swap("platformContext",oldPlatform);swap("instrumentation",oldInstrumentation);swap("agentConfig",oldConfig);}
        }
    }
    @Test void legacyRemovalUsesTheLowerEnvironmentInsteadOfTheFileAlone() throws Exception {
        try(var context=context();var logger=logger()) {
            context.getEnvironment().getPropertySources().addLast(new MapPropertySource("lower",Map.of("logging.level.app","TRACE")));
            Path file=tmp.resolve("layered.properties");Files.writeString(file,"");ReclazzAgent.baselinePropertyFile(file);
            Object platform=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                    (proxy,method,args)->{if(method.getName().equals("getAllApplicationContexts"))return List.of(context);throw new UnsupportedOperationException(method.getName());});
            Object oldPlatform=swap("platformContext",platform),oldInstrumentation=swap("instrumentation",logger.instrumentation()),oldConfig=swap("agentConfig",AgentConfig.parse(null));
            try {
                Files.writeString(file,"logging.level.app=DEBUG\n");handle(file);assertEquals("DEBUG",logger.level("app"));
                Files.writeString(file,"");handle(file);assertEquals("TRACE",logger.level("app"));
                assertEquals("TRACE",context.getEnvironment().getProperty("logging.level.app"));
                assertTrue(snapshots().removed(snapshots().pending(file)).isEmpty());
            } finally {swap("platformContext",oldPlatform);swap("instrumentation",oldInstrumentation);swap("agentConfig",oldConfig);}
        }
    }
    @Test void sapNonLevelRemovalWarnsOnceEvenWhileALoggerResetIsPending() throws Exception {
        try(var logger=logger()) {
            var platform=new com.onurkat.reclazz.platform.HybrisPlatformContext(tmp, AgentConfig.parse("")) {
                @Override public Object getApplicationContext() { return null; }
            };
            Path file=Files.createDirectories(tmp.resolve("hybris/config")).resolve("local.properties");Files.writeString(file,"log4j2.logger.fixture.name=app\nlog4j2.logger.fixture.level=DEBUG\nlog4j2.logger.fixture.additivity=false\n");ReclazzAgent.baselinePropertyFile(file);
            var messages=new ArrayList<String>();com.onurkat.reclazz.ui.StatusReporter.StatusListener listener=(level,message)->{if(level.equals("WARN"))messages.add(message);};
            Object oldPlatform=swap("platformContext",platform),oldInstrumentation=swap("instrumentation",logger.instrumentation());
            com.onurkat.reclazz.ui.StatusReporter.addListener(listener);
            try {
                var modules=new com.onurkat.reclazz.ext.PremiumModules(new com.onurkat.reclazz.ext.AlwaysAdmit());
                Method register=ReclazzAgent.class.getDeclaredMethod("registerPremiumAutomation",com.onurkat.reclazz.ext.PremiumModules.class,AgentConfig.class,PlatformContext.class);
                register.setAccessible(true);register.invoke(null,modules,AgentConfig.parse(null),platform);
                Files.writeString(file,"");
                var change=new com.onurkat.reclazz.ext.PlatformChangeHandler.Change(com.onurkat.reclazz.ext.Capability.CONFIG_APPLY,file,"MODIFIED","app","classes");
                modules.handle(change);modules.handle(change);
                assertEquals(1,messages.stream().filter(m->m.contains("Removed from") && m.contains("additivity")).count());
                assertFalse(snapshots().removed(snapshots().pending(file)).contains("log4j2.logger.fixture.additivity"));
                assertTrue(snapshots().removed(snapshots().pending(file)).contains("log4j2.logger.fixture.level"));
            } finally {com.onurkat.reclazz.ui.StatusReporter.removeListener(listener);swap("platformContext",oldPlatform);swap("instrumentation",oldInstrumentation);com.onurkat.reclazz.ui.RestartLedger.clear();}
        }
    }

    @Test void changedOwnershipDuringResetCannotEscapeTheAcceptanceHandler() throws Exception {
        try(var context=context();var logger=logger()) {
            Path file=tmp.resolve("ownership-race.properties");Files.writeString(file,"logging.level.app=DEBUG\n");
            for(var source:new org.springframework.boot.env.PropertiesPropertySourceLoader().load("race",new org.springframework.core.io.FileSystemResource(file)))context.getEnvironment().getPropertySources().addFirst(source);
            Object platform=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                    (proxy,method,args)->{if(method.getName().equals("getAllApplicationContexts"))return List.of(context);throw new UnsupportedOperationException(method.getName());});
            Object oldPlatform=swap("platformContext",platform),oldInstrumentation=swap("instrumentation",logger.instrumentation()),oldConfig=swap("agentConfig",AgentConfig.parse(null));
            Field field=ReclazzAgent.class.getDeclaredField("springPropertyFiles");field.setAccessible(true);
            var reload=(com.onurkat.reclazz.spring.SpringPropertyFiles)field.get(null);
            var changed=new java.util.concurrent.atomic.AtomicBoolean();
            com.onurkat.reclazz.ui.StatusReporter.StatusListener listener=(level,message)->{
                if(message.startsWith("Could not reset logger level app:") && changed.compareAndSet(false,true))try {
                    Files.writeString(file,"logging.level.app=ERROR\n");
                    assertEquals(PropertyChangeOutcome.State.APPLIED,reload.apply(file,List.of(context),Runnable::run).outcome().state());
                }catch(Exception failure){throw new AssertionError(failure);}
            };
            try {
                handle(file);Files.writeString(file,"");com.onurkat.reclazz.ui.StatusReporter.addListener(listener);
                assertDoesNotThrow(()->handle(file));assertTrue(changed.get());
                assertEquals("ERROR",context.getEnvironment().getProperty("logging.level.app"));
            } finally {com.onurkat.reclazz.ui.StatusReporter.removeListener(listener);swap("platformContext",oldPlatform);swap("instrumentation",oldInstrumentation);swap("agentConfig",oldConfig);}
        }
    }
    @Test void aPendingRemovalOnlyResetDoesNotReportAppliedZeroProperties() throws Exception {
        try(var context=context();var logger=logger()) {
            Path file=tmp.resolve("wording.properties");Files.writeString(file,"");ReclazzAgent.baselinePropertyFile(file);
            Object platform=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                    (proxy,method,args)->{if(method.getName().equals("getAllApplicationContexts"))return List.of(context);throw new UnsupportedOperationException(method.getName());});
            Object oldPlatform=swap("platformContext",platform),oldInstrumentation=swap("instrumentation",logger.instrumentation()),oldConfig=swap("agentConfig",AgentConfig.parse(null));
            var messages=new ArrayList<String>();com.onurkat.reclazz.ui.StatusReporter.StatusListener listener=(level,message)->messages.add(message);
            try {
                Files.writeString(file,"logging.level.app=DEBUG\n");handle(file);
                logger.config.getField("failRemoval").setBoolean(null,true);Files.writeString(file,"");
                com.onurkat.reclazz.ui.StatusReporter.addListener(listener);handle(file);handle(file);
                assertEquals(Set.of("logging.level.app"),snapshots().removed(snapshots().pending(file)));
                assertFalse(messages.stream().anyMatch(m->m.contains("Applied 0 property changes")),messages.toString());
            } finally {com.onurkat.reclazz.ui.StatusReporter.removeListener(listener);swap("platformContext",oldPlatform);swap("instrumentation",oldInstrumentation);swap("agentConfig",oldConfig);}
        }
    }

    @Test void successfulSameNamedFileDoesNotResolveAnotherFilesPendingSet() throws Exception { setLedgerOwnership(false); }
    @Test void successfulSameFileOwnerDoesNotResolveAnotherOwnersPendingSet() throws Exception { setLedgerOwnership(true); }
    private void setLedgerOwnership(boolean sharedFile) throws Exception {
        try(var a=context();var b=context();var logger=logger()) {
            a.setClassLoader(logger.loader());b.setClassLoader(logger.loader());logger.level("app");
            Path first=Files.createDirectories(tmp.resolve("a")).resolve("application.properties");
            Path second=sharedFile?first:Files.createDirectories(tmp.resolve("b")).resolve("application.properties");
            Files.writeString(first,"app.foo=0\n");Files.writeString(second,"app.foo=0\n");
            for(var source:new org.springframework.boot.env.PropertiesPropertySourceLoader().load("owned-a",new org.springframework.core.io.FileSystemResource(first)))a.getEnvironment().getPropertySources().addFirst(source);
            for(var source:new org.springframework.boot.env.PropertiesPropertySourceLoader().load("owned-b",new org.springframework.core.io.FileSystemResource(second)))b.getEnvironment().getPropertySources().addFirst(source);
            Object platform=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                    (proxy,method,args)->{if(method.getName().equals("getAllApplicationContexts"))return List.of(a,b);throw new UnsupportedOperationException(method.getName());});
            Object oldPlatform=swap("platformContext",platform),oldInstrumentation=swap("instrumentation",logger.instrumentation()),oldConfig=swap("agentConfig",AgentConfig.parse(null));
            com.onurkat.reclazz.ui.RestartLedger.clear();
            try {
                handle(first);if(!sharedFile)handle(second);
                if(sharedFile)logger.config().getField("failSetCountdown").setInt(null,1);else logger.config().getField("failSet").setBoolean(null,true);
                Files.writeString(first,"app.foo=0\nlogging.level.app=DEBUG\n");handle(first);
                logger.config().getField("failSet").setBoolean(null,false);
                if(!sharedFile){Files.writeString(second,"app.foo=0\nlogging.level.other=TRACE\n");handle(second);}
                String subject=first.toFile().getCanonicalPath();
                assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream().anyMatch(line->line.contains(subject) && line.contains("logging backend")),com.onurkat.reclazz.ui.RestartLedger.digest().toString());
                handle(first);
                assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream().noneMatch(line->line.contains(subject)),com.onurkat.reclazz.ui.RestartLedger.digest().toString());
            } finally {swap("platformContext",oldPlatform);swap("instrumentation",oldInstrumentation);swap("agentConfig",oldConfig);com.onurkat.reclazz.ui.RestartLedger.clear();}
        }
    }

    @Test void revertedOrDeletedFailedSetClearsItsLedgerConcern() throws Exception {
        for(boolean delete:List.of(false,true))try(var context=context();var logger=logger()) {
            context.setClassLoader(logger.loader());logger.level("app");Path file=tmp.resolve("revert-"+delete+".properties");
            String baseline="app.foo=0\nlogging.level.app=INFO\n";Files.writeString(file,baseline);
            for(var source:new org.springframework.boot.env.PropertiesPropertySourceLoader().load("revert",new org.springframework.core.io.FileSystemResource(file)))context.getEnvironment().getPropertySources().addFirst(source);
            Object platform=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                    (proxy,method,args)->{if(method.getName().equals("getAllApplicationContexts"))return List.of(context);throw new UnsupportedOperationException(method.getName());});
            Object oldPlatform=swap("platformContext",platform),oldInstrumentation=swap("instrumentation",logger.instrumentation()),oldConfig=swap("agentConfig",AgentConfig.parse(null));
            com.onurkat.reclazz.ui.RestartLedger.clear();
            try {
                handle(file);logger.config().getField("failSet").setBoolean(null,true);
                Files.writeString(file,"app.foo=0\nlogging.level.app=DEBUG\n");handle(file);
                assertEquals(1,com.onurkat.reclazz.ui.RestartLedger.size());
                logger.config().getField("failSet").setBoolean(null,false);Files.writeString(file,delete?"app.foo=0\n":baseline);handle(file);
                assertEquals("INFO",logger.level("app"));assertEquals(0,com.onurkat.reclazz.ui.RestartLedger.size(),com.onurkat.reclazz.ui.RestartLedger.digest().toString());
            } finally {swap("platformContext",oldPlatform);swap("instrumentation",oldInstrumentation);swap("agentConfig",oldConfig);com.onurkat.reclazz.ui.RestartLedger.clear();}
        }
    }

    @Test void distinctPendingReasonsWarnSeparatelyButChangingDetailsWarnOnce() throws Exception {
        try(var context=context()) {
            Path file=tmp.resolve("reason-categories.properties");Files.writeString(file,"app.foo=0\nlogging.level.fixturegroup=INFO\n");
            for(var source:new org.springframework.boot.env.PropertiesPropertySourceLoader().load("reasons",new org.springframework.core.io.FileSystemResource(file)))context.getEnvironment().getPropertySources().addFirst(source);
            context.getBeanFactory().registerSingleton(org.springframework.boot.context.logging.LoggingApplicationListener.LOGGER_GROUPS_BEAN_NAME,
                    new org.springframework.boot.logging.LoggerGroups(Map.of("fixturegroup",List.of("fixture.member"))));
            context.getEnvironment().getPropertySources().addLast(new MapPropertySource("broken-level",Map.of("logging.level.fixture.member","${missing.logger.level}")));
            var attempts=new java.util.concurrent.atomic.AtomicInteger();
            Object instrumentation=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.lang.instrument.Instrumentation.class},
                    (proxy,method,args)->{throw new IllegalStateException("changing backend detail "+attempts.incrementAndGet());});
            Object platform=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                    (proxy,method,args)->{if(method.getName().equals("getAllApplicationContexts"))return List.of(context);throw new UnsupportedOperationException(method.getName());});
            Object oldPlatform=swap("platformContext",platform),oldInstrumentation=swap("instrumentation",instrumentation),oldConfig=swap("agentConfig",AgentConfig.parse(null));
            var warnings=new ArrayList<String>();com.onurkat.reclazz.ui.StatusReporter.StatusListener listener=(level,message)->{if(level.equals("WARN"))warnings.add(message);};
            com.onurkat.reclazz.ui.StatusReporter.addListener(listener);
            try {
                Files.writeString(file,"app.foo=1\nlogging.level.fixturegroup=DEBUG\n");handle(file);
                assertEquals(1,warnings.size());assertTrue(warnings.get(0).contains("group metadata"));
                context.getEnvironment().getPropertySources().remove("broken-level");handle(file);handle(file);
                assertEquals(2,attempts.get());assertEquals(2,warnings.size(),warnings.toString());
                assertTrue(warnings.get(1).contains("logger update failed"),warnings.toString());
            } finally {com.onurkat.reclazz.ui.StatusReporter.removeListener(listener);swap("platformContext",oldPlatform);swap("instrumentation",oldInstrumentation);swap("agentConfig",oldConfig);com.onurkat.reclazz.ui.RestartLedger.clear();}
        }
    }

    @Test void unsupportedSpringLevelSetWarnsOnceAndIsAcknowledged() throws Exception {
        try(var context=context()) {
            Path file=tmp.resolve("no-backend.properties");Files.writeString(file,"app.foo=0\n");
            for(var source:new org.springframework.boot.env.PropertiesPropertySourceLoader().load("unsupported",new org.springframework.core.io.FileSystemResource(file)))context.getEnvironment().getPropertySources().addFirst(source);
            Object platform=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                    (proxy,method,args)->{if(method.getName().equals("getAllApplicationContexts"))return List.of(context);throw new UnsupportedOperationException(method.getName());});
            Object oldPlatform=swap("platformContext",platform),oldInstrumentation=swap("instrumentation",null),oldConfig=swap("agentConfig",AgentConfig.parse(null));
            var warnings=new ArrayList<String>();var messages=new ArrayList<String>();
            com.onurkat.reclazz.ui.StatusReporter.StatusListener listener=(level,message)->{messages.add(message);if(level.equals("WARN"))warnings.add(message);};
            com.onurkat.reclazz.ui.RestartLedger.clear();com.onurkat.reclazz.ui.StatusReporter.addListener(listener);
            try {
                handle(file);
                for(int i=1;i<=3;i++){Files.writeString(file,"app.foo="+i+"\nlogging.level.fixture=DEBUG\n");handle(file);}
                assertEquals(1,warnings.size(),warnings.toString());
                assertTrue(warnings.get(0).contains("no supported"),warnings.toString());
                assertEquals(1,messages.stream().filter(m->m.startsWith("Applied ") && m.contains("logging.level.fixture")).count(),messages.toString());
                Field spring=ReclazzAgent.class.getDeclaredField("springPropertyFiles");spring.setAccessible(true);
                assertTrue(((com.onurkat.reclazz.spring.SpringPropertyFiles)spring.get(null)).apply(file,List.of(context),Runnable::run).loggerOwners().isEmpty());
                assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream().anyMatch(line->line.contains("no-backend.properties") && line.contains("logger")));
            } finally {com.onurkat.reclazz.ui.StatusReporter.removeListener(listener);swap("platformContext",oldPlatform);swap("instrumentation",oldInstrumentation);swap("agentConfig",oldConfig);com.onurkat.reclazz.ui.RestartLedger.clear();}
        }
    }

    @Test void failedSpringLevelSetWarnsOnceRetriesAndResolvesItsLedgerConcern() throws Exception {
        try(var context=context();var logger=logger()) {
            context.setClassLoader(logger.loader());Path file=tmp.resolve("failed-set.properties");Files.writeString(file,"app.foo=0\n");
            for(var source:new org.springframework.boot.env.PropertiesPropertySourceLoader().load("failed-set",new org.springframework.core.io.FileSystemResource(file)))context.getEnvironment().getPropertySources().addFirst(source);
            Object platform=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                    (proxy,method,args)->{if(method.getName().equals("getAllApplicationContexts"))return List.of(context);throw new UnsupportedOperationException(method.getName());});
            Object oldPlatform=swap("platformContext",platform),oldInstrumentation=swap("instrumentation",logger.instrumentation()),oldConfig=swap("agentConfig",AgentConfig.parse(null));
            var warnings=new ArrayList<String>();com.onurkat.reclazz.ui.StatusReporter.StatusListener listener=(level,message)->{if(level.equals("WARN"))warnings.add(message);};
            com.onurkat.reclazz.ui.RestartLedger.clear();com.onurkat.reclazz.ui.StatusReporter.addListener(listener);
            try {
                handle(file);logger.level("app");logger.config().getField("failSet").setBoolean(null,true);
                for(int i=1;i<=3;i++){Files.writeString(file,"app.foo="+i+"\nlogging.level.app=DEBUG\n");handle(file);}
                assertEquals(1,warnings.size(),warnings.toString());assertTrue(warnings.get(0).contains("SET remains pending"));
                assertEquals("INFO",logger.level("app"));
                assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream().anyMatch(line->line.contains("failed-set.properties") && line.contains("logger")));
                logger.config().getField("failSet").setBoolean(null,false);handle(file);
                assertEquals("DEBUG",logger.level("app"));assertEquals(1,warnings.size());
                assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream().noneMatch(line->line.contains("failed-set.properties")));
            } finally {com.onurkat.reclazz.ui.StatusReporter.removeListener(listener);swap("platformContext",oldPlatform);swap("instrumentation",oldInstrumentation);swap("agentConfig",oldConfig);com.onurkat.reclazz.ui.RestartLedger.clear();}
        }
    }

    @Test void mixedSpringAndLog4jSpellingsApplyOnceWithTheSamePrecedenceInBothOrders() throws Exception {
        try(var logger=logger()) {
            for(boolean bootFirst:List.of(true,false))try(var context=context()) {
                context.setClassLoader(logger.loader());Path file=tmp.resolve("mixed-"+bootFirst+".properties");Files.writeString(file,"app.foo=0\n");
                for(var source:new org.springframework.boot.env.PropertiesPropertySourceLoader().load("mixed",new org.springframework.core.io.FileSystemResource(file)))context.getEnvironment().getPropertySources().addFirst(source);
                Object platform=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                        (proxy,method,args)->{if(method.getName().equals("getAllApplicationContexts"))return List.of(context);throw new UnsupportedOperationException(method.getName());});
                Object oldPlatform=swap("platformContext",platform),oldInstrumentation=swap("instrumentation",logger.instrumentation()),oldConfig=swap("agentConfig",AgentConfig.parse(null));
                try {
                    handle(file);logger.level("app");int before=logger.config().getField("levelWrites").getInt(null);
                    String boot="logging.level.app=DEBUG\n",pair="log4j2.logger.fixture.name=app\nlog4j2.logger.fixture.level=TRACE\n";
                    Files.writeString(file,"app.foo=0\n"+(bootFirst?boot+pair:pair+boot));handle(file);
                    assertEquals("TRACE",logger.level("app"));
                    assertEquals(1,logger.config().getField("levelWrites").getInt(null)-before,"one merged SET, order="+bootFirst);
                } finally {swap("platformContext",oldPlatform);swap("instrumentation",oldInstrumentation);swap("agentConfig",oldConfig);}
            }
        }
    }

    @Test void missingSpringBaselineDoesNotReplayAnAppliedLevelOnUnrelatedSaves() throws Exception { springPendingScenario(false); }
    @Test void retryableSpringResetDoesNotReplayAnAppliedLevelAndWarnsOnce() throws Exception { springPendingScenario(true); }
    private void springPendingScenario(boolean retryable) throws Exception {
        try(var context=context();var logger=logger()) {
            context.setClassLoader(logger.loader());
            Path file=tmp.resolve("spring-pending.properties");Files.writeString(file,(retryable?"":"logging.level.b=DEBUG\n")+"app.foo=0\n");
            for(var source:new org.springframework.boot.env.PropertiesPropertySourceLoader().load("pending",new org.springframework.core.io.FileSystemResource(file)))context.getEnvironment().getPropertySources().addFirst(source);
            Object platform=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                    (proxy,method,args)->{if(method.getName().equals("getAllApplicationContexts"))return List.of(context);throw new UnsupportedOperationException(method.getName());});
            Object oldPlatform=swap("platformContext",platform),oldInstrumentation=swap("instrumentation",logger.instrumentation()),oldConfig=swap("agentConfig",AgentConfig.parse(null));
            var warnings=new ArrayList<String>();com.onurkat.reclazz.ui.StatusReporter.StatusListener listener=(level,message)->{if(level.equals("WARN"))warnings.add(message);};
            com.onurkat.reclazz.ui.RestartLedger.clear();com.onurkat.reclazz.ui.StatusReporter.addListener(listener);
            try {
                if(retryable) {
                    Files.writeString(file,"app.foo=0\n");handle(file);
                    Files.writeString(file,"logging.level.b=DEBUG\napp.foo=0\n");handle(file);
                    logger.config.getField("failRemoval").setBoolean(null,true);
                } else logger.nativeLevel("b","DEBUG");
                handle(file);Files.writeString(file,"logging.level.a=DEBUG\napp.foo=0\n");handle(file);
                assertEquals("DEBUG",logger.level("a"));
                logger.nativeLevel("a","TRACE");
                for(int i=1;i<=2;i++){Files.writeString(file,"logging.level.a=DEBUG\napp.foo="+i+"\n");handle(file);assertEquals("TRACE",logger.level("a"));}
                assertEquals(1,warnings.size(),warnings.toString());
                if(!retryable) {
                    Field spring=ReclazzAgent.class.getDeclaredField("springPropertyFiles");spring.setAccessible(true);
                    var result=((com.onurkat.reclazz.spring.SpringPropertyFiles)spring.get(null)).apply(file,List.of(context),Runnable::run);
                    assertTrue(result.loggerOwners().isEmpty(),"restart-only removal must be acknowledged");
                }
                assertEquals("DEBUG",logger.level("b"));
                assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream().anyMatch(line->line.contains("b") && line.contains("reset")));
                if(retryable) {
                    logger.config.getField("failRemoval").setBoolean(null,false);handle(file);
                    assertEquals("INFO",logger.level("b"));assertEquals("TRACE",logger.level("a"));
                }
            } finally {com.onurkat.reclazz.ui.StatusReporter.removeListener(listener);swap("platformContext",oldPlatform);swap("instrumentation",oldInstrumentation);swap("agentConfig",oldConfig);com.onurkat.reclazz.ui.RestartLedger.clear();}
        }
    }

    @Test void nativeMergeFailureIsClearedWhenTheRemovedPairIsRestored() throws Exception { nativeMergeScenario(true); }
    @Test void nativeMergeFailureDoesNotSkipNonLoggingConsumersAndRetriesOnlyTheLogger() throws Exception { nativeMergeScenario(false); }
    private void nativeMergeScenario(boolean restorePair) throws Exception {
        Path classes=Files.createDirectories(tmp.resolve("native-config-fixture"));
        Map<String,String> sources=Map.of(
                "Config", "package de.hybris.platform.util; public class Config { public static final java.util.Map<String,String> values=new java.util.HashMap<>(java.util.Map.of(\"svc.timeout\",\"10\",\"obsolete\",\"old\")); public static String getParameter(String key){return values.get(key);} public static void setParameter(String key,String value){values.put(key,value);} public static java.util.Map<String,String> getAllParameters(){return java.util.Map.copyOf(values);} }",
                "Registry", "package de.hybris.platform.core; public class Registry { public static boolean hasCurrentTenant(){return true;} public static void activateMasterTenant(){} }",
                "PlatformConfig", "package de.hybris.bootstrap.config; public class PlatformConfig {}",
                "ConfigUtil", "package de.hybris.bootstrap.config; public class ConfigUtil { public static boolean fail=true; public static PlatformConfig getPlatformConfig(Class<?> type){return new PlatformConfig();} public static void loadRuntimeProperties(java.util.Properties target,PlatformConfig platform){if(fail)throw new IllegalStateException(\"forced merge failure\");} }",
                "NativeOwner", "package fixture; public class NativeOwner {}"
        );
        var arguments=new ArrayList<String>(List.of("-d",classes.toString()));
        for(var entry:sources.entrySet()){Path file=classes.resolve(entry.getKey()+".java");Files.writeString(file,entry.getValue());arguments.add(file.toString());}
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,arguments.toArray(String[]::new)));
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
            Object owner=loader.loadClass("fixture.NativeOwner").getConstructor().newInstance();
            var platform=new com.onurkat.reclazz.platform.HybrisPlatformContext(tmp,AgentConfig.parse("")) {@Override public Object getApplicationContext(){return owner;}};
            var snapshots=new PropertyFileSnapshots();Path file=Files.createDirectories(tmp.resolve("hybris/config")).resolve("local.properties");
            Files.writeString(file,"svc.timeout=10\nobsolete=old\nlog4j2.logger.fixture.name=app\nlog4j2.logger.fixture.level=DEBUG\n");snapshots.baseline(file);
            int[] callbacks=new int[3];var warnings=new ArrayList<String>();
            com.onurkat.reclazz.ui.StatusReporter.StatusListener listener=(level,message)->{if(level.equals("WARN"))warnings.add(message);};
            var context=(com.onurkat.reclazz.ext.PremiumContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{com.onurkat.reclazz.ext.PremiumContext.class},(proxy,method,args)->switch(method.getName()) {
                case "platform" -> platform;case "config" -> AgentConfig.parse("");case "propertySnapshots" -> snapshots;case "instrumentation","scopeFence" -> null;
                case "applyLoggerLevels" -> 0;
                case "resetLoggerLevels" -> {callbacks[0]++;yield 1;}
                case "reapplyXmlProperties" -> {callbacks[1]++;assertEquals("20",((Map<?,?>)args[0]).get("svc.timeout"));yield null;}
                case "propertiesApplied" -> {callbacks[2]++;assertTrue(((Collection<?>)args[0]).contains("svc.timeout"));yield null;}
                case "requestRunningSystemUpdate" -> false;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            var module=new com.onurkat.reclazz.hybris.SapPremiumModuleFactory().create(context);
            var handler=module.changeHandlers().get(com.onurkat.reclazz.ext.Capability.CONFIG_APPLY);
            var change=new com.onurkat.reclazz.ext.PlatformChangeHandler.Change(com.onurkat.reclazz.ext.Capability.CONFIG_APPLY,file,"MODIFIED","app","classes");
            com.onurkat.reclazz.ui.StatusReporter.addListener(listener);
            try {
                Files.writeString(file,"svc.timeout=20\n");assertDoesNotThrow(()->handler.handle(change));
                assertArrayEquals(new int[]{0,1,1},callbacks);
                assertEquals(Set.of("log4j2.logger.fixture.name","log4j2.logger.fixture.level"),snapshots.removed(snapshots.pending(file)));
                assertDoesNotThrow(()->handler.handle(change));assertArrayEquals(new int[]{0,1,1},callbacks);
                assertEquals(1,warnings.stream().filter(m->m.toLowerCase(java.util.Locale.ROOT).contains("logger") && m.contains("pending")).count());
                if(restorePair) {
                    Files.writeString(file,"svc.timeout=20\nlog4j2.logger.fixture.name=app\nlog4j2.logger.fixture.level=DEBUG\n");handler.handle(change);
                    assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream().noneMatch(line->line.contains("lower configuration merge unavailable")));
                    Files.writeString(file,"svc.timeout=20\n");handler.handle(change);
                    assertEquals(2,warnings.stream().filter(m->m.contains("pending")).count());
                }
                loader.loadClass("de.hybris.bootstrap.config.ConfigUtil").getField("fail").setBoolean(null,false);
                handler.handle(change);assertEquals(1,callbacks[0]);assertTrue(snapshots.removed(snapshots.pending(file)).isEmpty());
            } finally {com.onurkat.reclazz.ui.StatusReporter.removeListener(listener);com.onurkat.reclazz.ui.RestartLedger.clear();}
        }
    }

    private static AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("original",
                Map.of("svc.url", "old", "svc.timeout", "10")));
        context.register(Config.class); context.refresh(); return context;
    }
    private static String content(String timeout, String level) {
        return "svc.url=old\nsvc.timeout=" + timeout + "\nlogging.level.app=" + level + "\n";
    }
    private static Object swap(String name, Object value) throws Exception {
        Field field = ReclazzAgent.class.getDeclaredField(name); field.setAccessible(true);
        Object previous = field.get(null); field.set(null, value); return previous;
    }
    private static PropertyFileSnapshots snapshots() throws Exception {
        Field field = ReclazzAgent.class.getDeclaredField("propertySnapshots"); field.setAccessible(true);
        return (PropertyFileSnapshots) field.get(null);
    }
    private static void handle(Path file) throws Exception {
        Method method = ReclazzAgent.class.getDeclaredMethod("handlePropertiesChange", ChangeEvent.class);
        method.setAccessible(true);
        method.invoke(null, new ChangeEvent(file, ChangeEvent.Type.MODIFIED, "app", "classes"));
    }

    /** A private logging fixture, discoverable through the same loaded-class path as production. */
    private LoggerFixture logger() throws Exception {
        Path sources = Files.createDirectories(tmp.resolve("logger"));
        Path level = sources.resolve("Level.java"), config = sources.resolve("Configurator.java");
        Files.writeString(level, """
                package org.apache.logging.log4j;
                public enum Level { INFO, DEBUG, TRACE;
                    public static Level toLevel(String value, Level fallback) { return valueOf(value); }
                }
                """);
        Files.writeString(config, """
                package org.apache.logging.log4j.core.config;
                public class Configurator {
                    public static String level = "INFO"; public static boolean failRemoval, failSet; public static int levelWrites, failSetCountdown;
                    public static void setLevel(String logger, org.apache.logging.log4j.Level value) { level = value.name(); }
                }
                """);
        // Model a real single existing context. An invocation-only Configurator
        // stub cannot prove that the live logger received a level.
        java.util.Map<String, String> contextSources = java.util.Map.of(
                "LogManager", """
                    package org.apache.logging.log4j;
                    public class LogManager {
                        private static final org.apache.logging.log4j.core.impl.Log4jContextFactory FACTORY = new org.apache.logging.log4j.core.impl.Log4jContextFactory();
                        public static org.apache.logging.log4j.core.impl.Log4jContextFactory getFactory() { return FACTORY; }
                    }
                    """,
                "Log4jContextFactory", """
                    package org.apache.logging.log4j.core.impl;
                    public class Log4jContextFactory {
                        private final org.apache.logging.log4j.core.selector.ContextSelector selector = new org.apache.logging.log4j.core.selector.ClassLoaderContextSelector();
                        public org.apache.logging.log4j.core.selector.ContextSelector getSelector() { return selector; }
                    }
                    """,
                "ContextSelector", """
                    package org.apache.logging.log4j.core.selector;
                    public interface ContextSelector {
                        java.util.List<org.apache.logging.log4j.core.LoggerContext> getLoggerContexts();
                    }
                    """,
                "Lazy", """
                    package org.apache.logging.log4j.util;
                    public class Lazy {
                        public boolean isInitialized() { return false; }
                        public Object value() { throw new AssertionError("unused default initialized"); }
                    }
                    """,
                "ClassLoaderContextSelector", """
                    package org.apache.logging.log4j.core.selector;
                    import org.apache.logging.log4j.core.LoggerContext;
                    public class ClassLoaderContextSelector implements ContextSelector {
                        private final org.apache.logging.log4j.util.Lazy defaultLoggerContext = new org.apache.logging.log4j.util.Lazy();
                        public java.util.List<LoggerContext> getLoggerContexts() { return java.util.List.of(LoggerContext.INSTANCE); }
                        public LoggerContext getContext(String name, ClassLoader loader, boolean current) { throw new AssertionError(); }
                        public LoggerContext getContext(String name, ClassLoader loader, boolean current, java.net.URI uri) { throw new AssertionError(); }
                        public LoggerContext getContext(String name, ClassLoader loader, java.util.Map.Entry<String, Object> entry, boolean current, java.net.URI uri) { throw new AssertionError(); }
                        public static LoggerContext findContext(ClassLoader loader) { return LoggerContext.INSTANCE; }
                        protected LoggerContext getDefault() { throw new AssertionError(); }
                    }
                    """,
                "LoggerContext", """
                    package org.apache.logging.log4j.core;
                    public class LoggerContext {
                        public static final LoggerContext INSTANCE = new LoggerContext();
                        private final org.apache.logging.log4j.core.config.Configuration configuration =
                            new org.apache.logging.log4j.core.config.Configuration();
                        private final java.util.Map<String, Logger> loggers = new java.util.HashMap<>();
                        public org.apache.logging.log4j.core.config.Configuration getConfiguration() { return configuration; }
                        public java.util.Collection<Logger> getLoggers() { return loggers.values(); }
                        public Logger getLogger(String name) { return loggers.computeIfAbsent(name, n -> new Logger(n, this)); }
                        public void updateLoggers() { }
                    }
                    """,
                "Configuration", """
                    package org.apache.logging.log4j.core.config;
                    public class Configuration {
                        private final java.util.Map<String, LoggerConfig> entries = new java.util.HashMap<>();
                        private final LoggerConfig root = new LoggerConfig("ROOT", org.apache.logging.log4j.Level.INFO, true);
                        public LoggerConfig getRootLogger() { return root; }
                        public LoggerConfig getLoggerConfig(String name) { return entries.getOrDefault(name, root); }
                        public java.util.Map<String, LoggerConfig> getLoggers() { return entries; }
                        public void addLogger(String name, LoggerConfig config) { entries.put(name, config); }
                        public void removeLogger(String name) { if(Configurator.failRemoval)throw new IllegalStateException("reset held by fixture"); entries.remove(name); Configurator.level = "INFO"; }
                    }
                    """,
                "LoggerConfig", """
                    package org.apache.logging.log4j.core.config;
                    public class LoggerConfig {
                        private org.apache.logging.log4j.Level level;
                        public LoggerConfig(String name, org.apache.logging.log4j.Level value, boolean additive) { setLevel(value); }
                        public void setLevel(org.apache.logging.log4j.Level value) { if(Configurator.failSetCountdown>0){Configurator.failSetCountdown--;throw new IllegalStateException("first owner set held by fixture");} if(Configurator.failSet)throw new IllegalStateException("set held by fixture"); level = value; Configurator.level = value.name(); Configurator.levelWrites++; }
                        public org.apache.logging.log4j.Level getLevel() { return level; }
                    }
                    """,
                "Logger", """
                    package org.apache.logging.log4j.core;
                    public class Logger {
                        private final String name;
                        private final LoggerContext context;
                        public Logger(String name, LoggerContext context) { this.name = name; this.context = context; }
                        public String getName() { return name; }
                        public LoggerContext getContext() { return context; }
                        public org.apache.logging.log4j.Level getLevel() {
                            var entry=context.getConfiguration().getLoggers().get(name); return entry==null ? org.apache.logging.log4j.Level.INFO : entry.getLevel();
                        }
                    }
                    """);
        var compilerArgs = new java.util.ArrayList<String>(java.util.List.of("-d", sources.toString(), level.toString(), config.toString()));
        for (var entry : contextSources.entrySet()) {
            Path source = sources.resolve(entry.getKey() + ".java");
            Files.writeString(source, entry.getValue());
            compilerArgs.add(source.toString());
        }
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                compilerArgs.toArray(String[]::new)));
        var loader = new java.net.URLClassLoader(new java.net.URL[]{sources.toUri().toURL()}, getClass().getClassLoader());
        return new LoggerFixture(loader, loader.loadClass("org.apache.logging.log4j.Level"),
                loader.loadClass("org.apache.logging.log4j.core.config.Configurator"));
    }
    private record LoggerFixture(java.net.URLClassLoader loader, Class<?> levelType,
                                 Class<?> config) implements AutoCloseable {
        java.lang.instrument.Instrumentation instrumentation() {
            return (java.lang.instrument.Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{java.lang.instrument.Instrumentation.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getAllLoadedClasses")) return new Class<?>[]{levelType, config};
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
        String level() throws Exception { return (String) config.getField("level").get(null); }
        String level(String name) throws Exception {
            Class<?> type=loader.loadClass("org.apache.logging.log4j.core.LoggerContext");Object context=type.getField("INSTANCE").get(null);
            Object logger=type.getMethod("getLogger",String.class).invoke(context,name);return logger.getClass().getMethod("getLevel").invoke(logger).toString();
        }
        void nativeLevel(String name,String level) throws Exception {
            Class<?> contextType=loader.loadClass("org.apache.logging.log4j.core.LoggerContext");
            Object nativeContext=contextType.getField("INSTANCE").get(null);
            Object configuration=contextType.getMethod("getConfiguration").invoke(nativeContext);
            Class<?> entryType=loader.loadClass("org.apache.logging.log4j.core.config.LoggerConfig");
            Object parsed=levelType.getMethod("valueOf",String.class).invoke(null,level);
            Object entry=entryType.getConstructor(String.class,levelType,boolean.class).newInstance(name,parsed,true);
            configuration.getClass().getMethod("addLogger",String.class,entryType).invoke(configuration,name,entry);
        }
        public void close() throws Exception { loader.close(); }
    }
}
