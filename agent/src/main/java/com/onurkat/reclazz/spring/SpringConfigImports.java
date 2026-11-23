/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.onurkat.reclazz.spring.PropertyChangeCheck.call;

/**
 * R38: a saved Boot configuration document that adds, removes or reorders {@code spring.config.import} entries.
 *
 * <p>The result must equal what a restart on the saved files builds. Measured on Boot 2.7 (ConfigImportOrderProbe):
 * <ul>
 *   <li>a document's imports sit directly above that document, a later entry above an earlier one, and each imported
 *       document is followed (above it) by its own imports before the next sibling, recursively;</li>
 *   <li>an unprefixed location resolves against the importing file's directory; a relative {@code file:} location
 *       resolves against the JVM's working directory;</li>
 *   <li>a directory location ({@code .../}) loads {@code <spring.config.name>.yaml, .yml, .properties} from it (later
 *       above earlier), a {@code *} segment expands to its subdirectories in name order, and a file location also loads
 *       its profile-specific companions ({@code name-<profile>.ext}) above it for every active profile;</li>
 *   <li>{@code configtree:} adds one {@code Config tree '<dir>'} source;</li>
 *   <li>sources are named {@code Config resource '<resource>' via location '<entry as written>'}, Boot's loader adding
 *       {@code (document #n)} for multi-document files.</li>
 * </ul>
 * A missing required location holds the save: a restart would fail on it too. Nothing is changed then.
 */
final class SpringConfigImports {
    private SpringConfigImports() { }

    static final String KEY = "spring.config.import";
    private static final String ORIGIN_SOURCE = "org.springframework.boot.env.OriginTrackedMapPropertySource";
    private static final String PROFILE = "spring.config.activate.on-profile";
    /** Lowest precedence first: Boot's loaders list properties, xml, yml, yaml and the earlier extension wins. */
    private static final List<String> EXTENSIONS = List.of("yaml", "yml", "xml", "properties");
    private static final int MAX_DEPTH = 32;

    /** The source names each importing document contributed through a live save, so a later save removes exactly them. */
    private static final Map<Object, Map<String, Contribution>> CONTRIBUTED = Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** What one importing document's imports put into the live environment: the entries as applied and the source names. */
    private record Contribution(List<String> entries, Set<String> names) { }

    /**
     * The import entries the live environment currently reflects for this document: the last applied list when a save
     * applied one (even if that save's other keys did not bind), otherwise {@code accepted}.
     */
    static List<String> liveEntries(Object live, String anchor, List<String> accepted) {
        Map<String, Contribution> recorded = CONTRIBUTED.get(live);
        Contribution contribution = recorded == null ? null : recorded.get(anchor);
        return contribution == null ? accepted : contribution.entries();
    }

    /** Whether this key is part of the import list ({@code spring.config.import} or {@code spring.config.import[n]}). */
    static boolean isImportKey(String key) {
        return key.equals(KEY) || (key.startsWith(KEY + "[") && key.endsWith("]"));
    }

    /** The import entries a document declares, in order. */
    static List<String> entries(Map<String, Object> values) {
        List<String> result = new ArrayList<>();
        Object direct = values.get(KEY);
        if (direct != null) for (String part : String.valueOf(direct).split(",")) if (!part.isBlank()) result.add(part.trim());
        for (int i = 0; values.containsKey(KEY + "[" + i + "]"); i++) {
            String entry = String.valueOf(values.get(KEY + "[" + i + "]")).trim();
            if (!entry.isEmpty()) result.add(entry);
        }
        return result;
    }

    /**
     * What one save does to the imports of one document: the sources to remove and the sources to place, in insertion
     * order (lowest first), directly above {@code anchor}.
     */
    record Plan(Object live, String anchor, List<String> entries, Set<String> obsolete, List<Object> ordered, Set<Path> files,
                Set<Path> released, Map<String, Object> before, Map<String, Object> after) {
        /** Applies the plan to a MutablePropertySources; repeatable on the live and the candidate environment. */
        void apply(Object sources) throws Exception {
            for (String name : obsolete) if (call(sources, "contains", name) instanceof Boolean b && b) call(sources, "remove", name);
            String below = anchor;
            for (Object source : ordered) {
                call(sources, "addBefore", below, source);
                below = (String) call(source, "getName");
            }
        }

        /** Records what the live environment now holds for this document. */
        void committed() throws Exception {
            Set<String> names = new LinkedHashSet<>();
            for (Object source : ordered) names.add((String) call(source, "getName"));
            Map<String, Contribution> recorded = CONTRIBUTED.computeIfAbsent(live, k -> new ConcurrentHashMap<>());
            for (String gone : obsolete) if (!names.contains(gone)) recorded.remove(gone);
            recorded.put(anchor, new Contribution(List.copyOf(entries), names));
        }
    }

