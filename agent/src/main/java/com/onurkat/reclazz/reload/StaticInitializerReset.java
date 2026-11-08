/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.transform.StaticFieldPreparation;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** Existing statics only. Never executes application bytecode or replays <clinit>. */
public final class StaticInitializerReset implements Opcodes {
    private StaticInitializerReset() { }
    private static final String PENDING = "a changed static initializer was not applied";

    private static final class State {
        final Map<String, Initializer> fields;
        final Map<String, Initializer> observed;
        final boolean prepared;
        String observedClinit;
        boolean missingBaselineReported;

        State(byte[] original) {
            prepared = original != null;
            fields = prepared ? initializers(original) : new LinkedHashMap<>();
            observed = new HashMap<>(fields);
            observedClinit = prepared ? clinitSymbols(read(original)) : null;
        }
    }
    private static final ClassValue<State> BASELINES = new ClassValue<>() {
        @Override protected State computeValue(Class<?> owner) {
            byte[] original = StaticFieldPreparation.originalBytes(owner);
            return new State(original);
        }
    };

    /** Only after the JVM accepted the owner; per-Class lock serializes saves of that owner. */
    public static void afterReload(Class<?> owner, byte[] original, boolean enabled) {
        afterReload(owner, original, enabled, Set.of());
    }

    public static void afterReload(Class<?> owner, byte[] original, boolean enabled, Set<String> addedFields) {
        try {
            State state = BASELINES.get(owner);
            Map<String, Initializer> baseline = state.fields;
            synchronized (baseline) {
                if (!state.prepared) {
                    if (enabled && !state.missingBaselineReported) {
                        StatusReporter.info("Static initializer reset unavailable for " + owner.getName()
                                + ": missing-baseline (no recorded first definition)");
                        state.missingBaselineReported = true;
                    }
                    return;
                }
                ClassNode cls = read(original);
                String clinit = clinitSymbols(cls);
                boolean clinitChanged = !state.observedClinit.equals(clinit);
                Set<String> writtenFields = writtenFields(cls);
                Map<String, Initializer> candidates = initializers(original);
                // A removed startup field may be reintroduced as a logical
                // FieldStore field. Do not reclaim it just because the JVM
                // still has its old physical slot.
                baseline.keySet().removeAll(addedFields);
                try {
                    for (var entry : candidates.entrySet()) {
                        String key = entry.getKey();
                        Initializer candidate = entry.getValue();
                        Initializer before = baseline.get(key);
                        // A new field belongs exclusively to the existing FieldStore path.
                        if (before == null) continue;
                        if (before.symbols == null || candidate.symbols == null) {
                            // A missing slice cannot prove a field-level change.
                            // Only changed static code which writes this field can
                            // justify conservative advice, and only with opt-in.
                            if (enabled && clinitChanged && writtenFields.contains(key)) {
                                String reason = candidate.symbols == null ? "non-isolated-write" : "missing-baseline";
                                StatusReporter.warn("Static " + subject(owner,key) + " not reset: " + reason
                                        + " (static initialization changed; initializer comparison unavailable); restart to apply");
                                RestartLedger.note(subject(owner,key), PENDING);
                            }
                            continue;
                        }
                        if (before.symbols.equals(candidate.symbols)) {
                            RestartLedger.resolve(subject(owner,key), PENDING);
                            continue;
                        }
                        if (!enabled) {
                            Initializer observed = state.observed.get(key);
                            if (candidate.reason == null && candidate.expression != null && !candidate.constant
                                    && observed != null && observed.symbols != null
                                    && !observed.symbols.equals(candidate.symbols)) {
                                var field = owner.getDeclaredField(key.substring(0, key.indexOf(':')));
                                // A startup constant would still be final even if
                                // the option had been enabled at first definition.
                                if (!(before.constant && Modifier.isFinal(field.getModifiers())))
                                    report(owner, key, "option-off (enable resetStatics=true)");
                            }
                            continue;
                        }
                        String reason = candidate.reason;
                        if (candidate.constant) reason = "became-constant (callers inline it)";
                        var field = owner.getDeclaredField(key.substring(0, key.indexOf(':')));
                        if (Modifier.isFinal(field.getModifiers())) reason = "not-prepared (constant at first load or late attach)";
                        if (reason != null) { report(owner, key, reason); continue; }
                        Object value;
                        try { value = candidate.expression.value(); }
                        catch (Throwable failure) { report(owner, key, "evaluation-failed"); continue; }
                        try {
                            var setter = MethodHandles.privateLookupIn(owner, MethodHandles.lookup())
                                    .findStaticSetter(owner, field.getName(), field.getType());
                            setter.invokeWithArguments(StaticInitialiserSlicer.narrowConstant(field.getType().descriptorString(), value));
                        } catch (Throwable failure) { report(owner, key, "setter-access-or-type"); continue; }
                        baseline.put(key, candidate);
                        RestartLedger.resolve(subject(owner,key), PENDING);
                        StatusReporter.info("Reset static " + owner.getName() + "." + field.getName() + " (initializer changed)");
                    }
                } finally {
                    // Observation is about successful native redefinition;
                    // per-field applied baselines above still advance only
                    // after a successful value reset.
                    state.observedClinit = clinit;
                    state.observed.clear();
                    state.observed.putAll(candidates);
                }
            }
        } catch (Exception failure) {
            // Without analysis there is no evidence a restart is needed.
            if (enabled) StatusReporter.info("Static initializer reset unavailable for " + owner.getName() + ": analysis-failed");
        }
    }

