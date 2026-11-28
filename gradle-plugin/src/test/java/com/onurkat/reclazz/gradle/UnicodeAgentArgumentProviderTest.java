/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.gradle;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

class UnicodeAgentArgumentProviderTest {
    @Test void onlyTheOptionStringIsEncoded(@TempDir Path temp) throws Exception {
        checkEncoding(temp, "agent ordinary.jar");
    }

    @Test void aCjkJarPathIsResolvedSeparatelyFromTheOptions(@TempDir Path temp) throws Exception {
        String name = "agent 東京.jar";
        Charset fileNames = Charset.forName(System.getProperty("sun.jnu.encoding"));
        assumeTrue(fileNames.newEncoder().canEncode(name), () -> "Sample file name cannot be represented by " + fileNames);
        checkEncoding(temp, name);
    }

    private void checkEncoding(Path temp, String name) throws Exception {
        var project = ProjectBuilder.builder().build();
        var provider = project.getObjects().newInstance(AgentArgumentProvider.class);
        var root = temp.toRealPath();
        File jar = java.nio.file.Files.writeString(root.resolve(name), "agent").toFile();
        var override = root.resolve("stage");
        provider.getAgentDir().set(override.toString());
        provider.getAgentJar().from(jar);
        provider.getEnabled().set(true);
        var resolved = com.onurkat.reclazz.launchpath.AgentJarPath.forLaunch(jar.toPath(), override.toString());
        if (com.onurkat.reclazz.launchpath.AgentJarPath.windows()) {
            assertTrue(resolved.startsWith(override));
            assertArrayEquals(java.nio.file.Files.readAllBytes(jar.toPath()), java.nio.file.Files.readAllBytes(resolved));
        }
        String flag = "-javaagent:" + resolved + "=";
        String raw = "platform=spring,watchDirs=Alan Çalışma/classes;東京/classes";
        provider.getArgumentString().set(raw);
        String encoded = provider.asArguments().iterator().next();
        assertTrue(encoded.startsWith(flag + "reclazz-utf8:"), encoded);
        assertEquals(raw, new String(Base64.getUrlDecoder().decode(encoded.substring((flag + "reclazz-utf8:").length())), StandardCharsets.UTF_8));
        provider.getArgumentString().set("watchDirs=ordinary space/classes");
        assertEquals(flag + "watchDirs=ordinary space/classes", provider.asArguments().iterator().next());
    }
}
