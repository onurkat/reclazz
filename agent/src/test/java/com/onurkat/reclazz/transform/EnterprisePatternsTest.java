package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Patterns that show up in real Spring/Hybris production code.
 * Sourced from a survey of custom extensions.
 */
class EnterprisePatternsTest extends TransformTestBase {

    @Test
    void springStyleConstructorInjectionWithFinalFields() {
        // The canonical Spring service: final dependencies, single
        // constructor, no @Autowired (implicit injection). Bytecode
        // has PUTFIELD on final fields inside <init> — this exercised
        // the 1.0.9 final-field fix.
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("ServiceImpl",
                        "public class ServiceImpl {\n" +
                        "    private final String name;\n" +
                        "    private final int factor;\n" +
                        "    private final java.util.List<String> tags;\n" +
                        "    public ServiceImpl(String name, int factor, java.util.List<String> tags) {\n" +
                        "        this.name = name;\n" +
                        "        this.factor = factor;\n" +
                        "        this.tags = tags;\n" +
                        "    }\n" +
                        "    public String describe() {\n" +
                        "        return name + \"#\" + factor + \":\" + tags.size();\n" +
                        "    }\n" +
                        "    public static String run() {\n" +
                        "        return new ServiceImpl(\"svc\", 7, java.util.List.of(\"a\", \"b\")).describe();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("svc#7:2", invokeStatic(defineAndLoad(classes, "ServiceImpl"), "run"));
    }

    @Test
    void multiCatchUnion() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static String classify(int kind) {\n" +
                        "        try {\n" +
                        "            if (kind == 1) throw new IllegalStateException(\"is\");\n" +
                        "            if (kind == 2) throw new IllegalArgumentException(\"ia\");\n" +
                        "            if (kind == 3) throw new NullPointerException(\"np\");\n" +
                        "            return \"ok\";\n" +
                        "        } catch (IllegalStateException | IllegalArgumentException e) {\n" +
                        "            return \"checked:\" + e.getClass().getSimpleName();\n" +
                        "        } catch (NullPointerException e) {\n" +
                        "            return \"npe\";\n" +
                        "        }\n" +
                        "    }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        assertEquals("ok", invokeStatic(cls, "classify", 0));
        assertEquals("checked:IllegalStateException", invokeStatic(cls, "classify", 1));
        assertEquals("checked:IllegalArgumentException", invokeStatic(cls, "classify", 2));
        assertEquals("npe", invokeStatic(cls, "classify", 3));
    }

    @Test
    void exceptionWrappingChain() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static String run() {\n" +
                        "        try {\n" +
                        "            try {\n" +
                        "                throw new IllegalStateException(\"root\");\n" +
                        "            } catch (IllegalStateException e) {\n" +
                        "                throw new RuntimeException(\"wrapped: \" + e.getMessage(), e);\n" +
                        "            }\n" +
                        "        } catch (RuntimeException e) {\n" +
                        "            return e.getMessage() + \" / \" + e.getCause().getClass().getSimpleName();\n" +
                        "        }\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("wrapped: root / IllegalStateException", invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void chainedConstructorCalls() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    private final String greeting;\n" +
                        "    public T() { this(\"default\"); }\n" +
                        "    public T(String g) { this(g, 0); }\n" +
                        "    public T(String g, int n) { this.greeting = g + \"#\" + n; }\n" +
                        "    public static String run() {\n" +
                        "        return new T().greeting + \"|\" + new T(\"x\").greeting + \"|\" + new T(\"y\", 5).greeting;\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("default#0|x#0|y#5", invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void singletonFactoryWithPrivateConstructor() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    private static final T INSTANCE = new T(42);\n" +
                        "    private final int value;\n" +
                        "    private T(int v) { this.value = v; }\n" +
                        "    public static T getInstance() { return INSTANCE; }\n" +
                        "    public int getValue() { return value; }\n" +
                        "    public static int run() { return getInstance().getValue(); }\n" +
                        "}")
        );
        assertEquals(42, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void builderPattern() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    private final String name;\n" +
                        "    private final int count;\n" +
                        "    private T(Builder b) { this.name = b.name; this.count = b.count; }\n" +
                        "    public String describe() { return name + \"x\" + count; }\n" +
                        "    public static Builder builder() { return new Builder(); }\n" +
                        "    public static class Builder {\n" +
                        "        private String name;\n" +
                        "        private int count;\n" +
                        "        public Builder name(String n) { this.name = n; return this; }\n" +
                        "        public Builder count(int c) { this.count = c; return this; }\n" +
                        "        public T build() { return new T(this); }\n" +
                        "    }\n" +
                        "    public static String run() {\n" +
                        "        return T.builder().name(\"foo\").count(3).build().describe();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("foox3", invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void abstractJobPerformableLikeHierarchy() {
        // Mimics Hybris AbstractJobPerformable: abstract parent template
        // method, concrete subclass implements perform(), super exposes helper.
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("AbstractJob",
                        "public abstract class AbstractJob {\n" +
                        "    public abstract String perform();\n" +
                        "    protected String prefix() { return \"[job] \"; }\n" +
                        "    public final String run() { return prefix() + perform(); }\n" +
                        "}"),
                new SourceFile("MyJob",
                        "public class MyJob extends AbstractJob {\n" +
                        "    @Override public String perform() { return \"running\"; }\n" +
                        "    @Override protected String prefix() { return super.prefix() + \"my-\"; }\n" +
                        "    public static String go() { return new MyJob().run(); }\n" +
                        "}")
        );
        assertEquals("[job] my-running", invokeStatic(defineAndLoad(classes, "MyJob"), "go"));
    }

    @Test
    void anonymousClassWithMultipleMethodOverrides() {
        // Hybris SessionExecutionBody pattern: anonymous class implementing
        // multiple interface methods.
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Body",
                        "public interface Body {\n" +
                        "    String compute();\n" +
                        "    String describe();\n" +
                        "    default String full() { return describe() + \" -> \" + compute(); }\n" +
                        "}"),
                new SourceFile("Runner",
                        "public class Runner {\n" +
                        "    public static String run(int x) {\n" +
                        "        Body body = new Body() {\n" +
                        "            @Override public String compute() { return Integer.toString(x * 2); }\n" +
                        "            @Override public String describe() { return \"double-\" + x; }\n" +
                        "        };\n" +
                        "        return body.full();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("double-7 -> 14", invokeStatic(defineAndLoad(classes, "Runner"), "run", 7));
    }
}
