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
import com.onurkat.reclazz.hybris.PlatformTenant;
import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.Plural;

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

    private static final long MAX_IMPEX_SIZE = 50 * 1024 * 1024; // 50 MB

    /**
     * A REMOVE mode header: the keyword at the start of a line, followed by
     * the type it deletes. Header lines start with the mode keyword, so this
     * does not match the word appearing in a value or a comment, both of
     * which begin with something else.
     */
    private static final java.util.regex.Pattern REMOVE_HEADER =
            java.util.regex.Pattern.compile("(?im)^[ \\t]*\"?REMOVE[ \\t]+\\S");

    private final boolean allowRemove;

    public ImpexAutoImporter() {
        this(false);
    }

    public ImpexAutoImporter(boolean allowRemove) {
        this.allowRemove = allowRemove;
    }

    /**
     * The line number of the first REMOVE header, or -1 when there is none.
     * Reported rather than just counted: "line 14" is something you can go
     * and look at.
     */
    static int firstRemoveHeaderLine(String content) {
        String[] lines = content.split("\\R", -1);
        java.util.Map<String, String> macros = new java.util.TreeMap<>(
                java.util.Comparator.comparingInt(String::length).reversed()
                        .thenComparing(java.util.Comparator.naturalOrder()));
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].stripLeading();
            if (i == 0 && line.startsWith("\uFEFF")) line = line.substring(1).stripLeading();
            if (line.startsWith("#") || line.startsWith(";") || line.isBlank()) continue;
            var definition = java.util.regex.Pattern.compile("^(\\$[^=;\\s]+)[ \\t]*=(.*)$")
                    .matcher(line);
            if (definition.matches()) {
                macros.put(definition.group(1), definition.group(2));
                continue;
            }
            // Expand only a possible header. A bounded expansion also refuses
            // recursive/unresolved header macros instead of certifying them safe.
            for (int round = 0; round < 32; round++) {
                String previous = line;
                for (var macro : macros.entrySet()) {
                    // Avoid expanding a recursive macro into an unbounded allocation.
                    if (line.contains(macro.getKey())) {
                        long occurrences = (line.length() - line.replace(macro.getKey(), "").length()) / macro.getKey().length();
                        long expandedSize = line.length() + occurrences * ((long) macro.getValue().length() - macro.getKey().length());
                        if (expandedSize > 65536) return i + 1;
                        line = line.replace(macro.getKey(), macro.getValue());
                    }
                }
                if (line.equals(previous)) break;
            }
            String firstCell = line.split(";", 2)[0];
            if (REMOVE_HEADER.matcher(line).find() || firstCell.contains("$")) return i + 1;
        }
        return -1;
    }

    /**
     * Import an ImpEx file into the running system.
     */
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

            String content;
            try {
                content = Files.readString(impexFile);
            } catch (java.nio.charset.MalformedInputException notUtf8) {
                // Guessing would be worse than refusing. The content is
                // re-encoded below and handed to the platform's import
                // service, so a wrong guess at the encoding writes wrong
                // characters into the database. What the developer saw
                // instead of this was "Input length = 1".
                StatusReporter.error("ImpEx not imported: " + impexFile.getFileName()
                        + " is not UTF-8, and auto-import reads UTF-8 because it hands the"
                        + " content to the platform re-encoded. Save the file as UTF-8, or"
                        + " import it from HAC, which reads the encoding you choose there.");
                return;
            }

            if (content.isBlank()) {
                StatusReporter.info("ImpEx file is empty, skipping: " + impexFile.getFileName());
                return;
            }

            // Auto-import runs against the live database on save, with no
            // confirmation step and nothing that undoes it. INSERT and UPDATE
            // are what the edit-and-see-it loop is for; deleting rows because
            // a file was saved is a different act, and one nobody asked for by
            // turning auto-import on.
            int removeLine = firstRemoveHeaderLine(content);
            if (removeLine > 0 && !allowRemove) {
                StatusReporter.warn("ImpEx not imported: " + impexFile.getFileName()
                        + " has a REMOVE or unresolved macro header at line " + removeLine
                        + ". Auto-import will not delete data. Import it from HAC, or "
                        + "pass impexAllowRemove=true to the agent if you mean it.");
                return;
            }
            if (removeLine > 0) {
                StatusReporter.warn("ImpEx " + impexFile.getFileName()
                        + " contains a REMOVE or unresolved macro header (line " + removeLine + "); importing because "
                        + "impexAllowRemove is set.");
            }

            // Find the Hybris global context via the holder — the agent's own
            // classloader (system CL) cannot see de.hybris.* classes, so
            // Registry and friends must be loaded through the CONTEXT's
            // classloader (this used to fail with ClassNotFoundException and
            // the misleading "requires running server" warning).
            Object appContext = null;
            Object importService = null;
            for (Object candidate : ApplicationContextHolder.getAllContexts()) {
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
            if (!PlatformTenant.ensureActive(hybrisCl)) {
                StatusReporter.warn("ImpEx not imported: platform tenant unavailable; save again to retry.");
                return;
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
                // How many lines were refused, and not what was in them.
                //
                // getUnresolvedLines() hands back the platform's own rows, and
                // printing them printed the data: an ImpEx that loads customers
                // carries names, e-mail addresses and postal addresses, and
                // this line put them in the server console, over the status
                // socket, into the IDE tool window, and from there into any log
                // a developer exports and attaches to a ticket. Nobody chose
                // that, and under the GDPR and the KVKK it is processing that
                // nothing declares and nothing minimises.
                //
                // The count is what tells the developer their import failed.
                // The detail is in HAC, where the platform already decides who
                // may look at it.
                int refused = countOf(result);
                StatusReporter.error("ImpEx import had errors: "
                        + (refused < 0
                                ? "some lines were not resolved"
                                : Plural.of(refused, "line")
                                  + " could not be resolved")
                        + " in " + impexFile.getFileName()
                        + ". The lines themselves are not printed here, because an ImpEx "
                        + "carries data about people; HAC shows them under ImpEx Import.");
            } else {
                StatusReporter.success("ImpEx imported: " + impexFile.getFileName());
            }

        } catch (ClassNotFoundException e) {
            StatusReporter.warn("Hybris import classes not available. " +
                    "ImpEx auto-import requires running server.");
        } catch (Exception e) {
            StatusReporter.error("Failed to import ImpEx " + impexFile.getFileName() + ": " + Failures.describe(e));
        }
    }
    /**
     * How many lines the platform refused, without touching what is in them.
     *
     * <p>A count is safe from any shape the platform returns; a
     * {@code toString} is not, and this agent is compiled against no version of
     * it in particular.
     *
     * @return the number of unresolved lines, or -1 when this platform does not
     *         answer in a way that can be counted
     */
    private static int countOf(Object result) {
        try {
            Object lines = result.getClass().getMethod("getUnresolvedLines").invoke(result);
            if (lines instanceof java.util.Collection<?> collection) return collection.size();
            if (lines == null) return 0;
            return -1;
        } catch (Throwable notAvailable) {
            return -1;
        }
    }


}
