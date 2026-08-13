package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.transform.AnnotationSignatures;
import com.onurkat.reclazz.transform.TransformContext;
import com.onurkat.reclazz.transform.TransformTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An edit that moves nothing but an annotation adds and removes no members,
 * so the structural diff called it body-only and no framework was told
 * anything had happened. The class redefined cleanly, reflection reported the
 * new annotation, and Spring's MVC registry carried on serving the mapping it
 * read at startup, which read from the outside as the annotation being
 * ignored.
 *
 * These pin the detection. The routing that depends on it, MVC re-scan firing
 * for an annotation change, is a decision in SpringReloadOrchestrator that
 * only a live context can exercise; the end-to-end suite covers that.
 */
class AnnotationDiffTest extends TransformTestBase {

    private static final String ROUTE_SRC =
            "import java.lang.annotation.*;\n" +
            "@Retention(RetentionPolicy.RUNTIME)\n" +
            "@Target({ElementType.METHOD, ElementType.TYPE})\n" +
            "public @interface Route { String value(); }";

    private static byte[] compileOne(String body) {
        Map<String, byte[]> out = compile(
                new SourceFile("Route", ROUTE_SRC),
                new SourceFile("Subject", body));
        return out.get("Subject");
    }

    /** Metadata as the transform path records it, so the diff has a baseline. */
    private static TransformContext.ClassMetadata metadataFor(byte[] bytecode) {
        List<TransformContext.MethodSig> methods = new java.util.ArrayList<>();
        List<TransformContext.FieldSig> fields = new java.util.ArrayList<>();
        new org.objectweb.asm.ClassReader(bytecode).accept(
                new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.MethodVisitor visitMethod(
                            int access, String name, String descriptor,
                            String signature, String[] exceptions) {
                        methods.add(new TransformContext.MethodSig(name, descriptor, access));
                        return null;
                    }

                    @Override
                    public org.objectweb.asm.FieldVisitor visitField(
                            int access, String name, String descriptor,
                            String signature, Object value) {
                        fields.add(new TransformContext.FieldSig(name, descriptor, access));
                        return null;
                    }
                },
                org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_DEBUG);

        return new TransformContext.ClassMetadata(
                methods, fields, 0, "java/lang/Object",
                AnnotationSignatures.of(bytecode));
    }

    @Test
    void changingAnAnnotationValueIsNoticed() {
        byte[] before = compileOne(
                "public class Subject { @Route(\"/ping\") public String handle() { return \"x\"; } }");
        byte[] after = compileOne(
                "public class Subject { @Route(\"/pong\") public String handle() { return \"x\"; } }");

        var diff = StructuralAnalyzer.analyze(metadataFor(before), after);

        assertTrue(diff.isAnnotationsChanged(), "a changed annotation value must register");
        assertTrue(diff.isAnnotationOnly(), "nothing else moved, so this is annotation-only");
        assertFalse(diff.isStructural(), "no member was added or removed");
    }

    @Test
    void addingAndRemovingAnAnnotationIsNoticed() {
        byte[] without = compileOne(
                "public class Subject { public String handle() { return \"x\"; } }");
        byte[] with = compileOne(
                "public class Subject { @Route(\"/ping\") public String handle() { return \"x\"; } }");

        assertTrue(StructuralAnalyzer.analyze(metadataFor(without), with).isAnnotationsChanged(),
                "adding an annotation must register");
        assertTrue(StructuralAnalyzer.analyze(metadataFor(with), without).isAnnotationsChanged(),
                "removing an annotation must register");
    }

    @Test
    void aClassLevelAnnotationCountsToo() {
        byte[] before = compileOne(
                "@Route(\"/a\") public class Subject { public String handle() { return \"x\"; } }");
        byte[] after = compileOne(
                "@Route(\"/b\") public class Subject { public String handle() { return \"x\"; } }");

        assertTrue(StructuralAnalyzer.analyze(metadataFor(before), after).isAnnotationsChanged());
    }

    /**
     * The point of the flag is to distinguish an annotation edit from an
     * ordinary one. If a plain body change also raised it, every reload would
     * drag the framework re-scans along with it.
     */
    @Test
    void aPlainBodyChangeDoesNotRaiseIt() {
        byte[] before = compileOne(
                "public class Subject { @Route(\"/ping\") public String handle() { return \"one\"; } }");
        byte[] after = compileOne(
                "public class Subject { @Route(\"/ping\") public String handle() { return \"two\"; } }");

        var diff = StructuralAnalyzer.analyze(metadataFor(before), after);

        assertFalse(diff.isAnnotationsChanged(), "the annotations are identical");
        assertTrue(diff.isBodyOnly());
    }

    /**
     * Classes recorded before annotations were tracked carry an empty set.
     * Reading that as "had no annotations" would make the first reload after
     * an upgrade claim every annotated class had changed.
     */
    @Test
    void anUnknownBaselineIsNotTreatedAsChange() {
        byte[] after = compileOne(
                "public class Subject { @Route(\"/ping\") public String handle() { return \"x\"; } }");
        var legacy = new TransformContext.ClassMetadata(
                List.of(), List.of(), 0, "java/lang/Object");

        assertFalse(StructuralAnalyzer.analyze(legacy, after).isAnnotationsChanged(),
                "an empty baseline means unknown, not empty");
    }

    /** Recompiling the same source must not look like a change. */
    @Test
    void recompilingTheSameSourceIsStable() {
        String src = "@Route(\"/a\") public class Subject {\n" +
                "    @Route(\"/ping\") public String handle() { return \"x\"; }\n" +
                "    @Route(\"/other\") public String second() { return \"y\"; }\n" +
                "}";
        Set<String> first = AnnotationSignatures.of(compileOne(src));
        Set<String> second = AnnotationSignatures.of(compileOne(src));

        assertEquals(first, second, "the signature must not depend on compilation order");
        assertFalse(first.isEmpty(), "precondition: something was actually collected");
    }
}
