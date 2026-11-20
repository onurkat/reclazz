/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

/** Real, isolated vendor backends. No logging implementation is on the shipped graph. */
class LoggerLevelResetTest {
    @TempDir Path tmp;
    private int sequence;
    private static final String KEY="logging.level.fixture.declared";

    @ParameterizedTest @ValueSource(strings={"logback","log4j"})
    void restoresFileDeclarationAndLeavesConsoleLoggerUntouched(String backend)throws Exception {
        try(var f=fixture(backend)) {
            f.nativeLevel("fixture.console","TRACE");
            assertEquals(List.of("fixture.declared"),f.reloader().applyLevels(Map.of("fixture.declared","DEBUG")));
            assertEquals("DEBUG",f.level("fixture.declared"));
            assertEquals(List.of("fixture.declared"),f.reloader().resetLevels(f.loader,Map.of(KEY,"DEBUG"),List.of(KEY),Map.of()));
            assertEquals("INFO",f.level("fixture.declared"));assertEquals("TRACE",f.level("fixture.console"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"logback","log4j"})
    void removesOnlyCreatedDeclarationAndInheritsParent(String backend)throws Exception {
        try(var f=fixture(backend)) {
            f.reloader().applyLevels(Map.of("fixture.added","DEBUG"));
            assertEquals(List.of("fixture.added"),f.reloader().resetLevels(f.loader,
                    Map.of("logging.level.fixture.added","DEBUG"),List.of("logging.level.fixture.added"),Map.of()));
            assertEquals("WARN",f.level("fixture.added"));assertFalse(f.exact("fixture.added"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"logback","log4j"})
    void rootRestoresItsRealConfigurationNeverANamedRoot(String backend)throws Exception {
        try(var f=fixture(backend)) {
            f.reloader().applyLevels(Map.of("ROOT","DEBUG"));assertEquals("DEBUG",f.level("ROOT"));
            assertEquals(List.of("ROOT"),f.reloader().resetLevels(f.loader,
                    Map.of("logging.level.root","DEBUG"),List.of("logging.level.root"),Map.of()));
            assertEquals("WARN",f.level("ROOT"));
            if(backend.equals("log4j"))assertFalse(f.exact("root"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"logback","log4j"})
    void runtimeEditUndoesToPriorConsoleValueAndCanBeEditedAgain(String backend)throws Exception {
        try(var f=fixture(backend)) {
            f.nativeLevel("fixture.declared","TRACE");f.reloader().applyLevels(Map.of("fixture.declared","DEBUG"));
            f.reloader().resetLevels(f.loader,Map.of(KEY,"DEBUG"),List.of(KEY),Map.of());assertEquals("TRACE",f.level("fixture.declared"));
            f.nativeLevel("fixture.declared","ERROR");f.reloader().applyLevels(Map.of("fixture.declared","DEBUG"));
            f.reloader().resetLevels(f.loader,Map.of(KEY,"DEBUG"),List.of(KEY),Map.of());assertEquals("ERROR",f.level("fixture.declared"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"logback","log4j"})
    void unrecordedRuntimeLevelIsHeldAndRemainingEffectiveValueWins(String backend)throws Exception {
        try(var f=fixture(backend)) {
            f.nativeLevel("fixture.declared","DEBUG");
            assertTrue(f.reloader().resetLevels(f.loader,Map.of(KEY,"DEBUG"),List.of(KEY),Map.of()).isEmpty());
            assertEquals("DEBUG",f.level("fixture.declared"));
            f.reloader().applyLevels(Map.of("fixture.declared","TRACE"));
            f.reloader().resetLevels(f.loader,Map.of(KEY,"TRACE"),List.of(KEY),Map.of(KEY,"ERROR"));assertEquals("ERROR",f.level("fixture.declared"));
            f.reloader().resetLevels(f.loader,Map.of(KEY,"ERROR"),List.of(KEY),Map.of());assertEquals("DEBUG",f.level("fixture.declared"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"logback","log4j"})
    void ownerLoaderResetsOnlyItsFrameworkContext(String backend)throws Exception {
        try(var a=fixture(backend);var b=fixture(backend)) {
            a.reloader().applyLevels(Map.of("fixture.declared","DEBUG"));b.reloader().applyLevels(Map.of("fixture.declared","TRACE"));
            var all=new ArrayList<Class<?>>();all.addAll(a.types.values());all.addAll(b.types.values());
            var instrumentation=(Instrumentation)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Instrumentation.class},
                    (proxy,method,args)->{if(method.getName().equals("getAllLoadedClasses"))return all.toArray(Class<?>[]::new);throw new UnsupportedOperationException(method.getName());});
            assertEquals(List.of("fixture.declared"),new LoggingReloader(instrumentation).resetLevels(a.loader,Map.of(KEY,"DEBUG"),List.of(KEY),Map.of()));
            assertEquals("INFO",a.level("fixture.declared"));assertEquals("TRACE",b.level("fixture.declared"));
        }
    }

    @ParameterizedTest @ValueSource(strings={"logback","log4j"})
    void restoresExplicitNullInheritanceWithoutFreezingTheParentLevel(String backend)throws Exception {
        try(var f=fixture(backend)) {
            f.nativeInherit("fixture.declared");assertEquals("WARN",f.level("fixture.declared"));
            f.reloader().applyLevels(Map.of("fixture.declared","DEBUG"));
            assertEquals(List.of("fixture.declared"),f.reloader().resetLevels(f.loader,Map.of(KEY,"DEBUG"),List.of(KEY),Map.of()));
            assertEquals("WARN",f.level("fixture.declared"));
            f.nativeLevel("ROOT","ERROR");assertEquals("ERROR",f.level("fixture.declared"),"restored null keeps inheriting later parent changes");
        }
    }

    @org.junit.jupiter.api.Test
    void anActiveLog4jContextDoesNotRequireTheOptionalConfiguratorFacadeInTheLoadedInventory()throws Exception {
        try(var f=fixture("log4j")) {
            var inventory=new LinkedHashMap<>(f.types);inventory.remove("org.apache.logging.log4j.core.config.Configurator");
            var reloader=new LoggingReloader(inventory);assertTrue(reloader.frameworkPresent());
            assertEquals(List.of("fixture.declared"),reloader.applyLevels(Map.of("fixture.declared","DEBUG")));
            assertEquals("DEBUG",f.level("fixture.declared"));
        }
    }

    @ParameterizedTest @ValueSource(strings={"logback","log4j"})
    void aReconfigurationAllowsLowerLevelsAndNewCaptures(String backend) throws Exception {
        try (var f = fixture(backend)) {
            f.reloader().applyLevels(Map.of("fixture.declared", "DEBUG"));
            f.reconfigure("ERROR");
            assertEquals(List.of("fixture.declared"),f.reloader().resetLevels(f.loader,Map.of(KEY,"DEBUG"),List.of(KEY),Map.of(KEY,"WARN")));
            assertEquals("WARN",f.level("fixture.declared"));
            f.nativeLevel("fixture.declared","ERROR");
            f.reloader().applyLevels(Map.of("fixture.declared", "DEBUG"));
            assertEquals(List.of("fixture.declared"),f.reloader().resetLevels(f.loader,Map.of(KEY,"DEBUG"),List.of(KEY),Map.of()));
            assertEquals("ERROR",f.level("fixture.declared"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"logback","log4j"})
    void aNewEditRecapturesAfterReconfigurationWithoutAnInterveningReset(String backend) throws Exception {
        try(var f=fixture(backend)) {
            f.reloader().applyLevels(Map.of("fixture.declared","DEBUG"));
            f.reconfigure("ERROR");
            f.reloader().applyLevels(Map.of("fixture.declared","WARN"));
            assertEquals(List.of("fixture.declared"),f.reloader().resetLevels(f.loader,Map.of(KEY,"WARN"),List.of(KEY),Map.of()));
            assertEquals("ERROR",f.level("fixture.declared"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"logback","log4j"})
    void stalePriorIsDiscardedAndALaterEditCapturesTheNewConfiguration(String backend) throws Exception {
        try (var f=fixture(backend)) {
            f.reloader().applyLevels(Map.of("fixture.declared","DEBUG"));f.reconfigure("ERROR");
            var warnings=new ArrayList<String>();
            com.onurkat.reclazz.ui.StatusReporter.StatusListener listener=(level,message)->{if(level.equals("WARN"))warnings.add(message);};
            com.onurkat.reclazz.ui.StatusReporter.addListener(listener);
            try {
                assertTrue(f.reloader().resetLevels(f.loader,Map.of(KEY,"DEBUG"),List.of(KEY),Map.of()).isEmpty());
                assertTrue(f.reloader().resetLevels(f.loader,Map.of(KEY,"DEBUG"),List.of(KEY),Map.of()).isEmpty());
                assertEquals(1,warnings.stream().filter(message->message.contains("Could not reset logger level")).count());
            } finally {com.onurkat.reclazz.ui.StatusReporter.removeListener(listener);}
            assertEquals("ERROR",f.level("fixture.declared"));
            f.reloader().applyLevels(Map.of("fixture.declared","TRACE"));
            assertEquals(List.of("fixture.declared"),f.reloader().resetLevels(f.loader,Map.of(KEY,"TRACE"),List.of(KEY),Map.of()));
            assertEquals("ERROR",f.level("fixture.declared"));
        }
    }
    @org.junit.jupiter.api.Test
    void onlySapNameAndLevelKeysClaimAHandledReset() {
        assertFalse(LoggingReloader.isLevelKey("log4j2.logger.fixture.additivity"));
        assertFalse(LoggingReloader.isLevelKey("log4j2.logger.fixture.appenderRef.console.ref"));
        assertFalse(LoggingReloader.isLevelKey("log4j2.logger.fixture.appenderRef.console.level"));
        assertTrue(LoggingReloader.isLevelKey("log4j2.logger.fixture.name"));
        assertTrue(LoggingReloader.isLevelKey("log4j2.logger.fixture.level"));
    }

    @org.junit.jupiter.api.Test
    void emptyLoggingSuffixCannotResetTheRoot() throws Exception {
        try (var f = fixture("logback")) {
            f.reloader().applyLevels(Map.of("ROOT", "DEBUG"));
            assertTrue(LoggingReloader.resetLoggerNames(Map.of("logging.level.", "DEBUG"), List.of("logging.level.")).isEmpty());
            assertTrue(f.reloader().resetLevels(f.loader, Map.of("logging.level.","DEBUG"), List.of("logging.level."), Map.of()).isEmpty());
            assertEquals("DEBUG", f.level("ROOT"));
        }
    }

    private Fixture fixture(String backend)throws Exception {
        String classpath=System.getProperty("reclazz.logging."+backend+".classpath");assertNotNull(classpath);
        URL[] urls=Arrays.stream(classpath.split(java.io.File.pathSeparator)).map(s->{try{return Path.of(s).toUri().toURL();}catch(Exception e){throw new IllegalStateException(e);}}).toArray(URL[]::new);
        var loader=new URLClassLoader(urls,ClassLoader.getPlatformClassLoader());
        Path config=tmp.resolve("logging-"+(++sequence)+".xml");
        if(backend.equals("logback"))Files.writeString(config,"<configuration><logger name=\"fixture.declared\" level=\"INFO\"/><root level=\"WARN\"/></configuration>");
        else Files.writeString(config,"<Configuration><Appenders/><Loggers><Logger name=\"fixture.declared\" level=\"INFO\"/><Root level=\"WARN\"/></Loggers></Configuration>");
        return new Fixture(loader,backend,config);
    }
    private static final class Fixture implements AutoCloseable {
        final URLClassLoader loader;final Path config;final String backend;final Object context;final Map<String,Class<?>> types=new LinkedHashMap<>();final ClassLoader before;
        Fixture(URLClassLoader loader,String backend,Path config)throws Exception {
            this.loader=loader;this.config=config;this.backend=backend;before=Thread.currentThread().getContextClassLoader();Thread.currentThread().setContextClassLoader(loader);
            if(backend.equals("logback")) {
                for(String name:List.of("ch.qos.logback.classic.LoggerContext","ch.qos.logback.classic.Level","org.slf4j.LoggerFactory"))types.put(name,Class.forName(name,true,loader));
                context=types.get("org.slf4j.LoggerFactory").getMethod("getILoggerFactory").invoke(null);context.getClass().getMethod("reset").invoke(context);
                Class<?> parser=Class.forName("ch.qos.logback.classic.joran.JoranConfigurator",true,loader);Object xml=parser.getConstructor().newInstance();
                parser.getMethod("setContext",Class.forName("ch.qos.logback.core.Context",false,loader)).invoke(xml,context);
                parser.getMethod("doConfigure",java.io.File.class).invoke(xml,config.toFile());
            } else {
                for(String name:List.of("org.apache.logging.log4j.core.config.Configurator","org.apache.logging.log4j.Level","org.apache.logging.log4j.core.LoggerContext"))types.put(name,Class.forName(name,true,loader));
                context=types.get("org.apache.logging.log4j.core.config.Configurator").getMethod("initialize",String.class,ClassLoader.class,URI.class).invoke(null,"r27-"+System.identityHashCode(loader),loader,config.toUri());
            }
        }
        void reconfigure(String level)throws Exception {
            Files.writeString(config,Files.readString(config).replace("INFO",level));
            if(backend.equals("logback")) {
                for(String name:List.of("ch.qos.logback.classic.joran.JoranConfigurator","ch.qos.logback.core.Context"))types.put(name,Class.forName(name,true,loader));
                assertEquals("Logback",reloader().reconfigureFrom(config));
            } else {
                context.getClass().getMethod("setConfigLocation",URI.class).invoke(context,config.toUri());
            }
        }
        LoggingReloader reloader(){return new LoggingReloader(types);}
        Object logger(String name)throws Exception {
            if(backend.equals("log4j") && name.equals("ROOT"))return context.getClass().getMethod("getRootLogger").invoke(context);
            return context.getClass().getMethod("getLogger",String.class).invoke(context,name);
        }
        String level(String name)throws Exception {
            Object logger=logger(name);return String.valueOf(logger.getClass().getMethod(backend.equals("logback")?"getEffectiveLevel":"getLevel").invoke(logger));
        }
        boolean exact(String name)throws Exception {
            if(backend.equals("logback"))return logger(name).getClass().getMethod("getLevel").invoke(logger(name))!=null;
            Object config=context.getClass().getMethod("getConfiguration").invoke(context);return ((Map<?,?>)config.getClass().getMethod("getLoggers").invoke(config)).containsKey(name);
        }
        void nativeLevel(String name,String value)throws Exception {
            if(backend.equals("logback")) {
                Class<?> level=types.get("ch.qos.logback.classic.Level");Object parsed=level.getMethod("toLevel",String.class).invoke(null,value);
                Object logger=logger(name);logger.getClass().getMethod("setLevel",level).invoke(logger,parsed);
            } else {
                Class<?> level=types.get("org.apache.logging.log4j.Level"),entryType=Class.forName("org.apache.logging.log4j.core.config.LoggerConfig",false,loader);Object parsed=level.getMethod("valueOf",String.class).invoke(null,value);
                Object config=context.getClass().getMethod("getConfiguration").invoke(context);Map<?,?> entries=(Map<?,?>)config.getClass().getMethod("getLoggers").invoke(config);
                Object entry=name.equals("ROOT")?config.getClass().getMethod("getRootLogger").invoke(config):entries.get(name);
                if(entry==null){entry=entryType.getConstructor(String.class,level,boolean.class).newInstance(name,parsed,true);config.getClass().getMethod("addLogger",String.class,entryType).invoke(config,name,entry);}
                else entryType.getMethod("setLevel",level).invoke(entry,parsed);
                context.getClass().getMethod("updateLoggers").invoke(context);
            }
        }
        void nativeInherit(String name)throws Exception {
            if(backend.equals("logback")) {
                Object target=logger(name);target.getClass().getMethod("setLevel",types.get("ch.qos.logback.classic.Level")).invoke(target,(Object)null);
            } else {
                Object config=context.getClass().getMethod("getConfiguration").invoke(context);
                Object entry=((Map<?,?>)config.getClass().getMethod("getLoggers").invoke(config)).get(name);
                Class<?> entryType=Class.forName("org.apache.logging.log4j.core.config.LoggerConfig",false,loader);
                entryType.getMethod("setLevel",types.get("org.apache.logging.log4j.Level")).invoke(entry,(Object)null);
                context.getClass().getMethod("updateLoggers").invoke(context);
            }
        }
        public void close()throws Exception {
            try{context.getClass().getMethod("stop").invoke(context);}finally{Thread.currentThread().setContextClassLoader(before);loader.close();com.onurkat.reclazz.ui.RestartLedger.clear();}
        }
    }
}
