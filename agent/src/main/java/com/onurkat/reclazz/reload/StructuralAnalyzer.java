/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.transform.AnnotationSignatures;
import com.onurkat.reclazz.transform.TransformContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.*;

/**
 * Analyzes the structural diff between original class metadata and new bytecode.
 * Determines whether a reload requires structural changes (added/removed methods/fields)
 * or only method body changes.
 */
public class StructuralAnalyzer {

    /**
     * Analyze differences between the original class and new bytecode.
     */
    public static StructuralDiff analyze(TransformContext.ClassMetadata original, byte[] newBytecode) {
        // Parse new bytecode
        ClassReader reader = new ClassReader(newBytecode);
        NewClassCollector collector = new NewClassCollector();
        reader.accept(collector, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);

        Set<String> oldMethods = new LinkedHashSet<>();
        for (var m : original.getMethods()) {
            // Use ":" delimiter to avoid ambiguity between name and descriptor
            oldMethods.add(m.name() + ":" + m.descriptor());
        }

        Set<String> newMethods = new LinkedHashSet<>();
        for (var m : collector.methods) {
            newMethods.add(m.name() + ":" + m.descriptor());
        }

        Set<String> oldFields = new LinkedHashSet<>();
        for (var f : original.getFields()) {
            oldFields.add(f.name() + ":" + f.descriptor());
        }

        Set<String> newFields = new LinkedHashSet<>();
        for (var f : collector.fields) {
            newFields.add(f.name() + ":" + f.descriptor());
        }

        // Compute diffs
        Set<String> addedMethods = new LinkedHashSet<>(newMethods);
        addedMethods.removeAll(oldMethods);

        Set<String> removedMethods = new LinkedHashSet<>(oldMethods);
        removedMethods.removeAll(newMethods);

        // Kept with their modifiers, because a redefinition has to hand the JVM
        // the same members it already has, down to the access flags.
        java.util.List<TransformContext.MethodSig> removedMethodSigs = new java.util.ArrayList<>();
        for (var m : original.getMethods()) {
            if (removedMethods.contains(m.name() + ":" + m.descriptor())) removedMethodSigs.add(m);
        }

        Set<String> commonMethods = new LinkedHashSet<>(oldMethods);
        commonMethods.retainAll(newMethods);

        Set<String> addedFields = new LinkedHashSet<>(newFields);
        addedFields.removeAll(oldFields);

        Set<String> removedFields = new LinkedHashSet<>(oldFields);
        removedFields.removeAll(newFields);

        java.util.List<TransformContext.FieldSig> removedFieldSigs = new java.util.ArrayList<>();
        for (var f : original.getFields()) {
            if (removedFields.contains(f.name() + ":" + f.descriptor())) removedFieldSigs.add(f);
        }

        // Check for superclass/interface changes
        boolean superChanged = !Objects.equals(original.getSuperName(), collector.superName);
        // Interface changes are complex — flag as unsupported for now
        boolean interfacesChanged = false; // Simplified — could be expanded

        // An annotation-only edit adds and removes nothing, so without this it
        // reads as body-only and no framework is told anything happened.
        // An empty old set means the class was recorded before annotations
        // were tracked; treat that as unknown rather than as "had none", so a
        // first reload after an upgrade does not claim everything changed.
        Set<String> newAnnotations = AnnotationSignatures.of(newBytecode);
        boolean annotationsChanged = original.isAnnotationsKnown()
                && !original.getAnnotations().equals(newAnnotations);

        return new StructuralDiff(
                addedMethods, removedMethods, commonMethods,
                addedFields, removedFields,
                superChanged, interfacesChanged,
                collector.methods, collector.fields,
                collector.superName,
                annotationsChanged, newAnnotations,
                removedMethodSigs, removedFieldSigs
        );
    }

    private static class NewClassCollector extends ClassVisitor {
        String superName;
        final List<TransformContext.MethodSig> methods = new ArrayList<>();
        final List<TransformContext.FieldSig> fields = new ArrayList<>();

