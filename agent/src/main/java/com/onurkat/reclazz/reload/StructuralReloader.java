/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.agent.AgentConfig;
import com.onurkat.reclazz.agent.ClassReloader;
import com.onurkat.reclazz.bootstrap.DispatchTable;
import com.onurkat.reclazz.bootstrap.FieldStore;
import com.onurkat.reclazz.bootstrap.MethodForge;
import com.onurkat.reclazz.bootstrap.ReflectionBridge;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.transform.CallSiteAdapter;
import com.onurkat.reclazz.transform.TransformContext;

import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates structural class reloading using the companion class pattern.
 *
 * ALL changes to watched classes (body-only and structural) use the companion
 * class pattern to avoid re-adding synthetic fields via redefineClasses, which
 * would always fail on standard JVMs.
 */
public class StructuralReloader {

    private final Instrumentation instrumentation;
    private final TransformContext context;
    private final AgentConfig config;
    private final boolean isHybris;

    // Hybris-specific reloaders — lazily initialized only on Hybris platform
    private Object hibernateInvalidator; // HibernateCacheInvalidator, loaded via reflection to avoid import

    // Load-time transformer, used to re-transform new bytecode so body-only
    // diffs (incl. constructor bodies) can be applied via redefineClasses.
    private com.onurkat.reclazz.transform.ReclazzTransformer transformer;

    // Per-class version counter — thread-safe
    private final ConcurrentHashMap<String, Integer> versionCounters = new ConcurrentHashMap<>();

    public StructuralReloader(Instrumentation instrumentation, TransformContext context,
                              AgentConfig config, PlatformContext platformContext) {
        this.instrumentation = instrumentation;
        this.context = context;
        this.config = config;
        this.isHybris = platformContext != null &&
                platformContext.getPlatformId() == PlatformContext.Platform.HYBRIS;
        if (isHybris) {
            try {
                this.hibernateInvalidator = Class.forName(
                        "com.onurkat.reclazz.hybris.hibernate.HibernateCacheInvalidator")
                        .getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                // Swallowing this silently used to disable Hibernate L2
                // invalidation with no trace: reloads would appear to work
                // while the ORM kept serving cached instances.
                StatusReporter.warn("Hibernate cache invalidation unavailable ("
                        + e + ") — reloaded entities may serve cached data until restart");
            }
        }
    }

    public void setTransformer(com.onurkat.reclazz.transform.ReclazzTransformer transformer) {
        this.transformer = transformer;
    }

    /**
     * Reload a class, handling both body-only and structural changes.
     * Always uses the companion class pattern for watched classes to avoid
     * re-adding synthetic fields (which redefineClasses cannot do on standard JVMs).
     */
    public ClassReloader.ReloadResult reload(String className, byte[] newBytecode) {
        String internalName = className.replace('.', '/');

        try {
            TransformContext.ClassMetadata oldMetadata = context.getMetadata(internalName);

            if (oldMetadata == null) {
                // Class hasn't been transformed yet — use standard reload
                return standardReload(className, newBytecode);
            }

            // Analyze structural changes
            StructuralAnalyzer.StructuralDiff diff = StructuralAnalyzer.analyze(oldMetadata, newBytecode);

            if (diff.isUnsupported()) {
                return ClassReloader.ReloadResult.failure(
                        "Unsupported change: superclass or interface hierarchy changed. " +
                                "This requires a server restart.", true);
            }

            // Warn about removed methods — callers retain previous version's implementation
            if (!diff.getRemovedMethods().isEmpty()) {
                StatusReporter.warn("Removed methods detected in " + className +
                        " — existing callers will continue using the previous implementation: " +
                        diff.getRemovedMethods());
            }

            // Always use companion class pattern for watched classes.
            // Even body-only changes go through this path to avoid re-adding
            // __reclazz$ext and __reclazz$lookup fields via redefineClasses.
            return structuralReload(className, internalName, newBytecode, diff);

        } catch (Exception e) {
            StatusReporter.error("Structural reload error for " + className + ": " + e.getMessage());
            if (config.isVerbose()) {
                e.printStackTrace();
            }
            return ClassReloader.ReloadResult.failure("Structural reload error: " + e.getMessage(), false);
        }
    }

