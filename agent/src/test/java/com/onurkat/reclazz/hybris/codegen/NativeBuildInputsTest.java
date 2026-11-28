/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeBuildInputsTest {
    @TempDir Path platform;
    final Properties compiler = new Properties();
    final Properties extensions = new Properties();
    Path extension;
    Path beans;
    Path dependency;

    @BeforeEach void configureNativeInputs() throws IOException {
        for (String key : List.of("build.debug", "build.nowarn", "build.deprecation", "build.verbose",
                "build.source", "build.target", "build.encoding", "build.parameter-metadata",
                "standalone.jdkmodulesexports")) compiler.setProperty(key, "fixture");
        compiler.setProperty("build.compiler", "modern");
        Path resources = Files.createDirectories(platform.resolve("resources"));
        try (var output = Files.newOutputStream(resources.resolve("advanced.properties"))) {
            compiler.store(output, "Fixture native defaults");
        }
        extension = Files.createDirectories(platform.resolve("ext")).toRealPath();
        extensions.setProperty("demo", extension.toString());
        Files.createDirectories(extension.resolve("src"));
        beans = Files.writeString(Files.createDirectories(extension.resolve("resources")).resolve("demo-beans.xml"), "<beans/>");
        Path classpath = Files.createDirectory(platform.resolve("ordinary-classes"));
        dependency = Files.write(classpath.resolve("Dependency.class"), new byte[]{1, 2, 3});
        compiler.setProperty("reclazz.compiler.entries", classpath.toString());
        compiler.setProperty("HYBRIS_CONFIG_DIR", Files.createDirectory(platform.resolve("config")).toString());
        compiler.setProperty("HYBRIS_TEMP_DIR", platform.toString());
        compiler.setProperty("bundled.tomcat.home", platform.toString());
        Files.createDirectory(platform.resolve("lib"));
    }

    @Test void unchangedOrdinaryConsumerIsCheckedForNewGeneratedNames() throws IOException {
        Files.writeString(extension.resolve("src/Consumer.java"),
                "class Consumer { String read(Dto dto) { return dto.getStageText(); } }");
        var inputs = NativeBuildInputs.capture(platform, compiler, extensions);
        assertEquals(Set.of("getStageText"), inputs.possibleConsumerNames(Set.of("getStageText", "unused"), Set.of()));
        Files.writeString(beans, "<beans><!-- edit --></beans>");
        inputs.verify(NativeBuildInputs.capture(platform, compiler, extensions), beans);
    }

    @Test void changedOrdinaryClasspathContentInvalidatesEvenWithSameSizeAndTimestamp() throws IOException {
        var before = NativeBuildInputs.capture(platform, compiler, extensions);
        var stamp = Files.getLastModifiedTime(dependency);
        Files.write(dependency, new byte[]{1, 2, 4});
        Files.setLastModifiedTime(dependency, stamp);
        var after = NativeBuildInputs.capture(platform, compiler, extensions);
        assertThrows(IOException.class, () -> before.verify(after, beans));
    }
}
