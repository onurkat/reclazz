/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.agent.AgentConfig;
import com.onurkat.reclazz.ui.StatusReporter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ClassFileTransformer that intercepts watched classes at load time
 * and rewrites them with invokedynamic trampolines for hot-reload support.
 *
 * Only transforms classes that are in the TransformContext's watched set.
 * Platform/library classes pass through untouched.
 */
public class ReclazzTransformer implements ClassFileTransformer {

    private final TransformContext context;
    private final AgentConfig config;

    public ReclazzTransformer(TransformContext context, AgentConfig config) {
        this.context = context;
        this.config = config;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) throws IllegalClassFormatException {
        if (className == null) return null;

        // Only transform watched classes
        if (!context.isWatched(className)) return null;

        // A class that was already loaded when Reclazz attached has none of the
        // infrastructure, and a redefinition cannot introduce it: the JVM
        // refuses to change a class's shape. Transforming it here made every
        // reload fail with "attempted to change the schema", which is the whole
        // of what attaching to a running server could do. Handing the bytes
        // back untouched leaves the one thing that does work: method bodies.
        //
        // Whether we transformed it is our own record, not something to ask the
        // class: the injected members are hidden from reflection on purpose,
        // so asking the class says no even when the answer is yes.
        if (classBeingRedefined != null && context.getMetadata(className) == null) {
            return null;
        }

        // Already-transformed input passes through untouched. The marker is
        // the injected __reclazz$lookup field: nothing else puts a field of
        // that name in a class, and it is present in every transformed one.
        // Transforming such a class a second time renames trampolines over
        // their own targets and injects the fields twice, which the JVM
        // rejects as a ClassFormatError. Being idempotent instead is what
        // makes a spliced, already-transformed redefine payload safe to hand
        // to redefineClasses with this transformer still registered. The
        // pass-through is also recorded as the latest emitted bytecode,
        // because from the JVM's point of view it is.
        if (isAlreadyTransformed(classfileBuffer)) {
            TransformedClassCache.put(className, classfileBuffer);
            return null;
        }

        try {
            // Skip interfaces: interface fields must be PUBLIC STATIC FINAL,
            // which conflicts with our PRIVATE SYNTHETIC infrastructure fields
            // (__reclazz$lookup, __reclazz$ext). Adding such a field to an
            // interface produces ClassFormatError "Illegal field modifiers"
            // and crashes class loading. Hot-reloading default/static methods
            // on interfaces is not supported.
            ClassReader peek = new ClassReader(classfileBuffer);
            if ((peek.getAccess() & Opcodes.ACC_INTERFACE) != 0) {
                if (config.isVerbose()) {
                    StatusReporter.info("Skipping interface (hot-reload not supported): " +
                            className.replace('/', '.'));
                }
                return null;
            }

            byte[] transformed = doTransform(className, classfileBuffer, loader);

            // Always validate transformed bytecode before handing it to the JVM.
            // If our generated frames are inconsistent (which would crash class
            // loading with VerifyError), drop the transform and let the JVM load
            // the original class — the user keeps a working server, just without
            // hot-reload for that one class.
            if (!isBytecodeValid(className, transformed)) {
                StatusReporter.warn("Transform produced invalid bytecode for " + className +
                        " — falling back to original. Hot-reload disabled for this class.");
                return null;
            }

            if (config.isVerifyTransform()) {
                verifyBytecode(className, transformed);
            }

            if (config.getTransformDumpDir() != null) {
                dumpTransformed(className, transformed);
            }

            if (config.isVerbose()) {
                StatusReporter.info("Transformed: " + className.replace('/', '.'));
            }

            // The emitted bytes are the last-known-good version of this class:
            // its __reclazz$v0$ copies are what the per-method superclass
            // salvage pins an entangled method back to. Written on the way out
            // of every transform, load-time and redefine alike, so the cache
            // always holds what the transformer most recently produced.
            TransformedClassCache.put(className, transformed);

            return transformed;
        } catch (Exception e) {
            StatusReporter.error("Transform failed for " + className + ": " + e.getMessage());
            if (config.isVerbose()) {
                e.printStackTrace();
            }
            // Return null to use original bytecode (no transform)
            return null;
        }
    }

    /**
     * Whether these bytes already carry the transform, read from the bytes
     * themselves. Asking the Class does not work here: the injected members
     * are hidden from reflection on purpose, so the class answers no even
     * when the answer is yes. The field scan skips code and debug info, so
     * the check costs a header walk, not a full parse.
     */
    static boolean isAlreadyTransformed(byte[] classfileBuffer) {
        try {
            final boolean[] found = {false};
            new ClassReader(classfileBuffer).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.FieldVisitor visitField(
                        int access, String name, String descriptor,
                        String signature, Object value) {
                    if ("__reclazz$lookup".equals(name)) found[0] = true;
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return found[0];
        } catch (RuntimeException e) {
            // Unreadable bytes are not ours; let the normal path report them.
            return false;
        }
    }

    /**
     * Apply the transformation pipeline to a class.
     */
    public byte[] doTransform(String className, byte[] classfileBuffer, ClassLoader loader) {
        ClassReader reader = new ClassReader(classfileBuffer);

        // Custom ClassWriter that walks class hierarchies via ClassReader rather
        // than Class.forName — required because target classes are typically not
        // yet loaded by the JVM at premain transform time.
        ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES, loader);

        // Chain: ClassReader -> MethodTrampolineAdapter -> ClassWriter
        // MethodTrampolineAdapter internally applies CallSiteAdapter and FieldAccessAdapter
        // Annotations as they are before we touch anything. The reload path
        // compares against this to notice an edit that moved nothing but an
        // annotation, which the structural diff cannot see.
        // Computed from the ORIGINAL bytes, before anything is added to them,
        // and only when the class does not already say what its UID is.
        Long originalUid = SerialVersionUid.worthWriting(classfileBuffer)
                && !SerialVersionUid.alreadyDeclared(classfileBuffer)
                ? SerialVersionUid.computeFrom(classfileBuffer)
                : null;

        MethodTrampolineAdapter adapter = new MethodTrampolineAdapter(
                writer, context, AnnotationSignatures.of(classfileBuffer), originalUid);

        reader.accept(adapter, ClassReader.EXPAND_FRAMES);

        return writer.toByteArray();
    }

