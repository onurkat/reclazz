/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Re-reads what a controller advice contributes: its exception handlers, its
 * data-binder setup and its model attributes.
 *
 * <p>Spring works that out once, at startup, and keeps it: the advice beans
 * are scanned into a map when the resolver is initialised, and each
 * controller's own handlers are cached the first time an exception reaches it.
 * So adding {@code @ExceptionHandler} to a method that was already there
 * changes nothing, and the endpoint keeps answering with the framework's
 * default error page. Measured on Spring Boot 3.3.4, stock JDK 21:
 *
 * <pre>
 *   before                     GET /boom -> 500, the default error body
 *   add @ExceptionHandler      reload succeeds
 *   after                      GET /boom -> 500, the same body   (nothing said why)
 * </pre>
 *
 * <p>The advice map cannot simply be emptied: it is built once and never
 * lazily, so an empty one means no advice at all, which would turn a stale
 * handler into a missing one. Spring's own {@code initExceptionHandlerAdviceCache}
 * is what fills it, from the contexts it can already see, so that is what runs
 * here after the clear. The per-controller cache underneath it is lazy and is
 * emptied outright.
 *
 * <p>The same shape holds for the other two, on a different bean:
 * {@code RequestMappingHandlerAdapter} caches {@code @InitBinder} and
 * {@code @ModelAttribute} the same way, per controller and per advice, and
 * adding {@code @InitBinder} to a method that was already there was measured
 * to change nothing either. Its initialiser has one edge the exception one
 * does not: it PREPENDS to the request/response body advice list rather than
 * replacing it, so calling it twice would run a {@code ResponseBodyAdvice}
 * twice per response. That list is put back exactly as it was.
 *
 * <p>Reflective, no Spring dependency, and silent when it works: a handler
 * that now handles is what the developer just asked for.
 */
public class SpringControllerAdviceReloader {

    /**
     * What the container actually has a bean for.
     *
     * <p>Not the resolver itself: Boot registers a
     * {@code HandlerExceptionResolverComposite} and builds the individual
     * resolvers inside it, so asking for the concrete type answers nothing.
     * Measured, after asking for the concrete type and re-reading zero
     * resolvers on a server that plainly had one.
     */
    private static final String RESOLVER_BEAN =
            "org.springframework.web.servlet.HandlerExceptionResolver";

    /** The one inside the composite that owns the exception advice caches. */
    private static final String EXCEPTION_HANDLER_RESOLVER = "ExceptionHandlerExceptionResolver";

    /** The bean that owns the binder and model-attribute caches. */
    private static final String HANDLER_ADAPTER_BEAN = "org.springframework.web.servlet.HandlerAdapter";

    private static final String REQUEST_MAPPING_ADAPTER = "RequestMappingHandlerAdapter";

    /** Lazy per-controller caches: emptying them is the whole job. */
    private static final String[] LAZY_CACHES = {
            "exceptionHandlerCache", "initBinderCache", "modelAttributeCache",
    };

    /** Built once at startup, so they are cleared and then rebuilt. */
    private static final String[] ADVICE_CACHES = {
            "exceptionHandlerAdviceCache", "initBinderAdviceCache", "modelAttributeAdviceCache",
    };

    /** Spring's own filler for each of those, whichever the bean has. */
    private static final String[] INITIALISERS = {
            "initExceptionHandlerAdviceCache", "initControllerAdviceCache",
    };

    /** The list an initialiser prepends to instead of replacing. */
    private static final String PREPENDED_LIST = "requestResponseBodyAdvice";

    private final PlatformContext platformContext;

    public SpringControllerAdviceReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    /**
     * Whether this class is one an exception-handling change could be about.
     *
     * <p>The gate matters more here than anywhere else in this package,
     * because rebuilding is not a cache clear: Spring's initialiser re-scans
     * every advice bean in the context, and on a SAP Commerce server with
     * dozens of contexts that is real work. Measured without this gate, the
     * integration suite's reload batches went from about 1.5 seconds to 17.7,
     * and five scenarios failed on timing that had nothing to do with
     * exception handling.
     */
    public static boolean carriesAdvice(Class<?> clazz) {
        if (clazz == null) return false;
        try {
            for (var annotation : clazz.getAnnotations()) {
                String name = simpleName(annotation.annotationType().getName());
                if (name.equals("ControllerAdvice") || name.equals("RestControllerAdvice")) {
                    return true;
                }
            }
            for (Method method : clazz.getDeclaredMethods()) {
                for (var annotation : method.getAnnotations()) {
                    String name = simpleName(annotation.annotationType().getName());
                    if (name.equals("ExceptionHandler") || name.equals("InitBinder")
                            || name.equals("ModelAttribute")) {
                        return true;
                    }
                }
            }
        } catch (Throwable notReadable) {
            return false;
        }
        return false;
    }

