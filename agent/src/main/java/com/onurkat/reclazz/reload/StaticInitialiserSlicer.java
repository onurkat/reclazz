/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.*;
import com.onurkat.reclazz.bootstrap.InjectedNames;

/**
 * The initial value of a static field a reload added.
 *
 * <p>Adding the field is the easy half and has worked for a while: the value
 * lives in {@code FieldStore} and every access to it is rewritten to go there,
 * so nothing throws. The half that was missing is the value the field starts
 * with. That value is produced by {@code <clinit>}, which the JVM runs exactly
 * once per class and will not run again, and re-running the whole of it would
 * reset every other static the application has been mutating since startup.
 * So the field read as null or zero and Reclazz said so.
 *
 * <p>Saying so is honest but it is not the answer. The answer is to run the
 * part of {@code <clinit>} that belongs to the new field and nothing else.
 * Two things make that tractable.
 *
 * <p>The first is that most added statics are constants, and javac does not
 * put a constant in {@code <clinit>} at all: {@code static final int MAX = 50}
 * is stored as a {@code ConstantValue} attribute on the field itself. Reading
 * it is exact, and there is no code to run.
 *
 * <p>The second is the shape of what is left. javac emits each field
 * initialiser as a run of instructions that starts with an empty operand stack
 * and ends at the {@code PUTSTATIC} for that field:
 *
 * <pre>
 *    0: new ConcurrentHashMap / dup / invokespecial / putstatic CACHE
 *   10: iconst_2 / anewarray ... / invokestatic asList / putstatic NAMES
 *   36: getstatic System.out / ldc / invokevirtual println   &lt;- a static block
 * </pre>
 *
 * <p>So the segments between empty-stack points are the initialisers, one
 * apiece, and a static block with side effects is simply a segment that no
 * added field's PUTSTATIC ends. Taking the segments we want and leaving the
 * rest is the whole trick.
 * A conditional value also needs its condition and both arms. For statics,
 * the candidate is extended to include forward branches entering its interior,
 * then checked as a single-entry expression with one final field write. A
 * preceding block may arrive at its empty-stack boundary, but may not enter
 * its middle, skip the write or loop around it. Instance slicing still uses
 * the original straight-line boundary helpers.
 *
 * <p>This class refuses far more readily than it accepts, because the failure
 * it is avoiding is silent. A segment that writes a field the application
 * already owns, or that reads a local computed somewhere else, or that a
 * branch can enter from outside, is not an isolated initialiser however much
 * it looks like one. Those are reported as uninitialised, which is exactly the
 * behaviour that existed before this class.
 */
public final class StaticInitialiserSlicer implements Opcodes {

    /** The synthetic method a companion carries when there is code to run. */
    public static final String INIT_METHOD = InjectedNames.INIT_METHOD;

    private StaticInitialiserSlicer() {
    }

    /** What can be initialised, how, and what cannot. */
    public static final class Plan {
        /** Field key ("name:desc") to compile-time constant value. */
        public final Map<String, Object> constants;
        /** Sliced {@code <clinit>} code, or null when there is none to run. */
        public final InsnList initialiserCode;
        /** Field keys the sliced code initialises. */
        public final Set<String> slicedKeys;
        /** Field key to the reason it was left alone. */
        public final Map<String, String> refused;

        Plan(Map<String, Object> constants, InsnList initialiserCode,
             Set<String> slicedKeys, Map<String, String> refused) {
            this.constants = constants;
            this.initialiserCode = initialiserCode;
            this.slicedKeys = slicedKeys;
            this.refused = refused;
        }

        public boolean hasCode() {
            return initialiserCode != null && initialiserCode.size() > 0;
        }

        /** Every field this plan gives a value to, by either route. */
        public Set<String> initialisedKeys() {
            Set<String> all = new LinkedHashSet<>(constants.keySet());
            all.addAll(slicedKeys);
            return all;
        }
    }

