/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import java.net.URL;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Local process resources referenced by existing singletons, never a filename guess. */
public final class SapProcessResources {
    private static final String RESOURCE = "de.hybris.platform.processengine.definition.ProcessDefinitionResource";
    private SapProcessResources() { }

    public static boolean isRegistered(Path file) {
        return registeredFiles().contains(file.toAbsolutePath().normalize());
    }

    /** No retained context/classloader, lazy bean creation, parsing or remote URL access. */
    public static Set<Path> registeredFiles() {
        Set<Path> files = new HashSet<>();
        for (Object context : ApplicationContextHolder.getAllContexts()) {
            try {
                if (!Boolean.TRUE.equals(context.getClass().getMethod("isActive").invoke(context))) continue;
                Object factory = context.getClass().getMethod("getBeanFactory").invoke(context);
                ClassLoader loader = (ClassLoader) factory.getClass().getMethod("getBeanClassLoader").invoke(factory);
                Class<?> resourceType = Class.forName(RESOURCE, false, loader);
                var getSingleton = factory.getClass().getMethod("getSingleton", String.class);
                var getResource = resourceType.getMethod("getResource");
                for (String name : (String[]) factory.getClass().getMethod("getSingletonNames").invoke(factory)) {
                    try {
                        Object bean = getSingleton.invoke(factory, name);
                        if (!resourceType.isInstance(bean)) continue;
                        Object resource = getResource.invoke(bean);
                        if (resource == null) continue;
                        URL url = (URL) resource.getClass().getMethod("getURL").invoke(resource);
                        if (!"file".equals(url.getProtocol())) continue;
                        Path path = Path.of(url.toURI()).toAbsolutePath().normalize();
                        if (path.getFileName() != null && path.getFileName().toString().endsWith(".xml")) files.add(path);
                    } catch (Exception | LinkageError unavailable) {
                        // One unsupported registration must not hide the other known resources.
                    }
                }
            } catch (Exception | LinkageError unavailable) {
                // A closed/non-SAP context grants no process-resource ownership.
            }
        }
        return files;
    }
}
