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

        // Before anything is generated or swapped: the JVM cannot load a class
        // file newer than itself, and finding that out halfway through leaves a
        // warning in the middle of a reload that then reports success.
        String tooNew = com.onurkat.reclazz.util.BytecodeVersion.rejectionReason(newBytecode);
        if (tooNew != null) {
            return ClassReloader.ReloadResult.failure(className + " " + tooNew, false);
        }

        try {
            TransformContext.ClassMetadata oldMetadata = context.getMetadata(internalName);

            if (oldMetadata == null) {
                // Watched, but never instrumented: there is no companion to
                // dispatch to, so the structural path has nothing to work with
                // and this used to fall straight through to a redefine the JVM
                // rejects. Adding a field to a JPA entity ended exactly there,
                // losing the method bodies with it, and nothing said why.
                reportUninstrumented(className, findLoadedClass(className));
                return standardReload(className, newBytecode);
            }

            // Analyze structural changes
            StructuralAnalyzer.StructuralDiff diff = StructuralAnalyzer.analyze(oldMetadata, newBytecode);

            // Method bodies the superclass salvage pins to their previous
            // implementation, and the pre-spliced payload that carries those
            // previous bodies. Both stay empty outside the salvage path.
            java.util.Map<String, String> pinnedMethods = java.util.Map.of();
            byte[] pinnedRedefinePayload = null;

            if (diff.isUnsupported()) {
                // The hierarchy is not going to be applied by any JVM. The rest
                // of the file still can be, and refusing the whole save threw
                // away method bodies edited alongside the extends clause.
                Class<?> loadedForRevert = findLoadedClass(className);
                HierarchyRevert.Result reverted = HierarchyRevert.toLoadedSuperclass(
                        newBytecode, oldMetadata.getSuperName(),
                        loadedForRevert == null ? null : loadedForRevert.getSuperclass());

                if (!reverted.applied()) {
                    com.onurkat.reclazz.agent.RestartLedger.note(className,
                            "changed its superclass, which no JVM will redefine");
                    return ClassReloader.ReloadResult.failure(
                            className + " changed its superclass, and the rest of the class "
                                    + "cannot be applied without it (" + reverted.reason() + "). "
                                    + "No JVM applies a changed superclass to a loaded class: "
                                    + "redefineClasses rejects it on a stock JDK and on JetBrains "
                                    + "Runtime alike, because every object of this class already "
                                    + "has the old layout and identity. Restart to pick it up.",
                            true);
                }

                String newSuper = simpleName(superNameOf(newBytecode));
                String oldSuper = simpleName(oldMetadata.getSuperName());

                // Everything downstream reads the payload, so it has to be the
                // one that is actually going to be redefined.
                newBytecode = reverted.bytecode();
                diff = StructuralAnalyzer.analyze(oldMetadata, newBytecode);

                if (reverted.entangled().isEmpty()) {
                    StatusReporter.warn(className + " changed its superclass to " + newSuper
                            + ". No JVM applies that to a loaded class, so it still extends "
                            + oldSuper + " until a restart. The method bodies in this save "
                            + "were applied.");
                    com.onurkat.reclazz.agent.RestartLedger.note(className,
                            "changed its superclass, which no JVM will redefine; "
                                    + "method bodies were applied");
                } else {
                    // One or more bodies need the new superclass. The rest of
                    // the save still applies; those bodies are pinned to the
                    // implementation they had. The spliced payload is built
                    // NOW, before anything is generated or retargeted, so a
                    // refusal here leaves the class exactly as it was.
                    PinnedPrep prep = preparePinnedPayload(internalName, diff, newBytecode,
                            loadedForRevert, reverted.entangled().keySet());
                    if (prep.refusal() != null) {
                        String entangledSummary = reverted.entangled().entrySet().stream()
                                .map(e -> e.getKey().substring(0, e.getKey().indexOf(':'))
                                        + " " + e.getValue())
                                .collect(java.util.stream.Collectors.joining("; "));
                        com.onurkat.reclazz.agent.RestartLedger.note(className,
                                "changed its superclass, which no JVM will redefine");
                        return ClassReloader.ReloadResult.failure(
                                className + " changed its superclass, and the rest of the class "
                                        + "cannot be applied without it (" + entangledSummary
                                        + ", and " + prep.refusal() + "). "
                                        + "No JVM applies a changed superclass to a loaded class: "
                                        + "redefineClasses rejects it on a stock JDK and on JetBrains "
                                        + "Runtime alike, because every object of this class already "
                                        + "has the old layout and identity. Restart to pick it up.",
                                true);
                    }
                    pinnedMethods = reverted.entangled();
                    pinnedRedefinePayload = prep.payload();

                    StatusReporter.warn(className + " changed its superclass to " + newSuper
                            + ". No JVM applies that to a loaded class, so it still extends "
                            + oldSuper + " until a restart. The method bodies in this save "
                            + "were applied, except " + describePinned(pinnedMethods)
                            + ", which " + (pinnedMethods.size() == 1 ? "keeps" : "keep")
                            + " the implementation " + (pinnedMethods.size() == 1 ? "it" : "they")
                            + " had.");
                    com.onurkat.reclazz.agent.RestartLedger.note(className,
                            "changed its superclass, which no JVM will redefine; method bodies "
                                    + "were applied, except " + pinnedNames(pinnedMethods)
                                    + ", pinned to the previous implementation");
                }
            }

            // An added or removed interface is not the same case. Enhanced
            // redefinition applies it, existing objects included, and Reclazz
            // hands the class straight to the JVM there. On a stock JDK the
            // redefinition is refused, and the refusal used to be swallowed by
            // the handler that covers the benign constructor-refresh case: the
            // method bodies landed, the interface did not, and the reload was
            // reported as a plain success. Whatever else happens below, that
            // has to be said out loud.
            if (diff.isInterfacesChanged()) {
                reportInterfaceChange(className, diff);
            }

            // Same gap on this engine as on the enhanced one, and for the same
            // reason: the mapping is Hibernate's, built once, and a class
            // redefinition is not something it listens for.
            JpaEntityChange.reportIfChanged(className, findLoadedClass(className), newBytecode);

            // Removed methods are reported at the redefinition site below,
            // because what happens to their existing callers depends on
            // whether that redefinition lands and on where their call sites
            // already dispatch, and the message has to say the true thing for
            // the case that actually occurred.

            // Always use companion class pattern for watched classes.
            // Even body-only changes go through this path to avoid re-adding
            // __reclazz$ext and __reclazz$lookup fields via redefineClasses.
            return structuralReload(className, internalName, newBytecode, diff,
                    pinnedMethods, pinnedRedefinePayload);

        } catch (Exception e) {
            StatusReporter.error("Structural reload error for " + className + ": " + e.getMessage());
            if (config.isVerbose()) {
                e.printStackTrace();
            }
            return ClassReloader.ReloadResult.failure("Structural reload error: " + e.getMessage(), false);
        }
    }

    /** The methods this version declares synchronized, for the warning above. */
    private static java.util.List<String> synchronizedMethodNames(byte[] bytecode) {
        java.util.List<String> names = new ArrayList<>();
        try {
            new org.objectweb.asm.ClassReader(bytecode).accept(
                    new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public org.objectweb.asm.MethodVisitor visitMethod(
                                int access, String name, String descriptor,
                                String signature, String[] exceptions) {
                            if ((access & org.objectweb.asm.Opcodes.ACC_SYNCHRONIZED) != 0
                                    && !"<init>".equals(name) && !"<clinit>".equals(name)
                                    && !names.contains(name)) {
                                names.add(name);
                            }
                            return null;
                        }
                    }, org.objectweb.asm.ClassReader.SKIP_CODE);
        } catch (RuntimeException unreadable) {
            // A class that cannot be read produces no claim.
        }
        return names;
    }

    private static String simpleName(String internalName) {
        if (internalName == null) return "its previous superclass";
        return internalName.substring(internalName.lastIndexOf('/') + 1).replace('$', '.');
    }

    private static String superNameOf(byte[] bytecode) {
        try {
            return new org.objectweb.asm.ClassReader(bytecode).getSuperName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** What the pinned-payload preparation produced: a payload, or the reason it could not. */
    private record PinnedPrep(byte[] payload, String refusal) {
    }

    /**
     * The removal payload, plus the schema the loaded class ends up with.
     *
     * <p>The metadata is the part that was missed the first time, and the live
     * suite is what found it. On the ordinary path the raw payload goes to
     * {@code redefineClasses} and the registered transformer runs during the
     * redefinition, re-recording the class's members FROM that payload, which
     * carries the removed method because the JVM will not let it leave. A
     * pre-transformed payload is passed through untouched, so that recording
     * never happened and the class was left recorded without a member it still
     * has. Restoring the method in a later save then read as adding one, and a
     * class carrying an added member cannot be redefined at all: the
     * constructor-body refresh stopped landing from that point on, and a field
     * added afterwards read its default on new objects. Two of the twenty-three
     * live scenarios failed on exactly that, having passed for a year.
     */
    private record RemovalPrep(byte[] payload, String refusal,
                               TransformContext.ClassMetadata schema) {
    }

    /**
     * Build the redefine payload for a reload with pinned methods.
     *
     * <p>The first per-method salvage skipped the entangled method in the
     * companion and let the ordinary payload through, and the payload still
     * carried the method's NEW body, which the transformer renamed over
     * {@code __reclazz$v0$...}. The trampoline's fallback then dispatched into
     * a body calling a member only the new superclass provides and killed the
     * application thread. So the payload is prepared here, up front: reshaped,
     * transformed, and spliced so the pinned methods' OLD bodies are what the
     * loaded class ends up holding. Any doubt refuses, which is the
     * pre-salvage behaviour for the whole class.
     */
    private PinnedPrep preparePinnedPayload(String internalName,
                                            StructuralAnalyzer.StructuralDiff diff,
                                            byte[] revertedBytecode,
                                            Class<?> loadedClass,
                                            java.util.Set<String> pinnedKeys) {
        // A pinned body is the OLD body, and it may use a member this save
        // removes: the payload would then carry the remover's stub where the
        // pinned body expects an implementation, and the pin would throw at
        // its first call. Refusing is today's behaviour and stays the floor.
        if (!diff.getRemovedMethodSigs().isEmpty() || !diff.getRemovedFieldSigs().isEmpty()) {
            return new PinnedPrep(null,
                    "the same save also removes members, which a pinned body may still use");
        }
        if (transformer == null) {
            return new PinnedPrep(null, "no transformer is registered to prepare the payload");
        }
        byte[] lastKnownGood =
                com.onurkat.reclazz.transform.TransformedClassCache.get(internalName);
        if (lastKnownGood == null) {
            return new PinnedPrep(null,
                    "no last-known-good bytecode is cached for this class");
        }
        try {
            byte[] stripped = com.onurkat.reclazz.transform.AddedMemberStripper.reshape(
                    revertedBytecode, diff.getAddedFields(), diff.getAddedMethods(),
                    diff.getRemovedMethodSigs(), diff.getRemovedFieldSigs());
            // doTransform records metadata as a side effect, and the stripped
            // payload is not the class this reload installs. The record from
            // before is put back right after: the reload writes the real new
            // one when it commits, and a refusal below must leave no trace.
            TransformContext.ClassMetadata before = context.getMetadata(internalName);
            byte[] transformed = transformer.doTransform(internalName, stripped,
                    loadedClass == null ? null : loadedClass.getClassLoader());
            context.putMetadata(internalName, before);
            PinnedMethodSplice.Result spliced = PinnedMethodSplice.apply(
                    transformed, lastKnownGood, pinnedKeys);
            if (!spliced.applied()) {
                return new PinnedPrep(null, spliced.reason());
            }
            return new PinnedPrep(spliced.bytecode(), null);
        } catch (Exception e) {
            return new PinnedPrep(null, "preparing the pinned payload failed: " + e);
        }
    }

    /** The removed methods a payload can carry a previous body for. */
    private static java.util.Set<String> removableMethodKeys(
            StructuralAnalyzer.StructuralDiff diff) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (var m : diff.getRemovedMethodSigs()) {
            if ("<init>".equals(m.name()) || "<clinit>".equals(m.name())) continue;
            // A body that is not there in the first place has nothing to keep.
            if ((m.access() & (Modifier.ABSTRACT | Modifier.NATIVE)) != 0) continue;
            keys.add(m.name() + ":" + m.descriptor());
        }
        return keys;
    }

    /**
     * The redefine payload for a save that removes methods, with those
     * methods' previous implementations spliced back in.
     *
     * <p>The JVM will not let a method leave a loaded class, so the payload
     * has to carry it either way; the only question is what is in it.
     * {@link com.onurkat.reclazz.transform.AddedMemberStripper} puts a stub
     * there that throws, and for a while that was what an existing caller
     * met: measured on Spring Boot, a removed getter turned its JSON endpoint
     * into an HTTP 500 on the next request. Worse than the crash was the
     * inconsistency, because the same edit did something else entirely when
     * an earlier reload had already retargeted the call site, and something
     * else again when the redefinition was refused. Three outcomes for one
     * edit, decided by history the developer cannot see.
     *
     * <p>So there is one outcome now, and it is the one the documentation
     * already promised: code that already holds the method keeps running the
     * implementation it had. What removal means immediately is that every
     * scan stops seeing the member, which is where removal actually matters
     * (serialisation, mapping, injection); what it means for a direct call is
     * settled at the next restart, when the caller either compiles without it
     * or does not compile at all.
     *
     * <p>Same route as the pinned-superclass payload, for the same reason:
     * the splice works on transformed bytes, so the payload is reshaped,
     * transformed and spliced up front, and a refusal anywhere leaves the
     * stub behaviour exactly as it was.
     */
    private RemovalPrep prepareRemovalPayload(String internalName,
                                              StructuralAnalyzer.StructuralDiff diff,
                                              byte[] newBytecode,
                                              Class<?> loadedClass) {
        if (transformer == null) {
            return new RemovalPrep(null, "no transformer is registered to prepare the payload", null);
        }
        byte[] lastKnownGood =
                com.onurkat.reclazz.transform.TransformedClassCache.get(internalName);
        if (lastKnownGood == null) {
            return new RemovalPrep(null,
                    "no last-known-good bytecode is cached for this class", null);
        }
        try {
            byte[] stripped = com.onurkat.reclazz.transform.AddedMemberStripper.reshape(
                    newBytecode, diff.getAddedFields(), diff.getAddedMethods(),
                    diff.getRemovedMethodSigs(), diff.getRemovedFieldSigs());
            // doTransform records metadata as a side effect and this payload
            // is not the class the reload installs, so the record from before
            // is put back: a refusal below must leave no trace.
            TransformContext.ClassMetadata before = context.getMetadata(internalName);
            byte[] transformed = transformer.doTransform(internalName, stripped,
                    loadedClass == null ? null : loadedClass.getClassLoader());
            // What the transformer recorded from the payload IS the schema the
            // loaded class is about to have. It is put back only if the
            // redefinition lands, so a refusal here leaves no trace.
            TransformContext.ClassMetadata fromPayload = context.getMetadata(internalName);
            context.putMetadata(internalName, before);
            PinnedMethodSplice.Result spliced = PinnedMethodSplice.apply(
                    transformed, lastKnownGood, removableMethodKeys(diff));
            if (!spliced.applied()) {
                return new RemovalPrep(null, spliced.reason(), null);
            }
            return new RemovalPrep(spliced.bytecode(), null, fromPayload);
        } catch (Exception e) {
            return new RemovalPrep(null, "preparing the removal payload failed: " + e, null);
        }
    }

    /**
     * Say what a removed method's existing callers will actually meet.
     *
     * <p>One sentence used to claim "existing callers will continue using
     * the previous implementation" for every removal, and what really
     * happens was measured to be three cases. When the constructor-body
     * redefinition above lands, it installs AddedMemberStripper's throwing
     * stub over the removed method's renamed fallback; a call site that was
     * never retargeted to a companion falls back exactly there, so its
     * callers throw (measured on Spring Boot: a removed getter turned its
     * JSON endpoint into HTTP 500 while the message said old code was still
     * running). A site that WAS retargeted by an earlier reload keeps
     * dispatching to that companion body and never reaches the stub
     * (measured on the SAP Commerce integration run: reload the method, then
     * remove it, and callers keep the reloaded body). And when the
     * redefinition is refused, nothing changed at all, so every caller keeps
     * what it had. The first case fails and the other two keep serving,
     * which is why the discriminator is the redefinition outcome plus the
     * dispatch table, both known right here.
     */
    private void reportRemovedMethods(String className, Class<?> targetClass,
                                      StructuralAnalyzer.StructuralDiff diff,
                                      boolean payloadApplied) {
        java.util.List<String> failing = new java.util.ArrayList<>();
        java.util.List<String> keeping = new java.util.ArrayList<>();
        for (var m : diff.getRemovedMethodSigs()) {
            if ("<init>".equals(m.name()) || "<clinit>".equals(m.name())) continue;
            // ACC_STATIC and Modifier.STATIC share the value 0x0008.
            String siteKey = ((m.access() & Modifier.STATIC) != 0 ? "static:" : "")
                    + m.name() + ":" + CallSiteAdapter.descHash(m.descriptor());
            boolean companionServes = DispatchTable.hasCompanionTarget(targetClass, siteKey);
            if (payloadApplied && !companionServes) {
                failing.add(m.name());
            } else {
                keeping.add(m.name());
            }
        }
        if (!failing.isEmpty()) {
            StatusReporter.warn("Removed method(s) detected in " + className + ": " + failing
                    + ". The previous implementation could not be kept for "
                    + (failing.size() == 1 ? "it" : "them") + ", so an existing caller "
                    + "meets an UnsupportedOperationException naming the method. Restore "
                    + "the method or restart.");
            com.onurkat.reclazz.agent.RestartLedger.note(className,
                    "removed method(s) " + failing + " whose existing callers now throw");
        }
        if (!keeping.isEmpty()) {
            StatusReporter.warn("Removed method(s) detected in " + className + ": " + keeping
                    + ". They are hidden from reflection now, so scans stop acting on "
                    + (keeping.size() == 1 ? "it" : "them")
                    + "; code that already holds " + (keeping.size() == 1 ? "it" : "them")
                    + " keeps the previous implementation until restart.");
        }
    }

    /** "c (calls yalnizB2, which only Base2 provides)" for each pinned method. */
    private static String describePinned(java.util.Map<String, String> pinned) {
        return pinned.entrySet().stream()
                .map(e -> e.getKey().substring(0, e.getKey().indexOf(':'))
                        + " (" + e.getValue() + ")")
                .collect(java.util.stream.Collectors.joining(" and "));
    }

    private static String pinnedNames(java.util.Map<String, String> pinned) {
        return pinned.keySet().stream()
                .map(k -> k.substring(0, k.indexOf(':')))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** Whether the new bytecode declares an enum. */
    private static boolean isEnum(byte[] newBytecode) {
        try {
            final boolean[] result = {false};
            new org.objectweb.asm.ClassReader(newBytecode).accept(
                    new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public void visit(int version, int access, String name, String signature,
                                          String superName, String[] interfaces) {
                            result[0] = (access & org.objectweb.asm.Opcodes.ACC_ENUM) != 0
                                    || "java/lang/Enum".equals(superName);
                        }
                    }, org.objectweb.asm.ClassReader.SKIP_CODE);
            return result[0];
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Which of the added fields are static, read from the new bytecode.
     *
     * The diff carries name and descriptor but not access flags, and the
     * distinction matters here: an instance field added by a reload is
     * initialised on new objects, a static one is not.
     */
    /**
     * Say what happened to a changed interface list.
     *
     * <p>This runs only where Reclazz's own engine runs, which is a JVM
     * without enhanced redefinition; on JetBrains Runtime or DCEVM the agent
     * disables this engine and the VM applies the change itself, so there is
     * nothing to warn about there.
     *
     * <p>The message names the interface. "An interface changed" would send
     * the developer back to the diff to work out which one, and the whole
     * reason this exists is that they were previously sent to look for a bug
     * in their own code.
     */
    private void reportInterfaceChange(String className, StructuralAnalyzer.StructuralDiff diff) {
        List<String> added = simpleNames(diff.getAddedInterfaces());
        List<String> removed = simpleNames(diff.getRemovedInterfaces());

        StringBuilder what = new StringBuilder();
        if (!added.isEmpty()) what.append("now declares ").append(String.join(", ", added));
        if (!removed.isEmpty()) {
            if (what.length() > 0) what.append(" and ");
            what.append("no longer declares ").append(String.join(", ", removed));
        }

        // The way out differs by how the JVM was launched, and one sentence for
        // both was wrong for the audience that meets this most. A hybris server
        // runs SapMachine, started by the wrapper outside any IDE: "IntelliJ
        // already ships JBR" is true and useless there, because the bundled
        // runtime only reaches JVMs the IDE launches. Switching a hybris
        // server means installing standalone JBR and repointing the server's
        // own JVM, and JBR is not an SAP-supported runtime, which a team
        // deserves to know before rather than after.
        // Only an ADDED interface has a way out. DCEVM documents its one
        // unsupported operation as the hierarchy change, and counts removing an
        // interface as exactly that, alongside changing the superclass. Sending
        // someone to a different JVM for something that JVM also refuses is
        // worse than saying nothing, and on a hybris server it is worse again,
        // because taking that advice means running the platform on a runtime
        // SAP does not support. So a removal says restart, and only a removal
        // mixed with an addition mentions the flag at all.
        String wayOut;
        if (removed.isEmpty()) {
            wayOut = isHybris
                    ? "JBR or DCEVM with -XX:+AllowEnhancedClassRedefinition applies an added "
                      + "interface without a restart, but for this server that means changing "
                      + "its JVM: install standalone JetBrains Runtime, point the wrapper's java "
                      + "at it, and add the flag to tomcat.javaoptions. Note JBR is not an "
                      + "SAP-supported JVM."
                    : "JetBrains Runtime or DCEVM with -XX:+AllowEnhancedClassRedefinition "
                      + "applies an added interface without one; for an application launched "
                      + "from IntelliJ, the IDE already bundles JBR, so it is a "
                      + "run-configuration JVM change.";
        } else if (added.isEmpty()) {
            wayOut = "No JVM removes an interface from a loaded class: DCEVM counts that as a "
                    + "hierarchy change and refuses it the same way it refuses a changed "
                    + "superclass, so a restart is the only thing that applies it.";
        } else {
            wayOut = "An added interface is applied without a restart by JetBrains Runtime or "
                    + "DCEVM with -XX:+AllowEnhancedClassRedefinition; the removal is not, on "
                    + "any JVM, because removing an interface is a hierarchy change. A restart "
                    + "applies that half.";
        }
        StatusReporter.warn(className + " " + what + ", and this JVM will not change the "
                + "interfaces of a loaded class. Everything else in this class reloaded, so "
                + "an instanceof or a cast against " + (added.isEmpty() ? "it" : added.get(0))
                + " still answers the old way until a restart. " + wayOut);
        com.onurkat.reclazz.agent.RestartLedger.note(className, what.toString()
                + ", which a stock JVM cannot apply to a loaded class");
    }

    private static List<String> simpleNames(java.util.Collection<String> internalNames) {
        List<String> names = new ArrayList<>();
        for (String n : internalNames) {
            names.add(n.substring(n.lastIndexOf('/') + 1).replace('$', '.'));
        }
        return names;
    }

    /**
     * The added static fields that still have no value.
     *
     * <p>A field a reload adds never enters the loaded class's schema, because
     * the JVM cannot add one. So every later reload diffs it as added again,
     * and initialising it again would throw away whatever the application has
     * put there since. Asking the store what it already holds is what keeps an
     * unrelated edit from emptying a cache.
     */
    private static java.util.Set<String> uninitialisedStatics(
            StructuralAnalyzer.StructuralDiff diff, byte[] newBytecode, Class<?> owner) {
        java.util.Set<String> pending = new java.util.LinkedHashSet<>();
        try {
            new org.objectweb.asm.ClassReader(newBytecode).accept(
                    new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public org.objectweb.asm.FieldVisitor visitField(
                                int access, String name, String descriptor,
                                String signature, Object value) {
                            boolean isStatic =
                                    (access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0;
                            String key = name + ":" + descriptor;
                            // owner null (class not loaded) makes every added
                            // static count as pending; the reload fails right
                            // after this anyway, so it costs nothing.
                            if (isStatic && diff.getAddedFields().contains(key)
                                    && !FieldStore.isStaticInitialised(owner, name, descriptor)) {
                                pending.add(key);
                            }
                            return null;
                        }
                    }, org.objectweb.asm.ClassReader.SKIP_CODE);
        } catch (Exception ignored) {
            // An unreadable class fails later and louder; there is nothing
            // useful to initialise from it here.
        }
        return pending;
    }

    /**
     * Give the added static fields their initial values, and say plainly which
     * ones kept null or zero and why.
     */
    private void initialiseAddedStatics(String className, Class<?> targetClass,
                                        CompanionGenerator.CompanionResult companion,
                                        MethodHandles.Lookup companionLookup,
                                        Class<?> companionClass,
                                        java.util.Set<String> attempted) {
        StaticInitialiserSlicer.Plan plan = companion.getStaticPlan();
        java.util.List<String> done = new java.util.ArrayList<>();

        // Compile-time constants never reach <clinit>: javac puts them in a
        // ConstantValue attribute on the field, so there is nothing to run.
        for (var entry : plan.constants.entrySet()) {
            String key = entry.getKey();
            String name = key.substring(0, key.indexOf(':'));
            String desc = key.substring(key.indexOf(':') + 1);
            Object value = StaticInitialiserSlicer.narrowConstant(desc, entry.getValue());
            if (FieldStore.initialiseStaticOnce(targetClass, name, desc, value)) {
                done.add(name + " = " + describe(value));
            }
        }

        if (plan.hasCode()) {
            try {
                companionLookup.findStatic(companionClass,
                        StaticInitialiserSlicer.INIT_METHOD,
                        java.lang.invoke.MethodType.methodType(void.class)).invokeExact();
                for (String key : plan.slicedKeys) {
                    done.add(key.substring(0, key.indexOf(':')));
                }
            } catch (Throwable t) {
                // The initialiser threw where <clinit> would have thrown at
                // startup. The class is otherwise reloaded, so this is a
                // warning about one field rather than a failed reload.
                StatusReporter.warn("Initialiser for added static field(s) in " + className
                        + " threw " + t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage())
                        + ". Those fields read as null/0.");
                com.onurkat.reclazz.agent.RestartLedger.note(className,
                        "an added static field's initialiser threw, so it reads as null/0");
            }
        }

        if (!done.isEmpty()) {
            StatusReporter.info("Initialised added static field(s): " + String.join(", ", done));
        }

        java.util.List<String> refused = new java.util.ArrayList<>();
        for (var entry : plan.refused.entrySet()) {
            String key = entry.getKey();
            if (!attempted.contains(key)) continue;
            refused.add(key.substring(0, key.indexOf(':')) + " (" + entry.getValue() + ")");
        }
        if (!refused.isEmpty()) {
            StatusReporter.warn("Added static field(s) read as null/0 until restart: "
                    + String.join(", ", refused));
            com.onurkat.reclazz.agent.RestartLedger.note(className,
                    "added static field(s) " + refused + " that read as null/0");
        }
    }

    /** A value as it should appear in a one-line log, strings quoted. */
    private static String describe(Object value) {
        if (value instanceof String s) {
            String shown = s.length() > 40 ? s.substring(0, 37) + "..." : s;
            return '"' + shown + '"';
        }
        return String.valueOf(value);
    }

    private static java.util.List<String> staticFieldNames(StructuralAnalyzer.StructuralDiff diff,
                                                            byte[] newBytecode) {
        java.util.List<String> statics = new java.util.ArrayList<>();
        try {
            new org.objectweb.asm.ClassReader(newBytecode).accept(
                    new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public org.objectweb.asm.FieldVisitor visitField(
                                int access, String name, String descriptor,
                                String signature, Object value) {
                            boolean isStatic =
                                    (access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0;
                            if (isStatic && diff.getAddedFields().contains(name + ":" + descriptor)) {
                                statics.add(name);
                            }
                            return null;
                        }
                    }, org.objectweb.asm.ClassReader.SKIP_CODE);
        } catch (Exception ignored) {
            // Nothing to report is better than failing the reload over a message.
        }
        return statics;
    }

    /**
     * The added instance fields whose value the constructor sets.
     *
     * <p>The distinction is what makes the warning worth printing. An added
     * field with no initialiser reads null on a live object and reads null on
     * a new one, so nothing surprising has happened and there is nothing to
     * say. An added field WITH an initialiser reads the developer's value on
     * every object built from now on and null on every object that already
     * exists, and for a Spring singleton every object is one that already
     * exists. That is the case that produces a NullPointerException on a line
     * that reads as though it cannot produce one, which is measured in
     * AddedFieldInitialiserTest.
     *
     * <p>Read from the constructors rather than guessed: a field is counted
     * when some {@code <init>} assigns it, which is exactly what javac emits
     * for an initialiser and for an assignment in the constructor body alike.
     * Both leave a live object without the value, so both are worth naming.
     */
    private static java.util.List<String> initialisedInstanceFields(
            StructuralAnalyzer.StructuralDiff diff, byte[] newBytecode) {
        java.util.Set<String> added = new java.util.LinkedHashSet<>();
        java.util.List<String> assigned = new java.util.ArrayList<>();
        try {
            new org.objectweb.asm.ClassReader(newBytecode).accept(
                    new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public org.objectweb.asm.FieldVisitor visitField(
                                int access, String name, String descriptor,
                                String signature, Object value) {
                            boolean isStatic =
                                    (access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0;
                            if (!isStatic && diff.getAddedFields().contains(name + ":" + descriptor)) {
                                added.add(name);
                            }
                            return null;
                        }

                        @Override
                        public org.objectweb.asm.MethodVisitor visitMethod(
                                int access, String name, String descriptor,
                                String signature, String[] exceptions) {
                            if (!"<init>".equals(name)) return null;
                            return new org.objectweb.asm.MethodVisitor(
                                    org.objectweb.asm.Opcodes.ASM9) {
                                @Override
                                public void visitFieldInsn(int opcode, String owner,
                                                            String fieldName, String fieldDesc) {
                                    if (opcode == org.objectweb.asm.Opcodes.PUTFIELD
                                            && added.contains(fieldName)
                                            && !assigned.contains(fieldName)) {
                                        assigned.add(fieldName);
                                    }
                                }
                            };
                        }
                    }, 0);
        } catch (Exception ignored) {
            // Nothing to report is better than failing the reload over a message.
        }
        return assigned;
    }

    /**
     * Say why a watched class cannot be reloaded structurally.
     *
     * The companion engine needs two fields in the class, and they can only be
     * put there while it is being loaded: retransforming an already-loaded
     * class to add them is a schema change and the JVM rejects it, which was
     * measured rather than assumed. So a class that missed the load-time
     * transform can never get the infrastructure, for the life of that JVM.
     *
     * JPA entities are the case that meets this in practice. They are loaded
     * while the EntityManagerFactory is being built, early enough to slip past
     * instrumentation, and adding a field to one then failed with a JVM
     * message about schemas that said nothing about why this class and not
     * the one next to it.
     */
    private void reportUninstrumented(String className, Class<?> loaded) {
        // Missing metadata has two causes and they are not the same news.
        //
        // A class the JVM has never loaded is an ordinary new file: it will be
        // instrumented the first time something asks for it, and there is
        // nothing for the developer to do. Telling them it "was loaded before
        // Reclazz could instrument it" is a statement about their server that
        // is simply untrue, and it was being made every time a new class was
        // compiled beside a changed one.
        //
        // A class the JVM does have, with no metadata, really did miss the
        // load-time transform, and that one is worth saying.
        if (loaded == null) return;

        if (uninstrumentedReported.add(className)) {
            StatusReporter.warn(className + " was loaded before Reclazz could instrument it, "
                    + "so only method bodies can be reloaded; adding or removing members "
                    + "needs a restart. JPA entities hit this because the "
                    + "EntityManagerFactory loads them during startup.");
        }
    }

    private final java.util.Set<String> uninstrumentedReported =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

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

    /**
     * @param pinnedMethods         {@code name:descriptor} keys of methods the
     *                              superclass salvage pins to their previous
     *                              implementation, with the reason; empty
     *                              outside that path
     * @param pinnedRedefinePayload the pre-spliced, already-transformed
     *                              payload carrying the pinned methods' old
     *                              bodies, or null outside that path
     */
    private ClassReloader.ReloadResult structuralReload(String className, String internalName,
                                                         byte[] newBytecode,
                                                         StructuralAnalyzer.StructuralDiff diff,
                                                         java.util.Map<String, String> pinnedMethods,
                                                         byte[] pinnedRedefinePayload) {
        try {
            // Increment version (thread-safe)
            int version = versionCounters.merge(internalName, 1, Integer::sum);

            // Generate companion class. Added statics that have not been given
            // a value yet are worked out first, because the companion carries
            // the code that gives them one.
            // Resolved once here: the added-static bookkeeping and the enum
            // check both need the loaded class, and the storage is now keyed
            // by it. Null means "not loaded", handled by each callee and by
            // the explicit guard a few lines down.
            Class<?> loadedClass = findLoadedClass(className);
            java.util.Set<String> staticsNeedingValue =
                    uninitialisedStatics(diff, newBytecode, loadedClass);
            // Taken before anything is redefined, for the same reason as every
            // other check of this kind.
            EnumConstantChange.Change enumChange =
                    EnumConstantChange.check(loadedClass, newBytecode);
            CompanionGenerator.CompanionResult companion = CompanionGenerator.generate(
                    internalName, newBytecode, diff, version, staticsNeedingValue,
                    pinnedMethods.keySet());

            // Get Lookup from the target class's __reclazz$lookup field
            Class<?> targetClass = findLoadedClass(className);
            if (targetClass == null) {
                return ClassReloader.ReloadResult.failure(
                        "Class not loaded, cannot perform structural reload", false);
            }

            // Captured on the first reload, before ReflectionRootFilter hides
            // the field: from then on getDeclaredField answers
            // NoSuchFieldException for it, which the branch below reads as
            // "loaded before the agent". That is true for the class that
            // really was, and wrong for one whose field is merely filtered.
            MethodHandles.Lookup classLookup =
                    com.onurkat.reclazz.bootstrap.LookupCapture.get(targetClass);
            if (classLookup == null) {
                java.lang.reflect.Field lookupField;
                try {
                    lookupField = targetClass.getDeclaredField("__reclazz$lookup");
                } catch (NoSuchFieldException e) {
                    // The loaded class carries no reclazz infrastructure: it was
                    // loaded BEFORE the agent attached, so the load-time transform
                    // never ran (and standard JVMs cannot retrofit fields via
                    // retransform). Body-only changes still work through plain
                    // redefinition; structural changes genuinely need the agent
                    // present at class-load time.
                    if (!diff.isStructural()) {
                        return standardReload(className, newBytecode);
                    }
                    return ClassReloader.ReloadResult.failure(
                            "Class " + className + " was loaded before Reclazz attached; " +
                            "structural changes need the agent at server start " +
                            "(-javaagent in wrapper.conf / JVM args) or JBR/DCEVM.", true);
                }
                lookupField.setAccessible(true);
                classLookup = (MethodHandles.Lookup) lookupField.get(null);
            }

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

            // Hide the injected members at the root of reflection. Placed
            // after the lookup and companion work above (the filter's captures
            // need the pre-hiding window this reload is still in on its first
            // pass) and before the constructor-body redefinition below, so the
            // redefinition's reflection-cache invalidation lands on a class
            // whose filter is already on. Idempotent from the second reload on.
            com.onurkat.reclazz.transform.ReflectionRootFilter
                    .registerInjectedMembersOn(targetClass);

            // A name this save declares stops being hidden, so a
            // remove-then-restore round trip lands where it started.
            //
            // Driven by what the source declares NOW, not by what the diff
            // calls added, and the difference is a bug the live suite found
            // twice. Since the removal payload records the schema the loaded
            // class actually has, a removed method stays in the record, so
            // bringing it back is not an addition and an "added" gate never
            // fires: the member stayed hidden from reflection for good, the
            // MVC scan could not see the handler, and the endpoint the save
            // had just restored answered 404. Declared names are the honest
            // question anyway, and passing one that was never hidden costs a
            // set lookup that finds nothing.
            java.util.Set<String> declaredFieldNames = new java.util.LinkedHashSet<>();
            for (var f : diff.getNewFields()) {
                declaredFieldNames.add(f.name());
            }
            java.util.Set<String> declaredMethodNames = new java.util.LinkedHashSet<>();
            for (var m : diff.getNewMethods()) {
                declaredMethodNames.add(m.name());
            }
            com.onurkat.reclazz.transform.ReflectionRootFilter
                    .unhideRestoredMembersOn(targetClass, declaredFieldNames, declaredMethodNames);

            // A method this reload added is in the companion, which the call
            // sites reach and reflection does not. Anything a framework
            // discovers by looking at the class therefore misses it, and
            // misses it silently: the reload succeeds and the annotation the
            // developer just wrote does nothing.
            for (AddedMethodVisibility.Unseen unseen
                    : AddedMethodVisibility.check(newBytecode, diff.getNewMethods())) {
                StatusReporter.warn(className + "." + unseen.method() + " was added, and "
                        + unseen.reason() + ". Calls to it from your own code work; a restart "
                        + "is what puts it where the framework can see it.");
                com.onurkat.reclazz.agent.RestartLedger.note(className,
                        "added method " + unseen.method() + " that only a restart makes visible "
                        + "to framework scans");
            }

            // Members this reload REMOVED stay on the loaded class (a stock
            // JDK will not take them out), and a scan that keeps seeing them
            // keeps acting on them: a removed getter kept being serialised.
            // Hide them the way the injected members are hidden. A method
            // name is only hidden when no overload of it survives, because
            // the JDK filter works by name and would take the survivor too.
            if (!diff.getRemovedFieldSigs().isEmpty() || !diff.getRemovedMethodSigs().isEmpty()) {
                java.util.Set<String> removedFieldNames = new java.util.LinkedHashSet<>();
                for (var f : diff.getRemovedFieldSigs()) {
                    removedFieldNames.add(f.name());
                }
                java.util.Set<String> survivingMethodNames = new java.util.LinkedHashSet<>();
                for (var m : diff.getNewMethods()) {
                    survivingMethodNames.add(m.name());
                }
                java.util.Set<String> removedMethodNames = new java.util.LinkedHashSet<>();
                for (var m : diff.getRemovedMethodSigs()) {
                    if (!survivingMethodNames.contains(m.name())) {
                        removedMethodNames.add(m.name());
                    }
                }
                com.onurkat.reclazz.transform.ReflectionRootFilter
                        .hideRemovedMembersOn(targetClass, removedFieldNames, removedMethodNames);
                if (!removedFieldNames.isEmpty() || !removedMethodNames.isEmpty()) {
                    StatusReporter.info("Removed member(s) hidden from reflection: "
                            + java.util.stream.Stream.concat(
                                    removedFieldNames.stream(), removedMethodNames.stream())
                                    .collect(java.util.stream.Collectors.joining(", "))
                            + ". Scans no longer see them; old code holding them still runs.");
                }
            }

            // Two different limits, and they used to be reported as one.
            //
            // An instance field added by a reload is initialised on objects
            // built after it, because the new constructor now reaches the
            // loaded class. Objects that already existed keep the type
            // default: they were built before the field did.
            //
            // A static field has no constructor to carry it. Its initialiser
            // is in <clinit>, which the JVM will not run a second time, and
            // re-running the whole of it would reset every other static the
            // application is holding. What can be done is to run the part that
            // belongs to the new field, which is what happens here; what
            // cannot is reported with the reason rather than left to be
            // discovered as a null.
            if (!diff.getAddedFields().isEmpty()) {
                java.util.List<String> addedStatics = staticFieldNames(diff, newBytecode);
                // An enum constant is a static field of the enum's own type, so
                // without this it would be reported as a static field that reads
                // as null, which is true and useless. It is worth its own
                // sentence because the answer is different: not "wait for a
                // restart to see the value" but "this cannot work at all".
                //
                // Even if the constant were conjured up, every switch table,
                // EnumSet and EnumMap in the application is sized for the old
                // count and indexed by ordinal, so the new value would surface
                // as an ArrayIndexOutOfBoundsException somewhere unrelated. A
                // reload that half-works here is worse than one that refuses.
                // The wording lives in EnumConstantChange because the other
                // engine needs the same sentence: on JetBrains Runtime this
                // reloader is switched off, the JVM accepts the redefinition,
                // and the constant is still unusable.
                if (isEnum(newBytecode) && !addedStatics.isEmpty()) {
                    EnumConstantAppender.applyOrExplain(className, findLoadedClass(className),
                            newBytecode, enumChange, instrumentation);
                } else if (!addedStatics.isEmpty()) {
                    initialiseAddedStatics(className, targetClass, companion,
                            companionLookup, companionClass, staticsNeedingValue);
                }
                java.util.List<String> initialised =
                        initialisedInstanceFields(diff, newBytecode);
                if (initialised.isEmpty()) {
                    StatusReporter.info("Added fields are set on new instances; "
                            + "objects that already existed keep the default (null/0/false).");
                } else {
                    // Naming them is the whole value of the line. A developer
                    // who added one field wants to know that one field is
                    // empty, not that a category of thing behaves a way.
                    StatusReporter.warn(initialised + " " + (initialised.size() == 1 ? "has" : "have")
                            + " an initialiser that will not have run on objects that already "
                            + "existed: a field's initialiser is constructor code, and those "
                            + "objects were constructed before the field was written. They read "
                            + "null/0/false until they are rebuilt. Instances created from now on "
                            + "get the value.");
                }
            }

            // A pure removal never enters the added-fields branch above, so a
            // tail removal (the one removal that moves no ordinal) is handed
            // to the same decider, which applies it or refuses with the same
            // sentences the other engine uses.
            if (isEnum(newBytecode) && enumChange != null && diff.getAddedFields().isEmpty()) {
                EnumConstantAppender.applyOrExplain(className, findLoadedClass(className),
                        newBytecode, enumChange, instrumentation);
            }

            // Update metadata in TransformContext
            context.putMetadata(internalName, new TransformContext.ClassMetadata(
                    diff.getNewMethods(), diff.getNewFields(), 0, diff.getNewSuperName(),
                    diff.getNewAnnotations()));

            boolean isStructural = diff.isStructural();

            // A synchronized method whose body has moved to the companion no
            // longer takes the receiver's monitor: the companion holds it as a
            // static method, and a static method's own synchronized flag would
            // lock the companion class rather than the object. Measured on
            // Boot 3.3: two concurrent calls to a synchronized method took 6.3
            // seconds before a structural reload of its class and 3.2 after.
            //
            // Said rather than fixed, deliberately. Wrapping a copied body in
            // monitorenter and monitorexit is the correct repair and it is not
            // one to get subtly wrong: an unbalanced exit is a VerifyError at
            // best and a lock held forever at worst. Until that is written and
            // measured, losing mutual exclusion silently is the part worth
            // ending.
            if (isStructural) {
                java.util.List<String> guarded = synchronizedMethodNames(newBytecode);
                if (!guarded.isEmpty()) {
                    StatusReporter.warn("synchronized method(s) " + guarded + " in " + className
                            + " no longer exclude each other: this save moved their bodies to a "
                            + "companion, which cannot hold the object's monitor. Whatever they "
                            + "guard is unguarded until a restart.");
                    com.onurkat.reclazz.agent.RestartLedger.note(className,
                            "synchronized method(s) " + guarded + " that no longer exclude");
                }
            }

            // Jackson builds a class's serializer once per mapper and keeps it,
            // so a change to what that class serialises to reaches the JSON
            // only when the cache is dropped. Measured on Boot 3.3, stock JDK
            // 21, on a DTO whose endpoint had already been called: renaming a
            // property with @JsonProperty changed nothing at all in the
            // response until this ran.
            //
            // Both kinds of change qualify and for different reasons. An
            // annotation change is the one Jackson can actually see, since
            // redefinition puts the new annotations on the loaded class. A
            // structural change is thinner than it looks on a stock JDK: an
            // ADDED getter lives in the companion and reflection cannot see it
            // either way, which was measured too and is the wall, not a cache;
            // but a REMOVED one is hidden from reflection now, and a mapper
            // holding the old serializer would keep writing it.
            //
            // It says nothing when it runs: a cache that rebuilds lazily and
            // correctly is not news. The enum path keeps its own sentence,
            // because there the constant was failing outright.
            if (isStructural || diff.isAnnotationsChanged()) {
                int mappers = JacksonEnumCaches.flush();

                // Bean Validation resolves a class's constraints once and keeps
                // them, so adding @NotBlank to a field of a request body
                // reloaded and changed nothing: measured on Boot 3.3, the
                // request that should now be rejected was still accepted. Here
                // rather than in the Spring orchestrator, because that runs
                // only for classes that are beans and a request body is not.
                int constraints = com.onurkat.reclazz.spring.SpringValidatorReloader.flush();

                if (config.isVerbose()) {
                    StatusReporter.info("Framework caches flushed: " + mappers
                            + " Jackson mapper(s), " + constraints + " constraint cache(s)");
                }
            }

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
            // Constructor bodies reach the loaded class either way. For a
            // body-only diff the shape already matches. For a structural one
            // the added members are stripped first, which is the only reason
            // the JVM would have refused: without this, an object created
            // after the reload still ran the constructor compiled before the
            // new field existed, so the field it was supposed to initialise
            // came back null.
            boolean redefinePayloadApplied = false;
            boolean removedBodiesKept = false;
            {
                // RAW bytes on the ordinary path: the registered
                // ReclazzTransformer runs during redefineClasses and injects
                // the infrastructure exactly like at load time. The pinned
                // payload is the exception, prepared and transformed up front
                // so the old bodies could be spliced in; the transformer
                // recognises its own output and passes it through untouched
                // (see ConstructorBodyRedefineTest).
                byte[] redefinePayload = pinnedRedefinePayload != null
                        ? pinnedRedefinePayload
                        : diff.isStructural()
                                ? com.onurkat.reclazz.transform.AddedMemberStripper.reshape(
                                        newBytecode, diff.getAddedFields(), diff.getAddedMethods(),
                                        diff.getRemovedMethodSigs(), diff.getRemovedFieldSigs())
                                : newBytecode;

                // A removed method's callers keep what they had. The reshape
                // above re-adds the member the JVM will not let us delete and
                // gives it a throwing stub, and that stub used to be what an
                // existing caller met. This puts the previous implementation
                // there instead, so the removal is what it is documented to
                // be: hidden from every scan, still running for code that
                // already holds it.
                RemovalPrep kept = null;
                if (pinnedRedefinePayload == null && diff.isStructural()
                        && !removableMethodKeys(diff).isEmpty()) {
                    kept = prepareRemovalPayload(internalName, diff, newBytecode,
                            findLoadedClass(className));
                    if (kept.payload() != null) {
                        redefinePayload = kept.payload();
                        removedBodiesKept = true;
                    } else if (config.isVerbose()) {
                        StatusReporter.info("Removed method(s) in " + className
                                + " could not keep their previous implementation ("
                                + kept.refusal() + ").");
                    }
                }

                try {
                    instrumentation.redefineClasses(
                            new java.lang.instrument.ClassDefinition(targetClass, redefinePayload));
                    redefinePayloadApplied = true;
                    // The loaded class now holds what the payload holds,
                    // removed methods included, and the record has to say so
                    // or the next save reads restoring one as adding one.
                    if (removedBodiesKept && kept.schema() != null) {
                        context.putMetadata(internalName, kept.schema());
                    }
                } catch (UnsupportedOperationException expected) {
                    // Once a class has gained members they live in a companion,
                    // not in the loaded class, so the bytecode handed back here
                    // no longer matches what the JVM will accept redefining.
                    // The JVM says "attempted to add a method" and refuses.
                    //
                    // That is the companion engine working as designed, and the
                    // reload itself succeeds a few lines below. Reporting it as
                    // a warning with the raw exception attached told users their
                    // edit had failed, on the one surface they watch, every time
                    // they touched a class they had previously added a method to.
                    //
                    // Silence has a cost of its own, and the live suite is where
                    // it was paid: a field added to a class that had already
                    // gained a member read 0 on an object built after the
                    // reload, which is exactly the thing this refresh exists to
                    // prevent, with nothing anywhere saying why. So the case
                    // where the developer is about to see a wrong value is said
                    // out loud, and the rest goes to the ledger, where it can be
                    // asked for instead of scrolling past.
                    java.util.List<String> addedFieldNames = new ArrayList<>();
                    for (String key : diff.getAddedFields()) {
                        addedFieldNames.add(key.substring(0, key.indexOf(':')));
                    }
                    if (!addedFieldNames.isEmpty()) {
                        StatusReporter.warn("Added field(s) " + addedFieldNames + " in " + className
                                + " read their default even on objects created after this reload: "
                                + "the class carries member(s) added since startup, so the JVM "
                                + "refuses the redefinition that would install the new "
                                + "constructor. Everything else in this save reloaded. A restart "
                                + "runs the initialiser.");
                    } else if (config.isVerbose()) {
                        StatusReporter.info("Constructor bodies not refreshed for " + className
                                + ": the class carries members added after startup. "
                                + "Everything else reloaded.");
                    }
                    com.onurkat.reclazz.agent.RestartLedger.note(className,
                            "a constructor the JVM would not redefine, because the class "
                                    + "carries member(s) added since startup");
                } catch (Throwable t) {
                    StatusReporter.warn("Constructor-body refresh skipped for " + className + ": " + t);
                }
            }

            if (!diff.getRemovedMethodSigs().isEmpty()) {
                reportRemovedMethods(className, targetClass, diff,
                        redefinePayloadApplied && !removedBodiesKept);
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
            result.setMethodsAdded(!diff.getAddedMethods().isEmpty());
            result.setAddedMethodSigs(diff.getAddedMethods());
            result.setNewBytecode(newBytecode);
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
                // On the pinned path the raw payload still carries the
                // entangled methods' NEW bodies, and redefining it would put
                // exactly the crash back that the splice exists to prevent.
                // The spliced payload is the one that is safe to redefine.
                return standardReload(className,
                        pinnedRedefinePayload != null ? pinnedRedefinePayload : newBytecode);
            }
            return ClassReloader.ReloadResult.failure(
                    "Structural reload failed: " + e.getMessage(), true);
        }
    }

    private Class<?> findLoadedClass(String className) {
        return com.onurkat.reclazz.agent.ClassLookup.findLoadedClass(className, instrumentation);
    }

    /**
     * Whether the container has an instance of this class to look after.
     *
     * <p>By the annotation's own name, and the list has to include the
     * stereotypes that are only stereotypes through a meta-annotation.
     * {@code @ControllerAdvice} is a {@code @Component} one level up, and
     * reading direct annotations misses that: measured on Boot 3.3, adding
     * {@code @ExceptionHandler} to an advice class reached none of the Spring
     * reloaders at all, because the class was not counted as a bean and the
     * whole orchestrator was skipped. The reload said it had succeeded, which
     * it had, and the endpoint went on answering the default error body.
     */
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
                         annotName.endsWith(".ControllerAdvice") ||
                         annotName.endsWith(".RestControllerAdvice") ||
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
        ReflectionBridge.replaceClassState(targetClass, forgedMethods, forgedFields);
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
