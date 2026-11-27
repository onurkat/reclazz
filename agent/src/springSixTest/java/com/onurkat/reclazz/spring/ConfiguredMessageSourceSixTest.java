/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.core.io.DefaultResourceLoader;
import static org.junit.jupiter.api.Assertions.*;

class ConfiguredMessageSourceSixTest {
    @Test void sourceLoaderSelectsWhichIdenticallyNamedBundleCanResetTheCache(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("a/i18n"));
        Path b = Files.createDirectories(tmp.resolve("b/i18n"));
        Path base = Files.writeString(b.resolve("checkout.properties"), "button=default\n");
        Path locale = Files.writeString(b.resolve("checkout_tr.properties"), "button=before\n");
        Path other = Files.writeString(a.resolve("checkout_tr.properties"), "button=shadow\n");
        try (var loader = new URLClassLoader(new java.net.URL[]{b.getParent().toUri().toURL()}, null)) {
            var source = new ReloadableResourceBundleMessageSource();
            source.setBasename("classpath:i18n/checkout");
            source.setResourceLoader(new DefaultResourceLoader(loader));
            Locale tr = Locale.forLanguageTag("tr");
            assertEquals("before", source.getMessage("button", null, tr));
            assertFalse(SpringMessageSourceReloader.ownsBundle(source, other));
            assertFalse(SpringMessageSourceReloader.ownsBundle(source, Files.createFile(b.resolve("checkout2.properties"))));
            assertTrue(SpringMessageSourceReloader.ownsBundle(source, base));
            Files.writeString(locale, "button=after\n");
            assertTrue(SpringMessageSourceReloader.ownsBundle(source, locale));
            assertEquals(1, SpringMessageSourceReloader.reloadOwners(java.util.Set.of(source)));
            assertEquals("after", source.getMessage("button", null, tr));
        }
    }
}
