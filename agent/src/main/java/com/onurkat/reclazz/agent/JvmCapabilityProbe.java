/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.ui.StatusReporter;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

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
            // A name nothing else can be using, so a second probe in the same
            // JVM cannot collide with the first.
            String probeName = "com/onurkat/reclazz/agent/Probe$$Reclazz$$" + System.nanoTime();

            byte[] v1Bytes = probeClass(probeName, false);
            byte[] v2Bytes = probeClass(probeName, true);

            Class<?> probeClass = ProbeLoader.define(probeName, v1Bytes);

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
            StatusReporter.info("Capability probe inconclusive: " + com.onurkat.reclazz.ui.Failures.describe(e));
            return null; // Let fallback logic decide
        } catch (Error e) {
            // JVM-level errors (e.g., "attempted to add a method"). This is
            // the common path on standard JVMs — same reasoning as the
            // UnsupportedOperationException branch above: companion mode
            // still handles structural reloads.
            return CapabilityLevel.COMPANION_MODE;
        }
    }

    /**
     * The two-method-or-one class this probe redefines between.
     *
     * <p>Written with ASM rather than a code-generation library because it is
     * the only thing that library was here for. The agent is loaded into the
     * JVM that runs somebody else's application, so a dependency that exists
     * to emit two methods returning a constant is 25 MB of classes in that
     * application to save sixty lines here.
     *
     * @param withAddedMethod true for the second version, whose extra method is
     *                        the structural change the JVM is being asked about
     */
    private static byte[] probeClass(String internalName, boolean withAddedMethod) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                internalName, null, "java/lang/Object", null);

        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        constantMethod(cw, "probeMethod", withAddedMethod ? "v2" : "v1");
        if (withAddedMethod) {
            constantMethod(cw, "addedMethod", "added");
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void constantMethod(ClassWriter cw, String name, String value) {
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, "()Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitLdcInsn(value);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Defines the probe class without asking anyone else to. */
    private static final class ProbeLoader extends ClassLoader {

        private ProbeLoader() {
            super(JvmCapabilityProbe.class.getClassLoader());
        }

        static Class<?> define(String internalName, byte[] bytecode) {
            return new ProbeLoader().defineClass(
                    internalName.replace('/', '.'), bytecode, 0, bytecode.length);
        }
    }
}
