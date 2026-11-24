/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.EnumCollectionHealer;
import com.onurkat.reclazz.bootstrap.EnumSurgery;
import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.bootstrap.ReflectionBridge;
import com.onurkat.reclazz.transform.EnumCollectionTransformer;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import com.onurkat.reclazz.ui.RestartLedger;

/**
 * Adds enum constants to a running JVM, or declines and says why.
 *
 * <p>This is the piece that decides. The measurements it rests on were taken
 * on JDK 21 and JDK 25, on a stock JVM and on JetBrains Runtime, before any of
 * it was written:
 *
 * <pre>
 *   values()            = [NEW, PAID, SHIPPED]
 *   valueOf(SHIPPED)    = SHIPPED
 *   switch(SHIPPED)     = default branch          (was ArrayIndexOutOfBounds)
 *   switch(PAID)        = unchanged
 *   old EnumMap.put     = works                   (was ArrayIndexOutOfBounds)
 *   old EnumSet.add     = works                   (was ArrayIndexOutOfBounds)
 * </pre>
 *
 * <p>Three things have to be true together, and each one is checked before
 * anything is written.
 *
 * <p>The change must keep every existing ordinal. An append does; so does an
 * insert in the middle when it is applied as an append (the new constant takes the
 * next free ordinal until a restart), unless a loaded JPA entity stores the enum by
 * ordinal. A reorder keeps the existing runtime order. Removing a constant
 * before the physical end requires a restart.
 *
 * <p>The JDK must have the shapes this reaches into. They are not a contract,
 * so a JDK that moves one of them makes this decline, and the reload falls back
 * to the message it printed before any of this existed. It never half-applies.
 *
 * <p>Nothing is installed until it is needed. The transform that repairs
 * collections built before the append goes in on the first successful append
 * and not before, so an application that never reloads an enum runs untouched
 * {@code java.util} code.
 */
public final class EnumConstantAppender {

    public static void observeHibernateFactories() {
        com.onurkat.reclazz.bootstrap.HibernateFactories.onInitialized(HibernateEnumStorage::factoryInitialized);
    }

    private EnumConstantAppender() {
    }

    /**
     * Apply the enum change if it can be applied, and report either way.
     *
     * @param change the diff taken before the redefinition
     * @return true when the constants are live in the JVM
     */
    public static synchronized boolean applyOrExplain(String className, Class<?> loaded, byte[] newBytecode,
                                          EnumConstantChange.Change change,
                                          Instrumentation instrumentation) {
        if (change == null) return false;
        if (!EnumConstantChange.ordinalMismatch(loaded, newBytecode))
            return apply(className, loaded, newBytecode, change, instrumentation);

        // Reserve the prospective order before inspecting any factory. Provider constructors never
        // wait for enum surgery (a Spring context can itself be waiting for that constructor).
        var observation = HibernateEnumStorage.beginOrderChange(loaded);
        if (observation == null) {
            String reason = "a destructive persistence-unit rebuild is in progress; retry the enum save after it finishes";
            StatusReporter.warn("Enum " + className + " keeps its running order because " + reason);
            RestartLedger.note(className, reason);
            return false;
        }
        try {
            return apply(className, loaded, newBytecode, change, instrumentation);
        } finally {
            HibernateEnumStorage.finishOrderChange(loaded, observation);
        }
    }

