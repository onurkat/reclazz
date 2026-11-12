/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.reload.CompanionGenerator;
import com.onurkat.reclazz.reload.StructuralAnalyzer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CompanionInheritedProtectedFieldTest extends TransformTestBase {
    private record Fixture(Class<?> host, Class<?> base, MethodHandles.Lookup companion) {}

    private Fixture fixture() throws Throwable {
        var sources = new SourceFile[]{
                new SourceFile("sample.base.Base", """
                        package sample.base;
                        public class Base {
                            protected int number;
                            protected long wide;
                            protected double decimal;
                            protected Object reference;
                            protected static int shared;
                            protected volatile int visible;
                            public int publicNumber;
                        }
                        """),
                new SourceFile("sample.child.Local", """
                        package sample.child;
                        public class Local extends sample.base.Base { protected int local; }
                        """),
                new SourceFile("sample.child.Host", """
                        package sample.child;
                        public class Host extends Local {
                            private String own = "private";
                            private int number = 100;
                            public String run() {
                                super.number = 7; super.wide = 11L; decimal = 13.5;
                                reference = "ref"; shared = 17; super.shared = 19;
                                visible = 23; publicNumber = 29; local = 31;
                                return super.number + "|" + super.wide + "|" + decimal + "|" + reference
                                    + "|" + shared + "|" + visible + "|" + publicNumber + "|" + local
                                    + "|" + own + "|" + number;
                            }
                        }
                        """)};
        var original = compile(sources);
        var loader = sharedLoader(compileAndTransform(sources));
        Class<?> host = loader.load("sample.child.Host");
        Class<?> base = loader.load("sample.base.Base");
        var field = host.getDeclaredField(InjectedNames.LOOKUP_FIELD);
        field.setAccessible(true);
        var lookup = (MethodHandles.Lookup) field.get(null);
        byte[] bytes = original.get("sample/child/Host");
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        var before = new TransformContext.ClassMetadata(
                node.methods.stream().map(m -> new TransformContext.MethodSig(m.name, m.desc, m.access)).toList(),
                node.fields.stream().map(f -> new TransformContext.FieldSig(f.name, f.desc, f.access)).toList(),
                0, node.superName);
        var companion = CompanionGenerator.generate(node.name, bytes, StructuralAnalyzer.analyze(before, bytes),
                1, Set.of(), Set.of());
        ClassNode copied = new ClassNode(); new ClassReader(companion.getBytecode()).accept(copied, 0);
        var run = copied.methods.stream().filter(m -> m.name.equals("run")).findFirst().orElseThrow();
        Set<String> sites = new java.util.HashSet<>();
        for (var instruction : run.instructions) {
            if (instruction instanceof InvokeDynamicInsnNode site && site.bsm.getName().equals("physicalField"))
                sites.add(site.desc);
        }
        assertTrue(sites.containsAll(Set.of("(Lsample/child/Local;I)V", "(Lsample/child/Local;)I",
                "(I)V", "()I", "(Lsample/child/Local;J)V", "(Lsample/child/Local;)J")), sites.toString());
        return new Fixture(host, base, lookup.defineHiddenClass(companion.getBytecode(), true,
                MethodHandles.Lookup.ClassOption.NESTMATE));
    }

    @Test void inheritedFieldsAndOriginalAccessControlsSurviveInARealHiddenCompanion() throws Throwable {
        Fixture f = fixture();
        Object value = f.host().getConstructor().newInstance();
        var run = f.companion().findStatic(f.companion().lookupClass(), "run",
                MethodType.methodType(String.class, f.host()));
        assertEquals("7|11|13.5|ref|19|23|29|31|private|100", run.invoke(value));
        assertEquals("7|11|13.5|ref|19|23|29|31|private|100", run.invoke(value));
    }

    @Test void sameNamedHierarchiesKeepTheirLoaderAndReceiverRestrictions() throws Throwable {
        Fixture first = fixture(), second = fixture();
        assertNotSame(first.host(), second.host());
        var resolver = com.onurkat.reclazz.bootstrap.ProtectedCallResolver.class;
        var bootstrap = resolver.getMethod("physicalField", MethodHandles.Lookup.class, String.class,
                MethodType.class, String.class, String.class, int.class);
        // The symbolic owner is the base: host access narrows the handle's receiver to Host.
        var site = (java.lang.invoke.CallSite) bootstrap.invoke(null, first.companion(), "wide",
                MethodType.methodType(long.class, first.base()), "sample/base/Base", "sample/child/Host", 0);
        Object firstValue = first.host().getConstructor().newInstance();
        Object secondValue = second.host().getConstructor().newInstance();
        assertEquals(0L, site.dynamicInvoker().invoke(firstValue));
        assertThrows(ClassCastException.class, () -> site.dynamicInvoker().invoke(secondValue));
        assertThrows(ClassCastException.class,
                () -> site.dynamicInvoker().invoke(first.base().getConstructor().newInstance()));
        var own = (java.lang.invoke.CallSite) bootstrap.invoke(null, first.companion(), "own",
                MethodType.methodType(String.class, first.host()), "sample/child/Host", "sample/child/Host", 0);
        assertEquals("private", own.dynamicInvoker().invoke(firstValue));
        var missing = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> bootstrap.invoke(null, first.companion(), "missing",
                        MethodType.methodType(void.class, int.class), "sample/base/Base", "sample/child/Host", 3));
        assertInstanceOf(NoSuchFieldException.class, missing.getCause());
    }
}
