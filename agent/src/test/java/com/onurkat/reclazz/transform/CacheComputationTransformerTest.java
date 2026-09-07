/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.bootstrap.CacheDependencyLedger;
import com.onurkat.reclazz.bootstrap.CacheLedgerFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.util.CheckClassAdapter;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.interceptor.CacheOperationInvoker;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class CacheComputationTransformerTest {
    private static final String EXECUTE = "(Lorg/springframework/cache/interceptor/CacheOperationInvoker;Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String CACHES = "(Lorg/springframework/cache/interceptor/CacheOperationInvocationContext;Lorg/springframework/cache/interceptor/CacheResolver;)Ljava/util/Collection;";
    static class Helper { }
    static class After { }
    @BeforeEach void reset() { CacheLedgerFixture.reset(); CacheDependencyLedger.configure(true); }
    @AfterEach void cleanup() { CacheLedgerFixture.reset(); }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void normalCaughtAndUncaughtExitsPreserveResultsEvenWhenClearFails(boolean failingClear) throws Exception {
        byte[] bytes = new CacheComputationTransformer().transform(getClass().getClassLoader(),
                CacheComputationTransformer.TARGET, null, null, fixture());
        assertNotNull(bytes);
        Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        var execute = type.getMethod("execute", CacheOperationInvoker.class, Object.class, java.lang.reflect.Method.class, Object[].class);
        var cache = new ConcurrentMapCache("prices") {
            @Override public void clear() { if (failingClear) throw new AssertionError("clear failed"); super.clear(); }
        };
        Object target = type.getConstructor().newInstance(); type.getField("caches").set(target, List.of(cache));
        for (Throwable failure : new Throwable[]{null, new IllegalArgumentException("caught"), new IllegalStateException("uncaught"), new AssertionError("error")}) {
            CacheOperationInvoker invoker = () -> {
                CacheDependencyLedger.hit(Helper.class);
                CacheDependencyLedger.beginMutation(); CacheDependencyLedger.endMutation();
                if (failure instanceof Error e) throw e;
                if (failure instanceof RuntimeException e) throw e;
                return "result";
            };
            if (failure == null) assertEquals("result", execute.invoke(target, invoker, null, null, null));
            else if (failure instanceof IllegalArgumentException) assertEquals("caught", execute.invoke(target, invoker, null, null, null));
            else assertSame(failure, assertThrows(InvocationTargetException.class,
                        () -> execute.invoke(target, invoker, null, null, null)).getCause());
            assertEquals(List.of(cache), CacheDependencyLedger.cachesDependingOn(Helper.class));
            CacheDependencyLedger.hit(After.class);
            assertTrue(CacheDependencyLedger.cachesDependingOn(After.class).isEmpty());
        }
    }
    @Test void realSpringBytesVerify() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CacheComputationTransformer.TARGET + ".class")) {
            byte[] transformed = new CacheComputationTransformer().transform(getClass().getClassLoader(), CacheComputationTransformer.TARGET, null, null, in.readAllBytes());
            assertNotNull(transformed);
            StringWriter diagnostics = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(transformed), getClass().getClassLoader(), false, new PrintWriter(diagnostics));
            assertEquals("", diagnostics.toString());
        }
    }
    @Test void missingSignatureAndRetransformMarkPartialWithoutDroppingHistory() {
        var cache = new ConcurrentMapCache("prices");
        CacheDependencyLedger.open(); CacheDependencyLedger.cachesInUse(List.of(cache)); CacheDependencyLedger.hit(Helper.class); CacheDependencyLedger.close();
        assertNull(new CacheComputationTransformer().transform(getClass().getClassLoader(), CacheComputationTransformer.TARGET, null, null, new byte[]{0}));
        assertFalse(CacheDependencyLedger.completeCoverage()); assertEquals(List.of(cache), CacheDependencyLedger.cachesDependingOn(Helper.class));
        CacheDependencyLedger.configure(true);
        assertNotNull(new CacheComputationTransformer().transform(getClass().getClassLoader(), CacheComputationTransformer.TARGET, getClass(), null, fixture()));
        assertFalse(CacheDependencyLedger.completeCoverage());
    }
    private static byte[] fixture() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        String name = "test/CacheAspectFixture";
        w.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        w.visitField(ACC_PUBLIC, "caches", "Ljava/util/Collection;", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode(); m.visitVarInsn(ALOAD, 0); m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN); m.visitMaxs(0,0); m.visitEnd();
        m = w.visitMethod(ACC_PROTECTED, "getCaches", CACHES, null, null);
        m.visitCode(); m.visitVarInsn(ALOAD,0); m.visitFieldInsn(GETFIELD,name,"caches","Ljava/util/Collection;"); m.visitInsn(ARETURN); m.visitMaxs(0,0); m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC, "execute", EXECUTE, null, null);
        m.visitCode(); Label start = new Label(), end = new Label(), caught = new Label();
        m.visitTryCatchBlock(start,end,caught,"java/lang/IllegalArgumentException"); m.visitLabel(start);
        m.visitVarInsn(ALOAD,0); m.visitInsn(ACONST_NULL); m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKEVIRTUAL,name,"getCaches",CACHES,false); m.visitInsn(POP);
        m.visitVarInsn(ALOAD,1); m.visitMethodInsn(INVOKEINTERFACE,"org/springframework/cache/interceptor/CacheOperationInvoker","invoke","()Ljava/lang/Object;",true);
        m.visitLabel(end); m.visitInsn(ARETURN); m.visitLabel(caught); m.visitInsn(POP); m.visitLdcInsn("caught"); m.visitInsn(ARETURN);
        m.visitMaxs(0,0); m.visitEnd(); w.visitEnd(); return w.toByteArray();
    }
}
