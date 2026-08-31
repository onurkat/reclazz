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
    private final SpringOperationSourceReloader operationSourceReloader;

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
        this.operationSourceReloader = new SpringOperationSourceReloader(platformContext);
        this.newBeanRegistrar = new SpringNewBeanRegistrar(platformContext, mvcReloader);
    }

    private final SpringNewBeanRegistrar newBeanRegistrar;

    /**
     * A brand-new class file whose class the JVM has never loaded: register it
     * as a bean when it carries a stereotype.
     *
     * @return true when a bean was registered and the ordinary reload path
     *         has nothing left to do for this file
     */
    public boolean registerNewBeanClass(String className, byte[] bytecode) {
        return newBeanRegistrar.registerIfComponent(className, bytecode)
                == SpringNewBeanRegistrar.Outcome.REGISTERED;
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
        onClassReloaded(className, reloadedClass, isStructural, false);
    }

    public void onClassReloaded(String className, Class<?> reloadedClass,
                                boolean isStructural, boolean annotationsChanged) {
        onClassReloaded(className, reloadedClass, isStructural, annotationsChanged, false);
    }

    /**
     * @param addedMethods whether this reload added methods, which the mapping
     *                     scan cannot see on a stock JDK
     */
    public void onClassReloaded(String className, Class<?> reloadedClass,
                                boolean isStructural, boolean annotationsChanged,
                                boolean addedMethods) {
        onClassReloaded(className, reloadedClass, isStructural, annotationsChanged,
                addedMethods, java.util.Set.of(), null);
    }

    /**
     * @param addedMethodSigs the methods this reload added, as name:descriptor
     * @param newBytecode     the compiled class, where those methods can be read
     */
    public void onClassReloaded(String className, Class<?> reloadedClass,
                                boolean isStructural, boolean annotationsChanged,
                                boolean addedMethods,
                                java.util.Set<String> addedMethodSigs,
                                byte[] newBytecode) {
        if (reloadedClass == null) return;

        if (isSpringBean(reloadedClass)) {
            // 1. Bean refresh — pass the actual Class object: its own
            // classloader is the only reliable way to match bean types
            // across contexts with different classloaders.
            beanReloader.refreshBean(className, reloadedClass);

            // 2. MVC re-scan. Structural changes need it because the set of
            // handler methods moved; annotation changes need it because the
            // mapping itself did, and that edit is not structural. Gating on
            // structural alone left a changed @RequestMapping live in the
            // class and stale in the registry.
            // Any reload of a controller, not just a structural one. On a JVM
            // with native enhanced redefinition the agent does not compute a
            // diff, because the JVM applies the change itself, so a method
            // added to a controller arrived with nothing marked structural and
            // the mapping was never re-scanned: the new endpoint answered 404
            // on a runtime that could have served it. Re-scanning a single
            // controller is cheap and produces the same registry twice over.
            if (isController(reloadedClass)) {
                boolean mvcReloaded = mvcReloader.reloadMappings(reloadedClass);

                // The scan runs on every controller reload and says so only
                // when the mapping could have moved. A body-only change
                // re-registers the same mappings, which is worth doing and not
                // worth a line in the log.
                boolean worthSaying = isStructural || annotationsChanged;
                if (mvcReloaded && worthSaying) {
                    StatusReporter.success("Spring MVC mappings re-scanned for " + className);

                    // A re-scan reads the class through reflection, and a
                    // method this reload added is not there to be read: on a
                    // stock JDK it lives in the companion. Existing mappings
                    // are updated, a brand new one is not, and saying only
                    // that the scan ran would leave the developer refreshing a
                    // 404 wondering which of the two of us is wrong.
                    //
                    // Only the added methods that actually carry a mapping
                    // annotation count here. A reload that adds a private
                    // helper, or the lambda$ synthetics an edited body brings
                    // with it, used to end in "a handler method ... needs a
                    // restart" about handlers that never existed (measured:
                    // every lambda edit in a controller printed it).
                    java.util.Set<String> addedHandlers =
                            SpringMvcReloader.mappedMethodsAmong(addedMethodSigs, newBytecode);
                    if (isStructural && !addedHandlers.isEmpty()) {
                        // The scan cannot see a method that lives in the
                        // companion, so it is given a class that can be read.
                        boolean mapped = mvcReloader.registerAddedEndpoints(
                                reloadedClass, addedHandlers, newBytecode);
                        if (mapped) {
                            StatusReporter.success("Handler methods added by this reload are mapped.");
                        } else {
                            StatusReporter.warn("A handler method added by this reload is not visible "
                                    + "to the mapping scan and needs a restart. Existing mappings, "
                                    + "including changed ones, are live.");
                            com.onurkat.reclazz.agent.RestartLedger.note(reloadedClass.getName(),
                                    "a handler method added by a reload that the mapping scan cannot see");
                        }
                    }
                }
            }
        }

        // 3. Cache eviction
        cacheReloader.reloadCaches(reloadedClass);

        // 3b. Transaction/cache annotation metadata. Eviction above empties
        // the cached VALUES; this clears the cached ANSWER to "what does the
        // annotation on this method say", which redefinition changes without
        // changing the Method identity the answer is filed under.
        operationSourceReloader.reloadOperationSources(reloadedClass, annotationsChanged);

        // 3c. The same cache, one framework over. Method security resolves
        // @PreAuthorize once per method and keeps the answer under a key that
        // redefinition does not change, so an edited expression keeps being
        // enforced as it was written. This runs for every class, not only for
        // security configurations: the edit that needs it is an annotation on
        // a service method, which never reaches the filter-chain rebuild at
        // step 9 because such a class is not a security configuration.
        securityReloader.refreshMethodSecurity(reloadedClass);

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
