/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.agent.AgentConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A super call into a parent that was never instrumented.
 *
 * <p>A super call cannot be left alone in an instrumented class: the parent's
 * method is a trampoline, calling it dispatches virtually, and it comes back to
 * the child's override forever. So the call is rewritten to the parent's
 * renamed body, which is correct exactly when the parent has one.
 *
 * <p>The condition for that was "is the parent in a watched directory", and
 * being in a watched directory is not the same as having been instrumented. A
 * class loaded before the agent could reach it is watched and untransformed;
 * so is one whose own transform failed. In both cases the rewritten call names
 * a method that is not there, and nothing says so until the line runs:
 *
 * <pre>
 *   NoSuchMethodError: GeneratedBadge.__reclazz$v0$createItem$2c9e54f0e5226bd3(...)
 *       at Badge.__reclazz$v0$createItem$2c9e54f0e5226bd3(Badge.java:19)
 *       at Badge.createItem(Badge.java:19)
 *       at de.hybris.platform.jalo.Item.newInstanceInternal(Item.java:4110)
 * </pre>
 *
 * <p>Reported from a real SAP Commerce project, where every jalo item class
 * extends a generated one and calls {@code super.createItem}, and the generated
 * classes are loaded while the type system is being built. The import that hits
 * it aborts, and the agent had said nothing at any point before that.
 */
class SuperCallToUninstrumentedParentTest extends TransformTestBase {

    private static final String PARENT = "public class Parent {\n"
            + "    public String describe() { return \"parent\"; }\n"
            + "}";

    private static final String CHILD = "public class Child extends Parent {\n"
            + "    @Override public String describe() { return \"child of \" + super.describe(); }\n"
            + "}";

    /**
     * The parent is watched, as everything in the extension is, and is in the
     * JVM without the transform's members: either it was shown to the
     * transformer and declined, or it was loaded before the agent arrived.
     */
    @Test
    void aChildWhoseParentWasNeverTransformedStillCallsSuper() throws Exception {
        Map<String, byte[]> compiled = compile(
                new SourceFile("Parent", PARENT), new SourceFile("Child", CHILD));

        TransformContext context = new TransformContext();
        context.addWatched("Parent");
        context.addWatched("Child");
        // The parent is in the JVM and did not come out instrumented, which is
        // what the transformer records for a class it was shown and declined,
        // and what the agent records at attach time for a class that was
        // already loaded. Either way the members are not there and will not be.
        context.markSeen("Parent");
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));

        byte[] child = transformer.transform(
                TransformTestBase.class.getClassLoader(), "Child", null, null,
                compiled.get("Child"));
        assertNotNull(child, "the child is watched, so it should have been transformed");

        Map<String, byte[]> loaded = new LinkedHashMap<>();
        loaded.put("Parent", compiled.get("Parent"));   // untouched, as it would be
        loaded.put("Child", child);
        Class<?> childClass = defineAndLoad(loaded, "Child");

        Object instance = childClass.getDeclaredConstructor().newInstance();
        assertEquals("child of parent", childClass.getMethod("describe").invoke(instance),
                "the super call was rewritten to a method the parent does not have");
    }

    /** And when the parent really was transformed, the rewrite is still right. */
    @Test
    void aChildWhoseParentWasTransformedStillDoesNotLoop() throws Exception {
        Map<String, byte[]> compiled = compile(
                new SourceFile("Parent", PARENT), new SourceFile("Child", CHILD));

        TransformContext context = new TransformContext();
        context.addWatched("Parent");
        context.addWatched("Child");
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));

        byte[] parent = transformer.transform(
                TransformTestBase.class.getClassLoader(), "Parent", null, null,
                compiled.get("Parent"));
        byte[] child = transformer.transform(
                TransformTestBase.class.getClassLoader(), "Child", null, null,
                compiled.get("Child"));
        assertNotNull(parent);
        assertNotNull(child);

        Map<String, byte[]> loaded = new LinkedHashMap<>();
        loaded.put("Parent", parent);
        loaded.put("Child", child);
        Class<?> childClass = defineAndLoad(loaded, "Child");

        Object instance = childClass.getDeclaredConstructor().newInstance();
        assertEquals("child of parent", childClass.getMethod("describe").invoke(instance),
                "a super call into an instrumented parent must reach the parent's body once, "
                        + "not dispatch back into the override");
    }
}