    private static boolean apply(String className, Class<?> loaded, byte[] newBytecode,
                                 EnumConstantChange.Change change, Instrumentation instrumentation) {
        // A removal from the end is the append's mirror: nothing renumbers,
        // so the ordinal argument that refuses every other removal does not
        // apply, and the surgery is the shrink instead of the grow.
        List<String> removedTail = EnumConstantChange.removedTailNames(loaded, newBytecode);
        if (!removedTail.isEmpty()) {
            EnumSurgery.Outcome outcome = EnumSurgery.removeFromEnd(loaded, removedTail);
            if (!outcome.applied()) {
                StatusReporter.warn("Enum " + className + " could not drop " + removedTail
                        + ": " + outcome.declinedBecause() + ". values() and valueOf() keep "
                        + "the old set until a restart. Everything else in this class reloaded.");
                RestartLedger.note(className,
                        change.describe() + ", which could not be applied to this JVM");
                return false;
            }
            // The mapper caches still carry the removed constant: they would
            // keep serialising it and keep accepting its name.
            EnumConstantChange.accepted(loaded, newBytecode);
            int mappers = JacksonEnumCaches.flush(); GsonCaches.flush();
            EnumConstantChange.reportTailRemoved(className, removedTail, mappers);
            if (implementsHybrisEnumValue(loaded)) {
                StatusReporter.info("This is a SAP Commerce enumtype: the EnumerationValue "
                        + "item for " + removedTail + " still exists in the database and is "
                        + "yours to remove (ImpEx or HAC) when nothing references it.");
            }
            return true;
        }

        java.util.LinkedHashMap<String, Integer> inserted = new java.util.LinkedHashMap<>();
        if (!EnumConstantChange.isAppendOnly(loaded, newBytecode)) {
            inserted = EnumConstantChange.insertedNames(loaded, newBytecode);
            if (!change.removed().isEmpty() || (inserted.isEmpty() && !change.reordered())) {
                EnumConstantChange.reportNotAppendOnly(className, change);
                return false;
            }
            // SAP Commerce stores its own enumtypes by code, but a separate JPA entity may still store the same Java enum
            // by ordinal (audit F6), so the persistence check applies to every enum.
            String persisted = ordinalPersistence(loaded, instrumentation);
            if (persisted != null) {
                StatusReporter.warn("Enum " + className + " " + change.describe() + ". " + persisted
                        + " may store it by ordinal. Source and running ordinals differ, so rows could read back as another constant. "
                        + "Restart to pick this up. Everything else in this class reloaded.");
                RestartLedger.note(className, change.describe()
                        + " with unproven ordinal safety (" + persisted + ")");
                return false;
            }
            if (inserted.isEmpty()) {
                EnumConstantChange.accepted(loaded, newBytecode);
                reportReordered(className);
                return true;
            }
        }

        List<String> names = inserted.isEmpty()
                ? EnumConstantChange.appendedNames(loaded, newBytecode) : new ArrayList<>(inserted.keySet());
        if (names.isEmpty()) {
            EnumConstantChange.report(className, change);
            return false;
        }

        Field nativeCode = null;
        if (implementsHybrisEnumValue(loaded)) {
            nativeCode = nativeCodeField(loaded, newBytecode);
            if (nativeCode == null) {
                StatusReporter.warn("Enum " + className + " cannot gain " + names
                        + ": its native SAP enum code initialisation is not the supported generated shape; restart required.");
                RestartLedger.note(className, "unsupported native enum code initialisation; restart required");
                return false;
            }
        }

        // The collections have to be able to catch up before the constant
        // exists, or the first use of it races the transform.
        String collectionsProblem = EnumCollectionTransformer.install(instrumentation);
        if (collectionsProblem != null) {
            StatusReporter.warn("Enum " + className + " could gain " + names
                    + ", but the EnumMap/EnumSet repair could not be installed ("
                    + collectionsProblem + "), and without it a map or set built before "
                    + "the reload would throw on the new value.");
            EnumConstantChange.report(className, change);
            return false;
        }

        EnumSurgery.Outcome outcome = EnumSurgery.append(loaded, names, nativeCode);
        if (!outcome.applied()) {
            // One sentence, not two. Following "could not gain" with the
            // generic notice, which opens "gained value(s)", read as a
            // contradiction: the describe() there is about what the source
            // did, and next to a refusal it looked like the opposite of what
            // had just been said.
            StatusReporter.warn("Enum " + className + " could not gain " + names + ": "
                    + outcome.declinedBecause() + ". values() and valueOf() keep the old set "
                    + "until a restart. Everything else in this class reloaded.");
            RestartLedger.note(className,
                    change.describe() + ", which could not be applied to this JVM");
            return false;
        }

        EnumCollectionHealer.armed();
        EnumConstantChange.accepted(loaded, newBytecode);

        int tables = 0;
        if (instrumentation != null) {
            tables = EnumSurgery.growSwitchTables(
                    instrumentation.getAllLoadedClasses(), loaded, countConstants(loaded));
        }

        // Jackson keeps per-mapper enum caches sized to the old constant set;
        // measured to turn the new constant into a 500 on serialise and a 400
        // on deserialise until they are flushed. Zero mappers means no Spring
        // or no Jackson, and the report stays silent about a repair that had
        // nothing to repair.
        int mappers = JacksonEnumCaches.flush(); GsonCaches.flush();

        EnumConstantChange.reportAppended(className, outcome.appended(), tables, mappers);
        if (change.reordered()) reportReordered(className);
        if (!inserted.isEmpty()) {
            int ordinal = countConstants(loaded) - inserted.size();
            List<String> positions = new ArrayList<>();
            for (var entry : inserted.entrySet())
                positions.add(entry.getKey() + " ordinal " + (ordinal++) + " now, " + entry.getValue() + " after a restart");
            StatusReporter.info("Enum " + className + ": the inserted " + (inserted.size() == 1 ? "constant is" : "constants are")
                    + " added after the existing ones so no ordinal moves (" + String.join("; ", positions) + "). Until a restart, "
                    + "values(), EnumSet iteration and compareTo put " + (inserted.size() == 1 ? "it" : "them") + " last.");
        }

        // A static SAP Commerce enumtype compiles to a real Java enum, so the
        // append above made the JVM side whole; the platform side is not. The
        // persistence layer stores such a value as a reference to its
        // EnumerationValue item, and that row exists only after a system
        // update. Said here because this is the one place that knows both
        // facts; detected by interface name so a non-Hybris JVM never pays
        // for the question.
        if (implementsHybrisEnumValue(loaded)) {
            StatusReporter.info("This is a SAP Commerce enumtype: the platform can persist "
                    + names + " only after the matching EnumerationValue item exists. "
                    + "Run HAC -> Platform -> Update Running System (or import it via ImpEx) "
                    + "before using the value on a model.");
        }
        return true;
    }

