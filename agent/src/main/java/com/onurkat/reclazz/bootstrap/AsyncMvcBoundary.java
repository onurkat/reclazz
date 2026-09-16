/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Request-owned Spring async callbacks. No application classes are cached globally. */
public final class AsyncMvcBoundary {
    private static final String KEY = "com.onurkat.reclazz.__reclazz$asyncBoundary";
    private static final String ASYNC = "org.springframework.web.context.request.async.";
    private AsyncMvcBoundary() { }

    public static Object enter(Object request, Class<?> frameworkServlet, String namespace) {
        return enter(request, frameworkServlet.getClassLoader(), RequestGate.global(), namespace);
    }

    static Object enter(Object request, ClassLoader loader, RequestGate gate, String namespace) {
        Frame frame = new Frame(gate);
        try {
            Class<?> api = Class.forName(namespace + ".servlet.ServletRequest", false, loader);
            Object existing = api.getMethod("getAttribute", String.class).invoke(request, KEY);
            Token token = existing == null ? new Token(gate) : (Token) existing;
            gate.enter(token.lease);
            frame.entered = true;
            frame.token = token;
            frame.resumed = token.lease.held();
            frame.request = request;
            frame.asyncStarted = api.getMethod("isAsyncStarted");
            if (existing == null) {
                api.getMethod("setAttribute", String.class, Object.class).invoke(request, KEY, token);
                Object manager = Class.forName(ASYNC + "WebAsyncUtils", false, loader)
                        .getMethod("getAsyncManager", api).invoke(null, request);
                register(manager, token, loader, true);
                register(manager, token, loader, false);
            }
        } catch (Throwable failure) {
            failed(gate, failure);
            if (!frame.entered) { gate.enter(); frame.entered = true; }
        }
        return frame;
    }

    public static void exit(Object handle) {
        Frame frame = (Frame) handle;
        try {
            // No container completion event follows failure before startAsync.
            // A redispatch, however, still belongs to the pending native completion.
            if (frame.token != null && !frame.resumed && frame.token.lease.held()
                    && !Boolean.TRUE.equals(frame.asyncStarted.invoke(frame.request)))
                frame.token.lease.close();
        } catch (Throwable failure) { failed(frame.gate, failure); }
        finally { if (frame.entered) frame.gate.exit(); }
    }

    private static void register(Object manager, Token token, ClassLoader loader, boolean callable) throws Exception {
        String name = callable ? "Callable" : "DeferredResult";
        Class<?> type = Class.forName(ASYNC + name + "ProcessingInterceptor", false, loader);
        Object noResult = callable ? type.getField("RESULT_NONE").get(null) : Boolean.TRUE;
        Method completion = Class.forName(ASYNC + "AsyncWebRequest", false, loader)
                .getMethod("addCompletionHandler", Runnable.class);
        // Completion can run on a different thread. Only the worker's own
        // postProcess may release its ThreadLocal dispatch depth.
        ThreadLocal<Boolean> worker = new ThreadLocal<>();
        Object interceptor = Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "toString": return "Reclazz async request boundary";
                case "beforeConcurrentHandling":
                    try {
                        // Independent of the interceptor chain's preProcess index:
                        // even an earlier DeferredResult preProcess failure must close us.
                        completion.invoke(args[0], (Runnable) token.lease::close);
                        token.lease.start();
                    } catch (Throwable failure) { failed(token.gate, failure); }
                    return null;
                case "preProcess":
                    if (callable) { token.gate.enter(token.lease); worker.set(Boolean.TRUE); }
                    return null;
                case "postProcess":
                    if (callable && worker.get() != null) { worker.remove(); token.gate.exit(); }
                    return null;
                case "handleTimeout", "handleError": return noResult;
                default: return null; // Native completion handler, not afterCompletion, owns the lease.
            }
        });
        manager.getClass().getMethod("register" + name + "Interceptor", Object.class, type)
                .invoke(manager, KEY, interceptor);
    }

    private static void failed(RequestGate gate, Throwable failure) {
        gate.unavailable("Spring MVC async request hook failed: " + failure.getClass().getSimpleName());
    }
    private static final class Token {
        final RequestGate gate;
        final RequestGate.Lease lease;
        Token(RequestGate gate) { this.gate = gate; this.lease = gate.lease(); }
    }
    private static final class Frame {
        final RequestGate gate;
        Token token;
        Object request;
        Method asyncStarted;
        boolean entered, resumed;
        Frame(RequestGate gate) { this.gate = gate; }
    }
}