    /**
     * Quick verification pass: returns false if the bytecode would be rejected
     * by the JVM verifier. Uses ASM's CheckClassAdapter which catches the same
     * issues (frame mismatches, type errors, malformed structure).
     */
    private boolean isBytecodeValid(String className, byte[] bytecode) {
        try {
            ClassReader reader = new ClassReader(bytecode);
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            reader.accept(new org.objectweb.asm.util.CheckClassAdapter(node, true),
                    ClassReader.SKIP_DEBUG);
            return true;
        } catch (Throwable t) {
            if (config.isVerbose()) {
                StatusReporter.warn("CheckClassAdapter rejected " + className + ": " + t.getMessage());
            }
            return false;
        }
    }

    private void verifyBytecode(String className, byte[] bytecode) {
        try {
            // Use ASM's CheckClassAdapter for verification
            ClassReader reader = new ClassReader(bytecode);
            org.objectweb.asm.util.CheckClassAdapter.verify(reader, false,
                    new java.io.PrintWriter(System.err));
        } catch (Exception e) {
            StatusReporter.warn("Bytecode verification warning for " + className + ": " + e.getMessage());
        }
    }

    private void dumpTransformed(String className, byte[] bytecode) {
        try {
            Path dumpDir = config.getTransformDumpDir();
            Path file = dumpDir.resolve(className.replace('/', '_') + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, bytecode);
        } catch (IOException e) {
            StatusReporter.warn("Failed to dump transformed class: " + e.getMessage());
        }
    }

    /**
     * Custom ClassWriter that resolves class hierarchies for COMPUTE_FRAMES.
     *
     * The default ASM implementation calls Class.forName(type) which fails for
     * classes not yet loaded — which is the common case at premain time. The
     * naive fix (catch the exception and return java.lang.Object) produces
     * stackmap frames that pass ASM's frame computation but later fail JVM
     * verification at instructions like `athrow` that require a specific type.
     *
     * Instead, when Class.forName fails, walk the class hierarchy by reading
     * class file bytes via the supplied ClassLoader's getResourceAsStream.
     * This finds the actual common ancestor without triggering class loading.
     */
    private static class SafeClassWriter extends ClassWriter {
        private final ClassLoader loader;

        SafeClassWriter(ClassReader classReader, int flags, ClassLoader loader) {
            super(classReader, flags);
            this.loader = loader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1.equals(type2)) return type1;
            if ("java/lang/Object".equals(type1) || "java/lang/Object".equals(type2)) {
                return "java/lang/Object";
            }

            // Deliberately not Class.forName first.
            //
            // ASM's default getCommonSuperClass calls Class.forName, and it
            // used to be tried here as a fast path for classes already loaded.
            // It is also a class *loader*: computing frames for a method that
            // contains `new Product()` loads demo.Product, from inside our own
            // transformer, and the JVM does not re-enter transformers for a
            // class loaded during a transform. The class was then defined with
            // no instrumentation at all, for the life of the JVM, and could
            // never be structurally reloaded.
            //
            // It failed quietly and asymmetrically: a class referenced from
            // transformed bytecode lost, one named only in a string did not.
            // JPA entities lose, because whatever builds the
            // EntityManagerFactory usually references them, which is why
            // adding a field to an entity could not be reloaded.
            //
            // Reading class files answers the same question and loads nothing.

            // Walk both class hierarchies by reading bytecode, find common ancestor.
            try {
                Set<String> hierarchy1 = collectSupertypes(type1);
                String current = type2;
                while (current != null) {
                    if (hierarchy1.contains(current)) return current;
                    current = readSuperName(current);
                }
            } catch (Throwable ignored) {
                // Fall through to Object.
            }
            return "java/lang/Object";
        }

        private Set<String> collectSupertypes(String internalName) {
            Set<String> result = new LinkedHashSet<>();
            String current = internalName;
            int safety = 0;
            while (current != null && safety++ < 100) {
                result.add(current);
                if ("java/lang/Object".equals(current)) break;
                current = readSuperName(current);
            }
            return result;
        }

        private String readSuperName(String internalName) {
            if ("java/lang/Object".equals(internalName)) return null;
            String resourceName = internalName + ".class";
            try (InputStream is = openResource(resourceName)) {
                if (is == null) return null;
                ClassReader cr = new ClassReader(is);
                String superName = cr.getSuperName();
                return superName != null ? superName : "java/lang/Object";
            } catch (IOException e) {
                return null;
            }
        }

        private InputStream openResource(String resourceName) {
            // Try the supplied classloader first (it knows about the target class).
            if (loader != null) {
                InputStream is = loader.getResourceAsStream(resourceName);
                if (is != null) return is;
            }
            // Fall back to the system classloader for JDK classes.
            return ClassLoader.getSystemResourceAsStream(resourceName);
        }
    }
}
