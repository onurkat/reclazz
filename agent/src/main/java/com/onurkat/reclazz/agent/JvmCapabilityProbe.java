/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.ui.StatusReporter;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FixedValue;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Multi-layer JVM capability detection for enhanced class redefinition support.
 *
 * Detection strategy (in order):
 * 1. JVM flags — scan for -XX:+AllowEnhancedClassRedefinition
 * 2. VM identity — check vm.name/vendor for JBR, DCEVM, Trava
 * 3. Runtime probe — attempt a structural redefinition to definitively test
 */
public class JvmCapabilityProbe {

    public enum CapabilityLevel {
        /** JBR/DCEVM with {@code -XX:+AllowEnhancedClassRedefinition}: structural
         *  changes applied directly to the original {@code Class} object via
         *  {@link Instrumentation#redefineClasses}. Full reflective visibility. */
        ENHANCED_REDEFINITION,
        /** Standard JDK 17+: structural changes go through Reclazz's
         *  companion-class path (hidden nestmate + invokedynamic rewrite).
         *  Hot-compiled callers reach new members; reflection on the original
         *  class and reflective caches do not. */
        COMPANION_MODE,
        /** Instrumentation not supported (e.g. Native Image): method body
         *  changes only. */
        METHOD_BODY_ONLY
    }

    public static final class ProbeResult {
        private final CapabilityLevel capability;
        private final String vmDescription;
        private final String detectionMethod;

        public ProbeResult(CapabilityLevel capability, String vmDescription, String detectionMethod) {
            this.capability = capability;
            this.vmDescription = vmDescription;
            this.detectionMethod = detectionMethod;
        }

        public CapabilityLevel getCapability() { return capability; }
        public String getVmDescription() { return vmDescription; }
        public String getDetectionMethod() { return detectionMethod; }

        /** True only for JBR/DCEVM-class VMs with enhanced redefinition.
         *  ReclazzAgent uses this to decide whether to install its companion-class
         *  transform engine (skipped when the JVM already handles it natively). */
        public boolean hasEnhancedRedefinition() {
            return capability == CapabilityLevel.ENHANCED_REDEFINITION;
        }
    }

    /**
     * Probe the JVM to determine its class redefinition capabilities.
     */
    public static ProbeResult probe(Instrumentation instrumentation) {
        String vmDescription = buildVmDescription();

        // Layer 1: Check JVM flags for -XX:+AllowEnhancedClassRedefinition
        if (hasEnhancedRedefinitionFlag()) {
            return new ProbeResult(CapabilityLevel.ENHANCED_REDEFINITION, vmDescription, "jvm-flag");
        }

        // Layer 2: Check VM identity (informational — used if probe fails/skipped)
        String vmIdentity = detectVmIdentity();

        // Layer 3: Runtime probe — definitive test
        CapabilityLevel probed = runtimeProbe(instrumentation);
        if (probed != null) {
            return new ProbeResult(probed, vmDescription, "probe");
        }

        // Fallback: if probe couldn't run, use VM identity as best guess
        if (vmIdentity != null) {
            return new ProbeResult(CapabilityLevel.ENHANCED_REDEFINITION, vmDescription, "vm-name");
        }

        return new ProbeResult(CapabilityLevel.COMPANION_MODE, vmDescription, "default");
    }

    private static String buildVmDescription() {
        String vmName = System.getProperty("java.vm.name", "Unknown VM");
        String javaVersion = System.getProperty("java.version", "?");
        String vmVendor = System.getProperty("java.vm.vendor", "");
        String javaVendor = System.getProperty("java.vendor", "");

        // Try to build a concise, recognizable description
        if (vmName.contains("JetBrains") || vmName.contains("JBR")) {
            return "JBR " + javaVersion;
        }
        if (vmName.contains("DCEVM") || vmVendor.contains("Trava")) {
            return "DCEVM " + javaVersion;
        }
        if (vmName.contains("GraalVM") || vmVendor.contains("GraalVM")) {
            return "GraalVM " + javaVersion;
        }
        if (javaVendor.contains("SAP") || vmName.contains("SapMachine")) {
            return "SapMachine " + javaVersion;
        }
        if (javaVendor.contains("Oracle") && !vmName.contains("OpenJDK")) {
            return "Oracle JDK " + javaVersion;
        }
        if (javaVendor.contains("Amazon") || vmName.contains("Corretto")) {
            return "Corretto " + javaVersion;
        }
        if (javaVendor.contains("Eclipse") || javaVendor.contains("Adoptium") || vmName.contains("Temurin")) {
            return "Temurin " + javaVersion;
        }
        if (javaVendor.contains("Azul") || vmName.contains("Zulu")) {
            return "Azul Zulu " + javaVersion;
        }
        if (javaVendor.contains("BellSoft") || vmName.contains("Liberica")) {
            return "Liberica " + javaVersion;
        }
        return vmName + " " + javaVersion;
    }

