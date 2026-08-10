package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.agent.AgentConfig;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for applying CONSTRUCTOR body changes via
 * redefineClasses on a transformed class.
 *
 * Live finding: the agent's registered ReclazzTransformer also runs during
 * redefineClasses. Feeding it bytes that were ALREADY doTransform'ed made
 * it inject the infrastructure twice → ClassFormatError (duplicate field) →
 * "Constructor-body refresh skipped". The redefinition must therefore pass
 * the RAW compiled bytes and let the registered transformer do its work,
 * exactly like at load time.
 */
class ConstructorBodyRedefineTest extends TransformTestBase {

    private static Instrumentation instrumentation;

    @BeforeAll
    static void setup() {
        instrumentation = ByteBuddyAgent.install();
        assertNotNull(instrumentation);
    }

    private static final String V1 =
            "public class CtorClass {\n" +
            "    private final String tag;\n" +
            "    public CtorClass() { this.tag = \"old-ctor\"; }\n" +
            "    public String tag() { return tag; }\n" +
            "}";

    private static final String V2 =
            "public class CtorClass {\n" +
            "    private final String tag;\n" +
            "    public CtorClass() { this.tag = \"new-ctor\"; }\n" +
            "    public String tag() { return tag; }\n" +
            "}";

    @Test
    void rawBytesRedefineAppliesCtorBody_withRegisteredTransformer() throws Exception {
        Map<String, byte[]> v1 = compileAndTransform(new SourceFile("CtorClass", V1));
        Class<?> cls = defineAndLoad(v1, "CtorClass");

        Object oldInstance = cls.getDeclaredConstructor().newInstance();
        Method tag = cls.getDeclaredMethod("tag");
        assertEquals("old-ctor", tag.invoke(oldInstance));

        // Register a live transformer the way the agent does, so redefinition
        // re-transforms raw bytes exactly like class load did.
        TransformContext context = new TransformContext();
        context.addWatched("CtorClass");
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        instrumentation.addTransformer(transformer, true);
        try {
            Map<String, byte[]> v2raw = compile(new SourceFile("CtorClass", V2));
            instrumentation.redefineClasses(new ClassDefinition(cls, v2raw.get("CtorClass")));

            Object fresh = cls.getDeclaredConstructor().newInstance();
            assertEquals("new-ctor", tag.invoke(fresh),
                    "new instances must be built by the NEW constructor");
        } finally {
            instrumentation.removeTransformer(transformer);
        }
    }

    @Test
    void preTransformedBytesRedefineFails_documentsWhyRawBytesAreRequired() throws Exception {
        Map<String, byte[]> v1 = compileAndTransform(new SourceFile("CtorClass2",
                V1.replace("CtorClass", "CtorClass2")));
        Class<?> cls = defineAndLoad(v1, "CtorClass2");

        TransformContext context = new TransformContext();
        context.addWatched("CtorClass2");
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        instrumentation.addTransformer(transformer, true);
        try {
            // Pre-transformed bytes + registered transformer = double
            // infrastructure injection → the JVM must reject it.
            Map<String, byte[]> v2transformed = compileAndTransform(new SourceFile("CtorClass2",
                    V2.replace("CtorClass", "CtorClass2")));
            assertThrows(Throwable.class, () ->
                    instrumentation.redefineClasses(
                            new ClassDefinition(cls, v2transformed.get("CtorClass2"))));
        } finally {
            instrumentation.removeTransformer(transformer);
        }
    }
}
