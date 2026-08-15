package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.FieldStore;
import com.onurkat.reclazz.transform.AddedMemberStripper;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adding a field to a class and reloading it used to end in one of two wrong
 * places, both of them quiet.
 *
 * An instance field was reported as reloaded, and an object created *after*
 * the reload still came back with the field null. The loaded class kept its
 * original constructor, because a constructor cannot be moved to a companion,
 * so the code that was supposed to assign the field was the code compiled
 * before the field existed. Even where the new constructor did reach the
 * class, its write went to a call site that had resolved to
 * {@code MethodHandles.empty}: it accepted the value and dropped it.
 *
 * A static field was worse. The companion fell through to a plain GETSTATIC
 * against a class whose schema does not have the field, on the reasoning that
 * adding a static field was unusual, and the resulting NoSuchFieldError killed
 * the thread after the reload had already reported success.
 *
 * What is fixed and what is not, deliberately: instance fields are initialised
 * on new objects; static fields no longer throw but read as the type default,
 * because their initialiser lives in {@code <clinit>} and re-running that
 * would reset every other static the class holds. The second case is now said
 * out loud rather than looking like a null bug.
 */
class AddedFieldInitialisationTest {

    // ── The redefine payload ──────────────────────────────────────────────

    /**
     * A class that gained members cannot be redefined; the JVM rejects any
     * schema change. Stripping the added members is what makes the redefine
     * legal, and the redefine is what carries the new constructor bodies.
     */
    @Test
    void addedFieldsAreStrippedFromTheRedefinePayload() throws IOException {
        byte[] stripped = AddedMemberStripper.strip(
                bytecodeOf(Sample.class),
                Set.of("currency:Ljava/lang/String;"),
                Set.of());

        assertEquals(List.of("items"), fieldNames(stripped),
                "the added field must go, the original must stay");
    }

    @Test
    void addedMethodsAreStrippedToo() throws IOException {
        byte[] stripped = AddedMemberStripper.strip(
                bytecodeOf(Sample.class),
                Set.of(),
                Set.of("addedLater:()Ljava/lang/String;"));

        assertFalse(methodNames(stripped).contains("addedLater"),
                "a method the reload added is dispatched from the companion, "
                + "so it must not also be in the redefine payload");
        assertTrue(methodNames(stripped).contains("summary"),
                "methods that were always there must survive");
    }

    /**
     * Constructors are the entire reason the payload exists. Dropping one,
     * even if a diff somehow named it, would take the new field initialisation
     * with it.
     */
    @Test
    void constructorsAreNeverStripped() throws IOException {
        byte[] stripped = AddedMemberStripper.strip(
                bytecodeOf(Sample.class),
                Set.of(),
                Set.of("<init>:()V", "<clinit>:()V"));

        assertTrue(methodNames(stripped).contains("<init>"),
                "the constructor carries the new field's initialiser to the loaded class");
    }

    @Test
    void nothingToStripReturnsTheInputUntouched() throws IOException {
        byte[] original = bytecodeOf(Sample.class);
        assertSame(original, AddedMemberStripper.strip(original, Set.of(), Set.of()),
            "a body-only reload should not pay for a rewrite it does not need");
    }

    // ── Where an unresolvable field access goes ───────────────────────────

    /**
     * The fallbacks are the difference between a write that lands and a write
     * that disappears. Asserted on the compiled bootstrap because the failure
     * mode is a silent no-op, which no behavioural test would notice.
     */
    @Test
    void unresolvableFieldAccessFallsBackToTheCompanionStore() throws IOException {
        List<String> calls = callsIn("com/onurkat/reclazz/bootstrap/ReclazzBootstrap");

        assertTrue(calls.stream().anyMatch(c -> c.endsWith("ReclazzBootstrap.companionSetter")),
                "a write to a field that is not in the loaded class must reach the "
                + "companion store, not MethodHandles.empty. Calls: " + calls);
        assertTrue(calls.stream().anyMatch(c -> c.endsWith("ReclazzBootstrap.companionGetter")),
                "and the matching read must come from the same place");
    }

    @Test
    void theCompanionStoreIsWhatTheHelpersTalkTo() throws IOException {
        // FieldStore is named as a class constant handed to findStatic, not
        // called directly, so the reference lives in the constant pool.
        List<String> names = memberNamesIn("com/onurkat/reclazz/bootstrap/ReclazzBootstrap");
        assertTrue(names.stream().anyMatch(n -> n.contains("getExtField"))
                        && names.stream().anyMatch(n -> n.contains("putExtField")),
                "the fallbacks must resolve the companion store's accessors. Found: " + names);
    }

    // ── Static fields ─────────────────────────────────────────────────────

    @Test
    void theStaticStoreRoundTrips() {
        FieldStore.putStaticExtField("demo.Config", "zones", "Ljava/util/List;", List.of("UTC"));
        assertEquals(List.of("UTC"),
                FieldStore.getStaticExtField("demo.Config", "zones", "Ljava/util/List;"));
    }

    /**
     * Before anything writes, a read has to give what a real static field of
     * that type would hold. Returning null for an int would NPE at the unbox
     * the caller does immediately.
     */
    @Test
    void anUnwrittenStaticReadsAsTheTypeDefault() {
        assertEquals(0, FieldStore.getStaticExtField("demo.Nothing", "count", "I"));
        assertEquals(false, FieldStore.getStaticExtField("demo.Nothing", "flag", "Z"));
        assertNull(FieldStore.getStaticExtField("demo.Nothing", "name", "Ljava/lang/String;"));
    }