    private static void report(Class<?> owner, String key, String reason) {
        StatusReporter.warn("Static " + owner.getName() + "." + key.substring(0, key.indexOf(':'))
                + " initializer changed, not reset: " + reason + "; restart to apply");
        RestartLedger.note(subject(owner,key), PENDING);
    }
    private static String subject(Class<?> owner, String key) { return owner.getName() + "." + key.substring(0,key.indexOf(':')); }

    private record Initializer(String symbols, Expression expression, String reason, boolean constant) { }
    @FunctionalInterface private interface Expression { Object value() throws Throwable; }
    private static ClassNode read(byte[] bytes) {
        ClassNode cls = new ClassNode(); new ClassReader(bytes).accept(cls, 0); return cls;
    }
    private static String key(FieldNode field) { return field.name + ":" + field.desc; }

    private static Set<String> writtenFields(ClassNode cls) {
        Set<String> keys = new HashSet<>();
        for (MethodNode method : cls.methods) if (method.name.equals("<clinit>"))
            for (AbstractInsnNode insn : method.instructions)
                if (insn instanceof FieldInsnNode put && put.getOpcode() == PUTSTATIC && put.owner.equals(cls.name))
                    keys.add(put.name + ":" + put.desc);
        return keys;
    }

    private static String clinitSymbols(ClassNode cls) {
        for (MethodNode method : cls.methods) if (method.name.equals("<clinit>")) {
            StringBuilder out = new StringBuilder(symbols(method.instructions));
            Map<LabelNode,Integer> positions = labelPositions(method.instructions);
            for (TryCatchBlockNode handler : method.tryCatchBlocks)
                out.append("handler:").append(positions.get(handler.start)).append(':')
                        .append(positions.get(handler.end)).append(':').append(positions.get(handler.handler))
                        .append(':').append(handler.type).append(';');
            return out.toString();
        }
        return "no-clinit";
    }