    /**
     * @param newBytecode     the recompiled class
     * @param addedStaticKeys "name:desc" of static fields this reload adds and
     *                        that have not been given a value yet
     */
    public static Plan planFor(byte[] newBytecode, Set<String> addedStaticKeys) {
        Map<String, Object> constants = new LinkedHashMap<>();
        Map<String, String> refused = new LinkedHashMap<>();
        Set<String> slicedKeys = new LinkedHashSet<>();

        if (addedStaticKeys.isEmpty()) {
            return new Plan(constants, null, slicedKeys, refused);
        }

        ClassNode cls = new ClassNode();
        try {
            new org.objectweb.asm.ClassReader(newBytecode).accept(cls, 0);
        } catch (RuntimeException e) {
            for (String key : addedStaticKeys) refused.put(key, "the class could not be read");
            return new Plan(constants, null, slicedKeys, refused);
        }

        // Constants first: exact, and it removes them from the work below.
        Set<String> remaining = new LinkedHashSet<>(addedStaticKeys);
        for (FieldNode field : cls.fields) {
            String key = field.name + ":" + field.desc;
            if (remaining.contains(key) && field.value != null) {
                constants.put(key, field.value);
                remaining.remove(key);
            }
        }
        if (remaining.isEmpty()) {
            return new Plan(constants, null, slicedKeys, refused);
        }

        MethodNode clinit = null;
        for (MethodNode m : cls.methods) {
            if ("<clinit>".equals(m.name)) clinit = m;
        }
        if (clinit == null) {
            for (String key : remaining) {
                refused.put(key, "the field has no initialiser, so null/0 is its value");
            }
            return new Plan(constants, null, slicedKeys, refused);
        }

        Frame<BasicValue>[] frames;
        try {
            frames = new Analyzer<>(new BasicInterpreter()).analyze(cls.name, clinit);
        } catch (Exception e) {
            for (String key : remaining) {
                refused.put(key, "the static initialiser could not be analysed");
            }
            return new Plan(constants, null, slicedKeys, refused);
        }

        AbstractInsnNode[] insns = clinit.instructions.toArray();
        InsnList sliced = new InsnList();
        Map<LabelNode, LabelNode> labelCopies = new HashMap<>();
        Map<String, Integer> writes = new HashMap<>();
        for (AbstractInsnNode insn : insns) {
            // Forward jumps need their destination copies before cloning.
            if (insn instanceof LabelNode label) labelCopies.put(label, new LabelNode());
            if (insn instanceof FieldInsnNode put && put.getOpcode() == PUTSTATIC && put.owner.equals(cls.name))
                writes.merge(put.name + ":" + put.desc, 1, Integer::sum);
        }

        // In source order, so a field initialised from one declared above it
        // still sees the value: javac emits them in that order too.
        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof FieldInsnNode put) || put.getOpcode() != PUTSTATIC) continue;
            String key = put.name + ":" + put.desc;
            if (!remaining.contains(key) || !put.owner.equals(cls.name)) continue;

            int start = staticSegmentStart(insns, frames, i);
            String problem = writes.get(key) != 1 ? "the static block assigns this field more than once"
                    : (start < 0)
                    ? "its initialiser is not a self-contained block"
                    : check(insns, start, i, frames, clinit);
            if (problem != null) {
                refused.put(key, problem);
                remaining.remove(key);
                continue;
            }

            for (int j = start; j <= i; j++) {
                AbstractInsnNode insn = insns[j];
                if (insn instanceof FrameNode || insn instanceof LineNumberNode) continue;
                sliced.add(insn.clone(labelCopies(insn, labelCopies)));
            }
            slicedKeys.add(key);
            remaining.remove(key);
        }

        for (String key : remaining) {
            refused.put(key, "no initialiser for it was found in the static block");
        }
        return new Plan(constants, sliced.size() > 0 ? sliced : null, slicedKeys, refused);
    }

    /**
     * The nearest point at or before {@code putIndex} where the operand stack
     * is empty, which is where this field's initialiser began.
     *
     * @return -1 when there is no such point, meaning the write is entangled
     *         with whatever was on the stack before it
     */
    static int segmentStart(Frame<BasicValue>[] frames, int putIndex) {
        for (int i = putIndex; i >= 0; i--) {
            Frame<BasicValue> frame = frames[i];
            if (frame == null) return -1;          // unreachable code
            if (frame.getStackSize() == 0) return i;
        }
        return -1;
    }

    /** Include the condition and both arms when a previous jump enters the value expression. */
    private static int staticSegmentStart(AbstractInsnNode[] insns, Frame<BasicValue>[] frames, int end) {
        int start = segmentStart(frames, end);
        if (start < 0) return -1;
        while (true) {
            // Labels/frames at an empty-stack boundary belong to that boundary.
            while (start > 0 && insns[start - 1].getOpcode() < 0) start--;
            int earlier = start;
            for (int i = 0; i < start; i++) {
                if (!(insns[i] instanceof JumpInsnNode jump)) continue;
                int target = indexOf(insns, jump.label);
                // Entry at the boundary is fine; entry into an arm/merge is not.
                if (target > start && target <= end) {
                    int condition = segmentStart(frames, i);
                    if (condition < 0) return -1;
                    earlier = Math.min(earlier, condition);
                }
            }
            if (earlier == start) return start;
            start = earlier;
        }
    }

    /**
     * Whether a candidate segment really is one field's initialiser and
     * nothing else.
     *
     * @return null when it is, or the reason to leave it alone
     */
    private static String check(AbstractInsnNode[] insns, int start, int end,
                                Frame<BasicValue>[] frames, MethodNode clinit) {
        if (frames[start] == null || frames[start].getStackSize() != 0
                || frames[end] == null || frames[end].getStackSize() != 1)
            return "its initialiser does not have an empty entry and exit stack";
        boolean conditional = false;
        for (int i = 0; i < insns.length; i++) {
            AbstractInsnNode insn = insns[i];
            List<LabelNode> targets;
            if (insn instanceof JumpInsnNode jump) {
                targets = List.of(jump.label);
                if (i >= start && i <= end) {
                    conditional = true;
                    if (jump.getOpcode() == JSR) return "its initialiser branches to a subroutine";
                }
            } else if (insn instanceof TableSwitchInsnNode table) {
                if (i >= start && i <= end) return "its initialiser branches through a switch";
                targets = new ArrayList<>(table.labels);
                targets.add(table.dflt);
            } else if (insn instanceof LookupSwitchInsnNode lookup) {
                if (i >= start && i <= end) return "its initialiser branches through a switch";
                targets = new ArrayList<>(lookup.labels);
                targets.add(lookup.dflt);
            } else continue;
            for (LabelNode label : targets) {
                int target = indexOf(insns, label);
                if (target < 0) return "its initialiser branches to an unknown label";
                if (i >= start && i <= end) {
                    if (target <= i || target > end)
                        return "its initialiser branches backwards or outside its value expression";
                } else if ((i < start && target > start) || (i > end && target <= end)) {
                    // This includes an outer if that can skip the write, or a
                    // loop that encloses it, even without an edge into its middle.
                    return "a branch outside can enter, skip or repeat its initialiser";
                }
            }
        }
        if (conditional && !clinit.tryCatchBlocks.isEmpty())
            return "its conditional initialiser shares a static block with try/catch";
        Set<Integer> localsWrittenHere = new HashSet<>();

        for (int j = start; j <= end; j++) {
            AbstractInsnNode insn = insns[j];

            // A second write in the same segment means the computation is
            // shared, and running it would also write a field the application
            // already owns and may have changed since.
            if (j < end && insn.getOpcode() == PUTSTATIC) {
                return "its initialiser also writes " + ((FieldInsnNode) insn).name;
            }
            if (insn.getOpcode() == PUTFIELD) {
                return "its initialiser writes into an object it does not own here";
            }

            if (insn.getOpcode() == ATHROW || insn.getOpcode() == RET
                    || (insn.getOpcode() >= IRETURN && insn.getOpcode() <= RETURN)) {
                return "its initialiser does not fall through";
            }

            // Keep the new path to expressions: do not absorb separate work
            // or local state from one of the surrounding static-block arms.
            if (conditional) {
                int opcode = insn.getOpcode();
                if (insn instanceof VarInsnNode || insn instanceof IincInsnNode)
                    return "its conditional initialiser uses local state";
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
                if (isStore(var.getOpcode())) {
                    localsWrittenHere.add(var.var);
                } else if (!localsWrittenHere.contains(var.var)) {
                    // Reading a local the static block computed earlier: the
                    // value belongs to code we are not taking.
                    return "its initialiser reuses a value computed earlier in the static block";
                }
            }
            if (insn instanceof IincInsnNode iinc && !localsWrittenHere.contains(iinc.var)) {
                return "its initialiser reuses a value computed earlier in the static block";
            }
        }

        // An exception handler covering the segment means the segment is not
        // the whole story: something outside it decides what happens on throw.
        for (TryCatchBlockNode tryCatch : clinit.tryCatchBlocks) {
            int from = indexOf(insns, tryCatch.start);
            int to = indexOf(insns, tryCatch.end);
            if (from <= end && to >= start) {
                return "its initialiser sits inside a try/catch";
            }
        }
        return null;
    }

    /** Labels any jump, switch or handler can arrive at. */
    static Set<LabelNode> branchTargets(MethodNode method) {
        Set<LabelNode> targets = new HashSet<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump) {
                targets.add(jump.label);
            } else if (insn instanceof TableSwitchInsnNode table) {
                targets.add(table.dflt);
                targets.addAll(table.labels);
            } else if (insn instanceof LookupSwitchInsnNode lookup) {
                targets.add(lookup.dflt);
                targets.addAll(lookup.labels);
            }
        }
        for (TryCatchBlockNode tryCatch : method.tryCatchBlocks) {
            targets.add(tryCatch.handler);
        }
        return targets;
    }

    static int indexOf(AbstractInsnNode[] insns, AbstractInsnNode target) {
        for (int i = 0; i < insns.length; i++) {
            if (insns[i] == target) return i;
        }
        return -1;
    }

    static Map<LabelNode, LabelNode> labelCopies(AbstractInsnNode insn,
                                                  Map<LabelNode, LabelNode> copies) {
        if (insn instanceof LabelNode label) {
            copies.computeIfAbsent(label, unused -> new LabelNode());
        }
        return copies;
    }

    static boolean isStore(int opcode) {
        return opcode == ISTORE || opcode == LSTORE || opcode == FSTORE
                || opcode == DSTORE || opcode == ASTORE;
    }

    /**
     * A constant read from a {@code ConstantValue} attribute, as the type the
     * field declares.
     *
     * <p>The attribute stores {@code boolean}, {@code byte}, {@code short} and
     * {@code char} as {@code Integer}, because the constant pool has no
     * narrower form. Handing that Integer to a field store that a
     * {@code getstatic} will unbox as a Character is how a reload that looks
     * right throws ClassCastException somewhere unrelated.
     */
    public static Object narrowConstant(String desc, Object value) {
        if (!(value instanceof Integer i)) return value;
        return switch (desc) {
            case "Z" -> i != 0;
            case "B" -> i.byteValue();
            case "S" -> i.shortValue();
            case "C" -> (char) i.intValue();
            default -> value;
        };
    }

    /** Whether a descriptor names a type whose default is not null. */
    public static boolean isPrimitive(String desc) {
        return desc.length() == 1 && Type.getType(desc).getSort() != Type.OBJECT;
    }
}
