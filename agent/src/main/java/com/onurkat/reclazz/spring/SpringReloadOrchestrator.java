/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.ReloadEffects;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.ui.RestartLedger;

/**
 * Coordinates all Spring-related reloaders in the correct order.
 *
 * <p>The opening moves are bespoke and stay written out: what the container
 * thinks the bean needs injected has to be dropped before anything re-creates
 * it, the bean is refreshed, and the MVC mappings are re-scanned with the
 * added method signatures and the new bytecode in hand. They produce results
 * that later parts of the same method read.
 *
 * <p>Everything after that is a list. See {@link #buildSteps()}: ten steps of
 * the same shape, each told which class reloaded and left to decide whether it
 * has anything to do, and each run on its own so that one throwing is one step
 * that did not happen rather than the end of the sequence. A new framework
 * integration is a name and a lambda in that list.
 *
 * <p>Each reloader is a no-op if the relevant Spring module is not on the
 * classpath or the reloaded class does not use the relevant annotations.
 */
public class SpringReloadOrchestrator {

    private final SpringBeanReloader beanReloader;
    private final SpringMvcReloader mvcReloader;
    private final SpringCacheReloader cacheReloader;
    private final SpringSchedulerReloader schedulerReloader;
    private final SpringEventReloader eventReloader;
    private final SpringKafkaReloader kafkaReloader;
    private final SpringJmsReloader jmsReloader;
    private final SpringRabbitReloader rabbitReloader;
    private final SpringAddedBeanReloader addedBeanReloader;
    private final SpringAopReloader aopReloader;
    private final SpringAsyncReloader asyncReloader;
    private final SpringDataReloader dataReloader;
    private final SpringSecurityReloader securityReloader;
    private final SpringOperationSourceReloader operationSourceReloader;
    private final SpringInjectionMetadataReloader injectionMetadataReloader;
    private final SpringLifecycleReloader lifecycleReloader;
    private final PlatformContext platformContext;
    private final SpringControllerAdviceReloader exceptionHandlerReloader;
    private final java.util.List<ReloadSteps.Step> afterTheBeanIsBack;
    private java.util.Map<String, java.util.List<String>> refreshOwnersOnHelper = java.util.Map.of();
    // Class names that have added a member at any point this session, so they
    // carry a companion the agent regenerates on every reload. A companion-added
    // @EventListener or @Transactional/@Cacheable method can later be removed by
    // an edit the per-reload structural diff cannot see: the member was never in
    // the class's baseline, so adding or editing it reads as structural but
    // removing it reads as a body-only change. Once a class is known to carry
    // added members, the event and operation-source steps below run on every one
    // of its reloads, so a removed companion listener or transactional method is
    // still cleaned up. Plain classes that never add a member stay out of this
    // set, so the common body-only save skips both context-sized steps.
    private final java.util.Set<String> classesWithAddedMembers =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Opt-in helper-class -> cache-owner-class names to recreate after a helper reload. */
    public void setRefreshOwnersOnHelper(java.util.Map<String, java.util.List<String>> mapping) {
        this.refreshOwnersOnHelper = mapping == null ? java.util.Map.of() : mapping;
    }

    public SpringReloadOrchestrator(PlatformContext platformContext) {
        this.platformContext = platformContext;
        this.beanReloader = new SpringBeanReloader(platformContext);
        this.mvcReloader = new SpringMvcReloader(platformContext);
        this.cacheReloader = new SpringCacheReloader(platformContext);
        this.schedulerReloader = new SpringSchedulerReloader(platformContext);
        this.eventReloader = new SpringEventReloader(platformContext);
        this.kafkaReloader = new SpringKafkaReloader(platformContext);
        this.jmsReloader = new SpringJmsReloader(platformContext);
        this.rabbitReloader = new SpringRabbitReloader(platformContext);
        this.addedBeanReloader = new SpringAddedBeanReloader(platformContext);
        this.aopReloader = new SpringAopReloader(platformContext);
        this.asyncReloader = new SpringAsyncReloader(platformContext);
        this.dataReloader = new SpringDataReloader(platformContext);
        this.securityReloader = new SpringSecurityReloader(platformContext);
        this.operationSourceReloader = new SpringOperationSourceReloader(platformContext);
        this.injectionMetadataReloader = new SpringInjectionMetadataReloader(platformContext);
        this.lifecycleReloader = new SpringLifecycleReloader(platformContext);
        this.exceptionHandlerReloader = new SpringControllerAdviceReloader(platformContext);
        this.newBeanRegistrar = new SpringNewBeanRegistrar(platformContext, mvcReloader);
        // Last, because every step refers to a reloader above it.
        this.afterTheBeanIsBack = buildSteps();
    }

