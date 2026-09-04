/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles class redefinition using the Java Instrumentation API.
 *
 * <p>This is the low-level redefinition path used for classes NOT tracked by
 * Reclazz's {@code transformContext}. On standard JVMs, JVMTI
 * {@code redefineClasses} only accepts method-body changes, so structural
 * edits to unwatched classes fall back to the advice built below.
 *
 * <p>For watched classes (the common case — user project code),
 * {@link com.onurkat.reclazz.reload.StructuralReloader} handles structural
 * changes via the companion-class path on ANY JDK 17+ regardless of vendor.
 *
 * <p>On JBR/DCEVM with {@code -XX:+AllowEnhancedClassRedefinition} this
 * class also handles structural changes directly via enhanced redefinition.
 */
public class ClassReloader {

    private final Instrumentation instrumentation;
    private final JvmCapabilityProbe.CapabilityLevel capabilityLevel;
    private final String vmDescription;

    // Cache reflection lookups — classes rarely change their annotations at runtime
    private final Map<String, Boolean> springBeanCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> interceptorCache = new ConcurrentHashMap<>();

    public ClassReloader(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;

        JvmCapabilityProbe.ProbeResult probe = ReclazzAgent.getProbeResult();
        if (probe != null) {
            this.capabilityLevel = probe.getCapability();
            this.vmDescription = probe.getVmDescription();
        } else {
            this.capabilityLevel = JvmCapabilityProbe.CapabilityLevel.COMPANION_MODE;
            this.vmDescription = "Unknown";
        }
    }

    /**
     * Reload a class by its fully qualified name with new bytecode.
     */
    public ReloadResult reload(String className, byte[] newBytecode) {
        String tooNew = com.onurkat.reclazz.util.BytecodeVersion.rejectionReason(newBytecode);
        if (tooNew != null) {
            return ReloadResult.failure(className + " " + tooNew, false);
        }

        try {
            // Find the already-loaded class in the JVM
            Class<?> existingClass = findLoadedClass(className);

            if (existingClass == null) {
                // Class not loaded yet - this is a new class
                return handleNewClass(className, newBytecode);
            }

            // Taken before the redefinition: on an enhanced-redefinition VM the
            // redefine puts the field on the loaded class, and a comparison
            // made afterwards would find nothing to report.
            var mappingChange =
                    com.onurkat.reclazz.reload.JpaEntityChange.check(existingClass, newBytecode);
            var enumChange =
                    com.onurkat.reclazz.reload.EnumConstantChange.check(existingClass, newBytecode);

            // Redefine the existing class
            ClassDefinition definition = new ClassDefinition(existingClass, newBytecode);
            instrumentation.redefineClasses(definition);

            // A mapped class that gained a persistent field reloads cleanly and
            // is still not persisted, because Hibernate's mapping was built
            // once at startup and a redefinition is not something it listens
            // for. Said only after the reload actually succeeded.
            com.onurkat.reclazz.reload.JpaEntityChange.report(className, existingClass, mappingChange);
            // An enhanced-redefinition VM accepts an added enum constant and
            // leaves it null, so success here is exactly where it needs saying.
            com.onurkat.reclazz.reload.EnumConstantAppender.applyOrExplain(
                    className, existingClass, newBytecode, enumChange, instrumentation);

            // Invalidate caches — class may have gained/lost annotations
            springBeanCache.remove(className);
            interceptorCache.remove(className);

            // Check if this is a Spring bean or interceptor
            boolean isSpringBean = isSpringManagedBean(existingClass);
            boolean isInterceptor = isHybrisInterceptor(existingClass);

            return ReloadResult.success(isSpringBean, isInterceptor);

        } catch (UnsupportedOperationException e) {
            // This is the JVM refusing one change, not the JVM saying it cannot
            // redefine anything. HotSpot throws it with "class redefinition
            // failed: attempted to add a method", which is the whole
            // explanation, and it used to be replaced with "Class redefinition
            // not supported by this JVM": wrong about the JVM, and it threw
            // away the one sentence that said what to change. It also meant the
            // message matching below never ran for the commonest refusal there
            // is, because this catch comes first.
            String said = e.getMessage();
            ReloadResult result = ReloadResult.failure(
                    said != null && !said.isBlank()
                            ? said
                            : "Class redefinition not supported by this JVM",
                    true);
            result.setStructuralChangeAdvice(buildStructuralChangeAdvice());
            return result;

        } catch (ClassNotFoundException e) {
            return ReloadResult.failure("Class not found: " + className, false);

        } catch (UnmodifiableClassException e) {
            return ReloadResult.failure("Class is not modifiable: " + className, false);

        } catch (Error e) {
            // UnsupportedClassVersionError or other structural change errors
            String msg = e.getMessage();
            if (msg != null && (msg.contains("add") || msg.contains("remove") ||
                    msg.contains("schema") || msg.contains("hierarchy"))) {
                ReloadResult result = ReloadResult.failure(msg, true);
                result.setStructuralChangeAdvice(buildStructuralChangeAdvice());
                return result;
            }
            return ReloadResult.failure("JVM error: " + msg, false);

        } catch (Exception e) {
            String msg = e.getMessage();
            boolean structural = describesAStructuralRefusal(msg);
            ReloadResult result = ReloadResult.failure(msg, structural);
            if (structural) {
                result.setStructuralChangeAdvice(buildStructuralChangeAdvice());
            }
            return result;
        }
    }

