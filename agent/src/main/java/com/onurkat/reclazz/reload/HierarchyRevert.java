/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The method bodies from a class whose superclass also changed.
 *
 * <p>No JVM applies a changed superclass to a loaded class; that was measured
 * on a stock JDK, on JetBrains Runtime, and on JetBrains Runtime with enhanced
 * class redefinition, and all three answer "attempted to change superclass or
 * interfaces". So the hierarchy is not the question. The question is what
 * happens to everything else in the file.
 *
 * <p>It used to be thrown away. One line changed in the extends clause and the
 * three method bodies edited in the same save were refused along with it, with
 * a message about the superclass and nothing about the rest. The developer had
 * to restart to see work that had nothing to do with the hierarchy.
 *
 * <p>What makes salvage possible is where javac puts the reference. Compiling
 * {@code class Service extends Base2} with a call to an inherited
 * {@code who()} gives:
 *
 * <pre>
 *   invokespecial Base2."&lt;init&gt;":()V     &lt;- the super() call, in the constructor
 *   invokevirtual who:()Ljava/lang/String;  &lt;- owner is Service, not Base2
 * </pre>
 *
 * <p>An inherited call names the class itself, so it resolves against whatever
 * the loaded class actually extends. The only unavoidable mention of the new
 * superclass is the {@code super()} call. Point that back at the old
 * superclass and the class file becomes one the JVM will accept, carrying the
 * new bodies.
 *
 * <p>Anything beyond that mention is a problem, and it comes in two sizes. A
 * field typed as the new superclass, a method that takes or returns it, or a
 * constructor whose body needs it are class-level: no payload can carry them,
 * so the salvage is refused outright. A plain method body that needs the new
 * superclass (calls something only it provides, casts to it, reads its
 * fields) is smaller than that: the rest of the class does not depend on it,
 * so the body is reported as entangled and the reloader pins that one method
 * to the implementation it had, instead of throwing away the whole save.
 * Applying such a body anyway would plant a NoSuchMethodError for later,
 * which is worse than either.
 */
public final class HierarchyRevert {

    private HierarchyRevert() {
    }

    /**
     * @param bytecode  the rewritten class, or null when it was refused
     * @param blockers  what stopped it, when it was refused
     * @param entangled method bodies that need the new superclass and cannot
     *                  be applied, keyed {@code name:descriptor}, the value a
     *                  reason phrase without the method name (the caller
     *                  formats "c (calls yalnizB2, ...)" from it). Empty when
     *                  the class was refused or nothing was entangled.
     */
    public record Result(byte[] bytecode, List<String> blockers, Map<String, String> entangled) {

        public Result(byte[] bytecode, List<String> blockers) {
            this(bytecode, blockers, Map.of());
        }

        public boolean applied() {
            return bytecode != null;
        }

        public String reason() {
            return String.join("; ", blockers);
        }
    }

    /**
     * Rewrite the class to keep the superclass the loaded class already has.
     *
     * @param newBytecode    the recompiled class, declaring the new superclass
     * @param loadedSuper    internal name of the superclass the JVM has
     * @param loadedSuperClass the same, as a Class, to check its constructors
     */
    public static Result toLoadedSuperclass(byte[] newBytecode, String loadedSuper,
                                             Class<?> loadedSuperClass) {
        if (newBytecode == null || loadedSuper == null) {
            return new Result(null, List.of("the loaded superclass is not known"));
        }

        String declaredSuper = superNameOf(newBytecode);
        if (declaredSuper == null || declaredSuper.equals(loadedSuper)) {
            return new Result(null, List.of("the superclass did not change"));
        }

        List<String> blockers = new ArrayList<>();
        Map<String, String> entangled = new LinkedHashMap<>();
        Set<String> superConstructorDescriptors = new LinkedHashSet<>();

        // An inherited call does not name the superclass, it names this class:
        // `onlyOnNewBase()` compiles to `invokevirtual RebasedPlain.onlyOnNewBase`.
        // So scanning for the new superclass by name misses exactly the case
        // that matters, a body calling something only the new superclass
        // provides, and the salvage would plant a NoSuchMethodError for later.
        // What the members this class does not declare are resolved against is
        // the hierarchy it is about to keep, so that is what they are checked
        // against.
        Declared declared = declaredMembers(newBytecode);
        Inherited inherited = inheritedFrom(loadedSuperClass);

        ClassReader reader = new ClassReader(newBytecode);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new Rewriter(writer, declaredSuper, loadedSuper,
                blockers, entangled, superConstructorDescriptors, declared, inherited), 0);

