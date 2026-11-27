/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.agent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Publishes one complete credential after verifying the private temporary file. */
final class PrivatePortFile {
    private static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rw-------");
    private final Path target;
    private final Path guard;
    private final String contents;

    private PrivatePortFile(Path target, String contents) {
        this.target = target;
        this.guard = target.resolveSibling("." + target.getFileName() + ".publishing");
        this.contents = contents;
    }

    static PrivatePortFile publish(Path path, int port, String credential) throws IOException {
        if (path == null) throw new IOException("A private status port file is required");
        Path absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path directory = absolute.getParent().toRealPath();
        Path target = directory.resolve(absolute.getFileName());
        var publication = new PrivatePortFile(target, port + "\ntoken=" + credential + "\n");
        boolean posix = Files.getFileStore(directory).supportsFileAttributeView("posix");
        UserPrincipal owner = directory.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        Files.createDirectory(publication.guard);
        try {
            if (Files.isSymbolicLink(target)) throw new IOException("Refusing a symbolic status port file");
            Path temporary = directory.resolve(".reclazz-status-" + java.util.UUID.randomUUID() + ".tmp");
            SeekableByteChannel channel = createPrivate(temporary, owner, posix);
            try {
                try (channel) {
                    if (!posix) Files.getFileAttributeView(temporary, AclFileAttributeView.class, NOFOLLOW_LINKS)
                            .setAcl(List.of(ownerEntry(owner)));
                    verifyPrivate(temporary, owner, posix);
                    ByteBuffer data = StandardCharsets.US_ASCII.encode(publication.contents);
                    while (data.hasRemaining()) channel.write(data);
                }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                try {
                    verifyPrivate(target, owner, posix);
                } catch (IOException | RuntimeException invalid) {
                    try { publication.removeMatchingContents(); }
                    catch (IOException cleanup) { invalid.addSuppressed(cleanup); }
                    throw invalid;
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } finally {
            Files.delete(publication.guard);
        }
        return publication;
    }

    private static SeekableByteChannel createPrivate(Path path, UserPrincipal user, boolean posix) throws IOException {
        if (posix) return Files.newByteChannel(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PRIVATE));
        if (Files.getFileAttributeView(path, AclFileAttributeView.class, NOFOLLOW_LINKS) == null)
            throw new IOException("Status port file requires POSIX mode or an owner-only ACL");

        AclEntry entry = ownerEntry(user);
        FileAttribute<List<AclEntry>> attribute = new FileAttribute<>() {
            public String name() { return "acl:acl"; }
            public List<AclEntry> value() { return List.of(entry); }
        };
        return Files.newByteChannel(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE,
                com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE), attribute);
    }

    private static AclEntry ownerEntry(UserPrincipal user) {
        return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
    }

    private static boolean trustedWindowsPrincipal(UserPrincipal principal, UserPrincipal user) {
        String name = principal.getName();
        return principal.equals(user) || name.equalsIgnoreCase("NT AUTHORITY\\SYSTEM")
                || name.equalsIgnoreCase("BUILTIN\\Administrators");
    }

    private static void verifyPrivate(Path path, UserPrincipal user, boolean posix) throws IOException {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw new IOException("Expected a private regular status port file");
        UserPrincipal owner = Files.getOwner(path, NOFOLLOW_LINKS);
        if (posix) {
            if (!owner.equals(user) || !Files.getPosixFilePermissions(path, NOFOLLOW_LINKS).equals(PRIVATE))
                throw new IOException("Status port file requires current-user ownership and mode 0600");
        } else {
            List<AclEntry> acl = Files.getFileAttributeView(path, AclFileAttributeView.class, NOFOLLOW_LINKS).getAcl();
            boolean readable = acl.stream().anyMatch(e -> e.principal().equals(user) && e.type() == AclEntryType.ALLOW
                    && e.permissions().contains(AclEntryPermission.READ_DATA));
            if (!trustedWindowsPrincipal(owner, user) || !readable || acl.stream().anyMatch(e ->
                    e.type() == AclEntryType.ALLOW && !trustedWindowsPrincipal(e.principal(), user)))
                throw new IOException("Status port file requires a private Windows ACL");
        }
    }

    void removeIfCurrent() throws IOException {
        Files.createDirectory(guard);
        try {
            removeMatchingContents();
        } finally {
            Files.delete(guard);
        }
    }

    private void removeMatchingContents() throws IOException {
        if (!Files.isRegularFile(target, NOFOLLOW_LINKS)) return;
        try (var in = Files.newInputStream(target, NOFOLLOW_LINKS)) {
            if (!contents.equals(new String(in.readNBytes(257), StandardCharsets.US_ASCII))) return;
        }
        Files.delete(target);
    }
}