    /** A nested annotation's name is separated by a dollar, not a dot. */
    private static String simpleName(String name) {
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }

    /**
     * @return how many resolvers were re-read
     */
    public int reload() {
        int reloaded = 0;
        for (Object appContext : platformContext.getAllApplicationContexts()) {
            for (String type : new String[] {RESOLVER_BEAN, HANDLER_ADAPTER_BEAN}) {
                for (String name : SpringBeans.beanNamesForType(appContext, type)) {
                    Object bean = SpringBeans.getBean(appContext, name);
                    if (bean == null) continue;
                    for (Object resolver : unwrap(bean)) {
                        if (rebuild(resolver)) reloaded++;
                    }
                }
            }
        }
        return reloaded;
    }

    /** The bean itself, or the resolvers a composite is holding. */
    static java.util.List<Object> unwrap(Object bean) {
        String beanType = bean.getClass().getName();
        if (beanType.endsWith(EXCEPTION_HANDLER_RESOLVER)
                || beanType.endsWith(REQUEST_MAPPING_ADAPTER)) {
            return java.util.List.of(bean);
        }
        java.util.List<Object> found = new java.util.ArrayList<>();
        for (Class<?> c = bean.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!java.util.List.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(bean);
                    if (!(value instanceof java.util.List<?> list)) continue;
                    for (Object element : list) {
                        if (element != null
                                && element.getClass().getName().endsWith(EXCEPTION_HANDLER_RESOLVER)) {
                            found.add(element);
                        }
                    }
                } catch (Throwable notReadable) {
                    // A composite that will not be read holds what it holds.
                }
            }
        }
        return found;
    }

    /** Empty what is lazy, rebuild what is not. */
    static boolean rebuild(Object resolver) {
        java.util.List<Map<?, ?>> adviceCaches = new java.util.ArrayList<>();
        for (String name : ADVICE_CACHES) {
            Map<?, ?> cache = mapField(resolver, name);
            if (cache != null) adviceCaches.add(cache);
        }
        Method init = null;
        for (String name : INITIALISERS) {
            init = com.onurkat.reclazz.util.Reflect.findMethod(resolver.getClass(), name);
            if (init != null) break;
        }

        // Located first, touched second: rebuilding an advice map without the
        // method that fills it would leave the application with no advice at
        // all, which is worse than the stale handler this is fixing.
        if (adviceCaches.isEmpty() || init == null) return false;

        try {
            for (String name : LAZY_CACHES) {
                Map<?, ?> lazy = mapField(resolver, name);
                if (lazy != null) lazy.clear();
            }
            for (Map<?, ?> cache : adviceCaches) {
                cache.clear();
            }

            // The adapter's initialiser prepends to this rather than replacing
            // it, so without the snapshot a ResponseBodyAdvice would run once
            // more per reload, for every response.
            Object prepended = readField(resolver, PREPENDED_LIST);
            java.util.List<Object> before = prepended instanceof java.util.List<?> list
                    ? new java.util.ArrayList<>(list) : null;

            init.invoke(resolver);

            if (before != null && prepended instanceof java.util.List<?>) {
                @SuppressWarnings("unchecked")
                java.util.List<Object> live = (java.util.List<Object>) prepended;
                live.clear();
                live.addAll(before);
            }
            return true;
        } catch (Throwable notThisShape) {
            return false;
        }
    }

    private static Object readField(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException keepWalking) {
                // the next class up may declare it
            } catch (Throwable notReadable) {
                return null;
            }
        }
        return null;
    }

    private static Map<?, ?> mapField(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                if (!Map.class.isAssignableFrom(field.getType())) return null;
                field.setAccessible(true);
                return (Map<?, ?>) field.get(target);
            } catch (NoSuchFieldException keepWalking) {
                // the next class up may declare it
            } catch (Throwable notReadable) {
                return null;
            }
        }
        return null;
    }
}