    private ClassReloader.ReloadResult standardReload(String className, byte[] newBytecode) {
        try {
            Class<?> existingClass = findLoadedClass(className);
            if (existingClass == null) {
                StatusReporter.info("New class detected: " + className);
                return ClassReloader.ReloadResult.success(false, false);
            }

            var definition = new java.lang.instrument.ClassDefinition(existingClass, newBytecode);
            instrumentation.redefineClasses(definition);
            return ClassReloader.ReloadResult.success(false, false);
        } catch (Exception e) {
            return ClassReloader.ReloadResult.failure("Standard reload failed: " + e.getMessage(), false);
        }
    }

    private ClassReloader.ReloadResult structuralReload(String className, String internalName,
                                                         byte[] newBytecode,
                                                         StructuralAnalyzer.StructuralDiff diff) {
        try {
            // Increment version (thread-safe)
            int version = versionCounters.merge(internalName, 1, Integer::sum);

            // Generate companion class
            CompanionGenerator.CompanionResult companion =
                    CompanionGenerator.generate(internalName, newBytecode, diff, version);

            // Get Lookup from the target class's __reclazz$lookup field
            Class<?> targetClass = findLoadedClass(className);
            if (targetClass == null) {
                return ClassReloader.ReloadResult.failure(
                        "Class not loaded, cannot perform structural reload", false);
            }

            java.lang.reflect.Field lookupField;
            try {
                lookupField = targetClass.getDeclaredField("__reclazz$lookup");
            } catch (NoSuchFieldException e) {
                // The loaded class carries no reclazz infrastructure — it was
                // loaded BEFORE the agent attached, so the load-time transform
                // never ran (and standard JVMs cannot retrofit fields via
                // retransform). Body-only changes still work through plain
                // redefinition; structural changes genuinely need the agent
                // present at class-load time.
                if (!diff.isStructural()) {
                    return standardReload(className, newBytecode);
                }
                return ClassReloader.ReloadResult.failure(
                        "Class " + className + " was loaded before Reclazz attached — " +
                        "structural changes need the agent at server start " +
                        "(-javaagent in wrapper.conf / JVM args) or JBR/DCEVM.", true);
            }
            lookupField.setAccessible(true);
            MethodHandles.Lookup classLookup = (MethodHandles.Lookup) lookupField.get(null);

            if (classLookup == null) {
                return ClassReloader.ReloadResult.failure(
                        "No Lookup available for " + className + " (class may not have been transformed)", false);
            }

            // Define hidden companion class with NESTMATE access
            MethodHandles.Lookup companionLookup = classLookup.defineHiddenClass(
                    companion.getBytecode(), true,
                    MethodHandles.Lookup.ClassOption.NESTMATE);
            Class<?> companionClass = companionLookup.lookupClass();

            // Build method handle map from companion methods
            Map<String, MethodHandle> newTargets = new LinkedHashMap<>();
            for (var entry : companion.getMethodHandleKeys().entrySet()) {
                String siteKey = entry.getKey();
                String methodDesc = entry.getValue();

                // Parse method name and descriptor
                int descStart = methodDesc.indexOf('(');
                String methodName = methodDesc.substring(0, descStart);
                String descriptor = methodDesc.substring(descStart);

                try {
                    MethodType mt = MethodType.fromMethodDescriptorString(descriptor,
                            targetClass.getClassLoader());
                    MethodHandle mh = companionLookup.findStatic(companionClass, methodName, mt);
                    newTargets.put(siteKey, mh);
                } catch (Exception e) {
                    StatusReporter.warn("Failed to resolve companion method: " +
                            methodName + descriptor + ": " + e.getMessage());
                }
            }

            // Re-target all call sites atomically via bootstrap-CL DispatchTable
            DispatchTable.retargetAll(targetClass, companionLookup, newTargets);

            // Register new fields in FieldStore (bootstrap-CL copy)
            for (String fieldKey : diff.getAddedFields()) {
                int colonIdx = fieldKey.indexOf(':');
                String fieldName = fieldKey.substring(0, colonIdx);
                String fieldDesc = fieldKey.substring(colonIdx + 1);
                FieldStore.registerField(internalName, fieldName, fieldDesc);
            }

            // Register forged Method/Field objects with ReflectionBridge
            // so that getDeclaredMethods()/getDeclaredFields() return added members
            registerReflectionMetadata(targetClass, internalName, diff, newTargets, companionLookup, companionClass);

            // Log warning about new fields having default values on existing instances
            if (!diff.getAddedFields().isEmpty()) {
                StatusReporter.info("New fields on existing instances will have default values (null/0/false)");
            }

            // Update metadata in TransformContext
            context.putMetadata(internalName, new TransformContext.ClassMetadata(
                    diff.getNewMethods(), diff.getNewFields(), 0, diff.getNewSuperName(),
                    diff.getNewAnnotations()));

            boolean isStructural = diff.isStructural();
            if (isStructural) {
                StatusReporter.info("Structural reload: " + className +
                        " (v" + version + ", " + diff.getSummary() + ")");
                // Invalidate Hibernate L2 cache for structurally changed classes (Hybris only)
                if (isHybris && hibernateInvalidator != null) {
                    try {
                        hibernateInvalidator.getClass().getMethod("invalidateCache", String.class)
                                .invoke(hibernateInvalidator, className);
                    } catch (Exception ignored) {}
                }
            }

            // Constructor bodies never route through the companion (super()
            // call chains can't be dispatched), so a changed constructor was
            // silently lost: refreshed beans were still built by the OLD
            // constructor. For body-only diffs the schema is unchanged (same
            // members, same injected infrastructure), so we can legally
            // redefine the original class with the freshly transformed new
            // bytecode — this applies constructor bodies directly.
            if (!diff.isStructural()) {
                try {
                    // RAW bytes on purpose: the registered ReclazzTransformer
                    // runs during redefineClasses and injects the
                    // infrastructure exactly like at load time. Feeding it
                    // pre-transformed bytes double-injects the fields →
                    // ClassFormatError (see ConstructorBodyRedefineTest).
                    instrumentation.redefineClasses(
                            new java.lang.instrument.ClassDefinition(targetClass, newBytecode));
                } catch (Throwable t) {
                    StatusReporter.warn("Constructor-body refresh skipped for " + className + ": " + t);
                }
            }

            boolean springBean = isSpringBean(targetClass);

            // Spring reload orchestration is now handled by ReclazzAgent.handleClassFileChange()
            // via SpringReloadOrchestrator.onClassReloaded()

            // Backoffice widget reload (Hybris only)
            if (isHybris && isStructural && isBackofficeWidget(targetClass)) {
                try {
                    Class<?> bwrClass = Class.forName(
                            "com.onurkat.reclazz.hybris.backoffice.BackofficeWidgetReloader");
                    boolean widgetReloaded = (boolean) bwrClass.getMethod("reloadWidget", Class.class)
                            .invoke(null, targetClass);
                    if (widgetReloaded) {
                        StatusReporter.success("Backoffice widget reloaded for " + className);
                    }
                } catch (Exception e) {
                    StatusReporter.warn("Backoffice widget reload failed for " + className
                            + " (" + e + ") — the widget may need a page refresh");
                }
            }

            ClassReloader.ReloadResult result = ClassReloader.ReloadResult.structuralSuccess(
                    springBean, isInterceptor(targetClass), isStructural);
            result.setAnnotationsChanged(diff.isAnnotationsChanged());
            if (diff.isAnnotationOnly()) {
                StatusReporter.info("Annotation change on " + className
                        + " — re-scanning the frameworks that read it");
            }
            return result;

        } catch (Throwable e) {
            // Throwable: defineHiddenClass surfaces VerifyError (an Error)
            // for e.g. cross-companion invocations between nested classes.
            // For body-only diffs a plain redefinition of the original class
            // is a fully valid application of the change — the registered
            // transformer re-injects the infrastructure during redefine.
            if (!diff.isStructural()) {
                StatusReporter.warn("Companion reload failed for " + className +
                        " (" + e + ") — falling back to class redefinition");
                return standardReload(className, newBytecode);
            }
            return ClassReloader.ReloadResult.failure(
                    "Structural reload failed: " + e.getMessage(), true);
        }
    }

