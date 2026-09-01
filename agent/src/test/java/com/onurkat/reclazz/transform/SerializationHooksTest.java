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
 * Serialization finds writeObject and readObject by name and exact signature,
 * on the class itself, and they are private. The transform renames every method
 * body and leaves a trampoline under the original name, which is exactly the
 * kind of thing that quietly stops a by-name lookup working.
 *
 * <p>It does not, and this says so out loud rather than leaving it to be
 * assumed: a class with custom serialization keeps its custom serialization
 * under the agent.
 */
class SerializationHooksTest extends TransformTestBase {

    @Test
    void customWriteObjectAndReadObjectStillRun() throws Exception {
        String source = "import java.io.*;\n"
                + "public class SerHooks implements Serializable {\n"
                + "    private String name = \"n\";\n"
                + "    public transient String note = \"fresh\";\n"
                + "    private void writeObject(ObjectOutputStream out) throws IOException {\n"
                + "        out.defaultWriteObject();\n"
                + "        out.writeUTF(\"marker\");\n"
                + "    }\n"
                + "    private void readObject(ObjectInputStream in)\n"
                + "            throws IOException, ClassNotFoundException {\n"
                + "        in.defaultReadObject();\n"
                + "        note = in.readUTF();\n"
                + "    }\n"
                + "    public String getNote() { return note; }\n"
                + "}";
        byte[] raw = compile(new SourceFile("SerHooks", source)).get("SerHooks");
        TransformContext context = new TransformContext();
        context.addWatched("SerHooks");
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        byte[] transformed = transformer.transform(
                TransformTestBase.class.getClassLoader(), "SerHooks", null, null, raw);
        assertNotNull(transformed);
        Map<String, byte[]> one = new LinkedHashMap<>();
        one.put("SerHooks", transformed);
        Class<?> cls = defineAndLoad(one, "SerHooks");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(cls.getDeclaredConstructor().newInstance());
        }

        Object read;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
            @Override
            protected Class<?> resolveClass(ObjectStreamClass desc) {
                return cls;
            }
        }) {
            read = in.readObject();
        }

        assertEquals("marker", cls.getMethod("getNote").invoke(read),
                "the hooks are private and found by name; a trampoline under that name "
                + "has to be the one serialization calls");
    }
}