    private static Map<String, Initializer> initializers(byte[] bytes) {
        ClassNode cls = read(bytes);
        Map<String, Initializer> result = new LinkedHashMap<>();
        for (FieldNode field : cls.fields) {
            if ((field.access & ACC_STATIC) == 0 || (field.access & ACC_SYNTHETIC) != 0
                    || field.name.equals("serialVersionUID")) continue;
            String key = key(field);
            if (field.value != null) {
                result.put(key, new Initializer("constant:" + field.desc + ":" + field.value, null, "compile-time-constant", true));
                continue;
            }
            var plan = StaticInitialiserSlicer.planFor(bytes, Set.of(key));
            if (!plan.hasCode()) {
                boolean absent = cls.methods.stream().filter(m -> m.name.equals("<clinit>"))
                        .flatMap(m -> Arrays.stream(m.instructions.toArray()))
                        .noneMatch(i -> i instanceof FieldInsnNode put && put.getOpcode() == PUTSTATIC
                                && put.owner.equals(cls.name) && (put.name + ":" + put.desc).equals(key));
                result.put(key, new Initializer(absent ? "no-write" : null, null, "non-isolated-write", false));
                continue;
            }
            String symbols = symbols(plan.initialiserCode);
            String reason = (cls.access & (ACC_ENUM | ACC_INTERFACE)) != 0 ? "excluded-owner" : null;
            Expression expression = null;
            if (reason == null) {
                try { expression = expression(plan.initialiserCode); }
                catch (Refused refused) { reason = refused.getMessage(); }
            }
            result.put(key, new Initializer(symbols, expression, reason, false));
        }
        return result;
    }

    private static String symbols(InsnList code) {
        StringBuilder out = new StringBuilder();
        Map<LabelNode,Integer> labels = labelPositions(code);
        for (AbstractInsnNode insn : code) {
            int op = insn.getOpcode(); if (op < 0) continue;
            out.append(op).append(':');
            if (insn instanceof LdcInsnNode i) out.append(i.cst.getClass().getName()).append(':')
                    .append(i.cst.toString().length()).append(':').append(i.cst);
            else if (insn instanceof FieldInsnNode i) out.append(i.owner).append('.').append(i.name).append(i.desc);
            else if (insn instanceof MethodInsnNode i) out.append(i.owner).append('.').append(i.name).append(i.desc).append(i.itf);
            else if (insn instanceof TypeInsnNode i) out.append(i.desc);
            else if (insn instanceof IntInsnNode i) out.append(i.operand);
            else if (insn instanceof VarInsnNode i) out.append(i.var);
            else if (insn instanceof IincInsnNode i) out.append(i.var).append(':').append(i.incr);
            else if (insn instanceof MultiANewArrayInsnNode i) out.append(i.desc).append(':').append(i.dims);
            else if (insn instanceof JumpInsnNode i) out.append(labels.get(i.label));
            else if (insn instanceof InvokeDynamicInsnNode i) out.append(i.name).append(i.desc).append(i.bsm).append(Arrays.toString(i.bsmArgs));
            else if (insn instanceof TableSwitchInsnNode i) out.append(i.min).append(':').append(i.max).append(':')
                    .append(labels.get(i.dflt)).append(i.labels.stream().map(labels::get).toList());
            else if (insn instanceof LookupSwitchInsnNode i) out.append(i.keys).append(':')
                    .append(labels.get(i.dflt)).append(i.labels.stream().map(labels::get).toList());
            out.append(';');
        }
        return out.toString();
    }

    private static Map<LabelNode,Integer> labelPositions(InsnList code) {
        Map<LabelNode,Integer> labels = new IdentityHashMap<>();
        int position = 0;
        for (AbstractInsnNode insn : code) {
            if (insn instanceof LabelNode label) labels.put(label, position);
            if (insn.getOpcode() >= 0) position++;
        }
        return labels;
    }

    private static final class Refused extends Exception { Refused(String reason) { super(reason); } }
    private static Refused refuse(String reason) { return new Refused(reason); }
    private record Literal(Object literal) implements Expression { public Object value() { return literal; } }
    private static final class Construction implements Expression {
        final String type; Expression argument;
        Construction(String type) { this.type = type; }
        public Object value() throws Throwable {
            Object text = argument.value();
            return type.equals("java/math/BigDecimal") ? new BigDecimal((String) text) : new BigInteger((String) text);
        }
    }
    private static final class FreshArray implements Expression {
        final String type; final Expression[] elements; boolean consumed;
        FreshArray(String type, int length) { this.type = type; elements = new Expression[length]; }
        public Object value() throws Throwable {
            Object[] array = type.equals("java/util/Map$Entry") ? new Map.Entry<?,?>[elements.length] : new Object[elements.length];
            Class<?> component = Class.forName(type.replace('/', '.'), false, null);
            for (int i=0; i<array.length; i++) {
                Object value = elements[i] == null ? null : elements[i].value();
                if (value != null && !component.isInstance(value)) throw new ArrayStoreException();
                array[i] = value;
            }
            return array;
        }
    }
    private record Call(MethodHandle handle, List<Expression> args, Type[] types) implements Expression {
        public Object value() throws Throwable {
            List<Object> values = new ArrayList<>();
            for (int i=0; i<args.size(); i++) values.add(StaticInitialiserSlicer.narrowConstant(types[i].getDescriptor(), args.get(i).value()));
            return handle.invokeWithArguments(values);
        }
    }