    private final SpringNewBeanRegistrar newBeanRegistrar;

    /**
     * A brand-new class file whose class the JVM has never loaded: register it
     * as a bean when it carries a stereotype.
     *
     * @return the registration result, including terminal condition outcomes
     *         that must not enter the ordinary class-initializing reload path
     */
    public SpringNewBeanRegistrar.Outcome registerNewBeanClass(String className, byte[] bytecode) {
        return newBeanRegistrar.registerIfComponent(className, bytecode);
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

    public void onHelperReloaded(String className, Class<?> type) {
        if (type == null) return;
        ReloadSteps.runAll(java.util.List.of(new ReloadSteps.Step("Cache eviction",
                        r -> cacheReloader.reloadCaches(r.type())),
                // @Bean/programmatic aspects need not carry a Spring stereotype.
                new ReloadSteps.Step("AOP proxy refresh", r -> aopReloader.reloadAopProxies(r.type()))),
                new ReloadSteps.Reloaded(className, type, false, false));
        refreshMappedCacheOwners(className, type);
    }

    /**
     * Opt-in: after a helper reloads, recreate the Spring singleton cache owners a
     * developer mapped to it, so a custom cache the owner holds is rebuilt through the
     * fresh helper logic. The agent cannot infer which owner a helper feeds, so the
     * mapping is explicit; each owner is recreated through the same guarded bean-reload
     * lifecycle, which rejects non-singletons and FactoryBeans, and active-listener
     * owners are declined here rather than risking a dropped registration.
     */
    private void refreshMappedCacheOwners(String helperName, Class<?> helperType) {
        java.util.List<String> owners = refreshOwnersOnHelper.get(helperName);
        if (owners == null || owners.isEmpty()) return;
        ClassLoader loader = helperType.getClassLoader();
        for (String ownerName : owners) {
            Class<?> ownerClass;
            try {
                // Resolve in the helper's own loader, so the same owner name in a
                // different context/classloader is never refreshed by accident.
                ownerClass = Class.forName(ownerName, false, loader);
            } catch (Throwable notHere) {
                StatusReporter.warn("Cache owner " + ownerName + " not found in "
                        + helperName + "'s classloader; not refreshed");
                continue;
            }
            if (isApplicationListener(ownerClass)) {
                StatusReporter.warn("Cache owner " + ownerName
                        + " is an event listener; restart to refresh it, not refreshed here");
                RestartLedger.note(ownerName,
                        "event-listener cache owner not refreshed after a helper reload; restart to rebuild its cache");
                continue;
            }
            try {
                var lifecycle = lifecycleReloader.prepare(ownerClass, java.util.Set.of(), null);
                if (lifecycle == null) {
                    StatusReporter.warn("Cache owner " + ownerName
                            + " is not a recreatable singleton; not refreshed");
                    continue;
                }
                if (!lifecycle.install()) {
                    StatusReporter.warn("Cache owner " + ownerName
                            + " could not be prepared for refresh; not refreshed");
                    continue;
                }
                beanReloader.refreshBean(ownerName, ownerClass);
                ReloadEffects.note("cache owner refreshed");
                StatusReporter.detail("Recreated cache owner " + ownerName
                        + " after " + helperName + " reloaded");
            } catch (Throwable failure) {
                StatusReporter.warn("Cache owner " + ownerName + " refresh failed: "
                        + com.onurkat.reclazz.ui.Failures.describe(failure) + "; not refreshed");
            }
        }
    }

    private static boolean isApplicationListener(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            for (Class<?> i : c.getInterfaces())
                if (i.getName().equals("org.springframework.context.ApplicationListener")) return true;
        return false;
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
        if (addedMethods) classesWithAddedMembers.add(className);

        if (isSpringBean(reloadedClass)) {
            var lifecycle = lifecycleReloader.prepare(reloadedClass, addedMethodSigs, newBytecode);
            if (lifecycle == null) return;
            // 0. What the container thinks this class needs injected, before
            // anything re-creates it. Spring answers that once per bean and
            // keeps it, so adding @Autowired to a field that was already there
            // reloaded, refreshed the bean, and left the field null: measured
            // on Boot 3.3, with nothing anywhere saying why. The refresh below
            // is what asks the cache, so emptying it after would be a reload
            // too late.
            if (isStructural || annotationsChanged) {
                injectionMetadataReloader.reload();
            }

            // 1. Bean refresh — pass the actual Class object: its own
            // classloader is the only reliable way to match bean types
            // across contexts with different classloaders.
            if (!rabbitReloader.beforeBeanRefresh(reloadedClass, newBytecode, addedMethodSigs, kafkaReloader, jmsReloader)) return;
            if (!jmsReloader.beforeBeanRefresh(reloadedClass, newBytecode, kafkaReloader)) return;
            if (!kafkaReloader.beforeBeanRefresh(reloadedClass, newBytecode)) return;
            if (!lifecycle.install()) return;
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
                // What Spring worked out about this handler's PARAMETERS, which
                // re-registering the mapping does not touch: the name, the
                // default and whether a parameter is required are cached per
                // MethodParameter, and that key compares the method and the
                // index, so a fresh one finds the stale answer. Measured on
                // Boot 3.3, changing a defaultValue changed nothing.
                SpringArgumentResolverCaches.flush(
                        platformContext.getAllApplicationContexts());

                java.util.Set<String> addedHandlers =
                        SpringMvcReloader.mappedMethodsAmong(addedMethodSigs, newBytecode);
                boolean mvcReloaded = mvcReloader.reloadMappings(reloadedClass, addedHandlers);

                // The scan runs on every controller reload and says so only
                // when the mapping could have moved. A body-only change
                // re-registers the same mappings, which is worth doing and not
                // worth a line in the log.
                boolean worthSaying = isStructural || annotationsChanged;
                if (mvcReloaded && worthSaying) {
                    ReloadEffects.note("mappings re-scanned");
                    StatusReporter.detail("Spring MVC mappings re-scanned for " + className);

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
                    if (isStructural && !addedHandlers.isEmpty()) {
                        // The scan cannot see a method that lives in the
                        // companion, so it is given a class that can be read.
                        boolean mapped = mvcReloader.registerAddedEndpoints(
                                reloadedClass, addedHandlers, newBytecode);
                        if (mapped) {
                            ReloadEffects.note("added handlers mapped");
                            StatusReporter.detail("Handler methods added by this reload are mapped.");
                        } else {
                            StatusReporter.warn("A handler method added by this reload is not visible "
                                    + "to the mapping scan and needs a restart. Existing mappings, "
                                    + "including changed ones, are live.");
                            RestartLedger.note(reloadedClass.getName(),
                                    "a handler method added by a reload that the mapping scan cannot see");
                        }
                    }
                }
            }
        }

        // Steps 3 to 9, each on its own. They share a shape (a class went in,
        // a framework was told) and they share a failure mode: every one asks
        // a class it did not compile against what it has, in an application
        // whose Spring version this agent has never seen. As a bare sequence
        // one of them throwing skipped all the ones after it.
        ReloadSteps.runAll(afterTheBeanIsBack,
                new ReloadSteps.Reloaded(className, reloadedClass, isStructural, annotationsChanged,
                        addedMethodSigs, newBytecode));
    }

