/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code @PreAuthorize} is resolved once per method and the answer is kept.
 *
 * <p>The key it is kept under is the method and its class, neither of which
 * redefinition changes, so an edited expression keeps being enforced as it was
 * written: a rule tightened at 11am is still the 9am rule at noon, and nothing
 * fails to say so. That is the same staleness {@code @Transactional} had, one
 * framework over, and it gets the same answer, which is to clear the map and
 * let it repopulate from annotations that are current by then.
 *
 * <p>Two generations hold that map in two shapes. Spring Security 5 caches on
 * a {@code MethodSecurityMetadataSource}; Spring Security 6 has one
 * interceptor bean per annotation, each holding an authorization manager,
 * which holds a registry, which holds the cache. On Boot 3.3 there is one more
 * level than that, and it is the one this was first written without: the bean
 * is a {@code DeferringMethodInterceptor} holding a {@code SingletonSupplier}
 * of the interceptor, so a walk down named fields reached nothing and the
 * whole refresh was a silent no-op against a live server.
 *
 * <p>So the walk follows every field and takes its bound from ownership
 * instead: nothing outside {@code org.springframework.security} is read from
 * or cleared. The shapes these tests use therefore live under that package
 * too, in {@code MethodSecurityShapes}, with the field names the real classes
 * use. What is held here is both generations, the deferred level, and the
 * three things that must end the walk: depth, a cycle, and an object that is
 * not Spring Security's.
 */
class SpringSecurityMethodMetadataTest {

    @Test
    void theLegacySourcesMapIsCleared() {
        var source = new org.springframework.security.authorization.method
                .MethodSecurityShapes.DelegatingMethodSecurityMetadataSource();
        source.cache().put("com.example.Service#read", "hasRole('OLD')");

        assertEquals(1, SpringSecurityReloader.clearMethodSecurityCaches(source, 0));
        assertTrue(source.cache().isEmpty(), "the old expression must not survive the reload");
    }

    @Test
    void theModernRegistryIsReachedThroughTheInterceptor() {
        var interceptor = new org.springframework.security.authorization.method
                .MethodSecurityShapes.BeforeMethodInterceptor();
        interceptor.manager().registry().cache().put("com.example.Service#read", "hasRole('OLD')");

        assertEquals(1, SpringSecurityReloader.clearMethodSecurityCaches(interceptor, 0),
                "interceptor to manager to registry is where Security 6.0 to 6.2 keeps it");
        assertTrue(interceptor.manager().registry().cache().isEmpty());
    }

    /**
     * The level that made the first version of this a no-op on a live server:
     * the bean is a wrapper and the interceptor is behind a supplier.
     */
    @Test
    void theDeferredInterceptorIsUnwrapped() {
        var bean = new org.springframework.security.authorization.method
                .MethodSecurityShapes.DeferringMethodInterceptor();
        bean.real().manager().registry().cache().put("com.example.Service#read", "hasRole('OLD')");

        assertEquals(1, SpringSecurityReloader.clearMethodSecurityCaches(bean, 0),
                "a supplier is unwrapped rather than followed");
        assertTrue(bean.real().manager().registry().cache().isEmpty());
    }

    /** A supplier that will not answer is a decline, not a failed reload. */
    @Test
    void aSupplierThatThrowsIsNotAFailure() {
        var bean = new org.springframework.security.authorization.method
                .MethodSecurityShapes.UnreadyInterceptor();

        assertEquals(0, SpringSecurityReloader.clearMethodSecurityCaches(bean, 0));
    }

    /**
     * A shape with a cache but no way to ask it anything is the one case that
     * still counts as nothing done: there was nothing to clear and no way to
     * fill it. That is what the honest warning is for, and counting it would
     * turn the warning into a false success.
     */
    @Test
    void aShapeThatCannotBeAskedIsNotCountedAsDone() {
        assertEquals(0, SpringSecurityReloader.clearMethodSecurityCaches(
                new org.springframework.security.authorization.method
                        .MethodSecurityShapes.BeforeMethodInterceptor(), 0));
    }

    @Test
    void aCycleEndsTheWalkRatherThanTheJvm() {
        var one = org.springframework.security.authorization.method
                .MethodSecurityShapes.Cycle.pair();
        one.cache().put("k", "stale");

        assertEquals(1, SpringSecurityReloader.clearMethodSecurityCaches(one, 0),
                "each object is visited once, and holding each other is not a loop");
        assertTrue(one.cache().isEmpty());
    }

    /**
     * The bound is ownership, not a field-name list: an object that is not
     * Spring Security's is never read from, whatever it holds.
     */
    @Test
    void nothingOutsideSpringSecurityIsTouched() {
        NotSpringSecurity theirs = new NotSpringSecurity();
        theirs.cachedAttributes.put("k", "not ours");

        assertEquals(0, SpringSecurityReloader.clearMethodSecurityCaches(theirs, 0));
        assertFalse(theirs.cachedAttributes.isEmpty());
    }

    /** Same field name, same shape, different owner. */
    static class NotSpringSecurity {
        final Map<Object, Object> cachedAttributes = new HashMap<>();
    }

