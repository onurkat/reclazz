/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import com.onurkat.reclazz.agent.AgentConfig;
import com.onurkat.reclazz.transform.ReclazzTransformer;
import com.onurkat.reclazz.transform.TransformContext;
import com.onurkat.reclazz.transform.TransformTestBase;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A field a reload adds lives in an array hung off the object, and
 * {@code Object.clone} copies fields, that array's reference included. So a
 * clone and its original shared one store, and a write through either was a
 * write to both: measured, setting a reload-added field on the clone changed
 * what the original returned, silently, with nothing in the source to see it
 * from.
 *
 * <p>Writes copy the array now instead of writing into it, which gives the two
 * objects separate stores from the first write onwards. That is what a shallow
 * copy of a field means for every other field on the object, which is the point:
 * an added field should behave like a field.
 */
class AddedFieldsAfterCloneTest extends TransformTestBase {

    private static final String DESC = "Ljava/lang/String;";

    private static Class<?> cloneable() throws Exception {
        String source = "public class Cloneish implements Cloneable {\n"
                + "    private String name = \"n\";\n"
                + "    public Cloneish copy() throws CloneNotSupportedException {\n"
                + "        return (Cloneish) super.clone();\n"
                + "    }\n"
                + "}";
        byte[] raw = compile(new SourceFile("Cloneish", source)).get("Cloneish");
        TransformContext context = new TransformContext();
        context.addWatched("Cloneish");
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        byte[] transformed = transformer.transform(
                TransformTestBase.class.getClassLoader(), "Cloneish", null, null, raw);
        assertNotNull(transformed);
        Map<String, byte[]> one = new LinkedHashMap<>();
        one.put("Cloneish", transformed);
        return defineAndLoad(one, "Cloneish");
    }

    @Test
    void writingAnAddedFieldOnACloneLeavesTheOriginalAlone() throws Exception {
        Class<?> cls = cloneable();
        Object original = cls.getDeclaredConstructor().newInstance();
        FieldStore.putExtField(original, "first", "Cloneish", "added", DESC);

        Object clone = cls.getMethod("copy").invoke(original);
        FieldStore.putExtField(clone, "second", "Cloneish", "added", DESC);

        assertEquals("first", FieldStore.getExtField(original, "Cloneish", "added", DESC),
                "the clone shared the original's store, so this read back 'second'");
        assertEquals("second", FieldStore.getExtField(clone, "Cloneish", "added", DESC));
    }

    /** And the other direction: the original writing does not reach the clone. */
    @Test
    void writingOnTheOriginalAfterCloningLeavesTheCloneAlone() throws Exception {
        Class<?> cls = cloneable();
        Object original = cls.getDeclaredConstructor().newInstance();
        FieldStore.putExtField(original, "first", "Cloneish", "added", DESC);

        Object clone = cls.getMethod("copy").invoke(original);
        FieldStore.putExtField(original, "changed", "Cloneish", "added", DESC);

        assertEquals("first", FieldStore.getExtField(clone, "Cloneish", "added", DESC),
                "a clone taken before the write keeps what it was copied with");
        assertEquals("changed", FieldStore.getExtField(original, "Cloneish", "added", DESC));
    }

    /** Several fields on one object still all round-trip after the copying. */
    @Test
    void copyingOnWriteKeepsTheOtherAddedFields() throws Exception {
        Class<?> cls = cloneable();
        Object instance = cls.getDeclaredConstructor().newInstance();

        FieldStore.putExtField(instance, "a", "Cloneish", "one", DESC);
        FieldStore.putExtField(instance, "b", "Cloneish", "two", DESC);
        FieldStore.putExtField(instance, "c", "Cloneish", "three", DESC);

        assertEquals("a", FieldStore.getExtField(instance, "Cloneish", "one", DESC));
        assertEquals("b", FieldStore.getExtField(instance, "Cloneish", "two", DESC));
        assertEquals("c", FieldStore.getExtField(instance, "Cloneish", "three", DESC));
    }
}