    @Test
    void theCompanionRoutesAddedStaticsToTheStore() throws IOException {
        // The generator emits the call; in its own bytecode the target name is
        // a string constant it passes to visitMethodInsn.
        List<String> names = memberNamesIn(
                "com/onurkat/reclazz/reload/CompanionGenerator$CompanionMethodAdapter");

        assertTrue(names.contains("getStaticExtField"),
                "an added static read used to fall through to a GETSTATIC against a "
                + "class that does not have the field, which threw NoSuchFieldError. "
                + "Found: " + names);
        assertTrue(names.stream().anyMatch(n -> n.contains("putStaticExtField")),
                "and the write side has to match. Found: " + names);
    }

    // ── Enums get their own answer ────────────────────────────────────────

    /**
     * An enum constant is a static field of the enum's own type, so without a
     * separate branch it would be reported as "reads as null until restart",
     * which is true and useless: the answer there is not to wait but that it
     * cannot work. Conjuring the constant up would not help either, since
     * every switch table, EnumSet and EnumMap in the application is sized for
     * the old count and indexed by ordinal.
     *
     * The reload itself still applies. Before, the whole class failed, so a
     * method body edited in the same save was lost along with it.
     */
    @Test
    void addingAnEnumValueIsReportedAsItsOwnCase() throws IOException {
        List<String> text = memberNamesIn("com/onurkat/reclazz/reload/StructuralReloader");

        assertTrue(text.stream().anyMatch(t -> t.contains("Enum constants cannot be added")),
                "adding an enum value needs a restart, and saying so is the whole "
                + "point of the branch. Found: "
                + text.stream().filter(t -> t.length() > 20).limit(8).collect(Collectors.toList()));
    }

    @Test
    void aStaticFieldThatIsNotAnEnumValueKeepsTheOtherMessage() throws IOException {
        List<String> text = memberNamesIn("com/onurkat/reclazz/reload/StructuralReloader");
        assertTrue(text.stream().anyMatch(t -> t.contains("until restart")),
                "an ordinary added static still reports that it reads as the default");
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    static class Sample {
        private int items = 3;
        private String currency = "EUR";

        String summary() { return "items=" + items; }
        String addedLater() { return currency; }
    }

    private static byte[] bytecodeOf(Class<?> c) throws IOException {
        try (InputStream in = c.getClassLoader()
                .getResourceAsStream(c.getName().replace('.', '/') + ".class")) {
            assertNotNull(in, "cannot read " + c.getName());
            return in.readAllBytes();
        }
    }

    private static ClassNode parse(byte[] b) {
        ClassNode node = new ClassNode();
        new ClassReader(b).accept(node, 0);
        return node;
    }

    private static List<String> fieldNames(byte[] b) {
        return parse(b).fields.stream().map(f -> f.name)
                .filter(n -> !n.startsWith("this$") && !n.startsWith("__reclazz$"))
                .collect(Collectors.toList());
    }

    private static List<String> methodNames(byte[] b) {
        return parse(b).methods.stream().map(m -> m.name).collect(Collectors.toList());
    }

    /**
     * Strings in the class, read straight from the constant pool.
     *
     * Walking LDC instructions through a ClassNode misses anything the reader
     * skips, and these are messages rather than control flow, so the pool is
     * both simpler and harder to get wrong.
     */
    private static List<String> memberNamesIn(String internalName) throws IOException {
        ClassReader reader = new ClassReader(readClass(internalName));
        List<String> out = new java.util.ArrayList<>();
        char[] buf = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            if (offset == 0) continue;
            try {
                if (reader.readByte(offset - 1) == 8 /* CONSTANT_String */) {
                    out.add(reader.readUTF8(offset, buf));
                }
            } catch (Exception ignored) {
                // Not every pool slot is readable as a string; skip it.
            }
        }
        return out;
    }

    private static byte[] readClass(String internalName) throws IOException {
        String path = "agent/build/classes/java/main/" + internalName + ".class";
        if (!new java.io.File(path).isFile()) {
            path = "build/classes/java/main/" + internalName + ".class";
        }
        java.io.File f = new java.io.File(path);
        if (!f.isFile()) fail(internalName + " is not compiled at " + path);
        return java.nio.file.Files.readAllBytes(f.toPath());
    }

    private static List<String> callsIn(String internalName) throws IOException {
        String path = "agent/build/classes/java/main/" + internalName + ".class";
        if (!new java.io.File(path).isFile()) {
            // The agent module runs its tests with its own directory as cwd.
            path = "build/classes/java/main/" + internalName + ".class";
        }
        java.io.File f = new java.io.File(path);
        if (!f.isFile()) {
            fail(internalName + " is not compiled at " + path);
        }
        ClassNode node = parse(java.nio.file.Files.readAllBytes(f.toPath()));
        return node.methods.stream()
                .flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .filter(i -> i instanceof MethodInsnNode)
                .map(i -> ((MethodInsnNode) i).owner + "." + ((MethodInsnNode) i).name)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unused")
    private static byte[] rewrite(byte[] b) {
        ClassWriter w = new ClassWriter(Opcodes.ASM9);
        new ClassReader(b).accept(w, 0);
        return w.toByteArray();
    }
}
