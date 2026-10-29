/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.agent.ClassReloader;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.transform.ReclazzTransformer;
import com.onurkat.reclazz.transform.ReflectionRootFilter;
import com.onurkat.reclazz.transform.TransformContext;
import com.onurkat.reclazz.transform.TransformTestBase;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A class loaded after startup meets the root reflection filter on its first
 * reload. That reload also shows the added members to reflection, and a scan
 * landing between the two steps saw both the added members and the
 * {@code __reclazz$} ones: a framework meta-reflection scan under load reported
 * an injected member leak. The hiding has to be on before anything is shown.
 *
 * <p>The class is defined on the test classes' own loader, which lives as long
 * as the JVM, because the root filter is deliberately skipped for discardable
 * loaders.
 */
class InjectedMembersHiddenBeforeShownTest extends TransformTestBase {

    private static final String NAME = "com/onurkat/reclazz/reload/HiddenBeforeShownTarget";

    @AfterEach
    void reset() {
        StructuralReloader.afterSwitchStepProbe = null;
    }

    @Test
    void theFirstReloadHidesInjectedMembersBeforeShowingAddedOnes() throws Exception {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        ReflectionRootFilter.install(instrumentation);

        TransformContext context = new TransformContext();
        context.addWatched(NAME);
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        ClassLoader loader = InjectedMembersHiddenBeforeShownTest.class.getClassLoader();
        byte[] transformed = transformer.transform(loader, NAME, null, null,
                compile(new SourceFile(NAME, source(""))).get(NAME));
        Class<?> cls = MethodHandles.lookup().defineClass(transformed);
        Object instance = Class.forName(cls.getName(), true, loader).getDeclaredConstructor().newInstance();
        assertFalse(injectedNames(cls).isEmpty(), "loaded after startup, the class is not filtered yet");

        List<String> steps = new CopyOnWriteArrayList<>();
        List<String> leakedWhenShown = new CopyOnWriteArrayList<>();
        StructuralReloader.afterSwitchStepProbe = what -> {
            steps.add(what);
            if (what.equals("showing the added members to reflection")) {
                try {
                    leakedWhenShown.addAll(injectedNames(cls));
                } catch (Exception e) {
                    leakedWhenShown.add("probe failed: " + e);
                }
            }
        };

        StructuralReloader reloader = new StructuralReloader(instrumentation, context, AgentConfig.parse(null), null);
        reloader.setTransformer(transformer);
        instrumentation.addTransformer(transformer, true);
        try {
            ClassReloader.ReloadResult result = reloader.reload(NAME.replace('/', '.'), compile(new SourceFile(NAME,
                    source("    public String getLabel() { return \"label\"; }\n"))).get(NAME));
            assertTrue(result.isSuccess(), String.valueOf(result.getError()));
        } finally {
            instrumentation.removeTransformer(transformer);
        }

        assertEquals("v2", cls.getMethod("hello").invoke(instance), "the reload went live");
        assertTrue(steps.contains("showing the added members to reflection"), steps.toString());
        assertEquals(List.of(), leakedWhenShown,
                "a meta-reflection scan at the moment the added members appear sees no injected member");
        assertTrue(steps.indexOf("hiding the injected members from reflection")
                        < steps.indexOf("showing the added members to reflection"),
                "the injected members are hidden before the added ones are shown: " + steps);
        assertEquals(List.of(), injectedNames(cls), "and none afterwards");
    }

    /** Meta-reflection, the route no rewritten call site covers. */
    private static List<String> injectedNames(Class<?> cls) throws Exception {
        Method[] methods = (Method[]) Class.class.getMethod("getDeclaredMethods").invoke(cls);
        Field[] fields = (Field[]) Class.class.getMethod("getDeclaredFields").invoke(cls);
        List<String> names = new ArrayList<>();
        Stream.concat(Arrays.stream(methods).map(Method::getName), Arrays.stream(fields).map(Field::getName))
                .filter(name -> name.startsWith("__reclazz$"))
                .forEach(names::add);
        return names;
    }

    private static String source(String extra) {
        return "package com.onurkat.reclazz.reload;\n"
                + "public class HiddenBeforeShownTarget {\n"
                + "    public String hello() { return \"" + (extra.isEmpty() ? "v1" : "v2") + "\"; }\n"
                + extra
                + "}";
    }
}
