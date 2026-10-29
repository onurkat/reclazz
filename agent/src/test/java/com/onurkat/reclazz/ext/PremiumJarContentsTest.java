/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ext;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class PremiumJarContentsTest {
    @Test void canonicalJarIsExactlyCorePlusPremiumWithoutDuplicateEntries() throws Exception {
        Path full = Path.of(System.getProperty("reclazz.agent.jar"));
        Path core = Path.of(System.getProperty("reclazz.core.agent.jar",
                full.toString().replaceFirst("\\.jar$", "-core.jar")));
        assertTrue(Files.isRegularFile(core), "physical core jar is missing: " + core);
        try (var fullJar = new JarFile(full.toFile()); var coreJar = new JarFile(core.toFile())) {
            Map<String, byte[]> all = entries(fullJar), free = entries(coreJar);
            String prefix = "com/onurkat/reclazz/hybris/";
            assertTrue(free.keySet().stream().noneMatch(n -> n.startsWith(prefix)));
            assertTrue(all.containsKey(prefix + "SapPremiumModuleFactory.class"));
            Set<String> premium = new TreeSet<>();
            Path output = Path.of("build/classes/java/premium");
            assertTrue(Files.isDirectory(output), "premium compiled output is missing");
            try (var files = Files.walk(output)) {
                for (Path file : files.filter(Files::isRegularFile).toList())
                    premium.add(output.relativize(file).toString().replace('\\', '/'));
            }
            assertFalse(premium.isEmpty());
            assertTrue(Collections.disjoint(free.keySet(), premium));
            Set<String> expected = new TreeSet<>(free.keySet()); expected.addAll(premium);
            assertEquals(expected, all.keySet(), "canonical jar must add exactly the premium classes");
            assertEquals(coreJar.getManifest(), fullJar.getManifest());
            for (String name : free.keySet()) assertArrayEquals(free.get(name), all.get(name), name);
            assertFalse(all.keySet().stream().anyMatch(n -> n.startsWith("com/onurkat/reclazz/bootstrap/")));
            assertTrue(free.containsKey("META-INF/reclazz-bootstrap.bin"));
            assertTrue(free.containsKey("com/onurkat/reclazz/shaded/asm/ClassReader.class"));
        }
    }
    private static Map<String, byte[]> entries(JarFile jar) throws Exception {
        Map<String, byte[]> entries = new TreeMap<>();
        Set<String> names = new HashSet<>();
        for (var item : Collections.list(jar.entries())) {
            assertTrue(names.add(item.getName()), "duplicate " + item.getName());
            if (!item.isDirectory()) {
                try (var in = jar.getInputStream(item)) { entries.put(item.getName(), in.readAllBytes()); }
            }
        }
        return entries;
    }
}
