package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Generic patterns: wildcards, bounded types, nested generics. */
class GenericsAdvancedTest extends TransformTestBase {

    @Test
    void boundedWildcardExtends() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "import java.util.*;\n" +
                        "public class T {\n" +
                        "    public static double sumAll(List<? extends Number> list) {\n" +
                        "        double s = 0;\n" +
                        "        for (Number n : list) s += n.doubleValue();\n" +
                        "        return s;\n" +
                        "    }\n" +
                        "    public static double run() {\n" +
                        "        return sumAll(java.util.Arrays.asList(1, 2.0, 3L));\n" +
                        "    }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        assertEquals(6.0, (double) invokeStatic(cls, "run"));

        // Verify generic parameter signature preserved
        Method m = cls.getDeclaredMethod("sumAll", java.util.List.class);
        Type paramType = m.getGenericParameterTypes()[0];
        assertTrue(paramType instanceof ParameterizedType);
        ParameterizedType pt = (ParameterizedType) paramType;
        Type arg = pt.getActualTypeArguments()[0];
        assertTrue(arg instanceof WildcardType, "expected wildcard, got: " + arg);
    }

    @Test
    void boundedWildcardSuper() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "import java.util.*;\n" +
                        "public class T {\n" +
                        "    public static <X> void copy(List<? super X> dst, List<X> src) {\n" +
                        "        for (X x : src) dst.add(x);\n" +
                        "    }\n" +
                        "    public static int run() {\n" +
                        "        List<Object> dst = new ArrayList<>();\n" +
                        "        copy(dst, Arrays.asList(\"a\", \"b\", \"c\"));\n" +
                        "        return dst.size();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals(3, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void boundedTypeParameter() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "import java.util.*;\n" +
                        "public class T {\n" +
                        "    public static <X extends Comparable<X>> X max(List<X> list) {\n" +
                        "        X best = list.get(0);\n" +
                        "        for (X x : list) if (x.compareTo(best) > 0) best = x;\n" +
                        "        return best;\n" +
                        "    }\n" +
                        "    public static String run() {\n" +
                        "        return max(java.util.Arrays.asList(\"banana\", \"apple\", \"cherry\"));\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("cherry", invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void multiBoundedTypeParameter() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Named",
                        "public interface Named { String name(); }"),
                new SourceFile("Item",
                        "public class Item implements Named, Comparable<Item> {\n" +
                        "    private final String name;\n" +
                        "    public Item(String name) { this.name = name; }\n" +
                        "    public String name() { return name; }\n" +
                        "    public int compareTo(Item o) { return name.compareTo(o.name); }\n" +
                        "}"),
                new SourceFile("T",
                        "import java.util.*;\n" +
                        "public class T {\n" +
                        "    public static <X extends Comparable<X> & Named> String describe(X x) {\n" +
                        "        return x.name() + \"@\" + x.compareTo(x);\n" +
                        "    }\n" +
                        "    public static String run() { return describe(new Item(\"foo\")); }\n" +
                        "}")
        );
        assertEquals("foo@0", invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void deeplyNestedGenerics() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "import java.util.*;\n" +
                        "public class T {\n" +
                        "    public static int run() {\n" +
                        "        Map<String, List<Map<Integer, Set<String>>>> deep = new HashMap<>();\n" +
                        "        Set<String> set = new HashSet<>();\n" +
                        "        set.add(\"x\"); set.add(\"y\");\n" +
                        "        Map<Integer, Set<String>> inner = new HashMap<>();\n" +
                        "        inner.put(1, set);\n" +
                        "        List<Map<Integer, Set<String>>> list = new ArrayList<>();\n" +
                        "        list.add(inner);\n" +
                        "        deep.put(\"key\", list);\n" +
                        "        return deep.get(\"key\").get(0).get(1).size();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals(2, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void genericClassWithMultipleTypeParameters() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Pair",
                        "public class Pair<A, B> {\n" +
                        "    private final A first;\n" +
                        "    private final B second;\n" +
                        "    public Pair(A a, B b) { this.first = a; this.second = b; }\n" +
                        "    public A first() { return first; }\n" +
                        "    public B second() { return second; }\n" +
                        "    public <C> Pair<A, C> withSecond(C c) { return new Pair<>(first, c); }\n" +
                        "}"),
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static String run() {\n" +
                        "        Pair<String, Integer> p = new Pair<>(\"x\", 42);\n" +
                        "        Pair<String, Double> q = p.withSecond(3.14);\n" +
                        "        return p.first() + \"|\" + p.second() + \"|\" + q.second();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("x|42|3.14", invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void streamComparatorChains() {
        // Comparator.comparing(...).thenComparing(...) — lambda-heavy
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Person",
                        "public class Person {\n" +
                        "    public final String name;\n" +
                        "    public final int age;\n" +
                        "    public Person(String n, int a) { this.name = n; this.age = a; }\n" +
                        "    public String getName() { return name; }\n" +
                        "    public int getAge() { return age; }\n" +
                        "}"),
                new SourceFile("T",
                        "import java.util.*;\n" +
                        "import java.util.stream.*;\n" +
                        "public class T {\n" +
                        "    public static String run() {\n" +
                        "        List<Person> list = new ArrayList<>();\n" +
                        "        list.add(new Person(\"alice\", 30));\n" +
                        "        list.add(new Person(\"bob\", 25));\n" +
                        "        list.add(new Person(\"alice\", 22));\n" +
                        "        return list.stream()\n" +
                        "            .sorted(Comparator.comparing(Person::getName).thenComparingInt(Person::getAge))\n" +
                        "            .map(p -> p.name + \"-\" + p.age)\n" +
                        "            .collect(Collectors.joining(\",\"));\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("alice-22,alice-30,bob-25", invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void intStreamRangeWithIndexedAccess() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "import java.util.*;\n" +
                        "import java.util.stream.*;\n" +
                        "public class T {\n" +
                        "    public static String run() {\n" +
                        "        List<String> items = Arrays.asList(\"a\", \"b\", \"c\");\n" +
                        "        return IntStream.range(0, items.size())\n" +
                        "            .mapToObj(i -> i + \":\" + items.get(i))\n" +
                        "            .collect(Collectors.joining(\";\"));\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("0:a;1:b;2:c", invokeStatic(defineAndLoad(classes, "T"), "run"));
    }
}
