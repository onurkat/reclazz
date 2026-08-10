package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.agent.AgentConfig;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Base utility for transform pipeline tests.
 *
 * Each test:
 * 1. Provides Java source as a string
 * 2. Compiles it in-memory via {@link JavaCompiler}
 * 3. Runs the compiled bytes through {@link ReclazzTransformer}
 * 4. Verifies the result via {@link CheckClassAdapter}
 * 5. Loads the transformed class via a custom classloader
 * 6. Invokes a method and asserts the result
 */
public abstract class TransformTestBase {

    /**
     * Compile a set of Java source files in-memory and return a map of
     * internal class name → bytecode.
     */
    protected static Map<String, byte[]> compile(SourceFile... sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available; run on a JDK, not JRE.");
        }

        StringWriter diagOut = new StringWriter();
        InMemoryFileManager fm = new InMemoryFileManager(
                compiler.getStandardFileManager(null, null, null));

        Iterable<? extends JavaFileObject> units = Arrays.asList(sources);
        List<String> options = Arrays.asList("-source", "17", "-target", "17",
                "-Xlint:-options", "-proc:none", "-parameters");
        boolean ok = compiler.getTask(diagOut, fm, null, options, null, units).call();
        if (!ok) {
            fail("Compilation failed:\n" + diagOut.toString());
        }
        return fm.classBytes;
    }

    /**
     * Transform a class through the Reclazz pipeline (no JVM agent
     * needed). Uses an empty {@link TransformContext} that watches every
     * passed class.
     */
    protected static byte[] transform(String internalName, byte[] original,
                                       Map<String, byte[]> watchedClasses,
                                       ClassLoader loader) {
        TransformContext context = new TransformContext();
        for (String name : watchedClasses.keySet()) {
            context.addWatched(name);
        }
        AgentConfig config = AgentConfig.parse(null);
        ReclazzTransformer transformer = new ReclazzTransformer(context, config);
        try {
            byte[] result = transformer.transform(loader, internalName, null, null, original);
            return result != null ? result : original;
        } catch (Exception e) {
            fail("Transform failed for " + internalName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Compile and transform the sources. Verification is left to the JVM's
     * own verifier when classes are loaded — using {@link CheckClassAdapter}
     * here would require a ClassLoader populated with all referenced types,
     * which is impractical for in-memory test sources. The
     * compile→transform→define→invoke cycle is the actual end-to-end test.
     */
    protected static Map<String, byte[]> compileAndTransform(SourceFile... sources) {
        Map<String, byte[]> compiled = compile(sources);
        Map<String, byte[]> transformed = new LinkedHashMap<>();
        // Use a loader that can find the compiled test classes by name so
        // ASM's COMPUTE_FRAMES hierarchy walking works.
        ClassLoader loader = new BytesAwareLoader(compiled,
                TransformTestBase.class.getClassLoader());
        for (Map.Entry<String, byte[]> entry : compiled.entrySet()) {
            byte[] result = transform(entry.getKey(), entry.getValue(), compiled, loader);
            transformed.put(entry.getKey(), result);
        }
        return transformed;
    }

    /**
     * Load a transformed class set via a custom classloader and return the
     * named class object.
     */
    protected static Class<?> defineAndLoad(Map<String, byte[]> classes, String binaryName) {
        TestClassLoader loader = new TestClassLoader(classes,
                TransformTestBase.class.getClassLoader());
        try {
            return Class.forName(binaryName, true, loader);
        } catch (ClassNotFoundException e) {
            fail("Could not load class " + binaryName + ": " + e.getMessage());
            return null;
        } catch (Throwable t) {
            fail("Class init failed for " + binaryName + ": " + t);
            return null;
        }
    }

    /**
     * Create a single classloader that holds all the transformed classes,
     * so multiple {@code load(...)} calls return Class objects with the
     * same loader identity. Use this instead of repeated
     * {@link #defineAndLoad} calls when classes need to share types
     * (e.g. a proxy backed by a class that implements a watched interface).
     */
    protected static SharedLoader sharedLoader(Map<String, byte[]> classes) {
        return new SharedLoader(new TestClassLoader(classes,
                TransformTestBase.class.getClassLoader()));
    }

    public static final class SharedLoader {
        private final ClassLoader loader;
        SharedLoader(ClassLoader loader) { this.loader = loader; }
        public Class<?> load(String binaryName) {
            try {
                return Class.forName(binaryName, true, loader);
            } catch (Throwable t) {
                fail("Could not load " + binaryName + ": " + t);
                return null;
            }
        }
        public ClassLoader loader() { return loader; }
    }

    /**
     * Invoke a static method by reflection with the given arguments.
     */
    protected static Object invokeStatic(Class<?> cls, String methodName, Object... args) {
        try {
            for (var m : cls.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return m.invoke(null, args);
                }
            }
        } catch (Throwable t) {
            fail("Static invoke " + cls.getName() + "." + methodName + " failed: " + t.getCause());
        }
        fail("Method not found: " + cls.getName() + "." + methodName);
        return null;
    }

    /** A Java source file (name + content) suitable for {@link JavaCompiler}. */
    public static class SourceFile extends SimpleJavaFileObject {
        final String code;
        public SourceFile(String binaryName, String code) {
            super(URI.create("string:///" + binaryName.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.code = code;
        }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return code; }
    }

    /** Captures compiled .class bytes in a map. */
    private static class InMemoryFileManager extends javax.tools.ForwardingJavaFileManager<StandardJavaFileManager> {
        final Map<String, byte[]> classBytes = new LinkedHashMap<>();

        InMemoryFileManager(StandardJavaFileManager delegate) {
            super(delegate);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                    JavaFileObject.Kind kind, javax.tools.FileObject sibling) throws IOException {
            return new InMemoryClassObject(className.replace('.', '/'));
        }

        class InMemoryClassObject extends SimpleJavaFileObject {
            final String internalName;
            InMemoryClassObject(String internalName) {
                super(URI.create("byte:///" + internalName + ".class"), Kind.CLASS);
                this.internalName = internalName;
            }
            @Override
            public java.io.OutputStream openOutputStream() {
                return new ByteArrayOutputStream() {
                    @Override public void close() throws IOException {
                        super.close();
                        classBytes.put(internalName, toByteArray());
                    }
                };
            }
        }
    }

    /**
     * Classloader used during transform that exposes compiled test classes
     * via getResourceAsStream. Required by ReclazzTransformer's SafeClassWriter
     * (1.0.6) to walk hierarchies for COMPUTE_FRAMES.
     */
    private static class BytesAwareLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        BytesAwareLoader(Map<String, byte[]> classes, ClassLoader parent) {
            super(parent);
            this.classes = classes;
        }

        @Override
        public java.io.InputStream getResourceAsStream(String name) {
            if (name.endsWith(".class")) {
                String internal = name.substring(0, name.length() - 6);
                byte[] bytes = classes.get(internal);
                if (bytes != null) return new java.io.ByteArrayInputStream(bytes);
            }
            return super.getResourceAsStream(name);
        }
    }

    /** Defines classes from a precompiled bytecode map. */
    private static class TestClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;
        private final Set<String> defined = new HashSet<>();

        TestClassLoader(Map<String, byte[]> classes, ClassLoader parent) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String binaryName) throws ClassNotFoundException {
            String internal = binaryName.replace('.', '/');
            byte[] bytes = classes.get(internal);
            if (bytes == null) throw new ClassNotFoundException(binaryName);
            if (defined.add(internal)) {
                return defineClass(binaryName, bytes, 0, bytes.length);
            }
            return findLoadedClass(binaryName);
        }
    }
}
