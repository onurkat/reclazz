/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

class ModernComposedMethodSecurityReloadTest extends ComposedMethodSecurityReloadTest {
    @Override String applicationSource() {
        return super.applicationSource().replace("EnableGlobalMethodSecurity", "EnableMethodSecurity");
    }
    @Override String applicationClasspath() {
        String observations = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> Path.of(p).getFileName().toString().startsWith("micrometer-"))
                .collect(Collectors.joining(File.pathSeparator));
        return super.applicationClasspath() + File.pathSeparator + observations;
    }
}
