/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.hybris.cockpitng.core.persistence.packaging;

/** Synthetic test loader with the native loader's opaque ProtectionDomain shape. */
public class WidgetClassLoader extends ClassLoader {
    public WidgetClassLoader(ClassLoader parent) { super(parent); }
    public Class<?> define(String name, byte[] bytes) {
        return defineClass(name, java.nio.ByteBuffer.wrap(bytes), null);
    }
}
