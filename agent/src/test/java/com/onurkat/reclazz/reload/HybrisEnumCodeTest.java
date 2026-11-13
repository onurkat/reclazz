/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HybrisEnumCodeTest {
    @TempDir Path dir;
    static final String CONTRACT = "package de.hybris.platform.core; public interface HybrisEnumValue { String getCode(); String getType(); }";
    static String source(String constants) {
        return "package app; public enum State implements de.hybris.platform.core.HybrisEnumValue { " + constants
            + "; private final String code; State(String code) { this.code=code.intern(); }"
            + " public String getCode() { return this.code; } public String getType() { return \"State\"; } }";
    }
    @Test void appendedNativeCodeIsInitialisedBeforeTheConstantIsPublished() throws Exception {
        try (var fixture = fixture(source("BASE(\"BASE\")"), source("BASE(\"BASE\"), NEXT(\"NEXT\")"))) {
            Class<?> type=fixture.type; Object base=type.getEnumConstants()[0];
            assertEquals("BASE",type.getMethod("getCode").invoke(base));
            assertTrue(apply(type,fixture.next));
            Object next=Enum.valueOf(type.asSubclass(Enum.class),"NEXT");
            assertEquals("NEXT",((Enum<?>)next).name());
            assertEquals("NEXT",type.getMethod("getCode").invoke(next),"Published SAP enum code must be initialised");
            assertSame(base,type.getEnumConstants()[0]);
            assertEquals(0,((Enum<?>)base).ordinal());
            assertEquals(1,((Enum<?>)next).ordinal());
        }
    }
    @Test void aSecondAppendPreservesBothExistingObjectsAndNativeCodes() throws Exception {
        try (var fixture=fixture(source("BASE(\"BASE\")"),source("BASE(\"BASE\"), NEXT(\"NEXT\")"))) {
            assertTrue(apply(fixture.type,fixture.next));
            Object[] before=fixture.type.getEnumConstants();
            Path third=dir.resolve("third");compile(third,"de.hybris.platform.core.HybrisEnumValue",CONTRACT);
            compile(third,"app.State",source("BASE(\"BASE\"), NEXT(\"NEXT\"), THIRD(\"THIRD\")"));
            assertTrue(apply(fixture.type,Files.readAllBytes(third.resolve("app/State.class"))));
            Object[] after=fixture.type.getEnumConstants();assertEquals(3,after.length);
            assertSame(before[0],after[0]);assertSame(before[1],after[1]);
            for (Object value:after) assertEquals(((Enum<?>)value).name(),fixture.type.getMethod("getCode").invoke(value));
        }
    }
    @Test void unsupportedNativeStateOrIncomingMappingIsRefusedBeforeAppend() throws Exception {
        String normal=source("BASE(\"BASE\"), NEXT(\"NEXT\")");
        List<String> incoming=List.of(
            normal.replace("private final String code;","private final Object code;").replace("return this.code;","return (String)this.code;"),
            normal.replace("private final String code;","").replace("this.code=code.intern();","").replace("return this.code;","return name();"),
            normal.replace("NEXT(\"NEXT\")","NEXT(\"OTHER\")"),
            normal.replace("private final String code;","private String code;"),
            normal.replace("private final String code;","private final String code; private int extra;"),
            normal.replace("code.intern()","code"),normal.replace("return this.code;","return name();"),
            normal.replace("private final String code;","private final String code; static { System.getProperty(\"probe\"); }"),
            normal.replace("private final String code;","private static final String code=\"shared\";").replace("this.code=code.intern();",""));
        for (String candidate:incoming) {
            try (var fixture=fixture(source("BASE(\"BASE\")"),candidate)) {
                Object base=fixture.type.getEnumConstants()[0];assertFalse(apply(fixture.type,fixture.next),candidate);
                assertEquals(1,fixture.type.getEnumConstants().length);assertSame(base,fixture.type.getEnumConstants()[0]);
                assertEquals("BASE",fixture.type.getMethod("getCode").invoke(base));
                assertThrows(IllegalArgumentException.class,()->Enum.valueOf(fixture.type.asSubclass(Enum.class),"NEXT"));
            }
        }
    }
    @Test void anExistingCustomCodeIsNotSilentlyReinterpretedAsItsName() throws Exception {
        try(var fixture=fixture(source("BASE(\"CUSTOM\")"),source("BASE(\"BASE\"), NEXT(\"NEXT\")"))) {
            assertFalse(apply(fixture.type,fixture.next));assertEquals(1,fixture.type.getEnumConstants().length);
            assertEquals("CUSTOM",fixture.type.getMethod("getCode").invoke(fixture.type.getEnumConstants()[0]));
        }
    }
    static boolean apply(Class<?> type, byte[] bytes) {
        // Exercise the real append path without installing java.util transforms into
        // the unit-test worker. The child-JVM regression exercises actual instrumentation.
        Instrumentation instrumentation=(Instrumentation)Proxy.newProxyInstance(HybrisEnumCodeTest.class.getClassLoader(),new Class<?>[]{Instrumentation.class},
            (proxy,method,args)->method.getName().equals("getAllLoadedClasses")?new Class<?>[0]:null);
        return EnumConstantAppender.applyOrExplain(type.getName(),type,bytes,EnumConstantChange.check(type,bytes),instrumentation);
    }
    record Fixture(URLClassLoader loader, Class<?> type, byte[] next) implements AutoCloseable {
        public void close() throws Exception {loader.close();}
    }
    Fixture fixture(String before,String after) throws Exception {
        Path old=dir.resolve("old"),next=dir.resolve("next");
        compile(old,"de.hybris.platform.core.HybrisEnumValue",CONTRACT);
        compile(old,"app.State",before);
        compile(next,"de.hybris.platform.core.HybrisEnumValue",CONTRACT);
        compile(next,"app.State",after);
        var loader=new URLClassLoader(new java.net.URL[]{old.toUri().toURL()},getClass().getClassLoader());
        return new Fixture(loader,Class.forName("app.State",true,loader),Files.readAllBytes(next.resolve("app/State.class")));
    }
    static void compile(Path out,String name,String source) throws Exception {
        Path file=out.resolveSibling(out.getFileName()+"-src").resolve(name.replace('.','/')+".java");
        Files.createDirectories(file.getParent());Files.createDirectories(out);Files.writeString(file,source);
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-cp",out.toString(),"-d",out.toString(),file.toString()));
    }
}
