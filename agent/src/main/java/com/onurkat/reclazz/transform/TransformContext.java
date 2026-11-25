/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.config.WatchInventory;
import com.onurkat.reclazz.ui.StatusReporter;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.onurkat.reclazz.ui.Failures;

/**
 * Tracks which classes are watched for structural reload transformation.
 * Only custom extension classes are transformed, platform/library code is untouched.
 */
public class TransformContext {

    // Set of internal class names (e.g., "com/example/MyService") that should be transformed
    private final Set<String> watchedClassNames = ConcurrentHashMap.newKeySet();

    // Set of class output directories to scan for watchable classes
    private final Set<Path> watchedClassDirs = ConcurrentHashMap.newKeySet();

    private final Set<Path> backofficeClassDirs = ConcurrentHashMap.newKeySet();
    private final Set<String> backofficeClassNames = ConcurrentHashMap.newKeySet();

    private volatile java.util.function.Supplier<WatchInventory> inventory = () -> null;
    private final ThreadLocal<WatchInventory> snapshot = new ThreadLocal<>();
    private final Set<Path> knownOutputRoots = ConcurrentHashMap.newKeySet();
    private final Map<ClassLoader,Set<String>> rawOwners = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<String,Path> origins = new ConcurrentHashMap<>();

    public void bindRuntime(AgentConfig config,PlatformContext platform) {
        inventory=()->config.runtime().inventory();
        PlatformContext all=platform instanceof com.onurkat.reclazz.platform.HybrisPlatformContext h
                ? new com.onurkat.reclazz.platform.ScopeView(h,new AgentConfig.WatchScope(false,h.getHybrisContext().getExtensions().keySet())) : platform;
        all.getClassOutputDirs().values().forEach(dirs->dirs.forEach(dir->addRoot(knownOutputRoots,dir)));
        addPotentialRoots(all,knownOutputRoots);
    }
    private WatchInventory currentInventory() {
        WatchInventory captured=snapshot.get();return captured!=null?captured:inventory.get();
    }
    /** One transform invocation keeps its original revision even if CONFIG commits concurrently. */
    public AutoCloseable snapshot() {
        WatchInventory previous=snapshot.get(),next=currentInventory();
        if(next!=null)snapshot.set(next);
        return ()->{if(previous==null)snapshot.remove();else snapshot.set(previous);};
    }
    private static void addRoot(Set<Path> roots,Path root) {
        roots.add(root.toAbsolutePath().normalize());
        try{roots.add(root.toRealPath());}catch(IOException missing){/* A clean build may create it later. */}
    }
    private static void addPotentialRoots(PlatformContext view,Set<Path> roots) {
        if(view instanceof com.onurkat.reclazz.platform.HybrisPlatformContext h)
            for(var extension:h.getHybrisContext().getExtensions().values()) {
                if(!h.watchScope().includes(extension.getName()) || (h.watchScope().all()&&!extension.isCustom()))continue;
                addRoot(roots,extension.getPath().resolve("classes"));
                addRoot(roots,extension.getPath().resolve("web/webroot/WEB-INF/classes"));
                if(extension.hasBackofficeModule())addRoot(roots,extension.getPath().resolve("backoffice/classes"));
            }
    }
    /** Pure disk/census staging: no transformer, baseline or Runtime mutation. */
    public WatchInventory stageInventory(PlatformContext view,Class<?>[] loaded) throws IOException {
        WatchInventory old=currentInventory();
        Set<String> names=new HashSet<>(watchedClassNames),boNames=new HashSet<>(backofficeClassNames);
        Map<String,List<java.lang.ref.WeakReference<Class<?>>>> raw=new HashMap<>();
        Set<Class<?>> recorded=Collections.newSetFromMap(new IdentityHashMap<>());
        if(old!=null){
            names.addAll(old.names());boNames.addAll(old.backofficeNames());
            old.rawDefinitions().forEach((name,definitions)->{
                for(var reference:definitions){Class<?> type=reference.get();
                    if(type!=null&&!isTransformed(type)&&recorded.add(type))raw.computeIfAbsent(name,key->new ArrayList<>()).add(new java.lang.ref.WeakReference<>(type));}
            });
        }
        Set<Path> roots=new HashSet<>(),boRoots=new HashSet<>();
        addPotentialRoots(view,roots);
        if(view instanceof com.onurkat.reclazz.platform.HybrisPlatformContext h)
            h.getBackofficeClassDirs().values().forEach(dir->addRoot(boRoots,dir));
        for(var entry:view.getClassOutputDirs().entrySet()) {
            for(Path dir:entry.getValue()) {
                addRoot(roots,dir);
                if(!Files.isDirectory(dir))continue;
                boolean bo=boRoots.contains(dir.toAbsolutePath().normalize());
                try(var paths=Files.walk(dir)) {
                    var files=paths.filter(path->Files.isRegularFile(path)&&path.toString().endsWith(".class")).toList();
                    for(Path file:files){String name=dir.relativize(file).toString().replace(java.io.File.separatorChar,'/');
                        name=name.substring(0,name.length()-6);names.add(name);if(bo)boNames.add(name);}
                }catch(java.io.UncheckedIOException failure){throw failure.getCause();}
            }
        }
        for(Class<?> type:loaded){String name=type.getName().replace('.','/');
            if(names.contains(name)&&!isTransformed(type)&&recorded.add(type))
                raw.computeIfAbsent(name,key->new ArrayList<>()).add(new java.lang.ref.WeakReference<>(type));}
        return new WatchInventory(names,roots,boNames,boRoots,raw);
    }
    public int countRawDefinitions(PlatformContext view,Class<?>[] loaded) {
        Set<Path> roots=new HashSet<>();view.getClassOutputDirs().values().forEach(dirs->dirs.forEach(dir->addRoot(roots,dir)));
        int count=0;
        for(Class<?> type:loaded) {
            if(type.isArray()||type.isPrimitive()||isTransformed(type))continue;
            try{Path source=origin(type.getProtectionDomain());if(source!=null&&roots.contains(source))count++;}
            catch(SecurityException unavailable){/* No ownership evidence: never claim this definition is ours. */}
        }
        return count;
    }

