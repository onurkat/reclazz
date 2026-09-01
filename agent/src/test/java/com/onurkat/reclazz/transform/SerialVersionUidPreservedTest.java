/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.agent.AgentConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A class that does not declare a serialVersionUID gets one computed from its
 * own shape, and the load-time transform changes that shape: it adds fields, it
 * adds a renamed copy of every method, and it adds a class initializer where
 * there was none. So attaching the agent changed what every watched Serializable
 * class serializes as. Measured with the JDK's own ObjectStreamClass:
 *
 * <pre>
 *   without the agent  -5455223060129582737
 *   with the agent      2754914338756156902
 * </pre>
 *
 * <p>Which is not a development-time inconvenience for this audience. Anything
 * written before the agent was attached stops being readable after it, and a
 * cluster node running with it cannot exchange objects with one running without:
 * InvalidClassException, local class incompatible. Sessions and distributed
 * caches are exactly where that lands.
 *
 * <p>Hiding the injected members from the computation is not available: the
 * specification drops private static and private transient fields, which the
 * injected fields can be, but it counts every non-private method, and the
 * renamed bodies have to keep the visibility they had or an override stops
 * being found. So the original number is computed and written into the class.
 */
class SerialVersionUidPreservedTest extends TransformTestBase {

    private static final String SOURCE =
            "import java.io.Serializable;\n"
            + "public class Ser implements Serializable {\n"
            + "    private String name = \"n\";\n"
            + "    private int count = 3;\n"
            + "    public String getName() { return name; }\n"
            + "    public int getCount() { return count; }\n"
            + "}";

    private static Class<?> load(String name, byte[] bytecode) throws Exception {
        Map<String, byte[]> one = new LinkedHashMap<>();
        one.put(name, bytecode);
        return defineAndLoad(one, name);
    }

    private static byte[] transform(byte[] raw) throws Exception {
        TransformContext context = new TransformContext();
        context.addWatched("Ser");
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        byte[] transformed = transformer.transform(
                TransformTestBase.class.getClassLoader(), "Ser", null, null, raw);
        assertNotNull(transformed, "the class was not transformed at all");
        return transformed;
    }

    @Test
    void theAgentDoesNotChangeWhatAClassSerializesAs() throws Exception {
        byte[] raw = compile(new SourceFile("Ser", SOURCE)).get("Ser");

        long withoutAgent = ObjectStreamClass.lookup(load("Ser", raw)).getSerialVersionUID();
        long withAgent = ObjectStreamClass.lookup(load("Ser", transform(raw))).getSerialVersionUID();

        assertEquals(withoutAgent, withAgent,
                "a changed UID is an InvalidClassException for every object already written");
    }

    /**
     * The statement a user would make: what was written before the agent is
     * still readable after it. Asserted end to end rather than through the
     * number, because the number is only the mechanism.
     */
    @Test
    void anObjectWrittenWithoutTheAgentIsReadWithIt() throws Exception {
        byte[] raw = compile(new SourceFile("Ser", SOURCE)).get("Ser");
        Class<?> plain = load("Ser", raw);
        Class<?> instrumented = load("Ser", transform(raw));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(plain.getDeclaredConstructor().newInstance());
        }

        Object read;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
            @Override
            protected Class<?> resolveClass(ObjectStreamClass desc) {
                return instrumented;
            }
        }) {
            read = in.readObject();
        }

        assertSame(instrumented, read.getClass());
        assertEquals("n", instrumented.getMethod("getName").invoke(read),
                "and the values come back, not just the object");
        assertEquals(3, instrumented.getMethod("getCount").invoke(read));
    }

    /** A class that says its own UID keeps saying it, and is not given a second. */
    @Test
    void aDeclaredUidIsNotOverwritten() throws Exception {
        byte[] raw = compile(new SourceFile("SerFixed",
                "import java.io.Serializable;\n"
                + "public class SerFixed implements Serializable {\n"
                + "    private static final long serialVersionUID = 4242L;\n"
                + "    public int x;\n"
                + "}")).get("SerFixed");

        TransformContext context = new TransformContext();
        context.addWatched("SerFixed");
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        byte[] transformed = transformer.transform(
                TransformTestBase.class.getClassLoader(), "SerFixed", null, null, raw);

        assertEquals(4242L,
                ObjectStreamClass.lookup(load("SerFixed", transformed)).getSerialVersionUID());
    }
}
