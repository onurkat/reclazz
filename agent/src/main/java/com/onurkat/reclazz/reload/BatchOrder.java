/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.watcher.ChangeEvent;
import org.objectweb.asm.ClassReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The order the classes of one save are reloaded in: what is called before
 * what calls it.
 *
 * <p>A reload is atomic for one class and not for a save. Between the first
 * class of a save and the last, the application runs a mixture, and the
 * mixture that fails is a caller reloaded before its callee: the caller's new
 * body reaches for a method the save added to the callee, the callee still has
 * its old shape, and the call throws until the callee's turn comes. The other
 * way round is safe in every case, because a reloaded callee keeps every
 * method its old callers use. So the callee goes first. Within a save the
 * references are read from each class file's constant pool, only references
 * to classes in the same save count, and a cycle is broken at the class the
 * most of its members call, arrival order settling a tie.
 *
 * <p>This does not make a save atomic; it removes the one interleaving that
 * fails by construction. What is left is a window in which old callers reach
 * new callees, which is what every other reload already is.
 */
public final class BatchOrder {

    private BatchOrder() {
    }

    /** {@code events}, callees first; anything unreadable keeps its place. */
    public static List<ChangeEvent> calleesFirst(List<ChangeEvent> events) {
        if (events.size() < 2) return events;

        // Internal name -> event, for the class files that can be read.
        Map<String, ChangeEvent> byName = new LinkedHashMap<>();
        Map<ChangeEvent, Set<String>> referenced = new HashMap<>();
        for (ChangeEvent event : events) {
            Path path = event.getPath();
            try {
                byte[] bytes = event.getBytes();
                if (bytes == null) bytes = Files.readAllBytes(path);
                ClassReader reader = new ClassReader(bytes);
                byName.put(reader.getClassName(), event);
                referenced.put(event, referencedClasses(reader));
            } catch (Exception unreadable) {
                // Not a class file, or not yet a whole one; it keeps its place.
            }
        }
        if (byName.size() < 2) return events;

        // Edges: caller -> callee, both in this save. A class's own name and
        // its nest (inner classes) are left out so an outer/inner pair does not
        // make a cycle out of every save that touches both.
        Map<ChangeEvent, List<ChangeEvent>> callees = new LinkedHashMap<>();
        Map<ChangeEvent, Integer> remaining = new LinkedHashMap<>();
        for (ChangeEvent event : events) {
            callees.put(event, new ArrayList<>());
            remaining.put(event, 0);
        }
        for (Map.Entry<String, ChangeEvent> entry : byName.entrySet()) {
            String caller = entry.getKey();
            ChangeEvent callerEvent = entry.getValue();
            for (String name : referenced.getOrDefault(callerEvent, Set.of())) {
                ChangeEvent calleeEvent = byName.get(name);
                if (calleeEvent == null || calleeEvent == callerEvent) continue;
                if (sameNest(caller, name)) continue;
                callees.get(callerEvent).add(calleeEvent);
                remaining.merge(callerEvent, 1, Integer::sum);
            }
        }

        // Kahn's algorithm from the callee side: emit what nothing left
        // depends on, in arrival order among the ready ones.
        List<ChangeEvent> ordered = new ArrayList<>(events.size());
        Deque<ChangeEvent> ready = new ArrayDeque<>();
        for (ChangeEvent event : events) {
            if (remaining.get(event) == 0) ready.add(event);
        }
        Set<ChangeEvent> emitted = new LinkedHashSet<>();
        while (emitted.size() < events.size()) {
            if (ready.isEmpty()) {
                // A cycle. Break it at the class the most of the stuck ones
                // call, so the class most likely to be reached goes first;
                // arrival order settles a tie.
                ChangeEvent mostCalled = null;
                int mostCallers = -1;
                for (ChangeEvent candidate : events) {
                    if (emitted.contains(candidate)) continue;
                    int callers = 0;
                    for (ChangeEvent other : events) {
                        if (!emitted.contains(other) && callees.get(other).contains(candidate)) callers++;
                    }
                    if (callers > mostCallers) {
                        mostCallers = callers;
                        mostCalled = candidate;
                    }
                }
                ready.add(mostCalled);
            }
            ChangeEvent next = ready.poll();
            if (!emitted.add(next)) continue;
            ordered.add(next);
            for (ChangeEvent caller : events) {
                if (emitted.contains(caller)) continue;
                if (callees.get(caller).remove(next)) {
                    if (remaining.merge(caller, -1, Integer::sum) == 0) ready.add(caller);
                }
            }
        }
        return ordered;
    }

    private static boolean sameNest(String a, String b) {
        return outer(a).equals(outer(b));
    }

    private static String outer(String internalName) {
        int dollar = internalName.indexOf('$');
        return dollar < 0 ? internalName : internalName.substring(0, dollar);
    }

    /** Every class the constant pool names, from CONSTANT_Class entries. */
    static Set<String> referencedClasses(ClassReader reader) {
        Set<String> names = new LinkedHashSet<>();
        char[] buffer = new char[reader.getMaxStringLength()];
        int items = reader.getItemCount();
        for (int i = 1; i < items; i++) {
            int offset = reader.getItem(i);
            if (offset == 0) continue;
            if (reader.readByte(offset - 1) != 7) continue;   // CONSTANT_Class
            String name = reader.readUTF8(offset, buffer);
            if (name == null || name.startsWith("[")) continue;
            names.add(name);
        }
        return names;
    }
}
