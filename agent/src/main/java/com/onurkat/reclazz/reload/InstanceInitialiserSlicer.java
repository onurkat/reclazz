/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.InjectedNames;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The initial value of an instance field a reload adds, for the objects that
 * already existed when it was added.
 *
 * <p>A field's initialiser is constructor code: {@code private final List<String>
 * cache = new ArrayList<>();} compiles to a run of instructions in every
 * constructor, between the super call and the next field. An object built
 * before the reload ran a constructor that did not have that run, so on it the
 * field reads null, and for a Spring singleton every object is one built before
 * the reload. Re-running the constructor is out of the question: it would also
 * reset the fields the object has been accumulating since startup.
 *
 * <p>What can be done is the same thing {@link StaticInitialiserSlicer} does
 * for {@code <clinit>}: find the run of instructions that belongs to this one
 * field and nothing else, and lift it out. The difference is when it runs. A
 * static field has one owner and one moment; an instance field has one owner
 * per object and no way to enumerate them, so the lifted code runs on first
 * read, per object, from the field store. The rules for what is self-contained
 * are a little wider than for statics, because the object is live and whole:
 * reading its other fields and calling its methods is exactly what an
 * initialiser like {@code index = buildIndex(items)} wants. A constructor
 * parameter is the one thing the live object no longer has, and an
 * initialiser that reads one is refused with that reason.
 */
public final class InstanceInitialiserSlicer implements Opcodes {

    private InstanceInitialiserSlicer() {
    }

    /** The companion method that produces the field's initial value for one object. */
    public static String methodName(String fieldName) {
        return InjectedNames.FIELD_INIT_PREFIX + fieldName;
    }

    /** What can be initialised on first read, how, and what cannot. */
    public static final class Plan {
        /**
         * Field key ("name:desc") to the instructions that compute its value
         * with the object in local 0, leaving the value on the stack.
         */
        public final Map<String, InsnList> initialisers;
        /** Field key to the reason it was left alone. */
        public final Map<String, String> refused;

        Plan(Map<String, InsnList> initialisers, Map<String, String> refused) {
            this.initialisers = initialisers;
            this.refused = refused;
        }

        public static Plan empty() {
            return new Plan(new LinkedHashMap<>(), new LinkedHashMap<>());
        }

        public boolean isEmpty() {
            return initialisers.isEmpty();
        }
    }

    /**
     * @param newBytecode    the recompiled class
     * @param addedFieldKeys "name:desc" of the fields this reload adds; the
     *                       static ones among them are not this class's
     *                       business and are ignored
     */
    public static Plan planFor(byte[] newBytecode, Set<String> addedFieldKeys) {
        Map<String, InsnList> initialisers = new LinkedHashMap<>();
        Map<String, String> refused = new LinkedHashMap<>();
        if (addedFieldKeys.isEmpty()) return new Plan(initialisers, refused);

        ClassNode cls = new ClassNode();
        try {
            new org.objectweb.asm.ClassReader(newBytecode).accept(cls, 0);
        } catch (RuntimeException e) {
            return new Plan(initialisers, refused);
        }

        Set<String> remaining = new LinkedHashSet<>();
        for (FieldNode field : cls.fields) {
            String key = field.name + ":" + field.desc;
            if ((field.access & ACC_STATIC) == 0 && addedFieldKeys.contains(key)) {
                remaining.add(key);
            }
        }
        if (remaining.isEmpty()) return new Plan(initialisers, refused);

        for (MethodNode ctor : cls.methods) {
            if (!"<init>".equals(ctor.name) || remaining.isEmpty()) continue;
            sliceConstructor(cls, ctor, remaining, initialisers, refused);
        }
        return new Plan(initialisers, refused);
    }

