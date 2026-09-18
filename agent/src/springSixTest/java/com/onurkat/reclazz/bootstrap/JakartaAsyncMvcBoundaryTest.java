/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.*;
import jakarta.servlet.AsyncEvent;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static com.onurkat.reclazz.bootstrap.AsyncRequestGateTest.assertReloadable;

class JakartaAsyncMvcBoundaryTest {
    @Test void resultAndRedispatchDoNotImpersonateNativeCompletion() throws Exception {
        var f = new Fixture(); var frame = f.enter(); var result = new DeferredResult<String>();
        f.manager.startDeferredResultProcessing(result); AsyncMvcBoundary.exit(frame);
        assertFalse(f.gate.tryBeginReload(1));
        assertTrue(result.setResult("done")); assertEquals("done", f.manager.getConcurrentResult());
        f.request.setAsyncStarted(false); // A servlet redispatch is no longer in its initial async dispatch.
        var redispatch = f.enter(); AsyncMvcBoundary.exit(redispatch);
        assertFalse(f.gate.tryBeginReload(1));
        f.context().complete(); f.context().complete();
        assertReloadable(f.gate);
    }

    @Test void completionBeforeInitialDispatchExitIsSafe() throws Exception {
        var f = new Fixture(); var frame = f.enter();
        var result = new DeferredResult<String>(); result.setResult("instant");
        f.manager.startDeferredResultProcessing(result); f.context().complete();
        assertEquals("instant", f.manager.getConcurrentResult());
        AsyncMvcBoundary.exit(frame); assertReloadable(f.gate);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void nativeTimeoutAndErrorResultsStillRequireCompletion(boolean error) throws Exception {
        var f = new Fixture(); var frame = f.enter();
        f.manager.startDeferredResultProcessing(new DeferredResult<>()); AsyncMvcBoundary.exit(frame);
        var cause = new IllegalStateException("native failure");
        var event = new AsyncEvent(f.context(), f.request, f.response, cause);
        for (var listener : f.context().getListeners()) {
            if (error) listener.onError(event); else listener.onTimeout(event);
        }
        if (error) assertSame(cause, f.manager.getConcurrentResult());
        else assertInstanceOf(AsyncRequestTimeoutException.class, f.manager.getConcurrentResult());
        assertFalse(f.gate.tryBeginReload(1));
        f.context().complete(); assertReloadable(f.gate);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failureBeforeNativeAsyncStartsReleasesOnDispatchExit(boolean callbackFailure) throws Exception {
        var f = new Fixture(); var frame = f.enter();
        if (callbackFailure) f.manager.registerDeferredResultInterceptor("failing", new DeferredResultProcessingInterceptor() {
            @Override public <T> void beforeConcurrentHandling(NativeWebRequest r, DeferredResult<T> result) {
                throw new IllegalArgumentException("beforeConcurrentHandling");
            }
        });
        else f.request.setAsyncSupported(false);
        try { assertThrows(RuntimeException.class, () -> f.manager.startDeferredResultProcessing(new DeferredResult<>())); }
        finally { AsyncMvcBoundary.exit(frame); }
        assertFalse(f.request.isAsyncStarted()); assertReloadable(f.gate);
    }

    @Test void earlierDeferredPreProcessFailureCannotSkipLeaseCompletion() throws Exception {
        var f = new Fixture(); var cause = new IllegalArgumentException("preProcess");
        f.manager.registerDeferredResultInterceptor("earlier", new DeferredResultProcessingInterceptor() {
            @Override public <T> void preProcess(NativeWebRequest r, DeferredResult<T> result) { throw cause; }
        });
        var frame = f.enter(); f.manager.startDeferredResultProcessing(new DeferredResult<>());
        AsyncMvcBoundary.exit(frame);
        assertSame(cause, f.manager.getConcurrentResult()); assertFalse(f.gate.tryBeginReload(1));
        f.context().complete(); assertReloadable(f.gate);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void completedCallableRemainsCountedUntilItsIgnoringWorkerActuallyExits(boolean error) throws Exception {
        var f = new Fixture(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            f.manager.setTaskExecutor(new TaskExecutorAdapter(executor));
            var frame = f.enter();
            f.manager.startCallableProcessing(() -> {
                entered.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException e) { interrupted.countDown(); }
                }
                return "worker result";
            });
            AsyncMvcBoundary.exit(frame);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var cause = new IllegalStateException("native callable error");
                var event = new AsyncEvent(f.context(), f.request, f.response, cause);
                for (var listener : f.context().getListeners()) {
                    if (error) listener.onError(event); else listener.onTimeout(event);
                }
                assertTrue(interrupted.await(2, TimeUnit.SECONDS));
                if (error) assertSame(cause, f.manager.getConcurrentResult());
                else assertInstanceOf(AsyncRequestTimeoutException.class, f.manager.getConcurrentResult());
                f.context().complete();
                assertFalse(f.gate.tryBeginReload(1), "native completion must not release the running body");
            } finally { release.countDown(); }
            // The same worker must be reusable with no stale ThreadLocal depth.
            executor.submit(() -> { f.gate.enter(); f.gate.exit(); }).get(2, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertReloadable(f.gate);
    }

    @Test void throwingLaterCallablePostProcessDoesNotPreventWorkerRelease() throws Exception {
        var f = new Fixture(); var cause = new IllegalStateException("postProcess");
        var executor = Executors.newSingleThreadExecutor();
        try {
            f.manager.setTaskExecutor(new TaskExecutorAdapter(executor));
            var frame = f.enter();
            f.manager.registerCallableInterceptor("later", new CallableProcessingInterceptor() {
                @Override public <T> void postProcess(NativeWebRequest r, Callable<T> task, Object result) { throw cause; }
            });
            f.manager.startCallableProcessing(() -> "done"); AsyncMvcBoundary.exit(frame);
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertSame(cause, f.manager.getConcurrentResult());
            f.context().complete(); assertReloadable(f.gate);
        } finally { executor.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectedOrCancelledBeforeStartCallableDoesNotLeakWorkerAccounting(boolean reject) throws Exception {
        var f = new Fixture(); var pending = new AtomicReference<Runnable>(); var called = new AtomicBoolean();
        f.manager.setTaskExecutor(new TaskExecutorAdapter(task -> {
            if (reject) throw new RejectedExecutionException("test rejection");
            pending.set(task);
        }));
        var frame = f.enter(); f.manager.startCallableProcessing(() -> { called.set(true); return "done"; });
        AsyncMvcBoundary.exit(frame);
        if (reject) assertInstanceOf(org.springframework.core.task.TaskRejectedException.class, f.manager.getConcurrentResult());
        else {
            for (var listener : f.context().getListeners()) listener.onTimeout(new AsyncEvent(f.context()));
            assertInstanceOf(AsyncRequestTimeoutException.class, f.manager.getConcurrentResult());
        }
        f.context().complete();
        if (!reject) pending.get().run();
        assertFalse(called.get()); assertReloadable(f.gate);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void callablePreProcessFailureBeforeOrAfterOurHookCannotLeakWorker(boolean earlier) throws Exception {
        var f = new Fixture(); var cause = new IllegalArgumentException("callable preProcess");
        var interceptor = new CallableProcessingInterceptor() {
            @Override public <T> void preProcess(NativeWebRequest r, Callable<T> task) { throw cause; }
        };
        if (earlier) f.manager.registerCallableInterceptor("failure", interceptor);
        var frame = f.enter();
        if (!earlier) f.manager.registerCallableInterceptor("failure", interceptor);
        var executor = Executors.newSingleThreadExecutor();
        try {
            f.manager.setTaskExecutor(new TaskExecutorAdapter(executor));
            f.manager.startCallableProcessing(() -> { fail("preProcess must prevent the body"); return null; });
            AsyncMvcBoundary.exit(frame);
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertSame(cause, f.manager.getConcurrentResult());
            f.context().complete(); assertReloadable(f.gate);
        } finally { executor.shutdownNow(); }
    }

    @Test void missingRequestApiKeepsReloadDisabledInsteadOfSilentlyDroppingProtection() throws Exception {
        var f = new Fixture();
        var frame = AsyncMvcBoundary.enter(new Object(), getClass().getClassLoader(), f.gate, "jakarta");
        AsyncMvcBoundary.exit(frame);
        assertFalse(f.gate.tryBeginReload(1));
        assertTrue(f.gate.waitingFor().startsWith("Spring MVC async request hook failed:"));
    }

    @Test void anotherVisibleServletNamespaceCannotOverrideTheFrameworkSignature() throws Exception {
        var writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_INTERFACE | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
                "javax/servlet/ServletRequest", null, "java/lang/Object", null);
        writer.visitEnd();
        byte[] otherApi = writer.toByteArray();
        ClassLoader both = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals("javax.servlet.ServletRequest")) return defineClass(name, otherApi, 0, otherApi.length);
                throw new ClassNotFoundException(name);
            }
        };
        assertSame(both, Class.forName("javax.servlet.ServletRequest", false, both).getClassLoader());
        assertSame(jakarta.servlet.ServletRequest.class, Class.forName("jakarta.servlet.ServletRequest", false, both));
        var f = new Fixture();
        var frame = AsyncMvcBoundary.enter(f.request, both, f.gate, "jakarta");
        f.manager.startDeferredResultProcessing(new DeferredResult<>()); AsyncMvcBoundary.exit(frame);
        assertFalse(f.gate.tryBeginReload(1));
        f.context().complete(); assertReloadable(f.gate);
    }

    private static final class Fixture {
        final RequestGate gate = new RequestGate();
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final WebAsyncManager manager = WebAsyncUtils.getAsyncManager(request);
        Fixture() {
            gate.installed(); request.setAsyncSupported(true);
            manager.setAsyncWebRequest(new StandardServletAsyncWebRequest(request, response));
        }
        Object enter() { return AsyncMvcBoundary.enter(request, getClass().getClassLoader(), gate, "jakarta"); }
        MockAsyncContext context() { return (MockAsyncContext) request.getAsyncContext(); }
    }
}
