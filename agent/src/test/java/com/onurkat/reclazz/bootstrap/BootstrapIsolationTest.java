/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the bootstrap package is allowed to touch.
 *
 * <p>These classes are packaged into their own jar and appended to the
 * bootstrap classloader, because the dispatch they perform has to be reachable
 * from application classes the agent never loaded. The shadow jar excludes
 * them for the same reason: two copies on two classloaders would be two
 * different types with the same name.
 *
 * <p>The consequence is a rule the compiler cannot enforce and the rest of the
 * test suite cannot see. A bootstrap class that references
 * {@code StatusReporter}, or the shaded ASM, or anything from Maven, compiles
 * cleanly, passes every test (in a test JVM it is all one classpath) and then
 * throws NoClassDefFoundError inside a customer's application, at reload time,
 * from the bootstrap classloader, where nothing that would explain it is
 * loaded.
 *
 * <p>So it is checked against the built class files, which is the only place
 * the answer is visible.
 */
class BootstrapIsolationTest {

    /**
     * The package is small and load-bearing, so this is also a floor: a walk
     * that finds nothing would otherwise report a clean bill of health.
     */
    private static final int AT_LEAST = 20;

    @Test
    void nothingInBootstrapReachesOutsideTheJdkAndItself() throws IOException {
        Path dir = bootstrapClasses();
        List<String> offences = new ArrayList<>();
        int examined = 0;

        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                examined++;
                for (String referenced : typesReferencedBy(Files.readAllBytes(file))) {
                    if (allowed(referenced)) continue;
                    offences.add(file.getFileName() + " -> " + referenced.replace('/', '.'));
                }
            }
        }

        int read = examined;
        assertTrue(read >= AT_LEAST,
                () -> "only " + read + " bootstrap classes were read from " + dir
                        + "; a guard that finds nothing is not a guard");
        assertEquals(List.of(), offences,
                "a bootstrap class references something the bootstrap classloader cannot see. "
                        + "It will be a NoClassDefFoundError at reload time in somebody's "
                        + "application, not here.");
    }

    /**
     * The rule itself. The JDK is present on every classloader; the package's
     * own classes travel with it in the same jar; nothing else does.
     */
    private static boolean allowed(String internalName) {
        return internalName.startsWith("java/")
                || internalName.startsWith("javax/")
                || internalName.startsWith("jdk/")
                || internalName.startsWith("sun/")
                || internalName.startsWith("com/onurkat/reclazz/bootstrap/")
                || internalName.startsWith("[");
    }

    /** Every internal name ASM hands out while walking the class. */
    private static Set<String> typesReferencedBy(byte[] bytecode) {
        Set<String> seen = new LinkedHashSet<>();
        // Opcodes.ASM9 explicitly: the no-argument Remapper constructor is
        // deprecated, and a warning left in a new file is the same debt this
        // guard exists to keep out.
        Remapper recorder = new Remapper(Opcodes.ASM9) {
            @Override
            public String map(String internalName) {
                seen.add(internalName);
                return internalName;
            }
        };
        new ClassReader(bytecode).accept(new ClassRemapper(new ClassNode(), recorder), 0);
        return seen;
    }

    /**
     * The compiled classes rather than the sources: an import that javac
     * erased is not a reference, and a constant folded in is not either.
     */
    private static Path bootstrapClasses() {
        Path here = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && here != null; depth++) {
            Path candidate = here.resolve("agent/build/classes/java/main/com/onurkat/reclazz/bootstrap");
            if (Files.isDirectory(candidate)) return candidate;
            Path inModule = here.resolve("build/classes/java/main/com/onurkat/reclazz/bootstrap");
            if (Files.isDirectory(inModule)) return inModule;
            here = here.getParent();
        }
        throw new IllegalStateException("bootstrap classes not built; looked upward from "
                + Path.of("").toAbsolutePath());
    }
}