    static Plan plan(ClassLoader loader, Object live, Path importer, String anchor, List<String> oldEntries,
                     List<String> newEntries) throws Exception {
        Map<String, Object> liveByName = new LinkedHashMap<>();
        for (Object source : (Iterable<?>) call(live, "getPropertySources")) liveByName.put((String) call(source, "getName"), source);
        Base base = Base.of(anchor, importer);

        // What this document contributed so far, and recursively what those imported documents contributed: recorded by
        // earlier saves, otherwise what Boot built from the old entries at startup (named the same way).
        Map<String, Contribution> recorded = CONTRIBUTED.getOrDefault(live, Map.of());
        Set<String> obsolete = new LinkedHashSet<>();
        Contribution own = recorded.get(anchor);
        if (own != null) obsolete.addAll(own.names());
        else {
            Expansion old = new Expansion(loader, live, false);
            // The old files may have changed or gone since; whatever still resolves names what to remove.
            try { old.expandAll(base, oldEntries, 0); } catch (IllegalStateException unreadable) { /* names kept so far */ }
            for (Object source : old.ordered) obsolete.add((String) call(source, "getName"));
            obsolete.addAll(old.names);
        }
        java.util.ArrayDeque<String> walk = new java.util.ArrayDeque<>(obsolete);
        while (!walk.isEmpty()) {
            Contribution nested = recorded.get(walk.pop());
            if (nested != null) for (String name : nested.names()) if (obsolete.add(name)) walk.push(name);
        }
        obsolete.retainAll(liveByName.keySet());
        obsolete.remove(anchor);

        // A source this document no longer needs but another importing document still does: Boot loads it once, at
        // the first importer's place, which this save cannot verify. Held rather than guessed.
        Set<Path> stillImported = importedElsewhere(loader, live, liveByName, anchor, obsolete);
        for (String name : obsolete)
            if (fileOf(name) != null && stillImported.contains(fileOf(name)))
                throw new IllegalStateException("The import " + name + " is also imported by another file; restart required");

        Map<String, Object> before = new LinkedHashMap<>();
        for (String name : obsolete) before.putAll(valuesOf(liveByName.get(name)));

        Set<String> seen = new LinkedHashSet<>();
        Expansion next = new Expansion(loader, live, true);
        for (String entry : newEntries)
            if (!seen.add(entry)) throw new IllegalStateException("The import " + entry + " is listed twice; restart required");
        next.expandAll(base, newEntries, 0);
        // Boot loads a resource once. One already loaded by a source this save does not replace stays where it is.
        Set<Path> loadedElsewhere = new LinkedHashSet<>();
        for (String name : liveByName.keySet()) if (!obsolete.contains(name) && fileOf(name) != null) loadedElsewhere.add(fileOf(name));
        Map<String, Object> after = new LinkedHashMap<>();
        for (Object source : next.ordered) {
            String name = (String) call(source, "getName");
            Path file = fileOf(name);
            if ((!obsolete.contains(name) && liveByName.containsKey(name)) || (file != null && loadedElsewhere.contains(file)))
                throw new IllegalStateException("The import " + name + " is already loaded by another config file; restart required");
            obsolete.add(name);
            after.putAll(valuesOf(source));
        }
        Set<Path> released = new LinkedHashSet<>();
        for (String name : obsolete) { Path file = fileOf(name); if (file != null && !next.files.contains(file)) released.add(file); }
        return new Plan(live, anchor, List.copyOf(newEntries), obsolete, List.copyOf(next.ordered), Set.copyOf(next.files),
                Set.copyOf(released), before, after);
    }

    /** The canonical file behind a Boot source name ({@code file [...]}, {@code class path resource [...]}), or null. */
    static Path fileOf(String name) {
        java.util.regex.Matcher file = FILE_NAME.matcher(name);
        try {
            if (file.find()) return Path.of(file.group(1)).toFile().getCanonicalFile().toPath();
        } catch (Exception unreadable) { /* not a file */ }
        return null;
    }
    private static final java.util.regex.Pattern FILE_NAME = java.util.regex.Pattern.compile("^Config resource 'file \\[(.+?)\\]'");

