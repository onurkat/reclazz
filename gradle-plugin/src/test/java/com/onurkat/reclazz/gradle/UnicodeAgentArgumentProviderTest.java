/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.gradle;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnicodeAgentArgumentProviderTest {
    @Test void onlyTheOptionStringIsEncoded() {
        var project = ProjectBuilder.builder().build();
        var provider = project.getObjects().newInstance(AgentArgumentProvider.class);
        File jar = new File("tools 東京", "reclazz-agent.jar").getAbsoluteFile();
        provider.getAgentJar().from(jar);
        provider.getEnabled().set(true);
        String flag = "-javaagent:" + jar + "=";
        String raw = "platform=spring,watchDirs=Alan Çalışma/classes;東京/classes";
        provider.getArgumentString().set(raw);
        String encoded = provider.asArguments().iterator().next();
        assertTrue(encoded.startsWith(flag + "reclazz-utf8:"), encoded);
        assertEquals(raw, new String(Base64.getUrlDecoder().decode(encoded.substring((flag + "reclazz-utf8:").length())), StandardCharsets.UTF_8));
        provider.getArgumentString().set("watchDirs=ordinary space/classes");
        assertEquals(flag + "watchDirs=ordinary space/classes", provider.asArguments().iterator().next());
    }
}
