/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.launchpath;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AgentJarPathTest {
    @TempDir Path temp;
    private Path root() throws IOException { return temp.toRealPath(); }
    private static final AgentJarPath.StageDirectory UNUSED = () -> { throw new AssertionError("unexpected staging"); };

    @Test void directPathsAndNonWindowsPreserveTheirOriginalArgument() throws Exception {
        Path plain = root().resolve("ordinary dir/agent.jar");
        assertEquals(plain, AgentJarPath.forLaunch(plain, null, true, UNUSED));
        Path unicode = root().resolve("東京=/agent.jar");
        assertEquals(unicode, AgentJarPath.forLaunch(unicode, "relative", false, UNUSED));
    }

    @Test void overrideWinsIsValidatedBeforeWritingAndExistingBytesAreVerified() throws Exception {
        Path source = Files.writeString(root().resolve("東京.jar"), "version one");
        Path override = root().resolve("override");
        Path staged = AgentJarPath.forLaunch(source, override.toString(), true, UNUSED);
        assertTrue(staged.startsWith(override));
        assertEquals(staged, AgentJarPath.forLaunch(source, override.toString(), true, UNUSED));
        Files.writeString(staged, "damaged");
        assertThrows(IOException.class, () -> AgentJarPath.forLaunch(source, override.toString(), true, UNUSED));
        assertEquals("damaged", Files.readString(staged));
        for (String invalid : List.of("relative", root().resolve("東京").toString(), root().resolve("a=b").toString()))
            assertThrows(IOException.class, () -> AgentJarPath.forLaunch(source, invalid, true, UNUSED));
    }

    @Test void concurrentJarsOfTheSameVersionGetCompleteContentAddressedPaths() throws Exception {
        Path one = Files.writeString(root().resolve("a=1.jar"), "one");
        Path two = Files.writeString(root().resolve("a=2.jar"), "two");
        var pool = Executors.newFixedThreadPool(3);
        var start = new CountDownLatch(1);
        try {
            List<Future<Path>> futures = new ArrayList<>();
            for (Path file : List.of(one, two, one)) futures.add(pool.submit(() -> {
                start.await();
                return AgentJarPath.forLaunch(file, null, true, () -> root().resolve("stage"));
            }));
            start.countDown();
            Path a = futures.get(0).get(), b = futures.get(1).get();
            assertEquals(a, futures.get(2).get());
            assertNotEquals(a, b);
            assertEquals("one", Files.readString(a));
            assertEquals("two", Files.readString(b));
        } finally { pool.shutdownNow(); }
    }

    @Test void serviceRequiresAnAlreadyProvisionedMatchingJarAndNeverWritesIt() throws Exception {
        Path source = Files.writeString(root().resolve("source.jar"), "bundled");
        Path legacy = root().resolve("東京/home.jar");
        Path directory = root().resolve("admin");
        assertThrows(IOException.class, () -> AgentJarPath.forService(legacy, source, null, true, file -> fail()));
        assertThrows(IOException.class, () -> AgentJarPath.forService(legacy, source, directory.toString(), true, file -> {}));
        assertFalse(Files.exists(directory));
        Path provisioned = Files.createDirectories(directory).resolve("reclazz-agent.jar");
        Files.writeString(provisioned, "old");
        assertThrows(IOException.class, () -> AgentJarPath.forService(legacy, source, directory.toString(), true, file -> {}));
        assertEquals("old", Files.readString(provisioned));
        Files.writeString(provisioned, "bundled");
        assertThrows(IOException.class, () -> AgentJarPath.forService(legacy, source, directory.toString(), true, file -> {
            throw new IOException("not administrator controlled");
        }));
        assertEquals(provisioned, AgentJarPath.forService(legacy, source, directory.toString(), true, file -> assertEquals(provisioned, file)));
    }

    @Test void stockVolumeAndProgramDataOwnersAreTrustedWithoutAcceptingAnOrdinaryUser() {
        UserPrincipal user = () -> "developer", system = () -> "system", admins = () -> "admins";
        UserPrincipal installer = () -> "resolved service SID";
        var who = new WindowsStageTrust.Principals(user, system, admins, installer, "user SID", false);
        assertTrue(who.trusted(installer, true));
        assertTrue(who.trusted(system, true));
        assertTrue(who.trusted(admins, true));
        assertFalse(who.trusted(user, true));
        assertFalse(who.trusted(installer, false));
    }

    private static AclEntry full(UserPrincipal principal) {
        return AclEntry.newBuilder().setPrincipal(principal).setType(AclEntryType.ALLOW)
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
    }

    @Test void administratorOwnedPrivateFolderRequiresTheAdministratorOwnerTokenAndPrivateGrants() throws IOException {
        UserPrincipal user = () -> "user", admins = () -> "administrators", other = () -> "other";
        var grants = List.of(full(user), full(admins));
        WindowsStageTrust.verifyPrivateAcl(admins, user, admins, true, grants);
        assertThrows(IOException.class, () -> WindowsStageTrust.verifyPrivateAcl(admins, user, admins, false, grants));
        assertThrows(IOException.class, () -> WindowsStageTrust.verifyPrivateAcl(other, user, admins, true, grants));
        assertThrows(IOException.class, () -> WindowsStageTrust.verifyPrivateAcl(admins, user, admins, true, List.of(full(admins))));
        assertThrows(IOException.class, () -> WindowsStageTrust.verifyPrivateAcl(admins, user, admins, true, List.of(full(user), full(other))));
    }

    @Test void aDifferentOwnerOrAdditionalTrusteeRejectsAnExistingDirectory() throws Exception {
        UserPrincipal user = () -> "user", admin = () -> "admin", extra = () -> "other";
        var expected = List.of(full(user), full(admin));
        WindowsStageTrust.verifyPrivateAcl(user, user, admin, false, expected);
        assertThrows(IOException.class, () -> WindowsStageTrust.verifyPrivateAcl(extra, user, admin, false, expected));
        assertThrows(IOException.class, () -> WindowsStageTrust.verifyPrivateAcl(user, user, admin, false, List.of(full(user), full(extra))));
        assertTrue(WindowsStageTrust.allowsParentReplacement(full(extra)));
        var inheritedOnly = AclEntry.newBuilder(full(extra)).setFlags(AclEntryFlag.INHERIT_ONLY).build();
        assertFalse(WindowsStageTrust.allowsParentReplacement(inheritedOnly));
    }
}
