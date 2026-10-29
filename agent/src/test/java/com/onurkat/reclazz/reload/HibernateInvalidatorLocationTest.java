/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.HibernateCacheInvalidator;
import com.onurkat.reclazz.platform.HybrisPlatformContext;
import com.onurkat.reclazz.transform.TransformContext;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class HibernateInvalidatorLocationTest {
    @Test
    void structuralReloaderResolvesItsExactHibernateClassName() throws Exception {
        Field name = StructuralReloader.class.getDeclaredField("HIBERNATE_CACHE_INVALIDATOR_CLASS");
        name.setAccessible(true);
        Class<?> resolved = Class.forName((String) name.get(null), false, StructuralReloader.class.getClassLoader());
        assertSame(HibernateCacheInvalidator.class, resolved);

        var config = AgentConfig.parse(null);
        var reloader = new StructuralReloader(null, new TransformContext(), config,
                new HybrisPlatformContext(Path.of("."), config));
        Field invalidator = StructuralReloader.class.getDeclaredField("hibernateInvalidator");
        invalidator.setAccessible(true);
        assertNotNull(invalidator.get(reloader), "the actual lazy constructor path must remain wired");
        assertSame(resolved, invalidator.get(reloader).getClass());
    }
}