        NewClassCollector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.superName = superName;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            methods.add(new TransformContext.MethodSig(name, descriptor, access));
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                        String signature, Object value) {
            fields.add(new TransformContext.FieldSig(name, descriptor, access));
            return null;
        }
    }

    /**
     * Result of structural analysis.
     */
    public static class StructuralDiff {
        private final Set<String> addedMethods;
        private final Set<String> removedMethods;
        private final Set<String> commonMethods;
        private final Set<String> addedFields;
        private final Set<String> removedFields;
        private final boolean superClassChanged;
        private final boolean interfacesChanged;
        private final List<TransformContext.MethodSig> newMethods;
        private final List<TransformContext.FieldSig> newFields;
        private final String newSuperName;
        private final boolean annotationsChanged;
        private final Set<String> newAnnotations;
        private final List<TransformContext.MethodSig> removedMethodSigs;
        private final List<TransformContext.FieldSig> removedFieldSigs;

        StructuralDiff(Set<String> addedMethods, Set<String> removedMethods,
                       Set<String> commonMethods, Set<String> addedFields,
                       Set<String> removedFields, boolean superClassChanged,
                       boolean interfacesChanged,
                       List<TransformContext.MethodSig> newMethods,
                       List<TransformContext.FieldSig> newFields,
                       String newSuperName,
                       boolean annotationsChanged,
                       Set<String> newAnnotations,
                       List<TransformContext.MethodSig> removedMethodSigs,
                       List<TransformContext.FieldSig> removedFieldSigs) {
            this.removedMethodSigs = removedMethodSigs;
            this.removedFieldSigs = removedFieldSigs;
            this.annotationsChanged = annotationsChanged;
            this.newAnnotations = newAnnotations;
            this.addedMethods = addedMethods;
            this.removedMethods = removedMethods;
            this.commonMethods = commonMethods;
            this.addedFields = addedFields;
            this.removedFields = removedFields;
            this.superClassChanged = superClassChanged;
            this.interfacesChanged = interfacesChanged;
            this.newMethods = newMethods;
            this.newFields = newFields;
            this.newSuperName = newSuperName;
        }

        public boolean isStructural() {
            return !addedMethods.isEmpty() || !removedMethods.isEmpty()
                    || !addedFields.isEmpty() || !removedFields.isEmpty();
        }

        public boolean isBodyOnly() {
            return !isStructural() && !superClassChanged && !interfacesChanged;
        }

        /**
         * True when the only thing that moved was an annotation. The class
         * redefines cleanly and reflection sees the new value; what does not
         * happen by itself is any framework noticing.
         */
        public boolean isAnnotationOnly() {
            return annotationsChanged && !isStructural() && !isUnsupported();
        }

        public boolean isAnnotationsChanged() { return annotationsChanged; }

        public Set<String> getNewAnnotations() { return newAnnotations; }

        public boolean isUnsupported() {
            return superClassChanged || interfacesChanged;
        }

        public Set<String> getAddedMethods() { return addedMethods; }
        public Set<String> getRemovedMethods() { return removedMethods; }
        public Set<String> getCommonMethods() { return commonMethods; }
        public Set<String> getAddedFields() { return addedFields; }
        /** Removed members with their modifiers, for rebuilding the class shape. */
        public List<TransformContext.MethodSig> getRemovedMethodSigs() { return removedMethodSigs; }

        public List<TransformContext.FieldSig> getRemovedFieldSigs() { return removedFieldSigs; }

        public Set<String> getRemovedFields() { return removedFields; }
        public boolean isSuperClassChanged() { return superClassChanged; }
        public List<TransformContext.MethodSig> getNewMethods() { return newMethods; }
        public List<TransformContext.FieldSig> getNewFields() { return newFields; }
        public String getNewSuperName() { return newSuperName; }

        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            if (!addedMethods.isEmpty()) sb.append("+").append(addedMethods.size()).append(" method(s) ");
            if (!removedMethods.isEmpty()) sb.append("-").append(removedMethods.size()).append(" method(s) ");
            if (!addedFields.isEmpty()) sb.append("+").append(addedFields.size()).append(" field(s) ");
            if (!removedFields.isEmpty()) sb.append("-").append(removedFields.size()).append(" field(s) ");
            return sb.toString().trim();
        }
    }
}
