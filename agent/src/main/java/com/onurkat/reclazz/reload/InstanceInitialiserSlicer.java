/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.InjectedNames;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
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
 * Conditional expressions additionally require a single constructor, forward
 * branches confined to one assignment and an independent value after stripping
 * the receiver. The constructor itself is never replayed for the live object.
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
     * skipped by the same rule. Conditional values are more conservative: a
     * class with multiple constructors is refused instead of assuming their
     * assignments agree.
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
                problem = check(insns, start, i, frames, cls, ctor);
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
            for (int j = start + 1; j < i; j++)
                if (insns[j] instanceof LabelNode label) labelCopies.put(label, new LabelNode());
            for (int j = start + 1; j < i; j++) {
                AbstractInsnNode insn = insns[j];
                if (insn instanceof FrameNode || insn instanceof LineNumberNode) continue;
                sliced.add(insn.clone(StaticInitialiserSlicer.labelCopies(insn, labelCopies)));
            }
            if (hasBranch(insns, start, i) && !isIndependentValue(cls.name, put, sliced, ctor)) {
                refused.put(key, "its conditional initialiser is not an independent value assigned to this");
                continue;
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
                                Frame<BasicValue>[] frames, ClassNode cls, MethodNode ctor) {
        if (frames[start] == null || frames[start].getStackSize() != 0
                || frames[end] == null || frames[end].getStackSize() != 2)
            return "its initialiser does not leave exactly this and its value";
        boolean conditional = hasBranch(insns, start, end);
        if (conditional) {
            if (cls.methods.stream().filter(m -> m.name.equals("<init>")).count() != 1)
                return "conditional initialisers require a single constructor";
            FieldInsnNode target = (FieldInsnNode) insns[end];
            int writes = 0;
            for (AbstractInsnNode insn : insns)
                if (insn instanceof FieldInsnNode put && put.getOpcode() == PUTFIELD
                        && put.owner.equals(target.owner) && put.name.equals(target.name) && put.desc.equals(target.desc)) writes++;
            if (writes != 1) return "the constructor assigns this field more than once";
            if (!ctor.tryCatchBlocks.isEmpty()) return "its conditional initialiser shares a constructor with try/catch";
        }
        for (int i = 0; i < insns.length; i++) {
            List<LabelNode> targets;
            if (insns[i] instanceof JumpInsnNode jump) {
                if (i >= start && i <= end && jump.getOpcode() == JSR)
                    return "its initialiser branches to a subroutine";
                targets = List.of(jump.label);
            } else if (insns[i] instanceof TableSwitchInsnNode table) {
                if (i >= start && i <= end) return "its initialiser branches through a switch";
                targets = new ArrayList<>(table.labels);
                targets.add(table.dflt);
            } else if (insns[i] instanceof LookupSwitchInsnNode lookup) {
                if (i >= start && i <= end) return "its initialiser branches through a switch";
                targets = new ArrayList<>(lookup.labels);
                targets.add(lookup.dflt);
            } else continue;
            for (LabelNode label : targets) {
                int target = StaticInitialiserSlicer.indexOf(insns, label);
                if (target < 0) return "its initialiser branches to an unknown label";
                if (i >= start && i <= end) {
                    if (target <= i || target > end)
                        return "its initialiser branches backwards or outside its value expression";
                } else if ((i < start && target > start) || (i > end && target <= end)) {
                    return "a branch outside can enter, skip or repeat its initialiser";
                }
            }
        }
        Set<Integer> localsWrittenHere = new HashSet<>();

        for (int j = start; j <= end; j++) {
            AbstractInsnNode insn = insns[j];

            if (j < end && insn.getOpcode() == PUTFIELD) {
                return "its initialiser also writes " + ((FieldInsnNode) insn).name;
            }
            if (insn.getOpcode() == PUTSTATIC) {
                return "its initialiser writes a static field";
            }
            if (insn.getOpcode() == ATHROW || insn.getOpcode() == RET
                    || (insn.getOpcode() >= IRETURN && insn.getOpcode() <= RETURN)) {
                return "its initialiser does not fall through";
            }

            if (conditional) {
                int opcode = insn.getOpcode();
                if (insn instanceof VarInsnNode var && (opcode != ALOAD || var.var != 0)
                        || insn instanceof IincInsnNode)
                    return "its conditional initialiser reads a constructor parameter or local state";
                if ((opcode >= IASTORE && opcode <= SASTORE) || opcode == MONITORENTER || opcode == MONITOREXIT
                        || opcode == POP || opcode == POP2)
                    return "its conditional initialiser writes an array, locks or discards a value";
                if (insn instanceof MethodInsnNode call && Type.getReturnType(call.desc).getSort() == Type.VOID
                        && !call.name.equals("<init>"))
                    return "its conditional initialiser includes a separate void call";
                if (insn instanceof InvokeDynamicInsnNode call && Type.getReturnType(call.desc).getSort() == Type.VOID)
                    return "its conditional initialiser includes a separate void call";
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

    private static boolean hasBranch(AbstractInsnNode[] insns, int start, int end) {
        for (int i = start; i <= end; i++)
            if (insns[i] instanceof JumpInsnNode || insns[i] instanceof TableSwitchInsnNode
                    || insns[i] instanceof LookupSwitchInsnNode) return true;
        return false;
    }

    /** Prove that removing the leading receiver leaves a stand-alone value producer. */
    private static boolean isIndependentValue(String owner, FieldInsnNode field, InsnList value, MethodNode ctor) {
        MethodNode producer = new MethodNode(ACC_STATIC, methodName(field.name),
                "(L" + owner + ";)" + field.desc, null, null);
        // Analyze a copy: the plan's instructions must remain unconsumed.
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode insn : value)
            if (insn instanceof LabelNode label) labels.put(label, new LabelNode());
        for (AbstractInsnNode insn : value) producer.instructions.add(insn.clone(labels));
        producer.instructions.add(new InsnNode(Type.getType(field.desc).getOpcode(IRETURN)));
        producer.maxLocals = 1;
        producer.maxStack = ctor.maxStack;
        try {
            Frame<BasicValue>[] frames = new Analyzer<>(new BasicInterpreter()).analyze(owner, producer);
            Frame<BasicValue> result = frames[frames.length - 1];
            return result != null && result.getStackSize() == 1;
        } catch (Exception failure) {
            return false;
        }
    }
}
