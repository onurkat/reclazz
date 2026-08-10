/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris.impex;

import com.onurkat.reclazz.hybris.HybrisContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Auto-imports changed ImpEx files into the running SAP Commerce instance.
 *
 * Uses the Hybris ImportService via reflection to import ImpEx files
 * without requiring HAC or restart.
 *
 * IMPORTANT: This feature is opt-in (autoImpex=true) because ImpEx imports
 * can modify data and should be used carefully in development.
 */
public class ImpexAutoImporter {

    /**
     * Import an ImpEx file into the running system.
     */
    private static final long MAX_IMPEX_SIZE = 50 * 1024 * 1024; // 50 MB

    public void importFile(Path impexFile, HybrisContext context) {
        try {
            if (!Files.exists(impexFile)) {
                StatusReporter.warn("ImpEx file not found: " + impexFile);
                return;
            }

            long fileSize = Files.size(impexFile);
            if (fileSize > MAX_IMPEX_SIZE) {
                StatusReporter.error("ImpEx file too large (" + (fileSize / (1024 * 1024)) + " MB, max 50 MB): " + impexFile.getFileName());
                return;
            }

            String content = Files.readString(impexFile);

            if (content.isBlank()) {
                StatusReporter.info("ImpEx file is empty, skipping: " + impexFile.getFileName());
                return;
            }

            // Find the Hybris global context via the holder — the agent's own
            // classloader (system CL) cannot see de.hybris.* classes, so
            // Registry and friends must be loaded through the CONTEXT's
            // classloader (this used to fail with ClassNotFoundException and
            // the misleading "requires running server" warning).
            Object appContext = null;
            Object importService = null;
            for (Object candidate : com.onurkat.reclazz.platform.ApplicationContextHolder.getAllContexts()) {
                try {
                    Method containsBean = candidate.getClass().getMethod("containsBean", String.class);
                    if ((Boolean) containsBean.invoke(candidate, "importService")) {
                        Method getBean = candidate.getClass().getMethod("getBean", String.class);
                        importService = getBean.invoke(candidate, "importService");
                        appContext = candidate;
                        break;
                    }
                } catch (Exception e) {
                    // One unusable context must not hide why the import
                    // eventually finds nothing.
                    StatusReporter.warn("Skipping a Spring context while looking for importService: " + e);
                }
            }
            if (appContext == null || importService == null) {
                StatusReporter.warn("ImportService not reachable in any live Spring context — ImpEx import skipped.");
                return;
            }

            // The watcher thread has no tenant — activate the master tenant
            // (Registry loaded via the context's classloader).
            ClassLoader hybrisCl = appContext.getClass().getClassLoader();
            Class<?> registryClass = Class.forName("de.hybris.platform.core.Registry", false, hybrisCl);
            Method hasCurrentTenant = registryClass.getMethod("hasCurrentTenant");
            if (!(Boolean) hasCurrentTenant.invoke(null)) {
                registryClass.getMethod("activateMasterTenant").invoke(null);
            }

            // Create ImpExResource from the file content
            Class<?> streamBasedClass = Class.forName(
                    "de.hybris.platform.servicelayer.impex.impl.StreamBasedImpExResource",
                    false, appContext.getClass().getClassLoader());

            // Use the string-based constructor
            Object impexResource = streamBasedClass
                    .getConstructor(java.io.InputStream.class, String.class)
                    .newInstance(
                            new java.io.ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            "UTF-8"
                    );

            // Import
            Class<?> impExResourceClass = Class.forName(
                    "de.hybris.platform.servicelayer.impex.ImpExResource",
                    false, appContext.getClass().getClassLoader());

            Method importData = importService.getClass().getMethod("importData", impExResourceClass);
            Object result = importData.invoke(importService, impexResource);

            // Check result
            Method isError = result.getClass().getMethod("isError");
            boolean hasError = (Boolean) isError.invoke(result);

            if (hasError) {
                Method getErrorMessage = result.getClass().getMethod("getUnresolvedLines");
                Object unresolvedLines = getErrorMessage.invoke(result);
                StatusReporter.error("ImpEx import had errors. Check unresolvedLines.");
                if (unresolvedLines != null) {
                    StatusReporter.error("  Unresolved: " + unresolvedLines);
                }
            } else {
                StatusReporter.success("ImpEx imported: " + impexFile.getFileName());
            }

        } catch (ClassNotFoundException e) {
            StatusReporter.warn("Hybris import classes not available. " +
                    "ImpEx auto-import requires running server.");
        } catch (Exception e) {
            StatusReporter.error("Failed to import ImpEx " + impexFile.getFileName() + ": " + e.getMessage());
        }
    }
}
