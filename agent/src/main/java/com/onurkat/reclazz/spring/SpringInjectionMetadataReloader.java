/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * Drops Spring's answer to "what does this class need injected".
 *
 * <p>Spring works that out once per bean and keeps it. Adding {@code @Autowired}
 * to a field that was already there is therefore one of the quietest failures
 * a reload can have: the class reloads, the annotation is on it, the bean is
 * even destroyed and re-created, and the field is still null, because the
 * post-processor asked its cache what to inject and the cache was filled before
 * the annotation existed. Measured on Spring Boot 3.3.4, stock JDK 21:
 *
 * <pre>
 *   before          /consumer -> not-injected
 *   add @Autowired  reload succeeds, "Annotation change ... re-scanning"
 *   after           /consumer -> not-injected      (and nothing said why)
 * </pre>
 *
 * <p>Four caches, because the question has four halves. {@code @Autowired} and
 * {@code @Value} injection points live in
 * {@code AutowiredAnnotationBeanPostProcessor.injectionMetadataCache}; which
 * constructor to use lives next to it in {@code candidateConstructorsCache},
 * which is what a {@code @Autowired} moved onto a constructor changes.
 * {@code @Resource} has its own copy of the first on
 * {@code CommonAnnotationBeanPostProcessor}, and {@code @PostConstruct} and
 * {@code @PreDestroy} live in {@code lifecycleMetadataCache} on its superclass.
 *
 * <p>Whole maps rather than picked entries, the same trade the transaction and
 * cache metadata makes: the key is a bean name where the value is what names
 * the class, so picking would mean reading every value to find the ones that
 * point here. Emptying costs one re-introspection per bean, and only for beans
 * that are created or refreshed after this, which for a running application is
 * the handful this reload touches.
 *
 * <p>All reflective, no Spring dependency, and it says nothing when it works.
 * An injection that now happens is the developer's own expectation, not news.
 */
public class SpringInjectionMetadataReloader {

    /** The processors that answer "what goes into this bean", by type. */
    private static final String[] PROCESSOR_TYPES = {
            "org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor",
            "org.springframework.context.annotation.CommonAnnotationBeanPostProcessor",
    };

    /** Every map on them that holds an answer a reload can invalidate. */
    private static final String[] CACHE_FIELDS = {
            "injectionMetadataCache", "candidateConstructorsCache", "lifecycleMetadataCache",
    };

    private final PlatformContext platformContext;

    public SpringInjectionMetadataReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    /**
     * Empty the caches so the next bean creation re-reads the class.
     *
     * <p>Has to run before the bean refresh, not after: the refresh is what
     * re-creates the bean, and it asks these caches on the way.
     *
     * @return how many caches held something and were emptied
     */
    public int reload() {
        int cleared = 0;
        for (Object appContext : platformContext.getAllApplicationContexts()) {
            for (String type : PROCESSOR_TYPES) {
                String[] names = SpringBeans.beanNamesForType(appContext, type);
                for (String name : names) {
                    Object processor = SpringBeans.getBean(appContext, name);
                    if (processor == null) continue;
                    cleared += clearCaches(processor);
                }
            }
        }
        return cleared;
    }

    /** Every named map this object has, up its hierarchy, emptied. */
    static int clearCaches(Object processor) {
        int cleared = 0;
        for (Class<?> c = processor.getClass(); c != null && c != Object.class;
                c = c.getSuperclass()) {
            for (String name : CACHE_FIELDS) {
                Field field;
                try {
                    field = c.getDeclaredField(name);
                } catch (NoSuchFieldException notOnThisOne) {
                    continue;
                }
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (!Map.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Map<?, ?> cache = (Map<?, ?>) field.get(processor);
                    if (cache == null || cache.isEmpty()) continue;
                    cache.clear();
                    cleared++;
                } catch (Throwable oneCache) {
                    // A cache that cannot be emptied keeps what it holds, and
                    // the count reports only what really happened.
                }
            }
        }
        return cleared;
    }
}