    /** Symbolic construction first; no calls, allocation factories or application methods during admission. */
    private static Expression expression(InsnList code) throws Refused {
        List<Expression> stack = new ArrayList<>();
        Expression result = null;
        for (AbstractInsnNode insn : code) {
            int op = insn.getOpcode(); if (op < 0 || op == NOP) continue;
            if (op >= ICONST_M1 && op <= ICONST_5) stack.add(new Literal(op-ICONST_0));
            else if (op == ACONST_NULL) stack.add(new Literal(null));
            else if (op == LCONST_0 || op == LCONST_1) stack.add(new Literal((long)(op-LCONST_0)));
            else if (op >= FCONST_0 && op <= FCONST_2) stack.add(new Literal((float)(op-FCONST_0)));
            else if (op == DCONST_0 || op == DCONST_1) stack.add(new Literal((double)(op-DCONST_0)));
            else if (op == BIPUSH || op == SIPUSH) stack.add(new Literal(((IntInsnNode)insn).operand));
            else if (insn instanceof LdcInsnNode ldc) {
                if (!(ldc.cst instanceof String || ldc.cst instanceof Integer || ldc.cst instanceof Long
                        || ldc.cst instanceof Float || ldc.cst instanceof Double)) throw refuse("unsupported-literal");
                stack.add(new Literal(ldc.cst));
            } else if (op == GETSTATIC) throw refuse("static-dependency");
            else if (op == NEW) {
                String type = ((TypeInsnNode)insn).desc;
                if (!type.equals("java/math/BigDecimal") && !type.equals("java/math/BigInteger")) throw refuse("unapproved-call");
                stack.add(new Construction(type));
            } else if (op == DUP) {
                Expression top = pop(stack);
                if (!(top instanceof FreshArray a && !a.consumed) && !(top instanceof Construction c && c.argument == null)) throw refuse("unsupported-expression");
                stack.add(top); stack.add(top);
            } else if (op == ANEWARRAY) {
                Expression length = pop(stack);
                String type = ((TypeInsnNode)insn).desc;
                if (!(length instanceof Literal l && l.literal instanceof Integer n && n >= 0 && n <= 65535)
                        || !ARRAY_ELEMENTS.contains(type)) throw refuse("unsupported-array");
                stack.add(new FreshArray(type, (Integer)((Literal)length).literal));
            } else if (op == AASTORE) {
                Expression value = pop(stack), index = pop(stack), array = pop(stack);
                leaf(value);
                if (!(array instanceof FreshArray a) || a.consumed || !(index instanceof Literal l && l.literal instanceof Integer n)
                        || n < 0 || n >= a.elements.length || a.elements[n] != null) throw refuse("unsupported-array");
                a.elements[n] = value;
            } else if (insn instanceof MethodInsnNode method) {
                Type[] types = Type.getArgumentTypes(method.desc);
                List<Expression> args = new ArrayList<>();
                for (int i=types.length-1; i>=0; i--) args.add(0, pop(stack));
                if (op == INVOKESPECIAL && method.name.equals("<init>") && method.desc.equals("(Ljava/lang/String;)V")) {
                    Expression receiver = pop(stack);
                    if (!(receiver instanceof Construction c) || !c.type.equals(method.owner) || c.argument != null
                            || !(args.get(0) instanceof Literal l && l.literal instanceof String)) throw refuse("unapproved-call");
                    c.argument = args.get(0);
                } else {
                    MethodHandle handle = op == INVOKESTATIC ? CALLS.get(method.owner + "." + method.name + method.desc) : null;
                    if (handle == null) throw refuse("unapproved-call");
                    for (int i=0; i<args.size(); i++) {
                        Expression arg = args.get(i);
                        if (types[i].getSort() == Type.ARRAY) {
                            if (!(arg instanceof FreshArray a) || a.consumed
                                    || (!types[i].getElementType().getInternalName().equals("java/lang/Object")
                                        && !types[i].getElementType().getInternalName().equals(a.type))) throw refuse("unsupported-array");
                            a.consumed = true;
                        } else leaf(arg);
                    }
                    stack.add(new Call(handle, args, types));
                }
            } else if (op == PUTSTATIC) {
                if (result != null) throw refuse("multiple-writes");
                result = pop(stack); leaf(result);
                if (result instanceof Literal l && l.literal == null) throw refuse("unsupported-expression");
                if (!stack.isEmpty()) throw refuse("non-isolated-write");
            } else if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) throw refuse("control-flow");
            else throw refuse(op == INVOKEDYNAMIC ? "unapproved-call" : "unsupported-expression");
        }
        if (result == null || !stack.isEmpty()) throw refuse("non-isolated-write");
        return result;
    }
    private static Expression pop(List<Expression> stack) throws Refused {
        if (stack.isEmpty()) throw refuse("unsupported-expression");
        return stack.remove(stack.size()-1);
    }
    private static void leaf(Expression value) throws Refused {
        if (value instanceof FreshArray || (value instanceof Construction c && c.argument == null)) throw refuse("unsupported-expression");
    }

    // Exact signatures, resolved against bootstrap JDK classes only. No application
    // receiver/equals/hashCode can enter an admitted expression tree.
    private static final Map<String, MethodHandle> CALLS = calls();
    private static final Set<String> ARRAY_ELEMENTS = Set.of("java/lang/Object", "java/util/Map$Entry",
            "java/util/Map", "java/util/List", "java/util/Set", "java/util/Collection", "java/lang/Number",
            "java/io/Serializable", "java/lang/Comparable", "java/lang/constant/Constable", "java/lang/constant/ConstantDesc",
            "java/lang/String", "java/lang/Integer", "java/lang/Long", "java/lang/Short", "java/lang/Byte",
            "java/lang/Character", "java/lang/Boolean", "java/lang/Double", "java/lang/Float",
            "java/math/BigDecimal", "java/math/BigInteger");
    private static Map<String, MethodHandle> calls() {
        Map<String,MethodHandle> calls = new HashMap<>();
        for (Class<?> type : List.of(List.class, Set.class, Map.class)) {
            for (int count=0; count<=10; count++) {
                Class<?>[] args = new Class<?>[type == Map.class ? 2*count : count]; Arrays.fill(args, Object.class);
                allow(calls, type, "of", type, args);
            }
        }
        allow(calls, List.class, "of", List.class, Object[].class);
        allow(calls, Set.class, "of", Set.class, Object[].class);
        allow(calls, Map.class, "ofEntries", Map.class, Map.Entry[].class);
        allow(calls, Map.class, "entry", Map.Entry.class, Object.class, Object.class);
        Class<?>[] wrappers = {Integer.class, Long.class, Short.class, Byte.class, Character.class, Boolean.class, Double.class, Float.class};
        Class<?>[] primitives = {int.class, long.class, short.class, byte.class, char.class, boolean.class, double.class, float.class};
        for (int i=0; i<wrappers.length; i++) allow(calls, wrappers[i], "valueOf", wrappers[i], primitives[i]);
        return Map.copyOf(calls);
    }
    private static void allow(Map<String,MethodHandle> calls, Class<?> owner, String name, Class<?> result, Class<?>... args) {
        try {
            MethodType type = MethodType.methodType(result, args);
            calls.put(owner.getName().replace('.', '/') + "." + name + type.descriptorString(),
                    MethodHandles.publicLookup().findStatic(owner, name, type).asFixedArity());
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
