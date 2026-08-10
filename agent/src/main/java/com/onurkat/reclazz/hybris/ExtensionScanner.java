/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import com.onurkat.reclazz.ui.StatusReporter;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import org.w3c.dom.*;

/**
 * Scans the SAP Commerce installation to discover extensions.
 *
 * Parses localextensions.xml to find active extensions,
 * then reads each extension's extensioninfo.xml for metadata.
 */
public class ExtensionScanner {

    private final Path platformHome;
    private final Path configDir;
    private final DocumentBuilderFactory xmlFactory;

    // Known platform extension directories
    private static final List<String> PLATFORM_EXT_DIRS = List.of(
            "ext", "modules"
    );

    public ExtensionScanner(Path platformHome, Path configDir) {
        this.platformHome = platformHome;
        this.configDir = configDir;
        try {
            this.xmlFactory = DocumentBuilderFactory.newInstance();
            // XXE prevention
            this.xmlFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            this.xmlFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            this.xmlFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            this.xmlFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            this.xmlFactory.setXIncludeAware(false);
            this.xmlFactory.setExpandEntityReferences(false);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create XML parser factory", e);
        }
    }

    private DocumentBuilder newDocumentBuilder() {
        try {
            return xmlFactory.newDocumentBuilder();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create XML parser", e);
        }
    }

    /**
     * Scan for all active extensions.
     */
    public Map<String, ExtensionInfo> scanExtensions() throws IOException {
        Map<String, ExtensionInfo> extensions = new LinkedHashMap<>();

        // Parse localextensions.xml
        Path localExtXml = configDir.resolve("localextensions.xml");
        Set<String> activeExtensionNames = new HashSet<>();
        Map<String, Path> extensionPaths = new HashMap<>();

        if (Files.exists(localExtXml)) {
            parseLocalExtensions(localExtXml, activeExtensionNames, extensionPaths);
        } else {
            StatusReporter.warn("localextensions.xml not found at: " + localExtXml);
        }

        // Build an index of all available extensions by scanning known directories
        Map<String, Path> allExtensions = indexAllExtensions();

        // Merge paths from localextensions.xml
        allExtensions.putAll(extensionPaths);

        // Process active extensions
        for (String extName : activeExtensionNames) {
            Path extPath = allExtensions.get(extName);
            if (extPath == null) {
                StatusReporter.warn("Extension not found: " + extName);
                continue;
            }

            ExtensionInfo info = parseExtensionInfo(extName, extPath, allExtensions);
            if (info != null) {
                extensions.put(extName, info);
            }
        }

        // Also scan for custom extensions in the bin/custom directory
        Path customDir = platformHome.getParent().resolve("custom");
        if (Files.isDirectory(customDir)) {
            scanCustomExtensions(customDir, extensions, allExtensions);
        }

        return extensions;
    }

    private void parseLocalExtensions(Path xmlFile, Set<String> names, Map<String, Path> paths) {
        try {
            DocumentBuilder builder = newDocumentBuilder();
            Document doc = builder.parse(xmlFile.toFile());

            NodeList extensionNodes = doc.getElementsByTagName("extension");
            for (int i = 0; i < extensionNodes.getLength(); i++) {
                Element elem = (Element) extensionNodes.item(i);
                String name = elem.getAttribute("name");
                String dir = elem.getAttribute("dir");

                if (name != null && !name.isBlank()) {
                    names.add(name);
                }

                if (dir != null && !dir.isBlank()) {
                    Path extDir = Paths.get(resolveVariables(dir));
                    if (Files.isDirectory(extDir)) {
                        // Extract extension name from dir if name not specified
                        if (name == null || name.isBlank()) {
                            name = extDir.getFileName().toString();
                            names.add(name);
                        }
                        paths.put(name, extDir);
                    }
                }
            }
        } catch (Exception e) {
            StatusReporter.error("Failed to parse localextensions.xml: " + e.getMessage());
        }
    }

    private Map<String, Path> indexAllExtensions() {
        Map<String, Path> index = new HashMap<>();
        Path binDir = platformHome.getParent();

        // Scan platform extensions
        for (String dir : PLATFORM_EXT_DIRS) {
            Path extRoot = binDir.resolve(dir);
            if (Files.isDirectory(extRoot)) {
                scanExtensionDirectory(extRoot, index);
            }
        }

        // Scan custom extensions
        Path customDir = binDir.resolve("custom");
        if (Files.isDirectory(customDir)) {
            scanExtensionDirectory(customDir, index);
        }

        // Platform itself is an extension
        index.put("platform", platformHome);

        return index;
    }