    /**
     * What every reloaded bean goes through once its own refresh is done, in
     * order. A new framework integration is a name and a lambda here.
     *
     * <p>Built once rather than per reload: the reloaders are stateless about
     * the class and hold their own caches, and a save that touches twenty
     * classes should not build twenty of these.
     */
    private java.util.List<ReloadSteps.Step> buildSteps() {
        return java.util.List.of(
            new ReloadSteps.Step("Added Rabbit listeners",
                    r -> rabbitReloader.reloadRabbitListeners(r.type(), r.addedMethods(), r.bytecode())),
            new ReloadSteps.Step("Added JMS listeners",
                    r -> jmsReloader.reloadJmsListeners(r.type(), r.addedMethods(), r.bytecode())),
            new ReloadSteps.Step("Added Kafka listeners",
                    r -> kafkaReloader.reloadKafkaListeners(r.type(), r.addedMethods(), r.bytecode())),
            new ReloadSteps.Step("Added bean factories",
                    r -> addedBeanReloader.reloadBeanMethods(r.type(), r.addedMethods(), r.bytecode())),
            new ReloadSteps.Step("Cache eviction",
                    r -> cacheReloader.reloadCaches(r.type())),

            // Which exceptions a controller advice handles. Spring scans the
            // advice beans once at startup and caches each controller's
            // handlers on first use, so adding @ExceptionHandler to a method
            // that was already there reached nothing: measured on Boot 3.3,
            // the endpoint kept answering the default error body.
            new ReloadSteps.Step("Controller advice re-scan", r -> {
                if (com.onurkat.reclazz.bootstrap.ExceptionHandlerBridge.wasAdapted(r.type())
                        || com.onurkat.reclazz.bootstrap.MvcBindingBridge.wasAdapted(r.type())
                        || ((r.structural() || r.annotationsChanged())
                        && SpringControllerAdviceReloader.carriesAdvice(r.type()))) {
                    exceptionHandlerReloader.reload();
                }
            }),

            // Transaction/cache annotation metadata. Eviction above empties
            // the cached VALUES; this clears the cached ANSWER to "what does
            // the annotation on this method say", which redefinition changes
            // without changing the Method identity the answer is filed under.
            // Only when something beyond the body changed, or the class carries
            // added members: a body-only edit of a plain class does not touch
            // annotations, so the cached answer stays correct, and re-reading it
            // anyway would clear Spring's global annotation caches and every
            // context's whole transaction/cache attribute cache for nothing. A
            // class with a companion still runs it, because a removed added
            // @Transactional/@Cacheable method is invisible to the structural diff.
            new ReloadSteps.Step("Transaction and cache metadata", r -> {
                if (r.structural() || r.annotationsChanged() || classesWithAddedMembers.contains(r.className()))
                    operationSourceReloader.reloadOperationSources(r.type(), r.annotationsChanged());
            }),

            // The same cache, one framework over. Method security resolves
            // @PreAuthorize once per method and keeps the answer under a key
            // that redefinition does not change, so an edited expression keeps
            // being enforced as it was written. This runs for every class, not
            // only for security configurations: the edit that needs it is an
            // annotation on a service method, which never reaches the
            // filter-chain rebuild below because such a class is not one.
            new ReloadSteps.Step("Method security metadata",
                    r -> securityReloader.refreshMethodSecurity(r.type())),

            new ReloadSteps.Step("Scheduler re-registration",
                    r -> schedulerReloader.reloadScheduledMethods(r.type(), r.addedMethods(), r.bytecode())),
            // Same relevance gate. An existing @EventListener adapter on an
            // original method holds a Method whose body redefinition updated in
            // place, so a body-only edit keeps dispatching through it with nothing
            // to re-register; running anyway means two full listener-registry
            // scans per context on every save. An added listener lives on the
            // companion, so the class is in classesWithAddedMembers and still runs
            // here: its removal is invisible to the structural diff and would
            // otherwise leave a stale adapter firing.
            new ReloadSteps.Step("Event listener re-registration", r -> {
                if (r.structural() || r.annotationsChanged() || classesWithAddedMembers.contains(r.className()))
                    eventReloader.reloadEventListeners(r.type(), r.addedMethods(), r.bytecode());
            }),
            new ReloadSteps.Step("AOP proxy refresh",
                    r -> aopReloader.reloadAopProxies(r.type())),
            new ReloadSteps.Step("Async re-processing",
                    r -> asyncReloader.reloadAsyncMethods(r.type())),
            new ReloadSteps.Step("Data repository refresh",
                    r -> dataReloader.reloadRepository(r.type())),
            new ReloadSteps.Step("Security configuration",
                    r -> securityReloader.reloadSecurityConfig(r.type())));
    }

    /**
     * Refresh only the bean singleton (used by legacy Hybris reloader delegation).
     */
    public void refreshBean(String className) {
        beanReloader.refreshBean(className);
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
