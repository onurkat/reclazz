/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The jar somebody attaches is a thing on its own.
 *
 * <p>The README tells people to build {@code agent-<version>.jar} and put it on
 * a command line, and plenty of them will never see the plugin zip. That jar
 * carries a hundred and fifty relocated ASM classes, and ASM's BSD licence asks
 * for its copyright notice to travel with them. It carried neither a licence
 * nor a notice: the build put those in the zip and not in the jar, under a
 * comment saying the licence terms travel with the thing they license.
 *
 * <p>The other half is that the notice has to be true. It said the agent shades
 * Byte Buddy, which it stopped doing in 1.1.0, and the third-party inventory
 * named ASM 9.8 when the build uses 9.10.1. A compliance review reads those two
 * files and nothing else, and a wrong version in them is the kind of thing that
 * costs a week.
 */
class TheJarCarriesItsLicencesTest {

    @Test
    void theAgentJarShipsItsLicenceAndNotice() throws IOException {
        Path jar = builtAgentJar();

        try (JarFile contents = new JarFile(jar.toFile())) {
            assertNotNull(contents.getEntry("META-INF/LICENSE"),
                    "the jar redistributes relocated ASM and has to carry a licence");
            assertNotNull(contents.getEntry("META-INF/NOTICE"),
                    "and the notice ASM's BSD terms ask for");
            assertNotNull(contents.getEntry("META-INF/THIRD-PARTY.md"),
                    "and the inventory that says what is in here and why");
        }
    }

    /** What the notice claims has to be what the jar contains. */
    @Test
    void theNoticeNamesWhatIsActuallyShaded() throws IOException {
        Path jar = builtAgentJar();
        String notice;
        List<String> shadedPackages = new ArrayList<>();

        try (JarFile contents = new JarFile(jar.toFile())) {
            notice = new String(contents.getInputStream(contents.getEntry("META-INF/NOTICE"))
                    .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            contents.stream()
                    .map(java.util.jar.JarEntry::getName)
                    .filter(name -> name.startsWith("com/onurkat/reclazz/shaded/"))
                    .map(name -> name.substring("com/onurkat/reclazz/shaded/".length()))
                    .map(name -> name.substring(0, Math.max(0, name.indexOf('/'))))
                    .filter(name -> !name.isEmpty())
                    .distinct()
                    .forEach(shadedPackages::add);
        }

        assertFalse(shadedPackages.isEmpty(), "nothing is shaded, so this proves nothing");

        // Both directions. Something shaded and unnamed is a licence obligation
        // nobody discharged; something named and not shaded is what the notice
        // did for a whole release, claiming Byte Buddy that had stopped
        // shipping, and a notice that is wrong in the easy direction is not
        // trusted in the hard one.
        for (String shaded : shadedPackages) {
            assertTrue(notice.contains("com.onurkat.reclazz.shaded." + shaded),
                    () -> "the jar shades " + shaded + " and the notice does not name it: "
                            + shadedPackages);
        }

        List<String> claimed = new ArrayList<>();
        java.util.regex.Matcher relocations = java.util.regex.Pattern
                .compile("com\\.onurkat\\.reclazz\\.shaded\\.(\\w+)").matcher(notice);
        while (relocations.find()) claimed.add(relocations.group(1));

        for (String named : claimed) {
            assertTrue(shadedPackages.contains(named),
                    () -> "the notice says " + named + " is shaded into this jar and it is not: "
                            + shadedPackages);
        }
    }

    /** The inventory's version has to be the version the build resolves. */
    @Test
    void theThirdPartyInventoryNamesTheVersionInTheBuild() throws IOException {
        Path root = repositoryRoot();
        String inventory = Files.readString(root.resolve("THIRD-PARTY.md"));
        String build = Files.readString(root.resolve("agent/build.gradle.kts"));

        java.util.regex.Matcher declared =
                java.util.regex.Pattern.compile("org\\.ow2\\.asm:asm:([0-9.]+)").matcher(build);
        assertTrue(declared.find(), "the build no longer declares ASM by that coordinate");
        String version = declared.group(1);

        assertTrue(inventory.contains(version),
                () -> "the build uses ASM " + version + " and THIRD-PARTY.md does not say so");
    }

    /**
     * The jar the build says it produced, rather than the newest thing in a
     * directory. The build passes this path and declares it as an input, so
     * changing the jar re-runs these; picking a file out of build/libs by hand
     * let them pass on the previous run's result, which is how a guard comes to
     * report a clean bill of health for a jar it never opened.
     */
    private static Path builtAgentJar() {
        String named = System.getProperty("reclazz.agent.jar");
        assertNotNull(named, "the build did not say which jar it built");
        Path jar = Path.of(named);
        assertTrue(Files.isRegularFile(jar), () -> "the agent jar is not built: " + jar);
        return jar;
    }

    private static Path repositoryRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && here != null; depth++) {
            if (Files.isRegularFile(here.resolve("THIRD-PARTY.md"))
                    && Files.isRegularFile(here.resolve("NOTICE"))) {
                return here;
            }
            here = here.getParent();
        }
        throw new IllegalStateException("repository root not found from "
                + Path.of("").toAbsolutePath());
    }
}
