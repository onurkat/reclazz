/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.EnumSurgery;
import com.onurkat.reclazz.transform.EnumCollectionTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adding an enum constant to a running JVM, which turned out to be possible.
 *
 * <p>It was measured before it was built, on JDK 21 and JDK 25, on a stock JVM
 * and on JetBrains Runtime. Building the instance, growing the enum's private
 * array and clearing the two caches on {@code Class} makes {@code values()} and
 * {@code valueOf} correct; growing the {@code $SwitchMap} tables turns the
 * ArrayIndexOutOfBoundsException a switch would throw into its default branch,
 * which is what code compiled before the constant existed should do.
 *
 * <p>Two things bound it, and both are held here rather than in prose.
 *
 * <p>It is an append or it is nothing. Inserting or removing a constant
 * renumbers the ones after it, and every structure indexed by ordinal is then
 * wrong, including any {@code @Enumerated} column already written to a
 * database. That is refused.
 *
 * <p>It reaches into shapes the JDK never promised: {@code $VALUES},
 * {@code Class.enumConstants}. So every one is located and identified before
 * anything is written, and a JDK that moves one makes the whole thing decline
 * rather than corrupt an enum and report success.
 */
class EnumAppendTest {

    // ── The surgery itself ────────────────────────────────────────────────

    @Test
    void anAppendedConstantBecomesRealToValuesAndValueOf() {
        var outcome = EnumSurgery.append(Appendable1.class, List.of("SHIPPED"));

        assertTrue(outcome.applied(), "declined: " + outcome.declinedBecause());
        assertEquals(List.of("SHIPPED"), outcome.appended());

        List<String> names = new ArrayList<>();
        for (Object c : Appendable1.class.getEnumConstants()) names.add(((Enum<?>) c).name());
        assertEquals(List.of("NEW", "PAID", "SHIPPED"), names,
                "values() has to see it, or nothing downstream will");

        Enum<?> shipped = Enum.valueOf(Appendable1.class.asSubclass(Enum.class), "SHIPPED");
        assertEquals("SHIPPED", shipped.name());
        assertEquals(2, shipped.ordinal(), "it takes the next ordinal and moves nothing");
    }

    /**
     * The new constant has to behave like one, not like an object that happens
     * to have the right name: the JDK's own collections index it by ordinal and
     * check its class.
     */
    @Test
    void theAppendedConstantWorksInTheCollectionsBuiltAfterIt() {
        EnumSurgery.append(Appendable2.class, List.of("SHIPPED"));
        @SuppressWarnings({"unchecked", "rawtypes"})
        Enum<?> shipped = Enum.valueOf((Class) Appendable2.class, "SHIPPED");

        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<Enum, String> map = new EnumMap(Appendable2.class);
        map.put(shipped, "gonderildi");
        assertEquals("gonderildi", map.get(shipped));

        @SuppressWarnings({"unchecked", "rawtypes"})
        Set<Enum> all = EnumSet.allOf((Class) Appendable2.class);
        assertTrue(all.contains(shipped), "EnumSet.allOf has to include it");
        assertEquals(3, all.size());
    }

    /**
     * javac compiles an enum switch two ways, and only one of them needs help.
     *
     * <p>When the enum is in the same compilation unit it emits
     * {@code ordinal()} into a {@code lookupswitch}, and an unknown ordinal
     * falls to the default label on its own. When the enum comes from another
     * file, which is the normal case, it emits a lookup through a synthetic
     * {@code int[]} sized when that class was initialised, and a new ordinal
     * indexes past the end.
     */
    @Test
    void aSwitchOverAnEnumInTheSameFileNeedsNoHelp() {
        assertEquals("odendi", SwitchUser.label(Appendable3.PAID));
        EnumSurgery.append(Appendable3.class, List.of("SHIPPED"));
        @SuppressWarnings({"unchecked", "rawtypes"})
        Enum<?> shipped = Enum.valueOf((Class) Appendable3.class, "SHIPPED");

        assertEquals("bilinmeyen", SwitchUser.label((Appendable3) shipped),
                "a lookupswitch on ordinal() takes its default for a value it has "
                + "never heard of, which is already the right answer");
    }

    /**
     * The table form, which is what a switch over an enum from another file
     * compiles to. Built here rather than borrowed from javac so the test does
     * not depend on which shape the compiler happened to choose.
     */
    @Test
    void switchTablesSizedForTheOldCountAreGrown() throws Exception {
        Class<?> holder = defineSwitchHolder(Appendable4.class, 2);
        int[] before = tableOf(holder, Appendable4.class);
        assertEquals(2, before.length, "the table starts sized for the old constant count");
        before[0] = 1;
        before[1] = 2;

        EnumSurgery.append(Appendable4.class, List.of("SHIPPED"));
        int grown = EnumSurgery.growSwitchTables(new Class<?>[]{holder}, Appendable4.class, 3);

        assertEquals(1, grown, "the table has to be found by its mangled name and grown");
        int[] after = tableOf(holder, Appendable4.class);
        assertEquals(3, after.length);
        assertArrayEquals(new int[]{1, 2, 0}, after,
                "the cases it knew keep their labels, and the new slot is zero, "
                + "which is what javac uses for 'not one of my cases'");
    }