        // The super() call is rewritten to the old superclass, so that
        // constructor has to exist there. Changing a superclass and its
        // constructor signature together is ordinary, and the rewritten call
        // would then resolve to nothing.
        for (String descriptor : superConstructorDescriptors) {
            if (!hasConstructor(loadedSuperClass, descriptor)) {
                blockers.add(simple(loadedSuper) + " has no constructor "
                        + simple(loadedSuper) + descriptor.substring(0, descriptor.indexOf(')') + 1));
            }
        }

        if (!blockers.isEmpty()) {
            return new Result(null, dedupe(blockers));
        }
        return new Result(writer.toByteArray(), List.of(), entangled);
    }

    private static final class Rewriter extends ClassVisitor {
        private final String declaredSuper;
        private final String loadedSuper;
        private final List<String> blockers;
        private final Map<String, String> entangled;
        private final Set<String> superConstructorDescriptors;
        private final Declared declared;
        private final Inherited inherited;
        private String className;

        Rewriter(ClassVisitor next, String declaredSuper, String loadedSuper,
                 List<String> blockers, Map<String, String> entangled,
                 Set<String> superConstructorDescriptors,
                 Declared declared, Inherited inherited) {
            super(Opcodes.ASM9, next);
            this.declaredSuper = declaredSuper;
            this.loadedSuper = loadedSuper;
            this.blockers = blockers;
            this.entangled = entangled;
            this.superConstructorDescriptors = superConstructorDescriptors;
            this.declared = declared;
            this.inherited = inherited;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.className = name;
            super.visit(version, access, name, signature, loadedSuper, interfaces);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                        String signature, Object value) {
            if (mentions(descriptor)) {
                blockers.add("the field " + name + " is typed " + simple(declaredSuper));
            }
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            if (mentions(descriptor)) {
                blockers.add(name + " takes or returns " + simple(declaredSuper));
            }
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            // A constructor or static initialiser cannot be pinned: their
            // bodies run on the real class, not through a trampoline, so
            // there is no previous implementation to keep serving. The same
            // is true of a method the transformer never trampolines. For
            // those, a body that needs the new superclass still refuses the
            // whole class, which is the pre-salvage behaviour.
            boolean pinnable = !"<init>".equals(name) && !"<clinit>".equals(name)
                    && !com.onurkat.reclazz.transform.TransformExclusions
                            .shouldSkipMethod(className, name, descriptor, access);
            return new BodyRewriter(mv, name, descriptor, pinnable);
        }

        private boolean mentions(String descriptor) {
            return descriptor != null && descriptor.contains("L" + declaredSuper + ";");
        }

        private final class BodyRewriter extends MethodVisitor {
            private final String methodName;
            private final String methodDescriptor;
            private final boolean pinnable;
            private final List<String> reasons = new ArrayList<>();

            BodyRewriter(MethodVisitor next, String methodName, String methodDescriptor,
                         boolean pinnable) {
                super(Opcodes.ASM9, next);
                this.methodName = methodName;
                this.methodDescriptor = methodDescriptor;
                this.pinnable = pinnable;
            }

            /**
             * A body-level need for the new superclass. In a pinnable method
             * it marks the method entangled; anywhere else it blocks the
             * class, because there is no previous implementation to fall
             * back to.
             */
            private void flag(String phrase) {
                if (pinnable) {
                    if (!reasons.contains(phrase)) reasons.add(phrase);
                } else {
                    blockers.add(methodName + " " + phrase);
                }
            }

            @Override
            public void visitEnd() {
                if (!reasons.isEmpty()) {
                    entangled.merge(methodName + ":" + methodDescriptor,
                            String.join("; ", reasons), (a, b) -> a + "; " + b);
                }
                super.visitEnd();
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name,
                                         String descriptor, boolean isInterface) {
                if (owner.equals(declaredSuper)) {
                    // The one mention every class with a constructor carries.
                    if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)) {
                        superConstructorDescriptors.add(descriptor);
                        super.visitMethodInsn(opcode, loadedSuper, name, descriptor, isInterface);
                        return;
                    }
                    flag("calls " + simple(declaredSuper) + "." + name);
                } else if (owner.equals(className) && !"<init>".equals(name)
                        && !declared.methods.contains(name + descriptor)
                        && !inherited.methods.contains(name + descriptor)) {
                    // Inherited from the superclass it is losing, and nothing
                    // in the hierarchy it keeps provides it.
                    flag("calls " + name
                            + ", which only " + simple(declaredSuper) + " provides");
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                if (owner.equals(declaredSuper)) {
                    flag("reads or writes " + simple(declaredSuper) + "." + name);
                } else if (owner.equals(className)
                        && !declared.fields.contains(name + ":" + descriptor)
                        && !inherited.fields.contains(name + ":" + descriptor)) {
                    flag("uses the field " + name
                            + ", which only " + simple(declaredSuper) + " provides");
                }
                super.visitFieldInsn(opcode, owner, name, descriptor);
            }

            @Override
            public void visitTypeInsn(int opcode, String type) {
                if (type.equals(declaredSuper)) {
                    flag("names " + simple(declaredSuper) + " as a type");
                }
                super.visitTypeInsn(opcode, type);
            }

            @Override
            public void visitLdcInsn(Object value) {
                if (value instanceof Type type && type.getSort() == Type.OBJECT
                        && type.getInternalName().equals(declaredSuper)) {
                    flag("uses the " + simple(declaredSuper) + " class literal");
                }
                super.visitLdcInsn(value);
            }
        }
    }

    private record Declared(Set<String> methods, Set<String> fields) { }

    private record Inherited(Set<String> methods, Set<String> fields) { }

    /** What the recompiled class declares itself, so inherited use stands out. */
    private static Declared declaredMembers(byte[] bytecode) {
        Set<String> methods = new LinkedHashSet<>();
        Set<String> fields = new LinkedHashSet<>();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                methods.add(name + descriptor);
                return null;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                            String signature, Object value) {
                fields.add(name + ":" + descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return new Declared(methods, fields);
    }

    /**
     * Everything reachable from the superclass the loaded class keeps,
     * interfaces included, since a default method is inherited too.
     */
    private static Inherited inheritedFrom(Class<?> superClass) {
        Set<String> methods = new LinkedHashSet<>();
        Set<String> fields = new LinkedHashSet<>();
        Set<Class<?>> seen = new LinkedHashSet<>();
        java.util.ArrayDeque<Class<?>> queue = new java.util.ArrayDeque<>();
        if (superClass != null) queue.add(superClass);

        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            if (current == null || !seen.add(current)) continue;
            try {
                for (var m : current.getDeclaredMethods()) {
                    methods.add(m.getName() + Type.getMethodDescriptor(m));
                }
                for (var f : current.getDeclaredFields()) {
                    fields.add(f.getName() + ":" + Type.getDescriptor(f.getType()));
                }
            } catch (Throwable ignored) {
                // A member this JVM will not describe cannot be vouched for,
                // and leaving it out means the salvage refuses rather than
                // guesses.
            }
            // Object's superclass is null, and an ArrayDeque refuses one.
            Class<?> parent = current.getSuperclass();
            if (parent != null) queue.add(parent);
            queue.addAll(java.util.List.of(current.getInterfaces()));
        }
        return new Inherited(methods, fields);
    }

    private static String superNameOf(byte[] bytecode) {
        try {
            return new ClassReader(bytecode).getSuperName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean hasConstructor(Class<?> type, String descriptor) {
        if (type == null) return false;
        for (var constructor : type.getDeclaredConstructors()) {
            if (Type.getConstructorDescriptor(constructor).equals(descriptor)) return true;
        }
        return false;
    }

    private static List<String> dedupe(List<String> reasons) {
        return new ArrayList<>(new LinkedHashSet<>(reasons));
    }

    private static String simple(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1).replace('$', '.');
    }
}