    private static void reportReordered(String className) {
        StatusReporter.success("Enum " + className + " reordered in source. Existing constants kept their running ordinals; "
                + "values(), compareTo and EnumSet/EnumMap iteration use the running order until restart.");
    }

    /** Accept only the simple native generated code=name constructor, never replay user code. */
    static Field nativeCodeField(Class<?> loaded, byte[] incoming) {
        try {
            Field code = loaded.getDeclaredField("code");
            if (code.getType() != String.class || code.getModifiers() != (Modifier.PRIVATE | Modifier.FINAL)) return nativeCodeRefused("loaded code field shape");
            Field[] physical = ReflectionBridge.physicalDeclaredFields(loaded);
            boolean ownLookup = java.util.Arrays.stream(physical).anyMatch(field ->
                    field.getName().equals(InjectedNames.LOOKUP_FIELD)
                    && field.getType() == java.lang.invoke.MethodHandles.Lookup.class
                    && field.getModifiers() == (Modifier.PRIVATE | Modifier.STATIC | Opcodes.ACC_SYNTHETIC));
            for (Field field : physical) {
                if (Modifier.isStatic(field.getModifiers()) || field.equals(code)) continue;
                // Only our exact per-object storage shape is outside the user's enum state.
                // Raw incoming bytecode below still must declare code alone.
                boolean ownStorage = ownLookup && field.getName().equals(InjectedNames.EXT_FIELD)
                        && field.getType() == Object[].class
                        && field.getModifiers() == (Modifier.PRIVATE | Modifier.TRANSIENT | Opcodes.ACC_SYNTHETIC);
                if (!ownStorage) return nativeCodeRefused("extra loaded instance field");
            }
            ClassNode node = new ClassNode();
            new ClassReader(incoming).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (!node.name.equals(loaded.getName().replace('.', '/')) || !"java/lang/Enum".equals(node.superName)) return nativeCodeRefused("incoming class identity");
            List<FieldNode> instance = node.fields.stream().filter(f -> (f.access & Opcodes.ACC_STATIC) == 0).toList();
            if (instance.size() != 1 || !instance.get(0).name.equals("code") || !instance.get(0).desc.equals("Ljava/lang/String;")
                    || instance.get(0).access != (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) return nativeCodeRefused("incoming instance fields");
            List<MethodNode> constructors = node.methods.stream().filter(m -> m.name.equals("<init>")).toList();
            if (constructors.size() != 1) return nativeCodeRefused("constructor count");
            MethodNode constructor = constructors.get(0);
            if (!constructor.desc.equals("(Ljava/lang/String;ILjava/lang/String;)V") || !constructor.tryCatchBlocks.isEmpty()) return nativeCodeRefused("constructor signature/handlers");
            List<AbstractInsnNode> body = instructions(constructor);
            if (body.size() != 9 || !variable(body.get(0), Opcodes.ALOAD, 0)
                    || !variable(body.get(1), Opcodes.ALOAD, 1) || !variable(body.get(2), Opcodes.ILOAD, 2)
                    || !call(body.get(3), Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V")
                    || !variable(body.get(4), Opcodes.ALOAD, 0) || !variable(body.get(5), Opcodes.ALOAD, 3)
                    || !call(body.get(6), Opcodes.INVOKEVIRTUAL, "java/lang/String", "intern", "()Ljava/lang/String;")
                    || !field(body.get(7), Opcodes.PUTFIELD, node.name, "code", "Ljava/lang/String;")
                    || body.get(8).getOpcode() != Opcodes.RETURN) return nativeCodeRefused("constructor code");
            MethodNode getter = node.methods.stream().filter(m -> m.name.equals("getCode") && m.desc.equals("()Ljava/lang/String;")).findFirst().orElse(null);
            if (getter == null || (getter.access & Opcodes.ACC_STATIC) != 0 || !getter.tryCatchBlocks.isEmpty()) return nativeCodeRefused("getCode signature/handlers");
            List<AbstractInsnNode> read = instructions(getter);
            if (read.size() != 3 || !variable(read.get(0), Opcodes.ALOAD, 0)
                    || !field(read.get(1), Opcodes.GETFIELD, node.name, "code", "Ljava/lang/String;")
                    || read.get(2).getOpcode() != Opcodes.ARETURN) return nativeCodeRefused("getCode code");
            MethodNode init = node.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElse(null);
            if (init == null || !init.tryCatchBlocks.isEmpty()) return nativeCodeRefused("class initializer handlers");
            List<AbstractInsnNode> initialisation = instructions(init);
            List<String> constants = node.fields.stream().filter(f -> (f.access & Opcodes.ACC_ENUM) != 0).map(f -> f.name).toList();
            int seen = 0;
            // Each constant must be the native NEW/DUP/name/ordinal/code/<init>/PUTSTATIC
            // prefix. Reject helper calls, branching, different code strings and custom state.
            for (String name : constants) {
                int i = seen * 7;
                if (initialisation.size() < i + 7 || !(initialisation.get(i) instanceof TypeInsnNode type)
                        || type.getOpcode() != Opcodes.NEW || !type.desc.equals(node.name)
                        || initialisation.get(i + 1).getOpcode() != Opcodes.DUP
                        || !literal(initialisation.get(i + 2), name) || integer(initialisation.get(i + 3)) != seen
                        || !literal(initialisation.get(i + 4), name)
                        || !call(initialisation.get(i + 5), Opcodes.INVOKESPECIAL, node.name, "<init>", constructor.desc)
                        || !field(initialisation.get(i + 6), Opcodes.PUTSTATIC, node.name, name, "L" + node.name + ";")) return nativeCodeRefused("constant code/name/ordinal initialization");
                seen++;
            }
            if (seen == 0) return nativeCodeRefused("empty constants");
            List<FieldNode> arrays = node.fields.stream().filter(f -> f.desc.equals("[L" + node.name + ";")
                    && (f.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)) == (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)).toList();
            if (arrays.size() != 1 || initialisation.size() != seen * 7 + 3
                    || !call(initialisation.get(seen * 7), Opcodes.INVOKESTATIC, node.name, "$values", "()[L" + node.name + ";")
                    || !field(initialisation.get(seen * 7 + 1), Opcodes.PUTSTATIC, node.name, arrays.get(0).name, arrays.get(0).desc)
                    || initialisation.get(seen * 7 + 2).getOpcode() != Opcodes.RETURN) return nativeCodeRefused("values array initialization");
            MethodNode values = node.methods.stream().filter(m -> m.name.equals("$values") && m.desc.equals("()[L" + node.name + ";")).findFirst().orElse(null);
            if (values == null || !values.tryCatchBlocks.isEmpty()) return nativeCodeRefused("values helper handlers");
            List<AbstractInsnNode> array = instructions(values);
            if (array.size() != 3 + constants.size() * 4 || integer(array.get(0)) != constants.size()
                    || !(array.get(1) instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.ANEWARRAY
                    || !allocation.desc.equals(node.name) || array.get(array.size() - 1).getOpcode() != Opcodes.ARETURN) return nativeCodeRefused("values helper allocation");
            for (int i = 0; i < constants.size(); i++) {
                int offset = 2 + i * 4;
                if (array.get(offset).getOpcode() != Opcodes.DUP || integer(array.get(offset + 1)) != i
                        || !field(array.get(offset + 2), Opcodes.GETSTATIC, node.name, constants.get(i), "L" + node.name + ";")
                        || array.get(offset + 3).getOpcode() != Opcodes.AASTORE) return nativeCodeRefused("values helper members");
            }
            for (Object old : loaded.getEnumConstants()) {
                if (!((Enum<?>) old).name().equals(loaded.getMethod("getCode").invoke(old))) return nativeCodeRefused("existing code/name mapping");
            }
            return code;
        } catch (Throwable unsupported) {
            return nativeCodeRefused("reflection or bytecode validation threw " + unsupported.getClass().getSimpleName());
        }
    }

    private static Field nativeCodeRefused(String reason) {
        StatusReporter.info("Native enum code validation declined: " + reason);
        return null;
    }

    private static List<AbstractInsnNode> instructions(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) result.add(instruction);
        return result;
    }
    private static boolean variable(AbstractInsnNode n, int opcode, int index) {
        return n instanceof VarInsnNode v && v.getOpcode() == opcode && v.var == index;
    }
    private static boolean call(AbstractInsnNode n, int opcode, String owner, String name, String descriptor) {
        return n instanceof MethodInsnNode m && m.getOpcode() == opcode && m.owner.equals(owner) && m.name.equals(name) && m.desc.equals(descriptor);
    }
    private static boolean field(AbstractInsnNode n, int opcode, String owner, String name, String descriptor) {
        return n instanceof FieldInsnNode f && f.getOpcode() == opcode && f.owner.equals(owner) && f.name.equals(name) && f.desc.equals(descriptor);
    }
    private static boolean literal(AbstractInsnNode n, String value) {
        return n instanceof LdcInsnNode l && value.equals(l.cst);
    }
    private static int integer(AbstractInsnNode n) {
        int opcode = n.getOpcode();
        if (opcode >= Opcodes.ICONST_0 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
        if (n instanceof IntInsnNode i && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) return i.operand;
        return n instanceof LdcInsnNode l && l.cst instanceof Integer i ? i : -1;
    }

    private static final List<String> PERSISTENT_TYPES = List.of(
            "jakarta.persistence.Entity", "jakarta.persistence.Embeddable", "jakarta.persistence.MappedSuperclass",
            "javax.persistence.Entity", "javax.persistence.Embeddable", "javax.persistence.MappedSuperclass");

    /**
     * Names a JPA consumer that could store this enum by ordinal, or null. Proving name-based storage needs the effective
     * persistence metadata (property access, map keys, converters, disabled conversion, XML overrides: audit F2-F5), so the
     * rule is conservative: any loaded entity, embeddable or mapped superclass that mentions the enum in a non-static field or
     * in a getter/setter signature counts, whatever its annotations say, and a loader of the enum or of such a type that ships
     * JPA XML mappings (META-INF/orm.xml, or mapping-file entries in META-INF/persistence.xml) makes the insert unprovable.
     * Reads annotations by name; no persistence class is loaded for this.
     */
    static String ordinalPersistence(Class<?> enumType, Instrumentation instrumentation) {
        Class<?>[] loaded = instrumentation == null ? new Class<?>[0] : instrumentation.getAllLoadedClasses();
        for (Class<?> type : loaded) {
            if (type.getName().equals(com.onurkat.reclazz.transform.HibernateFactoryTransformer.FACTORY)) {
                String storage = HibernateEnumStorage.refusal(enumType, loaded, JPA_MARKERS);
                return storage == null ? externalOrdinalConsumer(enumType, loaded) : storage;
            }
        }
        String xml = xmlMappings(enumType.getClassLoader());
        if (xml != null) return xml;
        if (instrumentation == null) return externalOrdinalConsumer(enumType, loaded);
        for (Class<?> type : loaded) {
            try {
                if (type.getClassLoader() == null || !annotated(type.getDeclaredAnnotations(), PERSISTENT_TYPES)) continue;
                for (Field field : type.getDeclaredFields())
                    if (!Modifier.isStatic(field.getModifiers()) && mentions(field.getGenericType(), enumType))
                        return type.getName() + "." + field.getName();
                for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                    if (Modifier.isStatic(method.getModifiers())) continue;
                    if (mentions(method.getGenericReturnType(), enumType)) return type.getName() + "." + method.getName() + "()";
                    for (var parameter : method.getGenericParameterTypes())
                        if (mentions(parameter, enumType)) return type.getName() + "." + method.getName() + "(...)";
                }
                xml = xmlMappings(type.getClassLoader());
                if (xml != null) return xml;
            } catch (LinkageError | RuntimeException unreadable) {
                // A persistence type whose members cannot be described cannot be proven safe.
                if (annotatedSafely(type)) return type.getName() + " (members unreadable)";
            }
        }
        // Raw collection mappings with targetClass/@MapKeyClass, XML-only entities in other loaders and namespaced or
        // multiple persistence descriptors cannot all be resolved without the provider's own metadata (audit round 2
        // F3a/F5a/F5b). So once JPA is in use anywhere in this JVM, an insert is not proven safe.
        String inUse = jpaInUse(instrumentation);
        if (inUse != null) return "JPA in this JVM (" + inUse + ")";
        return externalOrdinalConsumer(enumType, loaded);
    }
    private static String externalOrdinalConsumer(Class<?> target, Class<?>[] loaded) {
        String table = "$SWITCH_TABLE$" + target.getName().replace('.', '$');
        try {
            for (var annotation : target.getDeclaredAnnotations()) {
                if (!annotation.annotationType().getName().equals("com.fasterxml.jackson.annotation.JsonFormat")) continue;
                String shape = String.valueOf(annotation.annotationType().getMethod("shape").invoke(annotation));
                if (shape.startsWith("NUMBER") || shape.equals("ARRAY")) return "Jackson @JsonFormat(shape=" + shape + ")";
            }
            for (Object mapper : com.onurkat.reclazz.bootstrap.JacksonMapperRegistry.live()) {
                String reason = indexedMapper(mapper);
                if (reason != null) return reason;
            }
            for (Class<?> type : loaded) {
                if (type.getName().equals("org.apache.ibatis.type.EnumOrdinalTypeHandler"))
                    return "loaded MyBatis EnumOrdinalTypeHandler";
                if (type.getClassLoader() == null || type.isArray() || type.isHidden()) continue;
                try {
                    for (Field field : ReflectionBridge.physicalDeclaredFields(type)) {
                        if (field.getName().equals(table) && field.getType() == int[].class)
                            return "Eclipse switch table " + type.getName() + "." + table;
                    }
                } catch (LinkageError missingDependency) {
                    // Unresolved field types must not load optional application dependencies.
                    if (hasCompilerTable(type, target, table, false)) return "Eclipse switch table in " + type.getName();
                }
                if (type.getName().endsWith("$WhenMappings") && hasCompilerTable(type, target, table, true))
                    return "Kotlin WhenMappings for " + target.getName() + " in " + type.getName();
            }
            return null;
        } catch (ReflectiveOperationException | java.io.IOException | LinkageError | RuntimeException unknown) {
            return "cannot inspect compiled switch tables or enum serialization (" + unknown.getClass().getSimpleName() + ")";
        }
    }

    private static boolean hasCompilerTable(Class<?> holder, Class<?> target, String table, boolean kotlin)
            throws java.io.IOException {
        try (var bytes = holder.getResourceAsStream("/" + holder.getName().replace('.', '/') + ".class")) {
            if (bytes == null) return true;
            var node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (!kotlin) return node.fields.stream().anyMatch(f -> f.name.equals(table) && f.desc.equals("[I"));
            if (node.fields.stream().noneMatch(f -> f.name.startsWith("$EnumSwitchMapping$") && f.desc.equals("[I"))) return false;
            String owner = target.getName().replace('.', '/');
            for (var method : node.methods) for (var insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(owner)
                        || insn instanceof FieldInsnNode field && field.owner.equals(owner)) return true;
            }
            return false;
        }
    }

