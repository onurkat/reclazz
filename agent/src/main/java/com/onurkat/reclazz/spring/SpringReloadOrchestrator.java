/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

/**
 * Coordinates all Spring-related reloaders in the correct order.
 *
 * Execution order:
 * 1. Bean singleton refresh (destroys + recreates the bean)
 * 2. MVC re-scan (@RequestMapping re-registration)
 * 3. Cache eviction (@Cacheable/@CacheEvict/@CachePut)
 * 4. Scheduler re-registration (@Scheduled)
 * 5. Event listener re-registration (@EventListener)
 * 6. AOP proxy cache clear (@Aspect)
 * 7. Async re-processing (@Async)
 * 8. Data repository refresh (Spring Data)
 * 9. Security config notification (@EnableWebSecurity)
 *
 * Each reloader is a no-op if the relevant Spring module is not on the classpath
 * or the reloaded class does not use the relevant annotations.
 */
public class SpringReloadOrchestrator {

    private final SpringBeanReloader beanReloader;
    private final SpringMvcReloader mvcReloader;
    private final SpringCacheReloader cacheReloader;
    private final SpringSchedulerReloader schedulerReloader;
    private final SpringEventReloader eventReloader;
    private final SpringAopReloader aopReloader;
    private final SpringAsyncReloader asyncReloader;
    private final SpringDataReloader dataReloader;
    private final SpringSecurityReloader securityReloader;

    public SpringReloadOrchestrator(PlatformContext platformContext) {
        this.beanReloader = new SpringBeanReloader(platformContext);
        this.mvcReloader = new SpringMvcReloader(platformContext);
        this.cacheReloader = new SpringCacheReloader(platformContext);
        this.schedulerReloader = new SpringSchedulerReloader(platformContext);
        this.eventReloader = new SpringEventReloader(platformContext);
        this.aopReloader = new SpringAopReloader(platformContext);
        this.asyncReloader = new SpringAsyncReloader(platformContext);
        this.dataReloader = new SpringDataReloader(platformContext);
        this.securityReloader = new SpringSecurityReloader(platformContext);
    }

    /**
     * Run all applicable Spring reloaders after a class has been reloaded.
     *
     * @param className the fully qualified class name
     * @param reloadedClass the reloaded Class object (may be null if class is not yet loaded)
     * @param isStructural whether the reload involved structural changes
     */
    /**
     * Defer the expensive cross-context work (dependent cascade and
     * stale-reference healing) until {@link #endBatch()} so a multi-class
     * reload sweeps the singletons once instead of once per class.
     */
    public void beginBatch() {
        beanReloader.beginBatch();
    }

    public void endBatch() {
        beanReloader.endBatch();
    }

    public void onClassReloaded(String className, Class<?> reloadedClass, boolean isStructural) {
        if (reloadedClass == null) return;

        if (isSpringBean(reloadedClass)) {
            // 1. Bean refresh — pass the actual Class object: its own
            // classloader is the only reliable way to match bean types
            // across contexts with different classloaders.
            beanReloader.refreshBean(className, reloadedClass);

            // 2. MVC re-scan (only on structural changes to controllers)
            if (isStructural && isController(reloadedClass)) {
                boolean mvcReloaded = mvcReloader.reloadMappings(reloadedClass);
                if (mvcReloaded) {
                    StatusReporter.success("Spring MVC mappings re-scanned for " + className);
                }
            }
        }

        // 3. Cache eviction
        cacheReloader.reloadCaches(reloadedClass);

        // 4. Scheduler re-registration
        schedulerReloader.reloadScheduledMethods(reloadedClass);

        // 5. Event listener re-registration
        eventReloader.reloadEventListeners(reloadedClass);

        // 6. AOP proxy cache clear
        aopReloader.reloadAopProxies(reloadedClass);

        // 7. Async re-processing
        asyncReloader.reloadAsyncMethods(reloadedClass);

        // 8. Data repository refresh
        dataReloader.reloadRepository(reloadedClass);

        // 9. Security config notification
        securityReloader.reloadSecurityConfig(reloadedClass);
    }

    /**
     * Refresh only the bean singleton (used by legacy Hybris reloader delegation).
     */
    public void refreshBean(String className) {
        beanReloader.refreshBean(className);
    }

    /**
     * Re-scan MVC mappings for a controller class.
     */
    public boolean reloadMvcMappings(Class<?> controllerClass) {
        return mvcReloader.reloadMappings(controllerClass);
    }

    private boolean isSpringBean(Class<?> clazz) {
        try {
            for (var annotation : clazz.getAnnotations()) {
                String name = annotation.annotationType().getName();
                if (name.contains("springframework")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean isController(Class<?> clazz) {
        try {
            for (var annotation : clazz.getAnnotations()) {
                String name = annotation.annotationType().getName();
                if (name.endsWith(".Controller") || name.endsWith(".RestController")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