    private Class<?> findLoadedClass(String className) {
        return com.onurkat.reclazz.agent.ClassLookup.findLoadedClass(className, instrumentation);
    }

    private boolean isSpringBean(Class<?> clazz) {
        try {
            for (var annotation : clazz.getAnnotations()) {
                String annotName = annotation.annotationType().getName();
                if (annotName.contains("springframework") &&
                        (annotName.endsWith(".Service") ||
                         annotName.endsWith(".Component") ||
                         annotName.endsWith(".Repository") ||
                         annotName.endsWith(".Controller") ||
                         annotName.endsWith(".RestController") ||
                         annotName.endsWith(".Configuration") ||
                         annotName.endsWith(".Bean"))) {
                    return true;
                }
                if (annotName.contains("hybris") && annotName.endsWith(".SystemSetup")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean isInterceptor(Class<?> clazz) {
        try {
            for (Class<?> iface : clazz.getInterfaces()) {
                if (iface.getName().contains("de.hybris.platform.servicelayer.interceptor")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean isBackofficeWidget(Class<?> clazz) {
        try {
            // Check annotations for @SocketEvent
            for (var method : clazz.getDeclaredMethods()) {
                for (var annotation : method.getAnnotations()) {
                    if (annotation.annotationType().getName().contains("SocketEvent")) return true;
                }
            }
            // Check superclass chain for DefaultWidgetController
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                if (current.getName().contains("DefaultWidgetController")) return true;
                current = current.getSuperclass();
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Register forged Method/Field objects with ReflectionBridge for added members.
     */
    private void registerReflectionMetadata(Class<?> targetClass, String internalName,
                                             StructuralAnalyzer.StructuralDiff diff,
                                             Map<String, MethodHandle> newTargets,
                                             MethodHandles.Lookup companionLookup,
                                             Class<?> companionClass) {
        if (!MethodForge.isAvailable()) return;

        // Collect all forged objects first, then atomically swap into ReflectionBridge
        List<java.lang.reflect.Method> forgedMethods = new ArrayList<>();
        List<java.lang.reflect.Field> forgedFields = new ArrayList<>();

        // Forge Method objects for each added method
        for (String methodKey : diff.getAddedMethods()) {
            int colonIdx = methodKey.indexOf(':');
            String methodName = methodKey.substring(0, colonIdx);
            String descriptor = methodKey.substring(colonIdx + 1);

            // Skip constructors and class initializers
            if ("<init>".equals(methodName) || "<clinit>".equals(methodName)) continue;

            try {
                // Find the MethodSig from the new methods list to get access modifiers
                int modifiers = Modifier.PUBLIC; // default
                for (var sig : diff.getNewMethods()) {
                    if (sig.name().equals(methodName) && sig.descriptor().equals(descriptor)) {
                        modifiers = sig.access();
                        break;
                    }
                }

                // Parse descriptor to get parameter types and return type
                MethodType mt = MethodType.fromMethodDescriptorString(descriptor,
                        targetClass.getClassLoader());
                Class<?>[] paramTypes = mt.parameterArray();
                Class<?> returnType = mt.returnType();

                // Find dispatch MethodHandle for this method
                MethodHandle dispatchMH = findDispatchHandle(methodName, descriptor,
                        newTargets, companionLookup, companionClass, targetClass);

                java.lang.reflect.Method forged = MethodForge.forgeMethod(
                        targetClass, methodName, paramTypes, returnType,
                        modifiers, dispatchMH, null);

                if (forged != null) {
                    forgedMethods.add(forged);
                }
            } catch (Exception e) {
                if (config.isVerbose()) {
                    StatusReporter.warn("Failed to forge Method " + methodName + ": " + e.getMessage());
                }
            }
        }

        // Forge Field objects for each added field
        for (String fieldKey : diff.getAddedFields()) {
            int colonIdx = fieldKey.indexOf(':');
            String fieldName = fieldKey.substring(0, colonIdx);
            String fieldDesc = fieldKey.substring(colonIdx + 1);

            try {
                int modifiers = Modifier.PUBLIC;
                for (var sig : diff.getNewFields()) {
                    if (sig.name().equals(fieldName) && sig.descriptor().equals(fieldDesc)) {
                        modifiers = sig.access();
                        break;
                    }
                }

                Class<?> fieldType = descriptorToClass(fieldDesc, targetClass.getClassLoader());

                java.lang.reflect.Field forged = MethodForge.forgeField(
                        targetClass, fieldName, fieldType, modifiers);

                if (forged != null) {
                    forgedFields.add(forged);
                }
            } catch (Exception e) {
                if (config.isVerbose()) {
                    StatusReporter.warn("Failed to forge Field " + fieldName + ": " + e.getMessage());
                }
            }
        }

        // Atomic swap — no window where reflection sees incomplete state
        ReflectionBridge.replaceClassState(internalName, forgedMethods, forgedFields);
    }

    /**
     * Find the dispatch MethodHandle for a method from the companion class targets.
     */
    private MethodHandle findDispatchHandle(String methodName, String descriptor,
                                             Map<String, MethodHandle> newTargets,
                                             MethodHandles.Lookup companionLookup,
                                             Class<?> companionClass,
                                             Class<?> targetClass) {
        // Try to find in the newTargets map by exact site key match.
        // Site keys use format "name:descHash" (instance) or "static:name:descHash" (static).
        String descHash = CallSiteAdapter.descHash(descriptor);
        String instanceKey = methodName + ":" + descHash;
        String staticKey = "static:" + methodName + ":" + descHash;
        MethodHandle target = newTargets.get(instanceKey);
        if (target != null) return target;
        target = newTargets.get(staticKey);
        if (target != null) return target;

        // Try direct lookup in companion class
        try {
            // For instance methods, companion has static method with receiver prepended
            String staticDesc = "(" + "L" + targetClass.getName().replace('.', '/') + ";" +
                    descriptor.substring(1);
            MethodType mt = MethodType.fromMethodDescriptorString(staticDesc, targetClass.getClassLoader());
            return companionLookup.findStatic(companionClass, methodName, mt);
        } catch (Exception e) {
            // Returning null here means "this member has no companion
            // target", which is normal for unchanged members — but a real
            // resolution failure looks identical, so surface it in verbose.
            if (config.isVerbose()) {
                StatusReporter.warn("No companion target for " + methodName
                        + descriptor + ": " + e);
            }
        }

        return null;
    }

    /**
     * Convert a JVM field descriptor to a Class object.
     */
    private static Class<?> descriptorToClass(String desc, ClassLoader cl) {
        return switch (desc.charAt(0)) {
            case 'Z' -> boolean.class;
            case 'B' -> byte.class;
            case 'C' -> char.class;
            case 'S' -> short.class;
            case 'I' -> int.class;
            case 'J' -> long.class;
            case 'F' -> float.class;
            case 'D' -> double.class;
            case 'V' -> void.class;
            case 'L' -> {
                String className = desc.substring(1, desc.length() - 1).replace('/', '.');
                try {
                    yield Class.forName(className, false, cl);
                } catch (ClassNotFoundException e) {
                    yield Object.class;
                }
            }
            case '[' -> {
                try {
                    yield Class.forName(desc.replace('/', '.'), false, cl);
                } catch (ClassNotFoundException e) {
                    yield Object[].class;
                }
            }
            default -> Object.class;
        };
    }
}
