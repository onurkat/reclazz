/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.io.ObjectStreamClass;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Being close to the specification is not useful here: a wrong number is as
 * unreadable as a changed one. So every shape is checked against the JDK's own
 * answer rather than against a reading of section 4.6.
 */
class SerialVersionUidTest extends TransformTestBase {

    private static void agreesWithTheJdk(String name, String source) throws Exception {
        byte[] raw = compile(new SourceFile(name, source)).get(name);
        Map<String, byte[]> one = new LinkedHashMap<>();
        one.put(name, raw);
        Class<?> loaded = defineAndLoad(one, name);

        Long computed = SerialVersionUid.computeFrom(raw);
        assertNotNull(computed, name + ": nothing computed");
        assertEquals(ObjectStreamClass.lookup(loaded).getSerialVersionUID(), computed.longValue(),
                name + ": the JDK and this disagree, which makes the data unreadable either way");
    }

    @Test
    void aPlainSerializableClass() throws Exception {
        agreesWithTheJdk("SerPlain",
                "import java.io.Serializable;\n"
                + "public class SerPlain implements Serializable {\n"
                + "    private String name = \"n\";\n"
                + "    public String getName() { return name; }\n"
                + "}");
    }

    /** Private static and private transient fields are the two the spec drops. */
    @Test
    void everyFieldFlavour() throws Exception {
        agreesWithTheJdk("SerFields",
                "import java.io.Serializable;\n"
                + "public class SerFields implements Serializable {\n"
                + "    public int a;\n"
                + "    protected String b;\n"
                + "    private long c;\n"
                + "    private static int d;\n"
                + "    private transient String e;\n"
                + "    static final double f = 1.0;\n"
                + "    private volatile boolean g;\n"
                + "}");
    }

    /** Private methods and constructors are left out; the rest are sorted in. */
    @Test
    void everyMethodFlavour() throws Exception {
        agreesWithTheJdk("SerMethods",
                "import java.io.Serializable;\n"
                + "public class SerMethods implements Serializable {\n"
                + "    public SerMethods() {}\n"
                + "    protected SerMethods(int a) {}\n"
                + "    private SerMethods(String s) {}\n"
                + "    public void z() {}\n"
                + "    public void a(int i) {}\n"
                + "    public void a(String s) {}\n"
                + "    private void hidden() {}\n"
                + "    protected static synchronized void st() {}\n"
                + "    public final native void nat();\n"
                + "}");
    }

    @Test
    void aStaticInitialiserCounts() throws Exception {
        agreesWithTheJdk("SerClinit",
                "import java.io.Serializable;\n"
                + "public class SerClinit implements Serializable {\n"
                + "    static final java.util.List<String> L = new java.util.ArrayList<>();\n"
                + "    static { L.add(\"x\"); }\n"
                + "}");
    }

    @Test
    void interfacesAreSortedAndCounted() throws Exception {
        agreesWithTheJdk("SerIfaces",
                "import java.io.Serializable;\n"
                + "public class SerIfaces implements Serializable, Comparable<SerIfaces>, Runnable {\n"
                + "    public int compareTo(SerIfaces o) { return 0; }\n"
                + "    public void run() {}\n"
                + "}");
    }

    @Test
    void anAbstractAndAFinalClass() throws Exception {
        agreesWithTheJdk("SerFinal",
                "import java.io.Serializable;\n"
                + "public final class SerFinal implements Serializable { public int x; }");
        agreesWithTheJdk("SerAbstract",
                "import java.io.Serializable;\n"
                + "public abstract class SerAbstract implements Serializable {\n"
                + "    public abstract void go();\n"
                + "}");
    }

    /** A class that says its own UID must be left exactly as it is. */
    @Test
    void aDeclaredUidIsRecognised() throws Exception {
        byte[] declared = compile(new SourceFile("SerDeclared",
                "import java.io.Serializable;\n"
                + "public class SerDeclared implements Serializable {\n"
                + "    private static final long serialVersionUID = 42L;\n"
                + "}")).get("SerDeclared");
        byte[] plain = compile(new SourceFile("SerUndeclared",
                "import java.io.Serializable;\n"
                + "public class SerUndeclared implements Serializable { public int x; }"))
                .get("SerUndeclared");

        assertTrue(SerialVersionUid.alreadyDeclared(declared));
        assertFalse(SerialVersionUid.alreadyDeclared(plain));
    }

    /** An enum's UID is ignored by the serialization machinery; leave it alone. */
    @Test
    void enumsAndInterfacesAreLeftAlone() throws Exception {
        byte[] anEnum = compile(new SourceFile("SerEnum",
                "public enum SerEnum { A, B }")).get("SerEnum");
        byte[] anInterface = compile(new SourceFile("SerIface",
                "public interface SerIface { void go(); }")).get("SerIface");

        assertFalse(SerialVersionUid.worthWriting(anEnum));
        assertFalse(SerialVersionUid.worthWriting(anInterface));
    }
}