    /**
     * Batch reload multiple classes atomically using a single redefineClasses call.
     * Returns a map of class name to reload result.
     * New classes are handled individually; existing classes are batched for atomicity.
     */
    public Map<String, ReloadResult> reloadBatch(Map<String, byte[]> classMap) {
        Map<String, ReloadResult> results = new LinkedHashMap<>();
        List<ClassDefinition> definitions = new ArrayList<>();
        Map<String, Class<?>> resolvedClasses = new LinkedHashMap<>();
        // Compared before the batch redefinition, for the same reason as above.
        Map<String, com.onurkat.reclazz.reload.JpaEntityChange.Change> mappingChanges =
                new LinkedHashMap<>();
        Map<String, com.onurkat.reclazz.reload.EnumConstantChange.Change> enumChanges =
                new LinkedHashMap<>();

        // Resolve classes and separate new from existing
        for (var entry : classMap.entrySet()) {
            String className = entry.getKey();
            byte[] bytecode = entry.getValue();

            Class<?> existingClass = findLoadedClass(className);
            if (existingClass == null) {
                results.put(className, handleNewClass(className, bytecode));
            } else {
                definitions.add(new ClassDefinition(existingClass, bytecode));
                resolvedClasses.put(className, existingClass);
                var mappingChange =
                        com.onurkat.reclazz.reload.JpaEntityChange.check(existingClass, bytecode);
                if (mappingChange != null) mappingChanges.put(className, mappingChange);
                var enumChange =
                        com.onurkat.reclazz.reload.EnumConstantChange.check(existingClass, bytecode);
                if (enumChange != null) enumChanges.put(className, enumChange);
            }
        }

        if (definitions.isEmpty()) {
            return results;
        }

        // Attempt atomic batch redefinition
        try {
            instrumentation.redefineClasses(definitions.toArray(new ClassDefinition[0]));

            // Invalidate caches for all redefined classes
            for (String name : resolvedClasses.keySet()) {
                springBeanCache.remove(name);
                interceptorCache.remove(name);
            }

            for (var entry : mappingChanges.entrySet()) {
                com.onurkat.reclazz.reload.JpaEntityChange.report(entry.getKey(),
                        resolvedClasses.get(entry.getKey()), entry.getValue());
            }
            for (var entry : enumChanges.entrySet()) {
                com.onurkat.reclazz.reload.EnumConstantAppender.applyOrExplain(
                        entry.getKey(), resolvedClasses.get(entry.getKey()),
                        classMap.get(entry.getKey()), entry.getValue(), instrumentation);
            }

            // All succeeded — populate results
            for (var entry : resolvedClasses.entrySet()) {
                String className = entry.getKey();
                Class<?> clazz = entry.getValue();
                boolean isSpringBean = isSpringManagedBean(clazz);
                boolean isInterceptor = isHybrisInterceptor(clazz);
                results.put(className, ReloadResult.success(isSpringBean, isInterceptor));
            }
        } catch (Exception | Error e) {
            // Batch failed — fall back to individual reloads
            StatusReporter.info("Batch redefinition failed, falling back to individual reloads");
            for (var entry : classMap.entrySet()) {
                String className = entry.getKey();
                if (!results.containsKey(className)) {
                    results.put(className, reload(className, entry.getValue()));
                }
            }
        }

        // Counted here rather than per class, because the cost the developer
        // pays is per save: one save that redefines eight classes is one thing
        // they did, and the warning reads as the number of times they did it.
        MetaspaceWatch.afterReload();

        return results;
    }

