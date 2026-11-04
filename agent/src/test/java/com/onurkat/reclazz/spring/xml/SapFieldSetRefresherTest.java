/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SapFieldSetRefresherTest {
    @AfterEach void clear() { ApplicationContextHolder.clear(); }

    @Test void ordinaryObjectsCannotTriggerAnOccRefresh() {
        assertFalse(SapFieldSetRefresher.isMapping(null));
        assertFalse(SapFieldSetRefresher.isMapping(new Object()));
        // An empty mutation set must not inspect or invoke the context at all.
        assertTrue(SapFieldSetRefresher.refresh(new Object(), Set.of()).isEmpty());
    }

    @Test void aContextWithoutTheSapSdkDoesNotLoadOrChangeOtherBeans() {
        try (var context = new GenericApplicationContext()) {
            Object original = new Object();
            context.getBeanFactory().registerSingleton("original", original);
            context.refresh(); ApplicationContextHolder.register(context);
            assertTrue(SapFieldSetRefresher.refresh(context, Set.of(original)).isEmpty());
            assertSame(original, context.getBean("original"));
        }
    }

    /**
     * SAP Commerce makes web beans lazy, and a web application may define
     * mappings for DTOs it never loads. Refreshing one OCC context called
     * getBeansOfType in every other one, created those beans, and reported
     * each ClassNotFoundException as a restart.
     */
    @Test void aContextThatDoesNotHoldTheChangedMappingIsNotInstantiated(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("src/de/hybris/platform/webservicescommons/mapping/config/FieldSetLevelMapping.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package de.hybris.platform.webservicescommons.mapping.config;
                public class FieldSetLevelMapping {
                    private Class<?> dtoClass; private java.util.Map<String, String> levelMapping;
                    public Class<?> getDtoClass() { return dtoClass; }
                    public void setDtoClass(Class<?> value) { dtoClass = value; }
                    public java.util.Map<String, String> getLevelMapping() { return levelMapping; }
                    public void setLevelMapping(java.util.Map<String, String> value) { levelMapping = value; }
                }
                """);
        Path unloadable = source.resolveSibling("UnloadableMapping.java");
        Files.writeString(unloadable, """
                package de.hybris.platform.webservicescommons.mapping.config;
                public class UnloadableMapping extends FieldSetLevelMapping {
                    public static int created;
                    public UnloadableMapping() { created++; throw new IllegalStateException("ClassNotFoundException: SomeWsDTO"); }
                }
                """);
        Path helper = dir.resolve("src/de/hybris/platform/webservicescommons/mapping/FieldSetLevelHelper.java");
        Files.writeString(helper, "package de.hybris.platform.webservicescommons.mapping; public interface FieldSetLevelHelper { }");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none",
                "-d", dir.resolve("classes").toString(), source.toString(), unloadable.toString(), helper.toString()));
        try (var loader = new URLClassLoader(new java.net.URL[]{dir.resolve("classes").toUri().toURL()}, getClass().getClassLoader());
             var owner = new GenericApplicationContext(); var other = new GenericApplicationContext()) {
            Class<?> mappingType = loader.loadClass("de.hybris.platform.webservicescommons.mapping.config.FieldSetLevelMapping");
            Object changed = mappingType.getConstructor().newInstance();
            owner.setClassLoader(loader);
            owner.getBeanFactory().registerSingleton("probeMapping", changed);
            owner.refresh();
            other.setClassLoader(loader);
            var lazy = new RootBeanDefinition(loader.loadClass(mappingType.getPackageName() + ".UnloadableMapping"));
            lazy.setLazyInit(true);
            other.registerBeanDefinition("previewMapping", lazy);
            other.refresh();
            ApplicationContextHolder.register(owner);
            ApplicationContextHolder.register(other);

            var failures = SapFieldSetRefresher.refresh(owner, Set.of(changed));

            assertTrue(failures.isEmpty(), failures.toString());
            assertEquals(0, loader.loadClass(mappingType.getPackageName() + ".UnloadableMapping").getField("created").getInt(null));
        }
    }

    @Test void aContextInspectionFailureIsReportedRatherThanSwallowed() {
        var failures = SapFieldSetRefresher.refresh(new Object(), Set.of(new Object()));
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).startsWith("OCC field selection refresh failed:"));
    }
}