    /**
     * Clearing is half the job, and the half that was measured to be not
     * enough. On a live Boot 3.3 server the map was cleared and the very next
     * call filled it back up with the OLD expression, because the
     * re-resolution reads the annotation off the {@code Method} the AOP
     * invocation carries, captured when the proxy was built and never
     * re-parsed. So the map is refilled here from fresh {@code Method}
     * objects, whose equal keys are what the stale lookup finds.
     */
    @Test
    void anAskableRegistryIsRefilledFromFreshMethods() {
        var registry = new org.springframework.security.authorization.method
                .MethodSecurityShapes.AskableRegistry();
        registry.cache().put("guarded", "stale");

        assertEquals(1, SpringSecurityReloader.clearMethodSecurityCaches(
                registry, 0, Guarded.class));
        assertEquals("fresh:guarded", registry.cache().get("guarded"),
                "the entry has to be the one a fresh Method wrote");
    }

    /**
     * An empty cache is not a safe state, which is the second thing the live
     * run taught. The first call after a reload resolves through the
     * {@code Method} the AOP invocation carries, and that one still reads the
     * annotation it read at startup, so a cache nobody had filled yet fills
     * itself with the old expression exactly like a cleared one did. Filling
     * it here is what makes the answer current before anybody asks, and it is
     * why an empty cache still counts as work done.
     */
    @Test
    void anUntouchedCacheIsFilledRatherThanLeftToResolveItself() {
        var registry = new org.springframework.security.authorization.method
                .MethodSecurityShapes.AskableRegistry();
        assertTrue(registry.cache().isEmpty());

        assertEquals(1, SpringSecurityReloader.clearMethodSecurityCaches(
                registry, 0, Guarded.class));
        assertEquals("fresh:guarded", registry.cache().get("guarded"),
                "nothing was cleared, and the fresh answer is there anyway");
    }

    /**
     * The other shape: it resolves without caching, so the answer is computed
     * and stored under the key the framework itself would have used.
     */
    @Test
    void aResolvingManagerIsRefilledUnderTheFrameworksOwnKey() throws Exception {
        var manager = new org.springframework.security.authorization.method
                .MethodSecurityShapes.SecuredManager();
        manager.cache().put("anything", "stale");

        assertEquals(1, SpringSecurityReloader.clearMethodSecurityCaches(
                manager, 0, Guarded.class));

        Object key = new org.springframework.core.MethodClassKey(
                Guarded.class.getDeclaredMethod("guarded"), Guarded.class);
        assertEquals(java.util.Set.of("ROLE_guarded"), manager.cache().get(key),
                "the framework looks it up by MethodClassKey, so that is what it is stored under");
    }

    /**
     * Null is how these maps say "nothing cached", and one of them swaps in a
     * sentinel that is not ours to build. Storing null would either throw or
     * teach the map a lie.
     */
    @Test
    void aResolveThatAnswersNullIsNotStored() throws Exception {
        var manager = new org.springframework.security.authorization.method
                .MethodSecurityShapes.SecuredManager();
        manager.cache().put("anything", "stale");

        SpringSecurityReloader.clearMethodSecurityCaches(manager, 0, Guarded.class);

        Object key = new org.springframework.core.MethodClassKey(
                Guarded.class.getDeclaredMethod("unannotated"), Guarded.class);
        assertNull(manager.cache().get(key));
    }

    /** The class the refill is asked about. */
    static class Guarded {
        void guarded() {
        }

        void unannotated() {
        }
    }

    /**
     * The refresh runs on every reload, so what decides whether it says
     * anything is whether this class is one a security edit would be about.
     * A service carrying the annotation is the case the whole change exists
     * for, and it is not a security configuration class, which is why the
     * filter-chain path never reached it.
     */
    @Test
    void aServiceCarryingTheAnnotationCounts() {
        assertTrue(SpringSecurityReloader.carriesMethodSecurity(GuardedService.class));
        assertFalse(SpringSecurityReloader.carriesMethodSecurity(PlainService.class),
                "an unrelated reload must not print a line about security");
    }

    @Test
    void theEnforcedAnnotationsAreNamedInFull() {
        for (String enforced : new String[] {
                "org.springframework.security.access.prepost.PreAuthorize",
                "org.springframework.security.access.prepost.PostAuthorize",
                "org.springframework.security.access.prepost.PreFilter",
                "org.springframework.security.access.prepost.PostFilter",
                "org.springframework.security.access.annotation.Secured",
                "jakarta.annotation.security.RolesAllowed",
                "javax.annotation.security.RolesAllowed",
                "jakarta.annotation.security.DenyAll",
                "jakarta.annotation.security.PermitAll"}) {
            assertTrue(SpringSecurityReloader.isMethodSecurityAnnotation(enforced), enforced);
        }
        assertFalse(SpringSecurityReloader.isMethodSecurityAnnotation(
                "org.springframework.transaction.annotation.Transactional"));
    }

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @interface PreAuthorize {
        String value();
    }

    static class GuardedService {
        @PreAuthorize("hasRole('ADMIN')")
        void guarded() {
        }
    }

    static class PlainService {
        void open() {
        }
    }
}
