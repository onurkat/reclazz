/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** A single discovery snapshot. Its private credential must never appear in diagnostic data. */
public final class StatusEndpoint {
    private final int port;
    private final String credential;

    private StatusEndpoint(int port, String credential) {
        this.port = port;
        this.credential = credential;
    }

    public int port() { return port; }
    public boolean requiresAuthentication() { return credential != null; }

    public static StatusEndpoint legacy(int port) throws IOException {
        if (port < 1 || port > 65535) throw new IOException("invalid port");
        return new StatusEndpoint(port, null);
    }

    public static StatusEndpoint read(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Expected a regular status port file");
        byte[] data;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            data = input.readNBytes(257);
        }
        if (data.length > 256) throw new IOException("invalid port file");
        String text = new String(data, StandardCharsets.US_ASCII).replace("\r\n", "\n");
        if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
        String[] lines = text.split("\n", -1);
        String number = lines.length == 1 ? lines[0].strip() : lines[0];
        if (!number.matches("[0-9]{1,5}")) throw new IOException("invalid port file");
        StatusEndpoint endpoint = legacy(Integer.parseInt(number));
        if (lines.length == 1) return endpoint;
        if (lines.length != 2 || !lines[1].matches("token=[0-9a-f]{64}"))
            throw new IOException("invalid port credential");
        return new StatusEndpoint(endpoint.port, lines[1].substring(6));
    }

    public static StatusEndpoint resolve(Path file, Integer fixedPort) throws IOException {
        if (file == null) {
            if (fixedPort == null) throw new IOException("No status port file");
            return legacy(fixedPort);
        }
        StatusEndpoint endpoint = read(file);
        return fixedPort == null ? endpoint : endpoint.requirePort(fixedPort);
    }

    public int protocol() { return requiresAuthentication() ? 2 : 1; }

    public StatusEndpoint requirePort(int expected) throws IOException {
        if (port != expected) throw new IOException("Configured port does not match the status port file");
        return this;
    }

    @Override public boolean equals(Object other) {
        return other instanceof StatusEndpoint endpoint && port == endpoint.port
                && java.util.Objects.equals(credential, endpoint.credential);
    }

    @Override public int hashCode() { return Integer.hashCode(port); }

    public void authenticate(OutputStream stream) throws IOException {
        if (credential != null) {
            stream.write(("AUTH " + credential + "\n").getBytes(StandardCharsets.US_ASCII));
            stream.flush();
        }
    }
}
