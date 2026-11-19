/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.bootstrap.EnumSurgery;
import com.onurkat.reclazz.bootstrap.FieldStore;
import com.onurkat.reclazz.transform.TransformContext;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SwitchMapRefreshTest implements Opcodes {
    public enum Mode { FIRST, SECOND, THIRD }
    private static final String FIELD = "$SwitchMap$sample$Mode";
    private static byte[] bytes(boolean physical) {
        ClassWriter w = new ClassWriter(0); w.visit(V17, ACC_PUBLIC, "sample/Holder$1", null, "java/lang/Object", null);
        if (physical) w.visitField(ACC_STATIC | ACC_FINAL | ACC_SYNTHETIC, FIELD, "[I", null, null).visitEnd();
        w.visitEnd(); return w.toByteArray();
    }
    private static Class<?> owner(boolean physical) {
        return new ClassLoader(SwitchMapRefreshTest.class.getClassLoader()) {
            Class<?> define() { byte[] b = bytes(physical); return defineClass("sample.Holder$1", b, 0, b.length); }
        }.define();
    }
    private static Field physical(Class<?> owner) throws Exception { return owner.getDeclaredField(FIELD); }
    private static SwitchMapPlan plan(int first, int second) {
        return new SwitchMapPlan("sample/Holder$1", List.of(new SwitchMapPlan.Table(FIELD,
                Mode.class.getName().replace('.', '/'), List.of(new SwitchMapPlan.Entry("FIRST", first),
                new SwitchMapPlan.Entry("SECOND", second), new SwitchMapPlan.Entry("ABSENT", 7)))));
    }
    private static void begin(Class<?> owner, SwitchMapPlan plan) {
        SwitchMapRefresh.begin(Map.of(owner.getName(), bytes(true)), Map.of(owner.getName(), plan),
                name -> name.equals(owner.getName()) ? owner : null);
    }
    @Test void physicalTableRefreshesOnlyAfterSuccessAndMissingConstantsAreSkipped() throws Exception {
        Class<?> owner = owner(true); int[] old = {1, 2, 0}; EnumSurgery.writeSwitchTable(physical(owner), old);
        begin(owner, plan(2, 1));
        try {
            assertSame(old, EnumSurgery.readSwitchTable(physical(owner)));
            SwitchMapRefresh.result(owner, true); SwitchMapRefresh.complete(null);
            assertArrayEquals(new int[]{2, 1, 0}, EnumSurgery.readSwitchTable(physical(owner)));
            assertNull(SwitchMapRefresh.failure(owner));
        } finally { SwitchMapRefresh.end(); }
    }
    @Test void addedTableCanBeRenumberedAgainOnTheNextSave() {
        Class<?> owner = owner(false);
        for (int round : List.of(1, 2)) {
            begin(owner, plan(round, 3 - round));
            try {
                SwitchMapRefresh.result(owner, true); SwitchMapRefresh.complete(null);
                assertArrayEquals(new int[]{round, 3 - round, 0}, (int[]) FieldStore.getStaticExtField(owner, FIELD, "[I"));
            } finally { SwitchMapRefresh.end(); }
        }
    }
    @Test void forcedNativeFailureLeavesTheOldTableObjectUntouched() throws Exception {
        Class<?> owner = owner(true); int[] old = {1, 2, 0}; EnumSurgery.writeSwitchTable(physical(owner), old);
        Instrumentation failing = (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Instrumentation.class},
                (proxy, method, args) -> { if (method.getName().equals("redefineClasses")) throw new ClassFormatError("forced test failure"); return null; });
        StructuralReloader structural = new StructuralReloader(failing, new TransformContext(), AgentConfig.parse(null), null);
        structural.beginBatch(); structural.redefine(owner, new byte[0], outcome -> { }); structural.endBatch();
        assertEquals(StructuralReloader.NativeOutcome.FAILED, structural.nativeOutcome(owner));
        begin(owner, plan(2, 1));
        try {
            SwitchMapRefresh.result(owner, true); SwitchMapRefresh.complete(structural);
            assertSame(old, EnumSurgery.readSwitchTable(physical(owner))); assertNotNull(SwitchMapRefresh.failure(owner));
        } finally { SwitchMapRefresh.end(); }
    }
    @Test void matchingPlanDoesNotWriteAndAnotherLoaderKeepsItsTable() throws Exception {
        Class<?> a = owner(true), b = owner(true); int[] originalA = {1, 2, 0}, originalB = {1, 2, 0};
        EnumSurgery.writeSwitchTable(physical(a), originalA); EnumSurgery.writeSwitchTable(physical(b), originalB);
        var present = new SwitchMapPlan("sample/Holder$1", List.of(new SwitchMapPlan.Table(FIELD,
                Mode.class.getName().replace('.', '/'), List.of(new SwitchMapPlan.Entry("FIRST", 1), new SwitchMapPlan.Entry("SECOND", 2)))));
        assertTrue(SwitchMapRefresh.matches(a, present)); assertSame(originalA, EnumSurgery.readSwitchTable(physical(a)));
        begin(a, plan(2, 1));
        try { SwitchMapRefresh.result(a, true); SwitchMapRefresh.complete(null); }
        finally { SwitchMapRefresh.end(); }
        assertArrayEquals(new int[]{2, 1, 0}, EnumSurgery.readSwitchTable(physical(a)));
        assertSame(originalB, EnumSurgery.readSwitchTable(physical(b)));
    }
    @Test void missingCaseMustNotLookLikeAnUnchangedTableBeforeEnumAppend() throws Exception {
        Class<?> owner = owner(true); EnumSurgery.writeSwitchTable(physical(owner), new int[]{1, 2, 0});
        assertFalse(SwitchMapRefresh.matches(owner, plan(1, 2)), "a case absent from the live enum may be appended later in this same save");
    }
    @Test void appliedHashIsClassScopedAndInvalidatedBeforeFailedMutation() {
        Class<?> a = owner(false), b = owner(false); byte[] original = bytes(true);
        begin(a, plan(1, 2)); SwitchMapRefresh.result(a, true); SwitchMapRefresh.complete(null); SwitchMapRefresh.end();
        assertTrue(SwitchMapRefresh.alreadyApplied(a, original)); assertFalse(SwitchMapRefresh.alreadyApplied(b, original));
        SwitchMapRefresh.mutationStarting(a);
        begin(a, plan(2, 1)); SwitchMapRefresh.result(a, false); SwitchMapRefresh.complete(null); SwitchMapRefresh.end();
        assertFalse(SwitchMapRefresh.alreadyApplied(a, original), "a failed edit must not hide a later restore");
    }
    @Test void failedPendingEnumBlocksItsHolderBeforeReload() {
        Class<?> holder = owner(false);
        byte[] enumBytes;
        try (var input = Mode.class.getResourceAsStream("SwitchMapRefreshTest$Mode.class")) {
            enumBytes = input.readAllBytes();
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        SwitchMapRefresh.begin(Map.of(holder.getName(), bytes(true), Mode.class.getName(), enumBytes),
                Map.of(holder.getName(), plan(1, 2)), name -> name.equals(holder.getName()) ? holder : Mode.class);
        try {
            assertNull(SwitchMapRefresh.dependencyFailure(holder, null));
            SwitchMapRefresh.result(Mode.class, false);
            assertNotNull(SwitchMapRefresh.dependencyFailure(holder, null));
            SwitchMapRefresh.complete(null); assertNotNull(SwitchMapRefresh.failure(holder));
        } finally { SwitchMapRefresh.end(); }
    }
    @Test void failedEvaluationDoesNotPartlyWriteEarlierTables() throws Exception {
        Class<?> owner = owner(true); int[] old = {1, 2, 0}; EnumSurgery.writeSwitchTable(physical(owner), old);
        var tables = new ArrayList<>(plan(2, 1).tables());
        tables.add(new SwitchMapPlan.Table("$SwitchMap$missing", "absent/Enum", List.of()));
        begin(owner, new SwitchMapPlan("sample/Holder$1", tables));
        try {
            SwitchMapRefresh.result(owner, true); SwitchMapRefresh.complete(null);
            assertSame(old, EnumSurgery.readSwitchTable(physical(owner))); assertNotNull(SwitchMapRefresh.failure(owner));
        } finally { SwitchMapRefresh.end(); }
    }
}
