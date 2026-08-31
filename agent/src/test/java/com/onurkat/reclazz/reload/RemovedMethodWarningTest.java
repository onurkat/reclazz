/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.agent.AgentConfig;
import com.onurkat.reclazz.agent.ClassReloader;
import com.onurkat.reclazz.transform.ReclazzTransformer;
import com.onurkat.reclazz.transform.TransformContext;
import com.onurkat.reclazz.transform.TransformTestBase;
import com.onurkat.reclazz.ui.StatusReporter;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What happens to a removed method's existing callers, which is now one thing
 * and used to be three.
 *
 * <p>The JVM will not let a method leave a loaded class, so the payload
 * carries it either way and the only question is what is in it. It was
 * AddedMemberStripper's stub, which throws, and whether a caller met that stub
 * depended on history the developer cannot see: a call site retargeted to a
 * companion by an earlier reload kept the last reloaded body, a refused
 * redefinition kept everything, and a site that had never been reloaded threw
 * {@code UnsupportedOperationException} (measured on Spring Boot 3.3.4: a
 * removed getter turned its JSON endpoint into HTTP 500). One edit, three
 * outcomes, and the worst of them kills a live request on the developer's own
 * server for a change they made deliberately.
 *
 * <p>Now the previous implementation is spliced into the payload, so all three
 * paths end the same way and the way they end is the one the README already
 * promised: the member is hidden from reflection immediately, so every scan
 * stops acting on it, and code that already holds it keeps running. What a
 * direct call means is settled at the next restart, where the caller either
 * compiles without the method or does not compile at all.
 *
 * <p>These tests replay each of the three histories through the production
 * reload chain and hold them to the same answer.
 */
class RemovedMethodWarningTest extends TransformTestBase {

    private static Instrumentation instrumentation;

    @BeforeAll
    static void setup() {
        instrumentation = ByteBuddyAgent.install();
        assertNotNull(instrumentation);
    }

    private record Rig(TransformContext context, ReclazzTransformer transformer,
                       StructuralReloader reloader, Class<?> cls, Object instance,
                       Method keep, Method gone, List<String> warnings,
                       StatusReporter.StatusListener listener) {
    }

