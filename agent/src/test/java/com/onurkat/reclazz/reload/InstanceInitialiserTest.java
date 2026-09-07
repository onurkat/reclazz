/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.FieldStore;
import com.onurkat.reclazz.bootstrap.InjectedNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An instance field a reload adds, on an object that already existed.
 *
 * <p>The object ran a constructor that did not have the field, so the field's
 * initialiser never ran for it and the field reads null. What this pins: the
 * run of constructor instructions that belongs to one field is found and
 * lifted; on first read the field store runs it for the object and keeps the
 * value; a value the application wrote first, null included, is never
 * overwritten; and an initialiser that needs a constructor parameter, which
 * the live object no longer has, is refused with that reason.
 */
class InstanceInitialiserTest {

    // ── What javac hands us ───────────────────────────────────────────────

    /**
     * A plain initialiser, a computed one, and one that reads another field
     * of the object are all one run of instructions from {@code aload_0} to
     * the field's PUTFIELD. The live object has everything they need.
     */
    @Test
    void selfContainedInitialisersAreLiftedOutOfTheConstructor() throws IOException {
        var plan = planFor(Fixture.class,
                "cache:Ljava/util/List;", "retries:I", "label:Ljava/lang/String;");

        assertEquals(Set.of("cache:Ljava/util/List;", "retries:I", "label:Ljava/lang/String;"),
                plan.initialisers.keySet());
        assertTrue(plan.refused.isEmpty(), "nothing to refuse here: " + plan.refused);
    }

    /**
     * The object no longer has its constructor's arguments, so a field set
     * from one cannot be given a value later; the reason has to say that.
     */
    @Test
    void anInitialiserReadingAConstructorParameterIsRefused() throws IOException {
        var plan = planFor(Fixture.class, "name:Ljava/lang/String;");

        assertTrue(plan.initialisers.isEmpty());
        assertTrue(plan.refused.get("name:Ljava/lang/String;").contains("constructor parameter"),
                "the reason has to name the actual problem: " + plan.refused);
    }

    @Test
    void aBranchingInitialiserIsRefused() throws IOException {
        var plan = planFor(Fixture.class, "mode:Ljava/lang/String;");

        assertTrue(plan.initialisers.isEmpty());
        assertTrue(plan.refused.get("mode:Ljava/lang/String;").contains("branch"),
                "the reason has to be about control flow: " + plan.refused);
    }

    /** A static field is the other slicer's business, whatever the caller asks. */
    @Test
    void staticFieldsAreNotThisSlicersBusiness() throws IOException {
        var plan = planFor(Fixture.class, "COUNT:I");

        assertTrue(plan.initialisers.isEmpty());
        assertTrue(plan.refused.isEmpty());
    }

    /** A field assigned in no constructor has no initialiser, and null is its value. */
    @Test
    void aFieldWithNoInitialiserIsNeitherLiftedNorRefused() throws IOException {
        var plan = planFor(Fixture.class, "spare:J");

        assertTrue(plan.initialisers.isEmpty());
        assertTrue(plan.refused.isEmpty());
    }

    // ── Running it for real ───────────────────────────────────────────────

    /**
     * The plan is only worth the value that lands. So the lifted code is
     * compiled into a method the way the companion carries it, registered
     * with the field store, and read through the same call the companion
     * makes for a GETFIELD on an added field.
     */
    @Test
    void theFirstReadOnAnExistingObjectRunsTheInitialiserOnce() throws Throwable {
        registerInitialisers(Fixture.class, "cache:Ljava/util/List;", "retries:I",
                "label:Ljava/lang/String;");
        Fixture existing = new Fixture("built before the reload");

        Object cache = FieldStore.getExtField(existing, owner(), "cache", "Ljava/util/List;");
        assertInstanceOf(ArrayList.class, cache, "the field must hold what its initialiser builds");
        assertSame(cache, FieldStore.getExtField(existing, owner(), "cache", "Ljava/util/List;"),
                "the second read must see the object the first one produced, not a fresh one");

        assertEquals(3, FieldStore.getExtField(existing, owner(), "retries", "I"),
                "a primitive with an initialiser reads the initialiser's value, not zero");
        assertEquals("built before the reload!",
                FieldStore.getExtField(existing, owner(), "label", "Ljava/lang/String;"),
                "an initialiser may read the object's other fields, which the live object has");
    }

    /** Each object gets its own value, computed from its own state. */
    @Test
    void everyObjectGetsItsOwnValue() throws Throwable {
        registerInitialisers(Fixture.class, "cache:Ljava/util/List;", "label:Ljava/lang/String;");
        Fixture one = new Fixture("one");
        Fixture two = new Fixture("two");

        assertNotSame(FieldStore.getExtField(one, owner(), "cache", "Ljava/util/List;"),
                FieldStore.getExtField(two, owner(), "cache", "Ljava/util/List;"));
        assertEquals("two!", FieldStore.getExtField(two, owner(), "label", "Ljava/lang/String;"));
    }

    /**
     * A write before the first read is the application's value, and the
     * initialiser must not overwrite it. Null written on purpose is a value
     * too: the slot used to be unable to say so.
     */
    @Test
    void aValueTheApplicationWroteFirstIsKeptNullIncluded() throws Throwable {
        registerInitialisers(Fixture.class, "cache:Ljava/util/List;", "retries:I");
        Fixture existing = new Fixture("x");

        FieldStore.putExtField(existing, 9, owner(), "retries", "I");
        assertEquals(9, FieldStore.getExtField(existing, owner(), "retries", "I"));

        FieldStore.putExtField(existing, null, owner(), "cache", "Ljava/util/List;");
        assertNull(FieldStore.getExtField(existing, owner(), "cache", "Ljava/util/List;"),
                "the developer set it to null on purpose and that is its value");
    }