    /** Record a pending/raw definition before another class can generate an unsafe call to it. */
    public void markRaw(ClassLoader loader,String name) {
        synchronized(rawOwners){rawOwners.computeIfAbsent(loader,key->new HashSet<>()).add(name);}
        synchronized(transformedOwners){var owners=transformedOwners.get(loader);if(owners!=null)owners.remove(name);}
    }
    private Path origin(java.security.ProtectionDomain domain) {
        try {
            var source=domain==null?null:domain.getCodeSource();var url=source==null?null:source.getLocation();
            if(url==null || !url.getProtocol().equals("file"))return null;
            String key=url.toExternalForm();Path value=origins.get(key);if(value!=null)return value;
            value=Path.of(url.toURI()).toAbsolutePath().normalize();origins.put(key,value);return value;
        }catch(Exception unavailable){return null;}
    }
    public boolean knownOutput(java.security.ProtectionDomain domain) {
        Path root=origin(domain);return root!=null&&knownOutputRoots.contains(root);
    }
    /** Admit a future compiled class only from an active output root with a real class file. */
    public boolean admitOutput(String name,java.security.ProtectionDomain domain) {
        WatchInventory scope=currentInventory();Path root=origin(domain);
        Set<Path> roots=scope==null?watchedClassDirs:scope.roots();
        if(root==null || !roots.contains(root) || !Files.isRegularFile(root.resolve(name+".class")))return false;
        addWatched(name);return true;
    }

    // Metadata for each transformed class (populated after transform)
    private final ConcurrentHashMap<String, ClassMetadata> classMetadata = new ConcurrentHashMap<>();

    /**
     * Populate watched class names from a PlatformContext.
     * Scans all class output directories for .class files.
     */
    public void populateFromPlatformContext(PlatformContext platformContext) {
        if (platformContext instanceof com.onurkat.reclazz.platform.HybrisPlatformContext hybris) {
            backofficeClassDirs.addAll(hybris.getBackofficeClassDirs().values());
        }
        for (var entry : platformContext.getClassOutputDirs().entrySet()) {
            for (Path dir : entry.getValue()) {
                if (Files.isDirectory(dir)) {
                    addRoot(watchedClassDirs,dir);
                    scanClassDirectory(dir);
                }
            }
        }
    }