    /**
     * Layer 1: Scan JVM input arguments for -XX:+AllowEnhancedClassRedefinition.
     */
    private static boolean hasEnhancedRedefinitionFlag() {
        try {
            List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
            for (String arg : args) {
                if (arg.contains("AllowEnhancedClassRedefinition")) {
                    // Check it's enabled (+) not disabled (-)
                    return arg.contains("+AllowEnhancedClassRedefinition");
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Layer 2: Detect known enhanced-redefinition VMs by name/vendor.
     * Returns a non-null string if a known enhanced VM is detected.
     * Only returns non-null for VMs that support structural changes (JBR, DCEVM).
     */
    private static String detectVmIdentity() {
        String vmName = System.getProperty("java.vm.name", "").toLowerCase();
        String vmVendor = System.getProperty("java.vm.vendor", "").toLowerCase();
        String vmVersion = System.getProperty("java.vm.version", "").toLowerCase();

        if (vmName.contains("jetbrains") || vmName.contains("jbr")) {
            return "JBR";
        }
        if (vmName.contains("dcevm") || vmVendor.contains("trava") || vmVersion.contains("dcevm")) {
            return "DCEVM";
        }
        // GraalVM, Oracle, SapMachine etc. — standard redefinition only, return null
        return null;
    }

    /**
     * Check if running on GraalVM, which has known class redefinition limitations.
     */
    public static boolean isGraalVM() {
        String vmName = System.getProperty("java.vm.name", "");
        String vmVendor = System.getProperty("java.vm.vendor", "");
        return vmName.contains("GraalVM") || vmVendor.contains("GraalVM");
    }

    /**
     * Layer 3: Runtime probe — generate a class, then attempt a structural redefinition.
     * This is the only 100% reliable way to determine capability.
     */
    private static CapabilityLevel runtimeProbe(Instrumentation instrumentation) {
        if (!instrumentation.isRedefineClassesSupported()) {
            return CapabilityLevel.METHOD_BODY_ONLY;
        }

        try {
            String probeName = "com.onurkat.reclazz.agent.Probe$$Reclazz$$" + System.nanoTime();

            // Generate v1: a class with one method
            DynamicType.Unloaded<?> v1 = new ByteBuddy()
                    .subclass(Object.class)
                    .name(probeName)
                    .defineMethod("probeMethod", String.class, Visibility.PUBLIC)
                    .intercept(FixedValue.value("v1"))
                    .make();

            // Generate v2: same class with an added method (structural change)
            DynamicType.Unloaded<?> v2 = new ByteBuddy()
                    .subclass(Object.class)
                    .name(probeName)
                    .defineMethod("probeMethod", String.class, Visibility.PUBLIC)
                    .intercept(FixedValue.value("v2"))
                    .defineMethod("addedMethod", String.class, Visibility.PUBLIC)
                    .intercept(FixedValue.value("added"))
                    .make();

            byte[] v1Bytes = v1.getBytes();
            byte[] v2Bytes = v2.getBytes();

            // Load v1 into the JVM
            Class<?> probeClass = v1.load(JvmCapabilityProbe.class.getClassLoader())
                    .getLoaded();

            // Attempt structural redefinition with v2
            ClassDefinition redefinition = new ClassDefinition(probeClass, v2Bytes);
            instrumentation.redefineClasses(redefinition);

            // If we get here, the JVM natively accepts structural changes.
            return CapabilityLevel.ENHANCED_REDEFINITION;

        } catch (UnsupportedOperationException e) {
            // Standard JVM rejects structural changes via JVMTI. The Reclazz
            // companion-class reloader still handles structural reloads on
            // any JDK 17+ — report COMPANION_MODE so the agent installs its
            // transform engine instead of falling back to body-only.
            return CapabilityLevel.COMPANION_MODE;
        } catch (Exception e) {
            // Any error during probe (e.g., class format error, linkage error)
            // likely means structural changes aren't supported
            StatusReporter.info("Capability probe inconclusive: " + e.getMessage());
            return null; // Let fallback logic decide
        } catch (Error e) {
            // JVM-level errors (e.g., "attempted to add a method"). This is
            // the common path on standard JVMs — same reasoning as the
            // UnsupportedOperationException branch above: companion mode
            // still handles structural reloads.
            return CapabilityLevel.COMPANION_MODE;
        }
    }
}