    /**
     * Whether a JVM's refusal is about the shape of the class rather than
     * about the reload machinery.
     *
     * <p>Pure, and package-visible, because it is a decision made from a
     * string the JVM chose and there is no way to reach it through the reload
     * path without a JVM that refuses in each of these ways.
     */
    static boolean describesAStructuralRefusal(String jvmMessage) {
        if (jvmMessage == null) return false;
        return jvmMessage.contains("attempted to add")
                || jvmMessage.contains("attempted to delete")
                || jvmMessage.contains("attempted to change")
                || jvmMessage.contains("class modifiers")
                || jvmMessage.contains("hierarchy")
                || jvmMessage.contains("superclass");
    }

    private String buildStructuralChangeAdvice() {
        boolean hasFlag = false;
        try {
            for (String arg : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (arg.contains("+AllowEnhancedClassRedefinition")) {
                    hasFlag = true;
                    break;
                }
            }
        } catch (Exception ignored) {}

        boolean isEnhancedVm = capabilityLevel == JvmCapabilityProbe.CapabilityLevel.ENHANCED_REDEFINITION;
        boolean isGraalVM = JvmCapabilityProbe.isGraalVM();

        if (isGraalVM) {
            return "GraalVM has limited class redefinition support. " +
                    "Consider switching to JetBrains Runtime (JBR) or standard OpenJDK. VM: " + vmDescription;
        } else if (isEnhancedVm && hasFlag) {
            return "JBR/DCEVM enhanced redefinition active but change rejected — " +
                    "this may be a JVM version limitation. VM: " + vmDescription;
        } else if (isEnhancedVm) {
            return vmDescription + " detected but -XX:+AllowEnhancedClassRedefinition not set. " +
                    "Add it to JVM arguments.";
        } else {
            // COMPANION_MODE reached this fallback: the class is unwatched
            // (not in transformContext), so the companion-class path doesn't
            // apply. Reclazz's structural reloader handles watched project
            // code — this branch means the edit targets something outside
            // the watched set.
            return "Structural change detected on unwatched class " + vmDescription + ". " +
                    "Reclazz's companion-class reloader only handles classes in the watched " +
                    "project/extension set; library or platform classes can't be restructured " +
                    "at runtime on this JVM. Restart the server to pick up the change.";
        }
    }

    private Class<?> findLoadedClass(String className) {
        return ClassLookup.findLoadedClass(className, instrumentation);
    }

    /**
     * Handle a newly added class (not previously loaded).
     */
    private ReloadResult handleNewClass(String className, byte[] bytecode) {
        StatusReporter.info("New class detected: " + className);
        StatusReporter.info("New classes will be available when first referenced at runtime.");
        // New classes don't need redefinition - they'll be loaded fresh when first used.
        // The compiled .class file is already in the correct output directory.
        return ReloadResult.success(false, false);
    }

    /**
     * Check if a class is managed by Spring (has Spring annotations or is registered as a bean).
     * Results are cached since annotation presence is stable across reloads.
     */
    private boolean isSpringManagedBean(Class<?> clazz) {
        return springBeanCache.computeIfAbsent(clazz.getName(), k -> detectSpringBean(clazz));
    }

    private boolean detectSpringBean(Class<?> clazz) {
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
                // Check for Hybris-specific @SystemSetup
                if (annotName.contains("hybris") && annotName.endsWith(".SystemSetup")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // Class might not be fully loaded yet
        }
        return false;
    }

    /**
     * Check if a class is a Hybris interceptor.
     * Results are cached since interface hierarchy is stable across reloads.
     */
    private boolean isHybrisInterceptor(Class<?> clazz) {
        return interceptorCache.computeIfAbsent(clazz.getName(), k -> detectInterceptor(clazz));
    }

