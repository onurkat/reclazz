package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Computing stack map frames must never load a class.
 *
 * ASM's default {@code getCommonSuperClass} resolves types with
 * {@code Class.forName}, and that used to be tried first here as a fast path
 * for classes already loaded. It is also a class loader. Computing frames for
 * a method containing {@code new Product()} loads {@code demo.Product} from
 * inside the transformer, and the JVM does not re-enter transformers for a
 * class loaded during a transform, so that class was defined with no
 * instrumentation at all. Permanently: the infrastructure can only be added
 * while a class is being loaded, since retransforming to add fields is a
 * schema change the JVM rejects, which was measured rather than assumed.
 *
 * The failure was quiet and asymmetric, which is why it survived so long. A
 * class referenced from transformed bytecode lost; the same class named only
 * in a string did not. JPA entities lose, because whatever builds the
 * EntityManagerFactory references them, and adding a field to an entity
 * therefore failed with a JVM message about schemas that pointed nowhere near
 * the cause.
 *
 * Reading class files answers the same question and loads nothing, which is
 * what the fallback already did. This pins the ordering so the fast path
 * cannot come back.
 */
class FrameComputationMustNotLoadClassesTest {

    private static final String WRITER =
            "com/onurkat/reclazz/transform/ReclazzTransformer$SafeClassWriter";

    @Test
    void theSuperclassResolverNeverCallsClassForName() throws IOException {
        List<String> calls = callsIn(WRITER);

        assertTrue(calls.stream().noneMatch(c -> c.endsWith("Class.forName")),
                "Class.forName in getCommonSuperClass loads the type it is asked "
                + "about, from inside a transform, and the class then misses "
                + "instrumentation for good. Calls: " + calls);
    }

    @Test
    void itDoesNotDelegateToAsmsDefaultEither() throws IOException {
        List<String> calls = callsIn(WRITER);

        assertTrue(calls.stream().noneMatch(c -> c.endsWith("ClassWriter.getCommonSuperClass")),
                "ASM's own implementation is the one that calls Class.forName, so "
                + "delegating to it reintroduces the bug. Calls: " + calls);
    }

    /**
     * The replacement has to actually resolve the hierarchy, not shrug and
     * return Object: frames that claim Object where a specific type is
     * required pass ASM and then fail JVM verification at `athrow`.
     */
    @Test
    void itResolvesTheHierarchyFromClassFiles() throws IOException {
        List<String> calls = callsIn(WRITER);

        assertTrue(calls.stream().anyMatch(c -> c.contains("collectSupertypes"))
                        || calls.stream().anyMatch(c -> c.contains("readSuperName")),
                "the resolver must walk the hierarchy by reading class files. "
                + "Calls: " + calls);
        assertTrue(calls.stream().anyMatch(c -> c.contains("getResourceAsStream")),
                "reading class files is what makes it load-free. Calls: " + calls);
    }

    private static List<String> callsIn(String internalName) throws IOException {
        String path = "agent/build/classes/java/main/" + internalName + ".class";
        if (!new java.io.File(path).isFile()) {
            path = "build/classes/java/main/" + internalName + ".class";
        }
        java.io.File f = new java.io.File(path);
        if (!f.isFile()) fail(internalName + " is not compiled at " + path);

        ClassNode node = new ClassNode();
        new ClassReader(java.nio.file.Files.readAllBytes(f.toPath())).accept(node, 0);
        return node.methods.stream()
                .flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .filter(i -> i instanceof MethodInsnNode)
                .map(i -> ((MethodInsnNode) i).owner + "." + ((MethodInsnNode) i).name)
                .collect(Collectors.toList());
    }
}
