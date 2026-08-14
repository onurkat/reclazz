package com.onurkat.reclazz.reload;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * After a class has gained a member, its added members live in a companion
 * rather than in the loaded class. The bytecode the reloader hands back
 * therefore no longer matches what the JVM will accept, and the constructor
 * body refresh, which is a plain redefineClasses call, is refused with
 * {@code UnsupportedOperationException: attempted to add a method}.
 *
 * That is the companion engine working exactly as designed, and the reload
 * itself succeeds regardless. It was nonetheless reported as a warning with
 * the raw exception appended, on the tool window, which is the one surface
 * users watch while they work. So every subsequent edit to a class they had
 * once added a method to told them their reload had failed. It had not.
 *
 * The distinction matters both ways: a genuine failure here still has to be
 * reported, so the catch cannot simply be widened to swallow everything.
 */
class ConstructorRefreshNoiseTest {

    private static MethodNode reloadMethod() throws IOException {
        Path cls = Path.of("build/classes/java/main/com/onurkat/reclazz/reload/StructuralReloader.class");
        assumeCompiled(cls);
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(cls)).accept(node, ClassReader.SKIP_FRAMES);
        return node.methods.stream()
                .filter(m -> !m.tryCatchBlocks.isEmpty())
                .filter(m -> m.tryCatchBlocks.stream().anyMatch(
                        t -> "java/lang/UnsupportedOperationException".equals(t.type)
                          || "java/lang/Throwable".equals(t.type)))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no method in StructuralReloader guards the constructor-body refresh"));
    }

    private static void assumeCompiled(Path p) {
        if (!Files.exists(p)) {
            fail("StructuralReloader is not compiled; run the agent build first: " + p);
        }
    }

    /**
     * The expected refusal is caught on its own, ahead of the general catch.
     * Handler order is what makes that work, so it is what gets asserted.
     */
    @Test
    void theExpectedRefusalIsCaughtSeparatelyAndFirst() throws IOException {
        MethodNode m = reloadMethod();
        List<String> handlers = m.tryCatchBlocks.stream()
                .map(t -> t.type)
                .filter(t -> t != null)
                .collect(Collectors.toList());

        assertTrue(handlers.contains("java/lang/UnsupportedOperationException"),
                "the JVM's refusal to add a method must be recognised rather than "
                + "reported as an unexplained failure. Handlers: " + handlers);

        int expected = handlers.indexOf("java/lang/UnsupportedOperationException");
        int general = handlers.indexOf("java/lang/Throwable");
        if (general >= 0) {
            assertTrue(expected < general,
                    "the specific handler has to come first or the general one "
                    + "swallows it and the warning comes back. Handlers: " + handlers);
        }
    }

    /** A real failure still has to reach the user. */
    @Test
    void genuineFailuresAreStillReported() throws IOException {
        MethodNode m = reloadMethod();
        boolean general = m.tryCatchBlocks.stream()
                .map(TryCatchBlockNode::getClass)
                .findAny().isPresent()
                && m.tryCatchBlocks.stream().anyMatch(t -> "java/lang/Throwable".equals(t.type));

        assertTrue(general,
                "widening the catch to swallow everything would hide real breakage; "
                + "the general handler must survive alongside the specific one");
    }
}