    private boolean detectInterceptor(Class<?> clazz) {
        try {
            for (Class<?> iface : getAllInterfaces(clazz)) {
                String ifaceName = iface.getName();
                if (ifaceName.contains("de.hybris.platform.servicelayer.interceptor")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private Class<?>[] getAllInterfaces(Class<?> clazz) {
        java.util.Set<Class<?>> interfaces = new java.util.LinkedHashSet<>();
        Class<?> current = clazz;
        while (current != null) {
            for (Class<?> iface : current.getInterfaces()) {
                interfaces.add(iface);
            }
            current = current.getSuperclass();
        }
        return interfaces.toArray(new Class[0]);
    }

    /**
     * Result of a class reload attempt.
     */
    public static class ReloadResult {
        private final boolean success;
        private final boolean springBean;
        private final boolean interceptor;
        private final boolean structuralChange;
        private final boolean structuralReload;
        private final String error;
        private String structuralChangeAdvice;
        private boolean annotationsChanged;
        private boolean methodsAdded;
        private java.util.Set<String> addedMethodSigs = java.util.Set.of();
        private byte[] newBytecode;
        /**
         * What the change was, in the words the one reload line prints: "v1,
         * +1 method". Carried here so the shape and the timing arrive on the
         * same line instead of on two consecutive ones saying the same event.
         */
        private String shape;
        private boolean springMvcReloaded;

        private ReloadResult(boolean success, boolean springBean, boolean interceptor,
                             boolean structuralChange, boolean structuralReload, String error) {
            this.success = success;
            this.springBean = springBean;
            this.interceptor = interceptor;
            this.structuralChange = structuralChange;
            this.structuralReload = structuralReload;
            this.error = error;
        }

        public static ReloadResult success(boolean springBean, boolean interceptor) {
            return new ReloadResult(true, springBean, interceptor, false, false, null);
        }

        /**
         * A reload applied through the companion-class engine.
         *
         * @param structural whether the change actually added or removed
         *                   members. This drives user-facing reporting, so
         *                   it must reflect the CHANGE, not which engine ran
         *                   — every watched class goes through the companion
         *                   path, and hardcoding true here made a one-line
         *                   body edit announce itself as "Structural reload"
         *                   and inflate the IDE widget's structural counter.
         */
        public static ReloadResult structuralSuccess(boolean springBean, boolean interceptor,
                                                     boolean structural) {
            return new ReloadResult(true, springBean, interceptor, structural, structural, null);
        }

        public static ReloadResult failure(String error, boolean structuralChange) {
            return new ReloadResult(false, false, false, structuralChange, false, error);
        }

        void setStructuralChangeAdvice(String advice) { this.structuralChangeAdvice = advice; }
        public void setSpringMvcReloaded(boolean reloaded) { this.springMvcReloaded = reloaded; }
        public void setShape(String shape) { this.shape = shape; }
        public String getShape() { return shape; }

        /**
         * Whether the edit moved an annotation. Set separately from the
         * factory because it is orthogonal to how the reload was applied:
         * an annotation edit is not structural, yet the frameworks that read
         * annotations still have to be told, which is exactly what did not
         * happen before.
         */
        public void setAnnotationsChanged(boolean changed) { this.annotationsChanged = changed; }

        /** Whether this reload added methods; the mapping scan cannot see them. */
        public void setMethodsAdded(boolean added) { this.methodsAdded = added; }

        public boolean isMethodsAdded() { return methodsAdded; }

        /** The methods this reload added, as name:descriptor. */
        public void setAddedMethodSigs(java.util.Set<String> sigs) { this.addedMethodSigs = sigs; }

        public java.util.Set<String> getAddedMethodSigs() { return addedMethodSigs; }

        /** The compiled class as saved, which is where the added methods can still be read. */
        public void setNewBytecode(byte[] bytes) { this.newBytecode = bytes; }

        public byte[] getNewBytecode() { return newBytecode; }

        public boolean isSuccess() { return success; }
        public boolean isSpringBean() { return springBean; }
        public boolean isInterceptor() { return interceptor; }
        public boolean isStructuralChange() { return structuralChange; }
        public boolean isStructuralReload() { return structuralReload; }
        public boolean isAnnotationsChanged() { return annotationsChanged; }
        public boolean isSpringMvcReloaded() { return springMvcReloaded; }
        public String getError() { return error; }
        public String getStructuralChangeAdvice() { return structuralChangeAdvice; }
    }
}