    @Test
    void aTableThatIsAlreadyBigEnoughIsLeftAlone() throws Exception {
        Class<?> holder = defineSwitchHolder(Appendable5.class, 8);

        assertEquals(0, EnumSurgery.growSwitchTables(new Class<?>[]{holder}, Appendable5.class, 3),
                "growing a table that is already long enough would be a write for nothing");
    }

    // ── What it refuses ───────────────────────────────────────────────────

    @Test
    void insertingInTheMiddleIsNotAnAppend() throws IOException {
        assertFalse(EnumConstantChange.isAppendOnly(Ordered.class, bytecodeOf(OrderedInserted.class)),
                "PAID moves from 1 to 2, and every ordinal already written down is wrong");
    }

    @Test
    void removingAConstantIsNotAnAppend() throws IOException {
        assertFalse(EnumConstantChange.isAppendOnly(Ordered.class, bytecodeOf(OrderedShorter.class)));
    }

    @Test
    void appendingIsRecognisedAndTheNamesComeBackInOrder() throws IOException {
        assertTrue(EnumConstantChange.isAppendOnly(Ordered.class, bytecodeOf(OrderedAppended.class)));
        assertEquals(List.of("SHIPPED", "REFUNDED"),
                EnumConstantChange.appendedNames(Ordered.class, bytecodeOf(OrderedAppended.class)));
    }

    /**
     * On a stock JVM the loaded class never gains a field for an appended
     * constant, because the JVM cannot add one. Reading declared fields would
     * therefore see it as missing on the next reload and append it again, and
     * again. {@code values()} is what already includes it.
     */
    @Test
    void anAlreadyAppendedConstantIsNotAppendedTwice() throws IOException {
        EnumSurgery.append(Repeated.class, List.of("SHIPPED"));

        assertFalse(EnumConstantChange.isAppendOnly(Repeated.class, bytecodeOf(RepeatedAfter.class)),
                "the constant is already live, so this save adds nothing");
        assertTrue(EnumConstantChange.appendedNames(Repeated.class, bytecodeOf(RepeatedAfter.class)).isEmpty());
        assertEquals(3, Repeated.class.getEnumConstants().length,
                "and values() must not have grown a second time");
    }

    /**
     * The same names in a different order is not nothing: every ordinal after
     * the move changes, so the running enum and the source disagree about which
     * constant is which. Comparing the two as sets called that no change and
     * said nothing at all.
     */
    @Test
    void reorderingIsACHangeEvenThoughNoNameMoved() throws IOException {
        var change = EnumConstantChange.check(Reordered.class, bytecodeOf(ReorderedAfter.class));

        assertNotNull(change, "a set comparison sees nothing here, and that was the bug");
        assertTrue(change.added().isEmpty());
        assertTrue(change.removed().isEmpty());
        assertTrue(change.reordered());
        assertTrue(change.describe().contains("reordered"), change.describe());
        assertFalse(EnumConstantChange.isAppendOnly(Reordered.class, bytecodeOf(ReorderedAfter.class)));
    }

    @Test
    void anEmptyRequestIsDeclined() {
        assertFalse(EnumSurgery.append(Appendable1.class, List.of()).applied());
        assertFalse(EnumSurgery.append(NotAnEnum.class, List.of("X")).applied());
    }

    // ── The collection repair ─────────────────────────────────────────────

    /**
     * A map or set built before the append holds an array one slot short. The
     * instances cannot be found on the heap, so the repair is injected into the
     * methods that would otherwise index past the end.
     */
    @Test
    void everyInstanceMethodOfEnumMapGainsTheRepairCall() throws IOException {
        byte[] injected = EnumCollectionTransformer.inject(bytecodeOf(java.util.EnumMap.class));

        Map<String, Boolean> healed = firstCallPerMethod(injected);
        assertFalse(healed.isEmpty(), "EnumMap has instance methods to inject into");

        List<String> missed = new ArrayList<>();
        healed.forEach((method, hasCall) -> {
            if (!hasCall) missed.add(method);
        });
        assertTrue(missed.isEmpty(), "these would still throw on the new constant: " + missed);
    }

    /** A constructor is where the arrays are being built, so healing there would race it. */
    @Test
    void constructorsAndStaticsAreLeftAlone() throws IOException {
        byte[] injected = EnumCollectionTransformer.inject(bytecodeOf(java.util.EnumMap.class));

        assertFalse(firstCallPerMethod(injected).containsKey("<init>"),
                "a constructor must not be given the repair call");
    }