    private static Rig rig(String name, String v1Source) throws Exception {
        TransformContext context = new TransformContext();
        context.addWatched(name);
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));

        byte[] raw = compile(new SourceFile(name, v1Source)).get(name);
        byte[] transformed = transformer.transform(
                TransformTestBase.class.getClassLoader(), name, null, null, raw);
        assertNotNull(transformed);

        Map<String, byte[]> loadMap = new LinkedHashMap<>();
        loadMap.put(name, transformed);
        Class<?> cls = defineAndLoad(loadMap, name);
        Object instance = cls.getDeclaredConstructor().newInstance();
        Method keep = cls.getDeclaredMethod("keep");
        Method gone = cls.getDeclaredMethod("gone");
        assertEquals("keep-v1", keep.invoke(instance));
        assertEquals("gone-v1", gone.invoke(instance));

        StructuralReloader reloader = new StructuralReloader(
                instrumentation, context, AgentConfig.parse(null), null);
        reloader.setTransformer(transformer);

        List<String> warnings = new ArrayList<>();
        StatusReporter.StatusListener listener = (level, message) -> {
            if ("WARN".equals(level)) warnings.add(message);
        };
        StatusReporter.addListener(listener);
        instrumentation.addTransformer(transformer, true);
        return new Rig(context, transformer, reloader, cls, instance, keep, gone,
                warnings, listener);
    }

    private static void teardown(Rig rig) {
        instrumentation.removeTransformer(rig.transformer());
        StatusReporter.removeListener(rig.listener());
    }

    /**
     * A method that was reloaded and then removed: its call site was
     * retargeted to a companion, so the stub the successful redefinition
     * installs under the renamed fallback is never reached and callers keep
     * the last reloaded body. This is the case the SAP Commerce integration
     * run's remove-method scenario measures live; claiming a throw here
     * would be the original mistake mirrored.
     */
    @Test
    void aPreviouslyReloadedMethodsCallersKeepItsLastBodyAndTheMessageSaysSo() throws Exception {
        Rig rig = rig("RemovedAfterReload",
                "public class RemovedAfterReload {\n" +
                "    public String keep() { return \"keep-v1\"; }\n" +
                "    public String gone() { return \"gone-v1\"; }\n" +
                "}");
        try {
            // Body-only reload of gone(): its call site now dispatches to the
            // companion, not to the renamed fallback.
            ClassReloader.ReloadResult edited = rig.reloader().reload("RemovedAfterReload",
                    compile(new SourceFile("RemovedAfterReload",
                            "public class RemovedAfterReload {\n" +
                            "    public String keep() { return \"keep-v1\"; }\n" +
                            "    public String gone() { return \"gone-v2\"; }\n" +
                            "}")).get("RemovedAfterReload"));
            assertTrue(edited.isSuccess(), String.valueOf(edited.getError()));
            assertEquals("gone-v2", rig.gone().invoke(rig.instance()));
            rig.warnings().clear();

            ClassReloader.ReloadResult removed = rig.reloader().reload("RemovedAfterReload",
                    compile(new SourceFile("RemovedAfterReload",
                            "public class RemovedAfterReload {\n" +
                            "    public String keep() { return \"keep-v3\"; }\n" +
                            "}")).get("RemovedAfterReload"));
            assertTrue(removed.isSuccess(), String.valueOf(removed.getError()));
            assertEquals("keep-v3", rig.keep().invoke(rig.instance()));

            assertEquals("gone-v2", rig.gone().invoke(rig.instance()),
                    "the companion body keeps serving; the stub under the renamed "
                    + "fallback is never reached");

            String warning = String.join("\n", rig.warnings());
            assertTrue(warning.contains("keeps the previous implementation until restart"),
                    "the warning must describe the kept case, got: " + warning);
            assertTrue(warning.contains("hidden from reflection"),
                    "and what removal does apply immediately: " + warning);
            assertFalse(warning.contains("UnsupportedOperationException"),
                    "a throw that does not happen must not be claimed: " + warning);
        } finally {
            teardown(rig);
        }
    }

    /**
     * Rewrites the class's record so {@code extra()} counts as an existing
     * method. The removal payload then carries it, the loaded class does
     * not, and the JVM refuses the redefinition: the refused branch this
     * test is about.
     */
    private static void recordExtraAsExisting(TransformContext context, String name) {
        TransformContext.ClassMetadata m = context.getMetadata(name);
        assertNotNull(m);
        List<TransformContext.MethodSig> methods = new ArrayList<>(m.getMethods());
        if (methods.stream().noneMatch(s -> "extra".equals(s.name()))) {
            methods.add(new TransformContext.MethodSig(
                    "extra", "()Ljava/lang/String;", java.lang.reflect.Modifier.PUBLIC));
        }
        context.putMetadata(name, new TransformContext.ClassMetadata(
                methods, m.getFields(), 0, m.getSuperName(), m.getAnnotations(),
                m.isInterfacesKnown() ? m.getInterfaces() : null));
    }

    /**
     * A method that was never reloaded before its removal. Its call sites fall
     * back to the renamed method, which is exactly where the throwing stub
     * used to land, and this is the case that turned a live endpoint into an
     * HTTP 500. The previous implementation is spliced there instead, so the
     * call still answers what it answered before the save.
     */
    @Test
    void aNeverReloadedMethodsCallersKeepItsBody() throws Exception {
        Rig rig = rig("RemovedNoExtra",
                "public class RemovedNoExtra {\n" +
                "    public String keep() { return \"keep-v1\"; }\n" +
                "    public String gone() { return \"gone-v1\"; }\n" +
                "}");
        try {
            ClassReloader.ReloadResult result = rig.reloader().reload("RemovedNoExtra",
                    compile(new SourceFile("RemovedNoExtra",
                            "public class RemovedNoExtra {\n" +
                            "    public String keep() { return \"keep-v2\"; }\n" +
                            "}")).get("RemovedNoExtra"));
            assertTrue(result.isSuccess(), String.valueOf(result.getError()));
            assertEquals("keep-v2", rig.keep().invoke(rig.instance()),
                    "the rest of the save still applies");

            assertEquals("gone-v1", rig.gone().invoke(rig.instance()),
                    "this is the call that used to throw UnsupportedOperationException "
                    + "on a live server for a removal the developer made on purpose");

            String warning = String.join("\n", rig.warnings());
            assertTrue(warning.contains("keeps the previous implementation until restart"),
                    "the warning must describe the kept case, got: " + warning);
            assertFalse(warning.contains("UnsupportedOperationException"),
                    "nothing throws here any more: " + warning);
        } finally {
            teardown(rig);
        }
    }

    /**
     * When the redefinition is refused, the old implementation really keeps
     * serving; this is the one case the old universal sentence described
     * correctly, and it has to survive the split.
     *
     * <p>Getting the refusal takes more than adding a member first: writing
     * this test measured that the transformer rewrites the class's metadata
     * record during every redefinition, so a healthy record re-detects the
     * earlier-added member as added and the payload re-strips it, and the
     * redefinition lands again. The refusal occurs when the record still
     * lists the added member as existing, so the payload carries a method
     * the loaded class never gained; the live Spring Boot measurement behind
     * this split hit exactly that. The test constructs that record state
     * directly, because the message must be right whenever the refusal
     * happens, however the record got there.
     */
    @Test
    void whenTheRedefinitionIsRefusedCallersKeepTheOldBodyAndTheMessageSaysSo() throws Exception {
        Rig rig = rig("RemovedAfterAdd",
                "public class RemovedAfterAdd {\n" +
                "    public String keep() { return \"keep-v1\"; }\n" +
                "    public String gone() { return \"gone-v1\"; }\n" +
                "}");
        try {
            // First reload adds a member, so the class carries one beyond its
            // loaded shape.
            ClassReloader.ReloadResult added = rig.reloader().reload("RemovedAfterAdd",
                    compile(new SourceFile("RemovedAfterAdd",
                            "public class RemovedAfterAdd {\n" +
                            "    public String keep() { return \"keep-v1\"; }\n" +
                            "    public String gone() { return \"gone-v1\"; }\n" +
                            "    public String extra() { return \"extra\"; }\n" +
                            "}")).get("RemovedAfterAdd"));
            assertTrue(added.isSuccess(), String.valueOf(added.getError()));
            rig.warnings().clear();

            // The record state that makes the JVM refuse: extra listed as
            // existing, so the removal payload keeps it and no longer matches
            // the loaded shape.
            recordExtraAsExisting(rig.context(), "RemovedAfterAdd");

            ClassReloader.ReloadResult removed = rig.reloader().reload("RemovedAfterAdd",
                    compile(new SourceFile("RemovedAfterAdd",
                            "public class RemovedAfterAdd {\n" +
                            "    public String keep() { return \"keep-v3\"; }\n" +
                            "    public String extra() { return \"extra\"; }\n" +
                            "}")).get("RemovedAfterAdd"));
            assertTrue(removed.isSuccess(), String.valueOf(removed.getError()));
            assertEquals("keep-v3", rig.keep().invoke(rig.instance()));

            assertEquals("gone-v1", rig.gone().invoke(rig.instance()),
                    "the refused redefinition leaves the old implementation serving, "
                    + "which is what the message claims");

            String warning = String.join("\n", rig.warnings());
            assertTrue(warning.contains("keeps the previous implementation until restart"),
                    "the warning must describe the refused case, got: " + warning);
            assertFalse(warning.contains("Existing callers will now fail"),
                    "claiming a throw that does not happen is the same mistake "
                    + "mirrored: " + warning);
        } finally {
            teardown(rig);
        }
    }
}
