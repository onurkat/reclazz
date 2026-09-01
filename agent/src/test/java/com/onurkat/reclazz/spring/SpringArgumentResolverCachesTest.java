/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring reads a handler parameter's name, default and required flag once and
 * caches them, keyed by the MethodParameter. Re-registering the mapping builds
 * fresh MethodParameter objects and does not help, because that key compares
 * the method and the index: a fresh one equals the old one and finds the stale
 * answer. Measured on Boot 3.3, changing a defaultValue changed nothing;
 * measured again after this, alpha to beta lands, and so does required false to
 * true, which turns a 200 into a 400.
 *
 * <p>The caches are not all on one object, which is what these tests hold: the
 * adapter holds composites, each composite holds resolvers, and the cache that
 * matters is on the resolvers.
 */
class SpringArgumentResolverCachesTest {

    /** AbstractNamedValueMethodArgumentResolver: the one that matters. */
    static class NamedValueResolver {
        private final Map<Object, Object> namedValueInfoCache = new HashMap<>();

        Map<Object, Object> cache() {
            return namedValueInfoCache;
        }
    }

    /** HandlerMethodArgumentResolverComposite: holds resolvers and its own cache. */
    static class Composite {
        private final List<Object> argumentResolvers = new ArrayList<>();
        private final Map<Object, Object> argumentResolverCache = new HashMap<>();

        Composite(Object... resolvers) {
            for (Object r : resolvers) {
                argumentResolvers.add(r);
            }
        }

        Map<Object, Object> cache() {
            return argumentResolverCache;
        }
    }

    /** RequestMappingHandlerAdapter: holds the composites. */
    static class Adapter {
        private final Composite argumentResolvers;
        private final Composite initBinderArgumentResolvers;

        Adapter(Composite arguments, Composite initBinder) {
            this.argumentResolvers = arguments;
            this.initBinderArgumentResolvers = initBinder;
        }
    }

    @Test
    void theCacheOnEveryResolverIsReached() {
        NamedValueResolver requestParam = new NamedValueResolver();
        NamedValueResolver pathVariable = new NamedValueResolver();
        requestParam.cache().put("v", "defaultValue=alpha");
        pathVariable.cache().put("id", "required=true");
        Composite arguments = new Composite(requestParam, pathVariable);
        arguments.cache().put("v", "resolver");

        assertEquals(3, SpringArgumentResolverCaches.clearOn(
                new Adapter(arguments, new Composite())),
                "two resolver caches and the composite's own");
        assertTrue(requestParam.cache().isEmpty());
        assertTrue(pathVariable.cache().isEmpty());
        assertTrue(arguments.cache().isEmpty());
    }

    /** The binder resolvers are a second composite and just as stale. */
    @Test
    void theInitBinderResolversAreReachedToo() {
        NamedValueResolver binderParam = new NamedValueResolver();
        binderParam.cache().put("v", "stale");

        assertEquals(1, SpringArgumentResolverCaches.clearOn(
                new Adapter(new Composite(), new Composite(binderParam))));
        assertTrue(binderParam.cache().isEmpty());
    }

    /** An empty cache is not work done, and counting it would say it was. */
    @Test
    void anEmptyCacheIsNotCounted() {
        assertEquals(0, SpringArgumentResolverCaches.clearOn(
                new Adapter(new Composite(new NamedValueResolver()), new Composite())));
    }

    /** A shape this does not know changes nothing and throws nothing. */
    @Test
    void anUnknownShapeIsNotAFailure() {
        assertEquals(0, SpringArgumentResolverCaches.clearOn(new Object()));
    }
}
