/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.launchpath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.attribute.AclEntryPermission.*;

/** Verifies Windows file ownership and access rules before publishing a startup jar. */
final class WindowsStageTrust {
    private static final String SYSTEM = "S-1-5-18", ADMINS = "S-1-5-32-544";
    private static final String TRUSTED_INSTALLER = "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464";

    record Principals(UserPrincipal user, UserPrincipal system, UserPrincipal admins, UserPrincipal installer, String sid,
                      boolean administratorOwnerToken) {
        boolean administrator(UserPrincipal principal) { return principal.equals(system) || principal.equals(admins); }
        boolean trusted(UserPrincipal principal, boolean ancestor) {
            return administrator(principal) || ancestor && principal.equals(installer);
        }
    }

    private static void icacls(Path path, String... arguments) throws IOException {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getenv("SystemRoot"), "System32", "icacls.exe").toString());
        command.add(path.toString());
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (process.waitFor() != 0) throw new IOException("Cannot verify or set access rules for " + path);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted checking Windows access rules", e);
        }
    }

    private static List<AclEntry> acl(Path path) throws IOException {
        return Files.getFileAttributeView(path, AclFileAttributeView.class, NOFOLLOW_LINKS).getAcl();
    }

    // Resolve the well-known Windows trustees to native principals without depending on translated group names.
    private static Principals principals() throws IOException {
        com.sun.security.auth.module.NTSystem token;
        try { token = new com.sun.security.auth.module.NTSystem(); }
        catch (LinkageError e) { throw new IOException("Cannot read the Windows user token", e); }
        String sid = token.getUserSID();
        var user = FileSystems.getDefault().getUserPrincipalLookupService()
                .lookupPrincipalByName(token.getDomain() + "\\" + token.getName());
        Path probe = Files.createTempFile("reclazz-principals-", ".tmp");
        try {
            var view = Files.getFileAttributeView(probe, AclFileAttributeView.class, NOFOLLOW_LINKS);
            view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            icacls(probe, "/inheritancelevel:r", "/grant:r", "*" + SYSTEM + ":R", "*" + ADMINS + ":RX", "*" + TRUSTED_INSTALLER + ":(RA)");
            UserPrincipal system = null, admins = null, installer = null;
            for (AclEntry entry : view.getAcl()) {
                if (entry.type() != AclEntryType.ALLOW || entry.permissions().contains(WRITE_DATA)) continue;
                if (entry.permissions().contains(EXECUTE)) admins = entry.principal();
                else if (entry.permissions().contains(READ_DATA)) system = entry.principal();
                else if (entry.permissions().contains(READ_ATTRIBUTES)) installer = entry.principal();
            }
            if (system == null || admins == null || installer == null) throw new IOException("Cannot resolve trusted Windows principals");
            // A filtered token can list Administrators as deny-only. Its newly created
            // probe also has to receive the group as owner before we accept that layout.
            String[] groups = token.getGroupIDs();
            boolean administratorOwnerToken = groups != null && Arrays.asList(groups).contains(ADMINS)
                    && admins.equals(Files.getOwner(probe, NOFOLLOW_LINKS));
            return new Principals(user, system, admins, installer, sid, administratorOwnerToken);
        } finally { Files.deleteIfExists(probe); }
    }

    private static void noRedirect(Path path) throws IOException {
        if (!path.equals(path.toRealPath()) || Files.isSymbolicLink(path))
            throw new IOException("Unexpected redirected Windows agent path: " + path);
    }

    static boolean allowsParentReplacement(AclEntry entry) {
        if (entry.type() != AclEntryType.ALLOW || entry.flags().contains(AclEntryFlag.INHERIT_ONLY)) return false;
        var permissions = entry.permissions();
        return permissions.contains(DELETE_CHILD) || permissions.contains(DELETE)
                || permissions.contains(WRITE_ACL) || permissions.contains(WRITE_OWNER);
    }

    private static void verifyAdministrative(Path path, Principals who, boolean file) throws IOException {
        noRedirect(path);
        if (!who.trusted(Files.getOwner(path, NOFOLLOW_LINKS), !file)) throw new IOException("Administrator ownership required: " + path);
        for (AclEntry entry : acl(path)) {
            if (who.trusted(entry.principal(), !file) || entry.type() != AclEntryType.ALLOW
                    || entry.flags().contains(AclEntryFlag.INHERIT_ONLY)) continue;
            var rights = entry.permissions();
            if (allowsParentReplacement(entry) || file && (rights.contains(WRITE_DATA) || rights.contains(APPEND_DATA)))
                throw new IOException("An untrusted principal can change the agent path: " + path);
        }
    }

    static synchronized Path privateDirectory() throws IOException {
        String configured = System.getenv("ProgramData");
        if (configured == null) throw new IOException("ProgramData is unavailable");
        Path base = AgentJarPath.directory(configured);
        Principals who = principals();
        verifyAdministrative(base, who, false);
        // Only a direct child of a trusted volume root is admitted as the automatic system data directory.
        if (!base.getParent().equals(base.getRoot())) throw new IOException("Cannot verify relocated ProgramData");
        verifyAdministrative(base.getRoot(), who, false);
        String key;
        try { key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(who.sid.getBytes(StandardCharsets.US_ASCII)), 0, 8); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        Path folder = base.resolve("Reclazz-" + key);
        try {
            var full = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(who.user)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build();
            Files.createDirectory(folder, new FileAttribute<List<AclEntry>>() {
                public String name() { return "acl:acl"; }
                public List<AclEntry> value() { return List.of(full); }
            });
            icacls(folder, "/inheritancelevel:r", "/grant:r", "*" + who.sid + ":(OI)(CI)F", "*" + ADMINS + ":(OI)(CI)F");
            try (var children = Files.list(folder)) {
                if (children.findAny().isPresent()) throw new IOException("New staging directory was not empty");
            }
        } catch (FileAlreadyExistsException existing) { /* verify; never repair another owner's folder */ }
        noRedirect(folder);
        verifyPrivateAcl(Files.getOwner(folder, NOFOLLOW_LINKS), who.user, who.admins, who.administratorOwnerToken, acl(folder));
        Path saved = Files.createTempFile("reclazz-acl-", ".txt");
        try {
            icacls(folder, "/save", saved.toString(), "/q");
            boolean protectedDacl = Files.readString(saved, StandardCharsets.UTF_16LE).lines()
                    .anyMatch(line -> line.matches("D:[A-Z]*P[A-Z]*\\(.*"));
            if (!protectedDacl) throw new IOException("Staging folder must have a protected DACL");
        } finally { Files.deleteIfExists(saved); }
        return folder;
    }

    static void verifyPrivateAcl(UserPrincipal owner, UserPrincipal user, UserPrincipal admins,
                                 boolean administratorOwnerToken, List<AclEntry> entries) throws IOException {
        if (!user.equals(owner) && !(administratorOwnerToken && admins.equals(owner))) throw new IOException("Unexpected staging folder owner");
        boolean userFull = false;
        for (AclEntry entry : entries) {
            if (entry.type() != AclEntryType.ALLOW || !(entry.principal().equals(user) || entry.principal().equals(admins)))
                throw new IOException("Unexpected staging folder access grant");
            if (entry.principal().equals(user) && entry.permissions().containsAll(EnumSet.allOf(AclEntryPermission.class))
                    && entry.flags().containsAll(Set.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT))) userFull = true;
        }
        if (!userFull) throw new IOException("Staging folder requires inheritable user control");
    }

    static void verifyServiceFile(Path file) throws IOException {
        Principals who = principals();
        for (Path p = file; p != null; p = p.getParent()) verifyAdministrative(p, who, p.equals(file));
    }
}