    /** Files that importing documents other than {@code anchor} (and other than the ones being removed) import. */
    private static Set<Path> importedElsewhere(ClassLoader loader, Object live, Map<String, Object> liveByName, String anchor,
                                               Set<String> removing) throws Exception {
        Set<Path> found = new LinkedHashSet<>();
        for (var named : liveByName.entrySet()) {
            String name = named.getKey();
            if (name.equals(anchor) || removing.contains(name) || !name.startsWith("Config resource ")) continue;
            List<String> nested = entries(valuesOf(named.getValue()));
            if (nested.isEmpty()) continue;
            Expansion other = new Expansion(loader, live, false);
            try { other.expandAll(Base.of(name, null), nested, 0); } catch (IllegalStateException unreadable) { /* skip */ }
            found.addAll(other.files);
        }
        return found;
    }

    /** Where an importing document's unprefixed imports resolve: its directory, or its classpath folder. */
    private record Base(Path dir, String classpathDir) {
        private static final java.util.regex.Pattern FILE = java.util.regex.Pattern.compile("^Config resource 'file \\[(.+?)\\]' via location");
        private static final java.util.regex.Pattern CLASSPATH = java.util.regex.Pattern.compile("^Config resource 'class path resource \\[(.+?)\\]' via location");
        static Base of(String name, Path fallback) {
            java.util.regex.Matcher file = FILE.matcher(name);
            if (file.find()) return new Base(dirOf(Path.of(file.group(1))), null);
            java.util.regex.Matcher classpath = CLASSPATH.matcher(name);
            if (classpath.find()) {
                String path = classpath.group(1);
                int slash = path.lastIndexOf('/');
                return new Base(null, slash < 0 ? "" : path.substring(0, slash + 1));
            }
            return new Base(dirOf(fallback), null);
        }
    }

    private static Path dirOf(Path file) { return file == null ? null : file.getParent(); }

    /** One resource an import names; {@code base} is where its own unprefixed imports resolve (Boot's spelling, not canonical). */
    private record Resource(Object resource, String name, Path file, Base base, String extension, boolean tree) { }

    /** Builds the ordered sources an import list produces, recursively, the way Boot orders them. */
    private static final class Expansion {
        final ClassLoader loader; final Object live; final boolean strict;
        final List<Object> ordered = new ArrayList<>();
        final Set<Path> files = new LinkedHashSet<>();
        /** Names expected even when a file could not be read (old entries only). */
        final Set<String> names = new LinkedHashSet<>();
        final Set<String> imported = new LinkedHashSet<>();

        Expansion(ClassLoader loader, Object live, boolean strict) {
            this.loader = loader; this.live = live; this.strict = strict;
        }

        /**
         * Boot resolves an importer's whole list before it processes any imported document, and loads a resource once: a
         * file reached again later (deeper, or through a later sibling) is skipped, so the first claim names and places it
         * (measured: main imports [e, shared] and e imports shared -> shared via main's entry, above e).
         */
        void expandAll(Base base, List<String> entries, int depth) throws Exception {
            if (depth > MAX_DEPTH) throw new IllegalStateException("Imports nest deeper than " + MAX_DEPTH + "; restart required");
            List<Resource> claimed = new ArrayList<>();
            for (String entry : entries) {
                List<Resource> resources;
                try { resources = resolve(base, entry); }
                catch (IllegalStateException failure) { if (strict) throw failure; continue; }
                for (Resource resource : resources)
                    if (imported.add(resource.file() != null ? resource.file().toString() : resource.name())) claimed.add(resource);
            }
            for (Resource resource : claimed) {
                if (resource.file() != null) files.add(resource.file());
                if (resource.tree()) { ordered.add(tree(resource)); continue; }
                for (Object document : load(resource)) {
                    ordered.add(document);
                    Map<String, Object> values = valuesOf(document);
                    for (String key : values.keySet())
                        if ((key.startsWith("spring.config.") && !key.equals(PROFILE) && !isImportKey(key)) || key.startsWith("spring.profiles"))
                            throw new IllegalStateException("The imported file " + (resource.file() != null ? resource.file().getFileName() : resource.name()) + " sets " + key
                                    + "; config locations and profile changes in imported files require a restart");
                    List<String> nested = entries(values);
                    if (!nested.isEmpty()) expandAll(resource.base(), nested, depth + 1);
                }
            }
        }