    /**
     * An initialiser that throws is what the constructor would have thrown at
     * construction. The field reads as the default it read before, and the
     * initialiser is not tried again on every read.
     */
    @Test
    void anInitialiserThatThrowsIsRetiredAndTheFieldReadsTheDefault() throws Throwable {
        MethodHandle throwing = MethodHandles.dropArguments(
                MethodHandles.throwException(Object.class, IllegalStateException.class)
                        .bindTo(new IllegalStateException("no")),
                0, Object.class);
        FieldStore.setInstanceInitialisers(Fixture.class, Map.of("retries:I", throwing));
        Fixture existing = new Fixture("x");

        assertEquals(0, FieldStore.getExtField(existing, owner(), "retries", "I"));
        assertFalse(FieldStore.hasInstanceInitialiser(Fixture.class, "retries", "I"),
                "a throwing initialiser is taken out rather than run on every read");
    }

    /**
     * The next reload replaces the whole set: a field whose initialiser the
     * developer removed stops being initialised.
     */
    @Test
    void theNextReloadReplacesTheSet() throws Throwable {
        registerInitialisers(Fixture.class, "cache:Ljava/util/List;", "retries:I");
        assertTrue(FieldStore.hasInstanceInitialiser(Fixture.class, "retries", "I"));

        registerInitialisers(Fixture.class, "cache:Ljava/util/List;");

        assertFalse(FieldStore.hasInstanceInitialiser(Fixture.class, "retries", "I"));
        assertTrue(FieldStore.hasInstanceInitialiser(Fixture.class, "cache", "Ljava/util/List;"));
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    private static String owner() {
        return Fixture.class.getName().replace('.', '/');
    }

    private static InstanceInitialiserSlicer.Plan planFor(Class<?> type, String... keys)
            throws IOException {
        return InstanceInitialiserSlicer.planFor(bytecodeOf(type), Set.of(keys));
    }

    private static byte[] bytecodeOf(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "no bytecode for " + type);
            return in.readAllBytes();
        }
    }

    /**
     * The companion's shape without the companion: a hidden nestmate carrying
     * one static method per field, {@code (Fixture) -> Object}, whose body is
     * the lifted code followed by boxing and a return. Registered the way the
     * reload registers it.
     */
    private static void registerInitialisers(Class<?> type, String... keys) throws Throwable {
        byte[] bytecode = bytecodeOf(type);
        InstanceInitialiserSlicer.Plan plan = InstanceInitialiserSlicer.planFor(bytecode, Set.of(keys));
        assertEquals(keys.length, plan.initialisers.size(), "every asked field lifted: " + plan.refused);

        String ownerName = owner();
        ClassNode cls = new ClassNode();
        cls.version = Opcodes.V17;
        cls.access = Opcodes.ACC_PUBLIC;
        cls.name = ownerName + "$$Test";
        cls.superName = "java/lang/Object";
        for (var entry : plan.initialisers.entrySet()) {
            String key = entry.getKey();
            String fieldName = key.substring(0, key.indexOf(':'));
            org.objectweb.asm.Type fieldType =
                    org.objectweb.asm.Type.getType(key.substring(key.indexOf(':') + 1));
            MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    InstanceInitialiserSlicer.methodName(fieldName),
                    "(L" + ownerName + ";)Ljava/lang/Object;", null, null);
            InsnList body = method.instructions;
            body.add(entry.getValue());
            if (fieldType.getSort() != org.objectweb.asm.Type.OBJECT
                    && fieldType.getSort() != org.objectweb.asm.Type.ARRAY) {
                String boxed = switch (fieldType.getSort()) {
                    case org.objectweb.asm.Type.INT -> "java/lang/Integer";
                    case org.objectweb.asm.Type.LONG -> "java/lang/Long";
                    case org.objectweb.asm.Type.BOOLEAN -> "java/lang/Boolean";
                    default -> throw new IllegalArgumentException(key);
                };
                body.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC, boxed,
                        "valueOf", "(" + fieldType.getDescriptor() + ")L" + boxed + ";", false));
            }
            body.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));
            cls.methods.add(method);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cls.accept(writer);

        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                .defineHiddenClass(writer.toByteArray(), true,
                        MethodHandles.Lookup.ClassOption.NESTMATE);
        java.util.Map<String, MethodHandle> handles = new java.util.LinkedHashMap<>();
        for (String key : plan.initialisers.keySet()) {
            String fieldName = key.substring(0, key.indexOf(':'));
            MethodHandle producer = lookup.findStatic(lookup.lookupClass(),
                    InstanceInitialiserSlicer.methodName(fieldName),
                    MethodType.methodType(Object.class, type));
            handles.put(key, producer.asType(MethodType.methodType(Object.class, Object.class)));
        }
        FieldStore.setInstanceInitialisers(type, handles);
    }

    /**
     * A class as the agent leaves it: with the {@code __reclazz$ext} array the
     * companion's field reads go through. The fields under test are real here
     * so that javac writes their initialisers; the store does not care, it
     * only ever sees the array.
     */
    @SuppressWarnings("unused")
    static class Fixture {
        static int COUNT = 1;
        private Object[] __reclazz$ext;
        private String name;
        private final List<String> cache = new ArrayList<>();
        private int retries = 3;
        private final String label = name + "!";
        private final String mode = System.getProperty("reclazz.absent") != null ? "on" : "off";
        private long spare;

        Fixture(String name) {
            this.name = name;
        }
    }

    static {
        // The store looks the ext field up by its injected name; make sure
        // the fixture's spelling is the one the agent uses.
        if (!"__reclazz$ext".equals(InjectedNames.EXT_FIELD)) {
            throw new AssertionError("fixture field name drifted from " + InjectedNames.EXT_FIELD);
        }
    }
}