    private void scanClassDirectory(Path classesDir) {
        try {
            Files.walkFileTree(classesDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.toString();
                    if (fileName.endsWith(".class")) {
                        Path relative = classesDir.relativize(file);
                        String internalName = relative.toString()
                                .replace(".class", "")
                                .replace(java.io.File.separatorChar, '/');
                        watchedClassNames.add(internalName);
                        if (backofficeClassDirs.contains(classesDir)) backofficeClassNames.add(internalName);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            StatusReporter.warn("Failed to scan class directory: " + classesDir + ": " + Failures.describe(e));
        }
    }

    /**
     * Classes that went through the transformer and came out instrumented.
     *
     * <p>Being watched is not the same as having been instrumented, and the
     * difference is not academic: a class loaded before the agent could reach
     * it is watched and untouched, and so is one whose own transform failed.
     * Code generated for a watched class must not assume the members the
     * transform adds are present on another one just because it is watched too.
     */
    private final java.util.Set<String> transformed =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Map<ClassLoader,Set<String>> transformedOwners = Collections.synchronizedMap(new WeakHashMap<>());
    public void markTransformed(ClassLoader loader,String internalName) {
        markTransformed(internalName);
        synchronized(rawOwners){var owners=rawOwners.get(loader);if(owners!=null){owners.remove(internalName);if(owners.isEmpty())rawOwners.remove(loader);}}
        synchronized(transformedOwners){transformedOwners.computeIfAbsent(loader,key->new HashSet<>()).add(internalName);}
    }
    public boolean isTransformed(Class<?> type) {
        if(type==null)return false;
        synchronized(transformedOwners){return transformedOwners.getOrDefault(type.getClassLoader(),Set.of()).contains(type.getName().replace('.','/'));}
    }
    /** Say that this class now carries the members the transform adds. */
    public void markTransformed(String internalName) {
        if (internalName != null) transformed.add(internalName);
    }

    /**
     * Classes the JVM has already loaded, whether or not they came out
     * instrumented.
     *
     * <p>The pair is what makes the answer decidable. Seen and transformed is
     * safe. Seen and not transformed is the dangerous one: the class is in the
     * JVM without the members the transform adds, and nothing will add them
     * now. Not seen at all is safe for the opposite reason, since a watched
     * class that has not loaded yet will go through the transformer when it
     * does.
     */
    private final java.util.Set<String> seen =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Say that this class is in the JVM, however it got there. */
    public void markSeen(String internalName) {
        if (internalName != null) seen.add(internalName);
    }

    /**
     * Whether this class is already in the JVM without the transform's
     * members, which is when code generated for another class must not assume
     * they are there.
     */
    public boolean isLoadedUninstrumented(String internalName) {
        // Legacy name-only callers retain the pre-R21 transformed-name precedence.
        if(internalName==null || transformed.contains(internalName))return false;
        if(seen.contains(internalName))return true;
        synchronized(rawOwners){for(var names:rawOwners.values())if(names.contains(internalName))return true;}
        WatchInventory scope=currentInventory();
        if(scope!=null)for(var reference:scope.rawDefinitions().getOrDefault(internalName,List.of())) {
            Class<?> type=reference.get();if(type!=null&&!isTransformed(type))return true;
        }
        return false;
    }
    /** Resolve only observations visible from this defining loader, never a foreign duplicate. */
    public boolean isLoadedUninstrumented(ClassLoader loader,String internalName) {
        if(internalName==null)return false;
        WatchInventory scope=currentInventory();
        for(ClassLoader current=loader;;current=current.getParent()) {
            synchronized(rawOwners){if(rawOwners.getOrDefault(current,Set.of()).contains(internalName))return true;}
            synchronized(transformedOwners){if(transformedOwners.getOrDefault(current,Set.of()).contains(internalName))return false;}
            if(scope!=null)for(var reference:scope.rawDefinitions().getOrDefault(internalName,List.of())) {
                Class<?> type=reference.get();
                if(type!=null&&type.getClassLoader()==current)return !isTransformed(type);
            }
            if(current==null)break;
        }
        // Explicit legacy observations are used by old callers/tests, not the loader tracker.
        return seen.contains(internalName)&&!transformed.contains(internalName);
    }

    /**
     * Check if a class (by internal name) should be transformed.
     * O(1) HashSet lookup.
     */
    public boolean isWatched(String internalName) {
        WatchInventory scope=currentInventory();return watchedClassNames.contains(internalName)||(scope!=null&&scope.names().contains(internalName));
    }

    /**
     * Register a class as watched at runtime (e.g., when a new class is first loaded).
     */
    public void addWatched(String internalName) {
        watchedClassNames.add(internalName);
    }

    /**
     * Check if a .class file path falls under one of the watched class directories.
     * Used to dynamically add new classes to the watched set.
     */
    public boolean isInWatchedDir(Path classFile) {
        WatchInventory scope=currentInventory();
        for (Path dir : scope==null?watchedClassDirs:scope.roots()) {
            if (classFile.startsWith(dir)) return true;
        }
        return false;
    }

    /** Also discovers newly compiled module classes, without trusting a JAR filename. */
    public boolean isBackofficeClass(String internalName) {
        WatchInventory scope=currentInventory();
        if (backofficeClassNames.contains(internalName)||(scope!=null&&scope.backofficeNames().contains(internalName))) return true;
        for (Path root : scope==null?backofficeClassDirs:scope.backofficeRoots()) {
            if (Files.isRegularFile(root.resolve(internalName + ".class"))) {
                backofficeClassNames.add(internalName);
                return true;
            }
        }
        return false;
    }

    boolean matchesUniqueBackofficeOutput(String internalName, byte[] bytes) {
        Path match = null;
        WatchInventory scope=currentInventory();
        for (Path root : scope==null?backofficeClassDirs:scope.backofficeRoots()) {
            Path file = root.resolve(internalName + ".class");
            if (!Files.isRegularFile(file)) continue;
            if (match != null) return false;
            match = file;
        }
        try {
            return match != null && Files.size(match) == bytes.length
                    && Arrays.equals(Files.readAllBytes(match), bytes);
        } catch (IOException failure) {
            return false;
        }
    }

    public int getWatchedClassCount() {
        WatchInventory scope=currentInventory();if(scope==null)return watchedClassNames.size();
        Set<String> names=new HashSet<>(scope.names());names.addAll(watchedClassNames);return names.size();
    }

    public ClassMetadata getMetadata(String internalName) {
        return classMetadata.get(internalName);
    }

    public void putMetadata(String internalName, ClassMetadata metadata) {
        classMetadata.put(internalName, metadata);
    }

    /**
     * Metadata about a transformed class, tracks original method/field signatures
     * so we can detect structural changes on reload.
     */
    public static class ClassMetadata {
        private final List<MethodSig> methods;
        private final List<FieldSig> fields;
        private final int bytecodeHash;
        private final String superName;
        private final java.util.Set<String> annotations;
        private final boolean annotationsKnown;
        private java.util.Set<String> operationDescriptors;
        private final java.util.Set<String> interfaces;

        /** For callers with no annotation information; the diff stays silent. */
        public ClassMetadata(List<MethodSig> methods, List<FieldSig> fields,
                             int bytecodeHash, String superName) {
            this.methods = List.copyOf(methods);
            this.fields = List.copyOf(fields);
            this.bytecodeHash = bytecodeHash;
            this.superName = superName;
            this.annotations = java.util.Set.of();
            this.annotationsKnown = false;
            this.interfaces = null;
        }

        public ClassMetadata(List<MethodSig> methods, List<FieldSig> fields,
                             int bytecodeHash, String superName,
                             java.util.Set<String> annotations) {
            this(methods, fields, bytecodeHash, superName, annotations, null);
        }

        /**
         * @param interfaces the interfaces the loaded class declares, or null
         *                   when the caller does not know. Null means unknown
         *                   rather than none, the same distinction annotations
         *                   make: a class recorded before interfaces were
         *                   tracked would otherwise diff as having lost every
         *                   interface it has on the first reload after an
         *                   upgrade.
         */
        public ClassMetadata(List<MethodSig> methods, List<FieldSig> fields,
                             int bytecodeHash, String superName,
                             java.util.Set<String> annotations,
                             java.util.Set<String> interfaces) {
            this.methods = List.copyOf(methods);
            this.fields = List.copyOf(fields);
            this.bytecodeHash = bytecodeHash;
            this.superName = superName == null ? null : superName.intern();
            this.annotations = java.util.Set.copyOf(annotations);
            this.annotationsKnown = true;
            this.interfaces = interfaces == null ? null : java.util.Set.copyOf(interfaces);
        }

        /** Null when unknown; never confuse that with an empty set. */
        public java.util.Set<String> getInterfaces() { return interfaces; }

        public boolean isInterfacesKnown() { return interfaces != null; }

        public List<MethodSig> getMethods() { return methods; }
        public List<FieldSig> getFields() { return fields; }
        public int getBytecodeHash() { return bytecodeHash; }
        public String getSuperName() { return superName; }

        /**
         * Annotation signatures as of this version of the class, from
         * {@link AnnotationSignatures}. Empty when the class was recorded
         * before annotations were tracked, which the diff treats as
         * "nothing known" rather than "no annotations".
         */
        public java.util.Set<String> getAnnotations() { return annotations; }

        /**
         * Whether the annotation set was recorded at all. An empty set is a
         * real answer, "this class has no annotations", and a class that then
         * gains one has changed; that is different from never having looked,
         * which is what a class recorded by an older build looks like.
         */
        public boolean isAnnotationsKnown() { return annotationsKnown; }
        public java.util.Set<String> getOperationDescriptors() { return operationDescriptors; }
        public ClassMetadata withOperationDescriptors(java.util.Set<String> descriptors) {
            this.operationDescriptors = descriptors == null ? null : java.util.Set.copyOf(descriptors);
            return this;
        }
    }

    /**
     * A member's signature as the record keeps it. Names and descriptors are
     * interned: the same few hundred descriptors recur in every class, and
     * each class file used to hand the record its own copies of them.
     * Measured on the 685 transformed classes of spring-context, the
     * agent's retained heap per class went from 4,945 to about 4,400 bytes.
     */
    public record MethodSig(String name, String descriptor, int access) {
        public MethodSig {
            name = name == null ? null : name.intern();
            descriptor = descriptor == null ? null : descriptor.intern();
        }
    }

    public record FieldSig(String name, String descriptor, int access) {
        public FieldSig {
            name = name == null ? null : name.intern();
            descriptor = descriptor == null ? null : descriptor.intern();
        }
    }
}