    /** Nothing about a method's own logic may move; only a call is prepended. */
    @Test
    void theInjectionOnlyPrependsAndChangesNothingElse() throws IOException {
        byte[] original = bytecodeOf(java.util.EnumMap.class);
        byte[] injected = EnumCollectionTransformer.inject(original);

        assertEquals(instructionCounts(original).keySet(), instructionCounts(injected).keySet(),
                "the same methods, no more and no fewer");
        instructionCounts(original).forEach((method, count) -> {
            int after = instructionCounts(injected).get(method);
            assertTrue(after == count || after == count + 2,
                    method + " gained " + (after - count) + " instructions; the repair is two");
        });
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    enum Appendable1 { NEW, PAID }
    enum Appendable2 { NEW, PAID }
    enum Appendable3 { NEW, PAID }
    enum Appendable4 { NEW, PAID }
    enum Appendable5 { NEW, PAID }
    enum Repeated { NEW, PAID }
    enum RepeatedAfter { NEW, PAID, SHIPPED }

    enum Ordered { NEW, PAID }
    enum Reordered { NEW, PAID, SHIPPED }
    enum ReorderedAfter { NEW, SHIPPED, PAID }
    enum OrderedInserted { NEW, SHIPPED, PAID }
    enum OrderedShorter { NEW }
    enum OrderedAppended { NEW, PAID, SHIPPED, REFUNDED }

    static class NotAnEnum { }

    /** javac puts the switch table in a synthetic class beside this one. */
    static class SwitchUser {
        static String label(Appendable3 s) {
            switch (s) {
                case NEW: return "yeni";
                case PAID: return "odendi";
                default: return "bilinmeyen";
            }
        }
    }

    private static int[] tableOf(Class<?> holder, Class<?> enumClass) throws Exception {
        java.lang.reflect.Field f = holder.getDeclaredField(switchFieldName(enumClass));
        f.setAccessible(true);
        return (int[]) f.get(null);
    }

    private static String switchFieldName(Class<?> enumClass) {
        return "$SwitchMap$" + enumClass.getName().replace('.', '$');
    }

    /** A stand-in for the synthetic class javac generates to hold a switch table. */
    private static Class<?> defineSwitchHolder(Class<?> enumClass, int size) throws Exception {
        String name = "SwitchHolder$" + enumClass.getSimpleName();
        org.objectweb.asm.ClassWriter cw =
                new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null,
                "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                switchFieldName(enumClass), "[I", null, null).visitEnd();

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitIntInsn(Opcodes.BIPUSH, size);
        clinit.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, name, switchFieldName(enumClass), "[I");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();

        byte[] bytes = cw.toByteArray();
        ClassLoader loader = new ClassLoader(EnumAppendTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
            }
        };
        Class<?> defined = (Class<?>) loader.getClass()
                .getDeclaredMethod("define").invoke(loader);
        defined.getDeclaredField(switchFieldName(enumClass));   // forces initialisation
        java.lang.reflect.Field f = defined.getDeclaredField(switchFieldName(enumClass));
        f.setAccessible(true);
        f.get(null);
        return defined;
    }

    /** Method name to whether its first call is the repair helper. */
    private static Map<String, Boolean> firstCallPerMethod(byte[] bytecode) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                boolean skipped = (access & Opcodes.ACC_STATIC) != 0
                        || (access & Opcodes.ACC_ABSTRACT) != 0
                        || (access & Opcodes.ACC_NATIVE) != 0
                        || "<init>".equals(name) || "<clinit>".equals(name);
                if (skipped) return null;
                result.put(name + descriptor, false);
                return new MethodVisitor(Opcodes.ASM9) {
                    private boolean first = true;

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String call,
                                                 String desc, boolean isInterface) {
                        if (first) {
                            first = false;
                            result.put(name + descriptor,
                                    owner.endsWith("EnumCollectionHealer") && "heal".equals(call));
                        }
                    }
                };
            }
        }, 0);
        return result;
    }

    private static Map<String, Integer> instructionCounts(byte[] bytecode) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                String key = name + descriptor;
                counts.put(key, 0);
                return new MethodVisitor(Opcodes.ASM9) {
                    private void bump() {
                        counts.merge(key, 1, Integer::sum);
                    }

                    @Override public void visitInsn(int o) { bump(); }
                    @Override public void visitVarInsn(int o, int v) { bump(); }
                    @Override public void visitFieldInsn(int o, String a, String b, String c) { bump(); }
                    @Override public void visitMethodInsn(int o, String a, String b, String c, boolean d) { bump(); }
                    @Override public void visitTypeInsn(int o, String t) { bump(); }
                    @Override public void visitJumpInsn(int o, org.objectweb.asm.Label l) { bump(); }
                    @Override public void visitIntInsn(int o, int v) { bump(); }
                    @Override public void visitLdcInsn(Object v) { bump(); }
                };
            }
        }, 0);
        return counts;
    }

    private static byte[] bytecodeOf(Class<?> c) throws IOException {
        String resource = c.getName().replace('.', '/') + ".class";
        InputStream in = c.getClassLoader() == null
                ? ClassLoader.getSystemResourceAsStream(resource)
                : c.getClassLoader().getResourceAsStream(resource);
        if (in == null) in = Object.class.getModule().getResourceAsStream(resource);
        assertNotNull(in, "cannot read " + c.getName());
        try (InputStream stream = in) {
            return stream.readAllBytes();
        }
    }
}
