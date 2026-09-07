/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.transform.TransformTestBase;
import com.onurkat.reclazz.watcher.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Within one save, a class is reloaded after the classes it calls. Read
 * from real class files, because the references come from the constant
 * pool and that is what javac writes.
 */
class BatchOrderTest extends TransformTestBase {

    @TempDir
    Path dir;

    @Test
    void aCallerIsReloadedAfterItsCallee() throws Exception {
        List<ChangeEvent> events = write(compile(
                new SourceFile("Front", "public class Front { public String go() { return new Middle().go(); } }"),
                new SourceFile("Middle", "public class Middle { public String go() { return new Back().go(); } }"),
                new SourceFile("Back", "public class Back { public String go() { return \"back\"; } }")),
                "Front", "Middle", "Back");

        assertEquals(List.of("Back", "Middle", "Front"), names(BatchOrder.calleesFirst(events)));
    }

    @Test
    void unrelatedClassesKeepTheOrderTheyArrivedIn() throws Exception {
        List<ChangeEvent> events = write(compile(
                new SourceFile("One", "public class One { }"),
                new SourceFile("Two", "public class Two { }"),
                new SourceFile("Three", "public class Three { }")),
                "Two", "Three", "One");

        assertEquals(List.of("Two", "Three", "One"), names(BatchOrder.calleesFirst(events)));
    }

    @Test
    void aCycleKeepsArrivalOrderAndLosesNothing() throws Exception {
        List<ChangeEvent> events = write(compile(
                new SourceFile("Ping", "public class Ping { public Object other() { return new Pong(); } }"),
                new SourceFile("Pong", "public class Pong { public Object other() { return new Ping(); } }"),
                new SourceFile("Leaf", "public class Leaf { }"),
                new SourceFile("Root", "public class Root { Object l = new Leaf(); Object p = new Ping(); }")),
                "Root", "Ping", "Pong", "Leaf");

        List<String> ordered = names(BatchOrder.calleesFirst(events));
        assertEquals(4, ordered.size());
        assertTrue(ordered.indexOf("Leaf") < ordered.indexOf("Root"), ordered.toString());
        assertTrue(ordered.indexOf("Ping") < ordered.indexOf("Root"), ordered.toString());
        assertTrue(ordered.indexOf("Ping") < ordered.indexOf("Pong"), "the cycle keeps arrival order: " + ordered);
    }

    @Test
    void anInnerClassDoesNotMakeACycleWithItsOuter() throws Exception {
        List<ChangeEvent> events = write(compile(
                new SourceFile("Outer", "public class Outer { class Inner { Outer o() { return Outer.this; } } "
                        + "public Object mk() { return new Inner(); } public Object h() { return new Helper(); } }"),
                new SourceFile("Helper", "public class Helper { }")),
                "Outer", "Outer$Inner", "Helper");

        List<String> ordered = names(BatchOrder.calleesFirst(events));
        assertEquals(3, ordered.size());
        assertTrue(ordered.indexOf("Helper") < ordered.indexOf("Outer"), "the helper before its user: " + ordered);
        assertTrue(ordered.indexOf("Outer$Inner") < ordered.indexOf("Outer"),
                "the nest is not a cycle, so the inner class is a plain leaf: " + ordered);
    }

    @Test
    void anUnreadableFileKeepsItsPlaceAndTheRestStillSorts() throws Exception {
        List<ChangeEvent> events = write(compile(
                new SourceFile("User", "public class User { Object u = new Used(); }"),
                new SourceFile("Used", "public class Used { }")),
                "User", "Used");
        Path broken = dir.resolve("Broken.class");
        Files.write(broken, new byte[]{(byte) 0xCA, (byte) 0xFE});
        events.add(1, new ChangeEvent(broken, ChangeEvent.Type.MODIFIED, "m", null));

        List<String> ordered = names(BatchOrder.calleesFirst(events));
        assertEquals(3, ordered.size());
        assertTrue(ordered.indexOf("Used") < ordered.indexOf("User"), ordered.toString());
        assertTrue(ordered.contains("Broken"));
    }

    @Test
    void aSingleEventIsReturnedAsIs() {
        List<ChangeEvent> one = List.of(new ChangeEvent(dir.resolve("X.class"), ChangeEvent.Type.MODIFIED, "m", null));
        assertSame(one, BatchOrder.calleesFirst(one));
    }

    private List<ChangeEvent> write(Map<String, byte[]> compiled, String... arrivalOrder) throws Exception {
        List<ChangeEvent> events = new ArrayList<>();
        for (String name : arrivalOrder) {
            Path file = dir.resolve(name + ".class");
            Files.write(file, compiled.get(name));
            events.add(new ChangeEvent(file, ChangeEvent.Type.MODIFIED, "m", null));
        }
        return events;
    }

    private static List<String> names(List<ChangeEvent> events) {
        List<String> names = new ArrayList<>();
        for (ChangeEvent e : events) {
            String f = e.getPath().getFileName().toString();
            names.add(f.substring(0, f.length() - ".class".length()));
        }
        return names;
    }
}
