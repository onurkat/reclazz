/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.bootstrap.RabbitConsumerBridge;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.util.CheckClassAdapter;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.BlockingQueueConsumer;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class RabbitConsumerTransformerTest {
    private static final String OUTER = "org/springframework/amqp/rabbit/listener/SimpleMessageListenerContainer";
    private static final String TARGET = RabbitConsumerTransformer.TARGET;
    @Test void queuedWorkerAndNormalOrExceptionalExitStayTracked() throws Exception {
        byte[] bytes = new RabbitConsumerTransformer().transform(getClass().getClassLoader(), TARGET, null, null, fixture());
        assertNotNull(bytes);
        Class<?> worker = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        var ctor = worker.getConstructor(SimpleMessageListenerContainer.class, BlockingQueueConsumer.class);
        for (Throwable failure : new Throwable[]{null, new IllegalStateException("failed"), new AssertionError("error")}) {
            var container = new SimpleMessageListenerContainer();
            Object task = ctor.newInstance(container, null);
            assertTrue(RabbitConsumerBridge.isHooked(worker));
            worker.getField("action").set(task, (Runnable) () -> {
                if (failure instanceof Error e) throw e;
                if (failure instanceof RuntimeException e) throw e;
            });
            RabbitConsumerBridge.retire(container);
            assertFalse(RabbitConsumerBridge.awaitStopped(container, System.nanoTime()), "a queued worker is unfinished");
            if (failure == null) worker.getMethod("run").invoke(task);
            else assertSame(failure, assertThrows(InvocationTargetException.class, () -> worker.getMethod("run").invoke(task)).getCause());
            assertTrue(RabbitConsumerBridge.awaitStopped(container, System.nanoTime()));
            assertInstanceOf(IllegalStateException.class, assertThrows(InvocationTargetException.class,
                    () -> ctor.newInstance(container, null)).getCause());
        }
    }
    @Test void exactSpringBytesVerifyAndUnknownShapesOrLateAttachAreRefused() throws Exception {
        var transformer = new RabbitConsumerTransformer();
        try (var in = getClass().getClassLoader().getResourceAsStream(TARGET + ".class")) {
            byte[] original = in.readAllBytes();
            byte[] transformed = transformer.transform(getClass().getClassLoader(), TARGET, null, null, original);
            assertNotNull(transformed);
            var diagnostics = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(transformed), getClass().getClassLoader(), false, new PrintWriter(diagnostics));
            assertEquals("", diagnostics.toString());
            assertNull(transformer.transform(getClass().getClassLoader(), TARGET, getClass(), null, original));
        }
        assertNull(transformer.transform(getClass().getClassLoader(), TARGET, null, null, new byte[]{0}));
        assertFalse(RabbitConsumerBridge.isHooked(getClass()));
    }
    private static byte[] fixture() {
        var w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        w.visitField(ACC_FINAL, "this$0", "L" + OUTER + ";", null, null).visitEnd();
        w.visitField(ACC_PUBLIC, "action", "Ljava/lang/Runnable;", null, null).visitEnd();
        var m = w.visitMethod(ACC_PUBLIC, "<init>", "(L" + OUTER + ";Lorg/springframework/amqp/rabbit/listener/BlockingQueueConsumer;)V", null, null);
        m.visitCode(); m.visitVarInsn(ALOAD, 0); m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitVarInsn(ALOAD, 0); m.visitVarInsn(ALOAD, 1); m.visitFieldInsn(PUTFIELD, TARGET, "this$0", "L" + OUTER + ";");
        m.visitInsn(RETURN); m.visitMaxs(0,0); m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC, "run", "()V", null, null); m.visitCode(); m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, TARGET, "action", "Ljava/lang/Runnable;");
        m.visitMethodInsn(INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
        m.visitInsn(RETURN); m.visitMaxs(0,0); m.visitEnd(); w.visitEnd(); return w.toByteArray();
    }
}
