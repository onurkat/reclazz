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
 * Spring scans the controller advices once, at startup, and caches what each
 * one contributes, so adding {@code @ExceptionHandler} or {@code @InitBinder}
 * to a method that was already there reached nothing: measured on Boot 3.3,
 * the endpoint kept answering the default error body and the binder kept not
 * running. Measured again after this: the handler answers, and the binder
 * disallows the field it was told to.
 *
 * <p>Two things had to be right and neither was obvious. The advice map is
 * built once and never lazily, so emptying it alone would leave the
 * application with no advice at all, which is worse than a stale handler;
 * Spring's own initialiser is what refills it, and nothing is touched unless
 * that initialiser was found first. And the resolver is not a bean: Boot
 * registers a composite and builds the resolvers inside it, so the lookup has
 * to reach through the list the composite holds.
 */
class SpringControllerAdviceReloaderTest {

    /** ExceptionHandlerExceptionResolver: two maps and the initialiser. */
    static class ExceptionHandlerExceptionResolver {
        private final Map<Object, Object> exceptionHandlerAdviceCache = new HashMap<>();
        private final Map<Object, Object> exceptionHandlerCache = new HashMap<>();
        int initCalls;

        @SuppressWarnings("unused")
        private void initExceptionHandlerAdviceCache() {
            initCalls++;
            exceptionHandlerAdviceCache.put("advice", "rebuilt");
        }

        Map<Object, Object> advice() {
            return exceptionHandlerAdviceCache;
        }

        Map<Object, Object> perController() {
            return exceptionHandlerCache;
        }
    }

    /** The same maps with no way to refill them: touching these is the danger. */
    static class ResolverWithoutInitialiser {
        private final Map<Object, Object> exceptionHandlerAdviceCache = new HashMap<>();

        Map<Object, Object> advice() {
            return exceptionHandlerAdviceCache;
        }
    }

    /** HandlerExceptionResolverComposite: the resolvers are in a list. */
    static class Composite {
        @SuppressWarnings("unused")
        private final List<Object> resolvers = new ArrayList<>();

        Composite(Object... elements) {
            for (Object element : elements) {
                resolvers.add(element);
            }
        }
    }

    @Test
    void theAdviceMapIsRebuiltRatherThanLeftEmpty() {
        ExceptionHandlerExceptionResolver resolver = new ExceptionHandlerExceptionResolver();
        resolver.advice().put("advice", "stale");
        resolver.perController().put(String.class, "stale");

        assertTrue(SpringControllerAdviceReloader.rebuild(resolver));
        assertEquals(1, resolver.initCalls, "Spring's own initialiser is what refills it");
        assertEquals("rebuilt", resolver.advice().get("advice"));
        assertTrue(resolver.perController().isEmpty(), "the lazy one is emptied outright");
    }

    /**
     * Without the initialiser, clearing would turn a stale handler into no
     * handler at all. Nothing is touched.
     */
    @Test
    void aResolverThatCannotBeRefilledIsLeftAlone() {
        ResolverWithoutInitialiser resolver = new ResolverWithoutInitialiser();
        resolver.advice().put("advice", "stale");

        assertFalse(SpringControllerAdviceReloader.rebuild(resolver));
        assertEquals("stale", resolver.advice().get("advice"),
                "an application with stale advice beats one with none");
    }

    @Test
    void theResolverIsFoundInsideTheComposite() {
        ExceptionHandlerExceptionResolver resolver = new ExceptionHandlerExceptionResolver();

        List<Object> found = SpringControllerAdviceReloader.unwrap(
                new Composite("something else", resolver));

        assertEquals(List.of(resolver), found,
                "Boot has no bean for the resolver itself, only for the composite");
    }

    @Test
    void aResolverThatIsItsOwnBeanIsUsedDirectly() {
        ExceptionHandlerExceptionResolver resolver = new ExceptionHandlerExceptionResolver();

        assertEquals(List.of(resolver), SpringControllerAdviceReloader.unwrap(resolver));
    }

    @Test
    void aCompositeHoldingNothingRelevantAnswersNothing() {
        assertTrue(SpringControllerAdviceReloader.unwrap(new Composite("a", "b")).isEmpty());
    }

    /**
     * RequestMappingHandlerAdapter's shape, including the one that bites: its
     * initialiser PREPENDS to the body-advice list instead of replacing it, so
     * a second call would run a ResponseBodyAdvice twice for every response.
     */
    static class RequestMappingHandlerAdapter {
        private final Map<Object, Object> initBinderAdviceCache = new HashMap<>();
        private final Map<Object, Object> modelAttributeAdviceCache = new HashMap<>();
        private final Map<Object, Object> initBinderCache = new HashMap<>();
        private final Map<Object, Object> modelAttributeCache = new HashMap<>();
        private final List<Object> requestResponseBodyAdvice = new ArrayList<>();
        int initCalls;

        @SuppressWarnings("unused")
        private void initControllerAdviceCache() {
            initCalls++;
            initBinderAdviceCache.put("advice", "rebuilt");
            modelAttributeAdviceCache.put("advice", "rebuilt");
            requestResponseBodyAdvice.addAll(0, List.of("bodyAdvice"));
        }

        Map<Object, Object> binderAdvice() {
            return initBinderAdviceCache;
        }

        Map<Object, Object> binderPerController() {
            return initBinderCache;
        }

        List<Object> bodyAdvice() {
            return requestResponseBodyAdvice;
        }
    }

    @Test
    void theBinderAndModelCachesAreRebuiltToo() {
        RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();
        adapter.binderAdvice().put("advice", "stale");
        adapter.binderPerController().put(String.class, "stale");

        assertTrue(SpringControllerAdviceReloader.rebuild(adapter));
        assertEquals(1, adapter.initCalls);
        assertEquals("rebuilt", adapter.binderAdvice().get("advice"));
        assertTrue(adapter.binderPerController().isEmpty());
    }

    /**
     * The list has to come out the same length it went in. Without this, every
     * reload of an advice class adds another copy of every ResponseBodyAdvice,
     * and each copy runs on every response.
     */
    @Test
    void thePrependedListIsPutBackAsItWas() {
        RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();
        adapter.bodyAdvice().add("bodyAdvice");
        adapter.binderAdvice().put("advice", "stale");

        SpringControllerAdviceReloader.rebuild(adapter);
        assertEquals(List.of("bodyAdvice"), adapter.bodyAdvice(),
                "the initialiser prepends, so calling it twice would duplicate");

        SpringControllerAdviceReloader.rebuild(adapter);
        assertEquals(List.of("bodyAdvice"), adapter.bodyAdvice(), "and again");
    }

    @Test
    void theAdapterIsRecognisedAsItsOwnBean() {
        RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();

        assertEquals(List.of(adapter), SpringControllerAdviceReloader.unwrap(adapter));
    }

    /** The gate: what is worth walking the container for, and what is not. */
    @Test
    void onlyClassesThatCarryAdviceOpenTheGate() {
        assertTrue(SpringControllerAdviceReloader.carriesAdvice(Advised.class));
        assertTrue(SpringControllerAdviceReloader.carriesAdvice(Binding.class));
        assertFalse(SpringControllerAdviceReloader.carriesAdvice(Plain.class),
                "rebuilding re-scans every advice bean in every context; without this "
                + "gate the integration suite's batches went from 1.5s to 17.7s");
    }

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @interface ExceptionHandler {
    }

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @interface InitBinder {
    }

    static class Advised {
        @ExceptionHandler
        void handle() {
        }
    }

    static class Binding {
        @InitBinder
        void bind() {
        }
    }

    static class Plain {
        void ordinary() {
        }
    }
}
