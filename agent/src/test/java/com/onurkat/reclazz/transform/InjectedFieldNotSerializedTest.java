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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A field a reload adds lives in an array hung off the object, and that array
 * was an ordinary private field: not transient, so default serialization walked
 * straight into Reclazz's own storage.
 *
 * <p>Which broke serialization for a value that had nothing to do with it.
 * Measured on a plain Serializable class whose added field held something
 * unserializable, which is most things worth holding, a service, a client, a
 * lambda: {@code NotSerializableException: java.lang.Object}, naming a class
 * the developer never put in their object. It also meant Reclazz's internals
 * travelled inside the application's own persisted data, to a session store or
 * another cluster node.
 *
 * <p>Transient is what that array is. What a deserialized object then gets for
 * a reload-added field is the type default, which is exactly what an object
 * created before that reload already gets, so the two answers agree instead of
 * one of them being an exception.
 */
class InjectedFieldNotSerializedTest extends TransformTestBase {

    private static final String SOURCE =
            "import java.io.Serializable;\n"
            + "public class SerExt implements Serializable {\n"
            + "    private String name = \"n\";\n"
            + "    public String getName() { return name; }\n"
            + "}";

    private static Class<?> transformAndLoad() throws Exception {
        byte[] raw = compile(new SourceFile("SerExt", SOURCE)).get("SerExt");
        TransformContext context = new TransformContext();
        context.addWatched("SerExt");
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        byte[] transformed = transformer.transform(
                TransformTestBase.class.getClassLoader(), "SerExt", null, null, raw);
        assertNotNull(transformed);
        Map<String, byte[]> one = new LinkedHashMap<>();
        one.put("SerExt", transformed);
        return defineAndLoad(one, "SerExt");
    }

    @Test
    void theStorageForAddedFieldsIsTransient() throws Exception {
        Field ext = transformAndLoad().getDeclaredField("__reclazz$ext");

        assertTrue(Modifier.isTransient(ext.getModifiers()),
                "default serialization writes every non-transient field, this one included");
    }

    /**
     * The failure as a user meets it: an added field holding something that
     * cannot be serialized, on an object that is about to become a session
     * attribute.
     */
    @Test
    void anObjectWithAnUnserializableAddedFieldStillSerializes() throws Exception {
        Class<?> cls = transformAndLoad();
        Object instance = cls.getDeclaredConstructor().newInstance();

        Field ext = cls.getDeclaredField("__reclazz$ext");
        ext.setAccessible(true);
        ext.set(instance, new Object[]{new Object()});

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            assertDoesNotThrow(() -> out.writeObject(instance),
                    "this threw NotSerializableException naming a class the developer "
                    + "never put in their object");
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

        assertEquals("n", cls.getMethod("getName").invoke(read),
                "and the object's own state comes back");
        assertNull(ext.get(read),
                "the storage does not travel, so a deserialized object reads the type "
                + "default for a reload-added field, like any object built before it");
    }
}