    private static String indexedMapper(Object mapper) throws ReflectiveOperationException {
        for (Class<?> type = mapper.getClass(); type != null; type = type.getSuperclass()) {
            boolean modern = type.getName().equals("tools.jackson.databind.ObjectMapper");
            if (!modern && !type.getName().equals("com.fasterxml.jackson.databind.ObjectMapper")) continue;
            ClassLoader loader = type.getClassLoader();
            Class<?> feature = Class.forName(modern ? "tools.jackson.databind.cfg.EnumFeature"
                    : "com.fasterxml.jackson.databind.SerializationFeature", false, loader);
            Class<?> parameter = modern ? Class.forName("tools.jackson.databind.cfg.DatatypeFeature", false, loader) : feature;
            var enabled = type.getMethod("isEnabled", parameter);
            for (String flag : List.of("WRITE_ENUMS_USING_INDEX", "WRITE_ENUM_KEYS_USING_INDEX")) {
                if (Boolean.TRUE.equals(enabled.invoke(mapper, feature.getField(flag).get(null)))) return "Jackson " + flag;
            }
            break;
        }
        return null;
    }

    private static final Set<String> JPA_MARKERS = Set.of(
            "jakarta.persistence.EntityManagerFactory", "javax.persistence.EntityManagerFactory",
            "jakarta.persistence.Entity", "javax.persistence.Entity",
            "jakarta.persistence.Embeddable", "javax.persistence.Embeddable",
            "jakarta.persistence.MappedSuperclass", "javax.persistence.MappedSuperclass",
            "jakarta.persistence.ElementCollection", "javax.persistence.ElementCollection");
    /** Names a loaded JPA API type that shows persistence is active, or null. Reads class names only. */
    static String jpaInUse(Instrumentation instrumentation) {
        for (Class<?> type : instrumentation.getAllLoadedClasses()) if (JPA_MARKERS.contains(type.getName())) return type.getName();
        return null;
    }
    private static boolean annotatedSafely(Class<?> type) {
        try { return annotated(type.getDeclaredAnnotations(), PERSISTENT_TYPES); } catch (LinkageError | RuntimeException e) { return false; }
    }
    /** JPA XML mapping files visible to this loader, which can map types or override annotations without any annotation. */
    static String xmlMappings(ClassLoader loader) {
        if (loader == null) return null;
        try {
            if (loader.getResource("META-INF/orm.xml") != null) return "JPA XML mappings (META-INF/orm.xml)";
            var unit = loader.getResourceAsStream("META-INF/persistence.xml");
            if (unit != null) try (var in = unit) {
                if (new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("<mapping-file"))
                    return "JPA XML mappings (persistence.xml mapping-file)";
            }
        } catch (java.io.IOException | RuntimeException unreadable) {
            return "JPA XML mappings (unreadable persistence.xml)";
        }
        return null;
    }
    private static boolean annotated(java.lang.annotation.Annotation[] annotations, List<String> names) {
        for (var annotation : annotations) if (names.contains(annotation.annotationType().getName())) return true;
        return false;
    }
    private static boolean mentions(java.lang.reflect.Type type, Class<?> enumType) {
        if (type == enumType) return true;
        if (type instanceof Class<?> c) return c.isArray() && mentions(c.getComponentType(), enumType);
        if (type instanceof java.lang.reflect.ParameterizedType p) {
            for (var argument : p.getActualTypeArguments()) if (mentions(argument, enumType)) return true;
            return false;
        }
        if (type instanceof java.lang.reflect.GenericArrayType g) return mentions(g.getGenericComponentType(), enumType);
        if (type instanceof java.lang.reflect.WildcardType w) {
            for (var bound : w.getUpperBounds()) if (mentions(bound, enumType)) return true;
        }
        return false;
    }

    /** Walks the interface graph by name: no Hybris classes are ever loaded for this. */
    private static boolean implementsHybrisEnumValue(Class<?> type) {
        try {
            for (Class<?> iface : type.getInterfaces()) {
                if ("de.hybris.platform.core.HybrisEnumValue".equals(iface.getName())
                        || implementsHybrisEnumValue(iface)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // A class this JVM will not describe is not a Hybris enum.
        }
        return false;
    }

    private static int countConstants(Class<?> enumClass) {
        Object[] constants = enumClass.getEnumConstants();
        return constants == null ? 0 : constants.length;
    }
}