    /**
     * Walks {@code root} looking for extensioninfo.xml files. Hybris places
     * extensions at varying depths under {@code bin/modules}, including
     * grouped/deprecated subdirectories like
     * {@code bin/modules/event-tracking/deprecated/eventtrackingmodel/}.
     * That's depth 4 from {@code bin/modules}, so our previous limit of 3
     * was missing those (~21 of them in a typical SAP Commerce install,
     * producing "Extension not found" warnings on every startup). Use an
     * unbounded walk — Hybris module trees aren't deep enough for this to
     * be expensive.
     *
     * FOLLOW_LINKS: custom extensions are commonly symlinked into
     * bin/custom from a separate working copy; without following links
     * those extensions are silently invisible to the watcher. Symlink
     * cycles surface as FileSystemLoopException (wrapped in
     * UncheckedIOException mid-stream) — caught below so a single bad
     * link degrades to a partial index instead of aborting the scan.
     */
    private void scanExtensionDirectory(Path root, Map<String, Path> index) {
        try (Stream<Path> stream = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) {
            stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().equals("extensioninfo.xml"))
                    .forEach(extInfoFile -> {
                        Path extDir = extInfoFile.getParent();
                        try {
                            String name = extractExtensionName(extInfoFile);
                            if (name != null) {
                                index.put(name, extDir);
                            }
                        } catch (Exception e) {
                            // Skip unparseable extensions
                        }
                    });
        } catch (IOException | UncheckedIOException e) {
            StatusReporter.warn("Extension scan incomplete under " + root + ": " + e.getMessage());
        }
    }

    private static final java.util.regex.Pattern SAFE_EXTENSION_NAME =
            java.util.regex.Pattern.compile("[a-zA-Z0-9_.-]+");

    private String extractExtensionName(Path extensionInfoXml) throws Exception {
        DocumentBuilder builder = newDocumentBuilder();
        Document doc = builder.parse(extensionInfoXml.toFile());

        NodeList nodes = doc.getElementsByTagName("extension");
        if (nodes.getLength() > 0) {
            String name = ((Element) nodes.item(0)).getAttribute("name");
            if (name != null && SAFE_EXTENSION_NAME.matcher(name).matches()) {
                return name;
            }
        }
        return null;
    }

    private ExtensionInfo parseExtensionInfo(String name, Path extPath, Map<String, Path> allExtensions) {
        Path extInfoFile = extPath.resolve("extensioninfo.xml");

        boolean hasCoreModule = Files.isDirectory(extPath.resolve("src"));
        boolean hasWebModule = Files.isDirectory(extPath.resolve("web").resolve("src"));
        List<String> requiredExtensions = new ArrayList<>();

        if (Files.exists(extInfoFile)) {
            try {
                DocumentBuilder builder = newDocumentBuilder();
                Document doc = builder.parse(extInfoFile.toFile());

                // Check for coremodule and webmodule elements
                NodeList coreModules = doc.getElementsByTagName("coremodule");
                if (coreModules.getLength() > 0) {
                    hasCoreModule = true;
                }
                NodeList webModules = doc.getElementsByTagName("webmodule");
                if (webModules.getLength() > 0) {
                    hasWebModule = true;
                }

                // Parse requires-extension
                NodeList reqExts = doc.getElementsByTagName("requires-extension");
                for (int i = 0; i < reqExts.getLength(); i++) {
                    Element elem = (Element) reqExts.item(i);
                    String reqName = elem.getAttribute("name");
                    if (reqName != null && !reqName.isBlank()) {
                        requiredExtensions.add(reqName);
                    }
                }
            } catch (Exception e) {
                StatusReporter.warn("Failed to parse extensioninfo.xml for " + name);
            }
        }

        // Determine if custom (not under platform directories)
        // Use Path-based comparison to work on both Unix and Windows
        Path binDir = platformHome.getParent();
        boolean isCustom = true;
        try {
            Path normalizedExt = extPath.toAbsolutePath().normalize();
            for (String platformDir : List.of("platform", "modules", "ext")) {
                Path platformSubDir = binDir.resolve(platformDir).toAbsolutePath().normalize();
                if (normalizedExt.startsWith(platformSubDir)) {
                    isCustom = false;
                    break;
                }
            }
        } catch (Exception e) {
            // Fall back to string-based check if path normalization fails
            String pathStr = extPath.toString().replace('\\', '/');
            isCustom = !pathStr.contains("/bin/platform/") &&
                       !pathStr.contains("/bin/modules/") &&
                       !pathStr.contains("/bin/ext/");
        }

        return new ExtensionInfo(name, extPath, requiredExtensions, hasCoreModule, hasWebModule, isCustom);
    }

    private void scanCustomExtensions(Path customDir, Map<String, ExtensionInfo> extensions,
                                       Map<String, Path> allExtensions) {
        try (Stream<Path> stream = Files.walk(customDir, 3, FileVisitOption.FOLLOW_LINKS)) {
            stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().equals("extensioninfo.xml"))
                    .forEach(extInfoFile -> {
                        try {
                            String name = extractExtensionName(extInfoFile);
                            if (name != null && !extensions.containsKey(name)) {
                                Path extDir = extInfoFile.getParent();
                                ExtensionInfo info = parseExtensionInfo(name, extDir, allExtensions);
                                if (info != null) {
                                    extensions.put(name, info);
                                }
                            }
                        } catch (Exception ignored) {}
                    });
        } catch (IOException | UncheckedIOException e) {
            StatusReporter.warn("Custom extension scan incomplete under " + customDir + ": " + e.getMessage());
        }
    }

    private String resolveVariables(String value) {
        // Replace ${HYBRIS_BIN_DIR} and similar variables
        String result = value;
        String hybrisBinDir = System.getenv("HYBRIS_BIN_DIR");
        if (hybrisBinDir != null) {
            result = result.replace("${HYBRIS_BIN_DIR}", hybrisBinDir);
        }
        String hybrisConfigDir = System.getenv("HYBRIS_CONFIG_DIR");
        if (hybrisConfigDir != null) {
            result = result.replace("${HYBRIS_CONFIG_DIR}", hybrisConfigDir);
        }
        return result;
    }
}
