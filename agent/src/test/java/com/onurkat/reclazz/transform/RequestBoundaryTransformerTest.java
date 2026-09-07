/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.bootstrap.RequestGate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class RequestBoundaryTransformerTest {
    @ParameterizedTest
    @ValueSource(strings = {"javax", "jakarta"})
    void normalCaughtAndUncaughtExitsReleaseTheBoundary(String api) throws Exception {
        String request = api + "/servlet/http/HttpServletRequest";
        String response = api + "/servlet/http/HttpServletResponse";
        String target = RequestBoundaryTransformer.TARGET;
        byte[] transformed = new RequestBoundaryTransformer().transform(getClass().getClassLoader(),
                target, null, null, servlet(target, request, response));
        assertNotNull(transformed);
        Map<String, byte[]> definitions = Map.of(
                target.replace('/', '.'), transformed,
                request.replace('/', '.'), apiInterface(request, true),
                response.replace('/', '.'), apiInterface(response, false));
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    byte[] bytes = definitions.get(name);
                    if (bytes == null) return super.loadClass(name, resolve);
                    Class<?> type = findLoadedClass(name);
                    if (type == null) type = defineClass(name, bytes, 0, bytes.length);
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        };
        Class<?> servlet = loader.loadClass(target.replace('/', '.'));
        Class<?> reqType = loader.loadClass(request.replace('/', '.'));
        Object instance = servlet.getConstructor().newInstance();
        var process = servlet.getMethod("processRequest", reqType, loader.loadClass(response.replace('/', '.')));
        for (Throwable failure : new Throwable[] { null, new IllegalArgumentException("caught"),
                new IllegalStateException("implicit throw"), new AssertionError("error") }) {
            Object req = Proxy.newProxyInstance(loader, new Class<?>[] { reqType }, (proxy, method, args) -> {
                if (failure != null) throw failure;
                return "GET";
            });
            if (failure == null || failure instanceof IllegalArgumentException) process.invoke(instance, req, null);
            else assertSame(failure, assertThrows(InvocationTargetException.class,
                    () -> process.invoke(instance, req, null)).getCause());
            assertTrue(RequestGate.global().tryBeginReload(10), "dispatch leaked its active request");
            RequestGate.global().endReload();
        }
    }

    private static byte[] apiInterface(String name, boolean request) {
        ClassWriter w = new ClassWriter(0);
        w.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, name, null, "java/lang/Object", null);
        if (request) w.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getMethod", "()Ljava/lang/String;", null, null).visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static byte[] servlet(String name, String request, String response) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor init = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN); init.visitMaxs(0, 0); init.visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, "processRequest",
                "(L" + request + ";L" + response + ";)V", null, null);
        m.visitCode();
        Label start = new Label(), end = new Label(), caught = new Label();
        m.visitTryCatchBlock(start, end, caught, "java/lang/IllegalArgumentException");
        m.visitLabel(start); m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEINTERFACE, request, "getMethod", "()Ljava/lang/String;", true);
        m.visitInsn(POP); m.visitLabel(end); m.visitInsn(RETURN);
        m.visitLabel(caught); m.visitInsn(POP); m.visitInsn(RETURN);
        m.visitMaxs(0, 0); m.visitEnd(); w.visitEnd();
        return w.toByteArray();
    }
}
