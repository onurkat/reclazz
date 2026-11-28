/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.codegen;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Entry point in the build child. The SAP bootstrap classes come from this installation's Ant classpath. */
public final class NativeBeanStaging {
    private static final String BOOT = "de.hybris.bootstrap.";
    private static final String DEFAULT_PROCESSOR = BOOT + "beangenerator.postprocessors.SwaggerDocumentationPostProcessor";
    private static final List<String> DIRECTORIES = List.of("platformhome", "HYBRIS_BIN_DIR", "HYBRIS_CONFIG_DIR",
            "HYBRIS_BOOTSTRAP_BIN_DIR", "HYBRIS_DATA_DIR", "HYBRIS_LOG_DIR", "HYBRIS_TEMP_DIR");

    private NativeBeanStaging() { }

    public static void main(String[] args) throws ReflectiveOperationException, IOException {
        if (args.length != DIRECTORIES.size() + 1) throw new IllegalArgumentException("Expected native build directories and stage");
        Hashtable<String, String> properties = new Hashtable<>();
        for (int i = 0; i < DIRECTORIES.size(); i++) {
            Path directory = Path.of(args[i]);
            if (!directory.isAbsolute() || !Files.isDirectory(directory)) throw new IOException("Unresolved native build directory");
            properties.put(DIRECTORIES.get(i), directory.toString());
        }
        Path destination = Path.of(args[DIRECTORIES.size()]);
        // The parent creates a private operation directory. Refuse any pre-existing destination.
        Files.createDirectory(destination);
        Class<?> systemType = Class.forName(BOOT + "config.SystemConfig");
        Object system = systemType.getMethod("getInstanceByProps", Hashtable.class).invoke(null, properties);
        Class<?> platformType = Class.forName(BOOT + "config.PlatformConfig");
        Object platform = platformType.getMethod("getInstance", systemType).invoke(null, system);
        Class<?> codegenType = Class.forName(BOOT + "codegenerator.CodeGenerator");
        Object codegen = codegenType.getConstructor(platformType).newInstance(platform);
        Properties effective = (Properties) codegenType.getMethod("getPlatformProperties").invoke(codegen);
        String processor = effective.getProperty("generator.beans.post.processor.class", "");
        if (!processor.isBlank() && !DEFAULT_PROCESSOR.equals(processor)) throw new IOException("Custom DTO postprocessor");

        Class<?> extension = Class.forName(BOOT + "config.ExtensionInfo");
        var external = extension.getMethod("isExternalExtension");
        var name = extension.getMethod("getName");
        var directory = extension.getMethod("getExtensionDirectory");
        Map<String, String> extensions = new LinkedHashMap<>();
        Properties inputs = new Properties();
        for (Object item : (List<?>) platformType.getMethod("getExtensionInfosInBuildOrder").invoke(platform)) {
            String extName = (String) name.invoke(item);
            String extPath = ((File) directory.invoke(item)).getPath();
            inputs.setProperty(extName, extPath);
            if (!Boolean.TRUE.equals(external.invoke(item))) {
                extensions.put(extName, extPath);
            }
        }

        Class<?> generatorType = Class.forName(BOOT + "beangenerator.BeanGenerator");
        Object generator = generatorType.getConstructor(String.class, String.class, Map.class)
                .newInstance(properties.get("platformhome"), destination.toString(), extensions);
        Class<?> processorType = Class.forName(BOOT + "beangenerator.BeansPostProcessor");
        Object configured = processor.isBlank() ? null : Class.forName(DEFAULT_PROCESSOR).getConstructor().newInstance();
        generatorType.getMethod("setBeansPostProcessor", processorType).invoke(generator, configured);
        if (!Boolean.TRUE.equals(generatorType.getMethod("generate").invoke(generator)))
            throw new IOException("Native DTO generation did not complete");
        try (var out = Files.newOutputStream(destination.resolveSibling("extensions.properties"))) {
            inputs.store(out, "Native extension inputs");
        }
    }
}