    /**
     * javac writes the same initialiser code into every constructor that calls
     * super, so the first constructor that assigns a field decides for it. A
     * constructor that delegates with {@code this(...)} assigns nothing and is
     * skipped by the same rule.
     */
    private static void sliceConstructor(ClassNode cls, MethodNode ctor, Set<String> remaining,
                                         Map<String, InsnList> initialisers,
                                         Map<String, String> refused) {
        Frame<BasicValue>[] frames;
        try {
            frames = new Analyzer<>(new BasicInterpreter()).analyze(cls.name, ctor);
        } catch (Exception e) {
            return;
        }
        AbstractInsnNode[] insns = ctor.instructions.toArray();
        Set<LabelNode> enteredFromOutside = StaticInitialiserSlicer.branchTargets(ctor);

        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof FieldInsnNode put) || put.getOpcode() != PUTFIELD) continue;
            String key = put.name + ":" + put.desc;
            if (!remaining.contains(key) || !put.owner.equals(cls.name)) continue;

            int start = StaticInitialiserSlicer.segmentStart(frames, i);
            String problem;
            if (start < 0) {
                problem = "its initialiser is not a self-contained block";
            } else if (!(insns[start] instanceof VarInsnNode receiver)
                    || receiver.getOpcode() != ALOAD || receiver.var != 0) {
                problem = "its initialiser is not a plain assignment to this";
            } else {
                problem = check(insns, start, i, enteredFromOutside, ctor);
            }
            remaining.remove(key);
            if (problem != null) {
                refused.put(key, problem);
                continue;
            }

            // Without the receiver push at the front and the write at the
            // end: what is left computes the value and leaves it on the
            // stack, which is what a method returning it needs.
            InsnList sliced = new InsnList();
            Map<LabelNode, LabelNode> labelCopies = new HashMap<>();
            for (int j = start + 1; j < i; j++) {
                AbstractInsnNode insn = insns[j];
                if (insn instanceof FrameNode || insn instanceof LineNumberNode) continue;
                sliced.add(insn.clone(StaticInitialiserSlicer.labelCopies(insn, labelCopies)));
            }
            initialisers.put(key, sliced);
        }
    }

    /**
     * Whether a candidate segment really is one field's initialiser and
     * nothing else, computed from what a live object still has.
     *
     * @return null when it is, or the reason to leave it alone
     */
    private static String check(AbstractInsnNode[] insns, int start, int end,
                                Set<LabelNode> enteredFromOutside, MethodNode ctor) {
        Set<Integer> localsWrittenHere = new HashSet<>();

        for (int j = start; j <= end; j++) {
            AbstractInsnNode insn = insns[j];

            if (j < end && insn.getOpcode() == PUTFIELD) {
                return "its initialiser also writes " + ((FieldInsnNode) insn).name;
            }
            if (insn.getOpcode() == PUTSTATIC) {
                return "its initialiser writes a static field";
            }
            if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode
                    || insn instanceof LookupSwitchInsnNode) {
                return "its initialiser branches";
            }
            if (insn instanceof LabelNode label && enteredFromOutside.contains(label)) {
                return "code elsewhere can jump into its initialiser";
            }
            if (insn.getOpcode() == ATHROW || insn.getOpcode() == RET) {
                return "its initialiser does not fall through";
            }

            if (insn instanceof VarInsnNode var) {
                if (StaticInitialiserSlicer.isStore(var.getOpcode())) {
                    if (var.var == 0) return "its initialiser overwrites this";
                    localsWrittenHere.add(var.var);
                } else if (var.var != 0 && !localsWrittenHere.contains(var.var)) {
                    // Local 0 is the object, which the live object still is.
                    // Any other local is a constructor parameter or something
                    // computed from one, and the object no longer has those.
                    return "its initialiser reads a constructor parameter";
                }
            }
            if (insn instanceof IincInsnNode iinc && !localsWrittenHere.contains(iinc.var)) {
                return "its initialiser reads a constructor parameter";
            }
        }

        for (TryCatchBlockNode tryCatch : ctor.tryCatchBlocks) {
            int from = StaticInitialiserSlicer.indexOf(insns, tryCatch.start);
            int to = StaticInitialiserSlicer.indexOf(insns, tryCatch.end);
            if (from <= end && to >= start) {
                return "its initialiser sits inside a try/catch";
            }
        }
        return null;
    }
}