        private List<Resource> resolve(Base importer, String entry) throws Exception {
            String location = entry;
            boolean optional = location.startsWith("optional:");
            if (optional) location = location.substring("optional:".length());
            if (location.startsWith("configtree:")) {
                Path dir = absolute(location.substring("configtree:".length()), null);
                if (!Files.isDirectory(dir)) return missing(entry, optional);
                String trimmed = dir.toString().replaceAll("/+$", "");
                return List.of(new Resource(dir, "Config tree '" + trimmed + "'", dir.toRealPath(), new Base(dir, null), null, true));
            }
            boolean classpath = location.startsWith("classpath:");
            String path;
            Path base;
            if (classpath) { path = location.substring("classpath:".length()); base = null; }
            else if (location.startsWith("file:")) { path = location.substring("file:".length()); base = null; }
            else if (location.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*") && !location.matches("^[a-zA-Z]:[\\\\/].*"))
                throw new IllegalStateException("The import " + entry + " uses a custom location resolver; restart required");
            else if (importer.classpathDir() != null) {
                // Boot: an unprefixed import of a classpath resource is relative to that resource's classpath folder.
                classpath = true; path = importer.classpathDir() + location; base = null;
            }
            else { path = location; base = importer.dir(); }
            if (classpath) return classpathResources(entry, path.replaceFirst("^/+", ""), optional);

            List<Resource> found = new ArrayList<>();
            boolean directory = path.endsWith("/");
            List<Path> targets = wildcard(absolute(path, base), entry, optional);
            if (targets == null) return missing(entry, optional);
            for (Path target : targets) {
                if (directory) {
                    if (!Files.isDirectory(target)) { if (targets.size() == 1) return missing(entry, optional); continue; }
                    for (String name : configNames()) {
                        for (String extension : EXTENSIONS) fileResource(target.resolve(name + "." + extension), entry, extension, found);
                        for (String profile : profiles())
                            for (String extension : EXTENSIONS) fileResource(target.resolve(name + "-" + profile + "." + extension), entry, extension, found);
                    }
                } else {
                    String file = target.getFileName().toString();
                    int dot = file.lastIndexOf('.');
                    String extension = dot < 0 ? "" : file.substring(dot + 1);
                    if (!EXTENSIONS.contains(extension))
                        throw new IllegalStateException("The import " + entry + " is not a .properties, .yml or .yaml file; restart required");
                    if (!Files.isRegularFile(target)) return missing(entry, optional);
                    fileResource(target, entry, extension, found);
                    String stem = file.substring(0, dot);
                    for (String profile : profiles())
                        fileResource(target.resolveSibling(stem + "-" + profile + "." + extension), entry, extension, found);
                }
            }
            if (found.isEmpty() && directory && !optional && targets.size() == 1 && !Files.isDirectory(targets.get(0))) return missing(entry, optional);
            return found;
        }

        private List<Resource> classpathResources(String entry, String path, boolean optional) throws Exception {
            if (path.contains("*") || path.endsWith("/"))
                throw new IllegalStateException("The import " + entry + " is a classpath directory or pattern; restart required");
            int dot = path.lastIndexOf('.');
            String extension = dot < 0 ? "" : path.substring(dot + 1);
            if (!EXTENSIONS.contains(extension))
                throw new IllegalStateException("The import " + entry + " is not a .properties, .yml or .yaml file; restart required");
            List<Resource> found = new ArrayList<>();
            List<String> paths = new ArrayList<>(List.of(path));
            for (String profile : profiles()) paths.add(path.substring(0, dot) + "-" + profile + "." + extension);
            for (String candidate : paths) {
                Object resource = Class.forName("org.springframework.core.io.ClassPathResource", true, loader)
                        .getConstructor(String.class, ClassLoader.class).newInstance(candidate, loader);
                if (!(boolean) call(resource, "exists")) {
                    if (candidate.equals(path)) return missing(entry, optional);
                    continue;
                }
                Path file;
                try { file = ((java.io.File) call(resource, "getFile")).getCanonicalFile().toPath(); }
                catch (Exception notAFile) {
                    throw new IllegalStateException("The import " + entry + " is not a file on disk, so its edits cannot be followed; restart required");
                }
                int slash = candidate.lastIndexOf('/');
                found.add(new Resource(resource, "Config resource '" + call(resource, "getDescription") + "' via location '" + entry + "'", file,
                        new Base(null, slash < 0 ? "" : candidate.substring(0, slash + 1)), extension, false));
            }
            return found;
        }

        private void fileResource(Path target, String entry, String extension, List<Resource> found) throws Exception {
            if (!Files.isRegularFile(target)) return;
            Object resource = Class.forName("org.springframework.core.io.FileSystemResource", true, loader)
                    .getConstructor(java.io.File.class).newInstance(target.toFile());
            found.add(new Resource(resource, "Config resource '" + call(resource, "getDescription") + "' via location '" + entry + "'",
                    target.toFile().getCanonicalFile().toPath(), new Base(target.getParent(), null), extension, false));
        }

        /** Expands one {@code *} path segment to the existing subdirectories in name order; null when nothing matches. */
        private List<Path> wildcard(Path path, String entry, boolean optional) throws Exception {
            String text = path.toString();
            int star = text.indexOf('*');
            if (star < 0) return List.of(path);
            if (text.indexOf('*', star + 1) >= 0) throw new IllegalStateException("The import " + entry + " has more than one wildcard; a restart would fail too");
            Path parent = Path.of(text.substring(0, star));
            String rest = text.substring(star + 1).replaceFirst("^/", "");
            if (!Files.isDirectory(parent)) return null;
            List<Path> dirs = new ArrayList<>();
            try (var list = Files.list(parent)) {
                // Boot skips ".."-prefixed directories (Kubernetes config map mounts keep their data there).
                list.filter(Files::isDirectory).filter(dir -> !dir.getFileName().toString().startsWith(".."))
                        .sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                        .forEach(dir -> dirs.add(rest.isEmpty() ? dir : dir.resolve(rest)));
            }
            return dirs.isEmpty() ? null : dirs;
        }

        private Path absolute(String path, Path base) {
            Path resolved = Path.of(path);
            if (resolved.isAbsolute()) return resolved.normalize();
            // Boot: unprefixed locations are relative to the importing resource, prefixed ones to the working directory.
            return (base != null ? base : Path.of(System.getProperty("user.dir"))).resolve(resolved).normalize();
        }

        private List<Resource> missing(String entry, boolean optional) {
            if (optional) return List.of();
            throw new IllegalStateException("The import " + entry + " names a location that does not exist; a restart would fail too");
        }

        private List<String> configNames() throws Exception {
            Object name = call(live, "getProperty", "spring.config.name");
            List<String> names = new ArrayList<>();
            if (name != null) for (String part : String.valueOf(name).split(",")) if (!part.isBlank()) names.add(part.trim());
            return names.isEmpty() ? List.of("application") : names;
        }

        /** The profiles Boot loads companions for: the active ones, or the default ones when none is active. */
        private List<String> profiles() throws Exception {
            String[] active = (String[]) call(live, "getActiveProfiles");
            return List.of(active.length > 0 ? active : (String[]) call(live, "getDefaultProfiles"));
        }

        private Object tree(Resource resource) throws Exception {
            // Boot's ConfigTreeConfigDataLoader trims one trailing new line from each value.
            Class<?> type = Class.forName("org.springframework.boot.env.ConfigTreePropertySource", true, loader);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object trim = Enum.valueOf((Class) Class.forName("org.springframework.boot.env.ConfigTreePropertySource$Option", true, loader),
                    "AUTO_TRIM_TRAILING_NEW_LINE");
            Object options = java.lang.reflect.Array.newInstance(trim.getClass(), 1);
            java.lang.reflect.Array.set(options, 0, trim);
            return type.getConstructor(String.class, Path.class, options.getClass()).newInstance(resource.name(), (Path) resource.resource(), options);
        }

        /** Loads a file with Boot's own loader; returns the active documents, lowest precedence first. */
        private List<Object> load(Resource resource) throws Exception {
            String loaderName = resource.extension().equals("properties") || resource.extension().equals("xml")
                    ? "PropertiesPropertySourceLoader" : "YamlPropertySourceLoader";
            Object parser = Class.forName("org.springframework.boot.env." + loaderName, true, loader).getConstructor().newInstance();
            List<?> documents = (List<?>) call(parser, "load", resource.name(), resource.resource());
            List<Object> active = new ArrayList<>();
            for (Object document : documents) {
                Map<String, Object> values = valuesOf(document);
                Object profile = values.get(PROFILE);
                if (profile != null) {
                    String expression = String.valueOf(profile);
                    if (expression.contains("${")) throw new IllegalStateException("Profile placeholders require a restart");
                    Class<?> profiles = Class.forName("org.springframework.core.env.Profiles", true, loader);
                    Object selector = profiles.getMethod("of", String[].class).invoke(null, (Object) new String[]{expression});
                    if (!(boolean) call(live, "acceptsProfiles", selector)) continue;
                }
                if (!document.getClass().getName().equals(ORIGIN_SOURCE))
                    throw new IllegalStateException("The imported file " + resource.name() + " did not load as a Boot source");
                active.add(document);
            }
            names.add(resource.name());
            return active;
        }
    }

    static Map<String, Object> valuesOf(Object source) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : (String[]) call(source, "getPropertyNames")) {
            Object value = call(source, "getProperty", key);
            if (value != null) result.put(key, value);
        }
        return result;
    }
}
