/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package org.springframework.security.authorization.method;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Spring Security's own shapes, under their own package, because the package
 * is what the refresh trusts.
 *
 * <p>The walk that clears method-security metadata reads and clears nothing
 * outside {@code org.springframework.security}, so a fake built anywhere else
 * would be rejected before it proved anything. These are the classes and field
 * names taken off a live Boot 3.3 / Security 6.3 run and off Security 5's
 * metadata source; Spring Security itself is deliberately not a test
 * dependency, so there is no name to collide with.
 */
public final class MethodSecurityShapes {

    private MethodSecurityShapes() {
    }

    /** Security 6: the registry that caches the parsed expression. */
    public static class ExpressionAttributeRegistry {
        private final Map<Object, Object> cachedAttributes = new ConcurrentHashMap<>();

        public Map<Object, Object> cache() {
            return cachedAttributes;
        }
    }

    /** Security 6: the manager holding that registry. */
    public static class PreAuthorizeManager {
        @SuppressWarnings("unused")
        private final ExpressionAttributeRegistry registry = new ExpressionAttributeRegistry();

        public ExpressionAttributeRegistry registry() {
            return registry;
        }
    }

    /** Security 6: the interceptor holding that manager. */
    public static class BeforeMethodInterceptor {
        @SuppressWarnings("unused")
        private final PreAuthorizeManager authorizationManager = new PreAuthorizeManager();

        public PreAuthorizeManager manager() {
            return authorizationManager;
        }
    }

    /**
     * Security 6.3: what the bean actually is. The interceptor is behind a
     * supplier, which is the level a named-field walk missed.
     */
    public static class DeferringMethodInterceptor {
        private final BeforeMethodInterceptor real = new BeforeMethodInterceptor();
        @SuppressWarnings("unused")
        private final Supplier<BeforeMethodInterceptor> delegate = () -> real;

        public BeforeMethodInterceptor real() {
            return real;
        }
    }

    /**
     * Security 6 again, this time answering the question the refill asks.
     *
     * <p>An expression registry fills its own map, so the refill only has to
     * ask it with a fresh {@code Method}. The value here records which
     * {@code Method} object did the asking, which is the whole point: the
     * stale one the proxy carries reads the annotation it read at startup.
     */
    public static class AskableRegistry {
        private final Map<Object, Object> cachedAttributes = new ConcurrentHashMap<>();

        Object getAttribute(java.lang.reflect.Method method, Class<?> targetClass) {
            Object attribute = "fresh:" + method.getName();
            cachedAttributes.put(method.getName(), attribute);
            return attribute;
        }

        public Map<Object, Object> cache() {
            return cachedAttributes;
        }
    }

    /**
     * {@code @Secured}: resolves without caching, so the refill has to store
     * the answer itself, under the key the framework would have used.
     */
    public static class SecuredManager {
        private final Map<Object, Object> cachedAuthorities = new ConcurrentHashMap<>();

        private java.util.Set<String> resolveAuthorities(java.lang.reflect.Method method,
                                                         Class<?> targetClass) {
            if (method.getName().equals("unannotated")) return null;
            return java.util.Set.of("ROLE_" + method.getName());
        }

        public Map<Object, Object> cache() {
            return cachedAuthorities;
        }
    }

    /** Security 5: the source caches the attributes itself. */
    public static class DelegatingMethodSecurityMetadataSource {
        private final Map<Object, Object> attributeCache = new HashMap<>();

        public Map<Object, Object> cache() {
            return attributeCache;
        }
    }

    /** Two objects holding each other: the walk must not follow them forever. */
    public static class Cycle {
        @SuppressWarnings("unused")
        private Cycle other;
        private final Map<Object, Object> cachedAttributes = new HashMap<>();

        public static Cycle pair() {
            Cycle one = new Cycle();
            Cycle two = new Cycle();
            one.other = two;
            two.other = one;
            return one;
        }

        public Map<Object, Object> cache() {
            return cachedAttributes;
        }
    }

    /** A supplier that throws: a decline, never a failed reload. */
    public static class UnreadyInterceptor {
        @SuppressWarnings("unused")
        private final Supplier<Object> delegate = () -> {
            throw new IllegalStateException("not built yet");
        };
    }
}
