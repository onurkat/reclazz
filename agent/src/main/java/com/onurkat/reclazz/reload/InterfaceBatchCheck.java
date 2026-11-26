/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.transform.RawClassSchema;
import com.onurkat.reclazz.util.ClassLookup;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.HashSet;
import java.util.Map;
import java.util.TreeSet;

/** Admission for captured stock-VM saves. Never defines a method or loads an incoming class. */
public final class InterfaceBatchCheck {
    private InterfaceBatchCheck() { }

    public record Hold(String subject, String reason) { }

    public static Hold check(Map<String, byte[]> incoming, ClassLookup.LoadedClasses loaded) {
        var interfaces = new TreeSet<String>();
        for (var entry : incoming.entrySet()) {
            int flags = new ClassReader(entry.getValue()).getAccess();
            if ((flags & Opcodes.ACC_INTERFACE) == 0 || (flags & Opcodes.ACC_ANNOTATION) != 0) continue;
            var definitions = loaded.definitions(entry.getKey());
            if (definitions.isEmpty()) continue;
            var required = new TreeSet<String>();
            var incomingDeclarations = new HashSet<String>();
            for (var member : RawClassSchema.read(entry.getValue()).methods()) {
                incomingDeclarations.add(member.name() + member.descriptor());
                if ((member.access() & Opcodes.ACC_ABSTRACT) != 0)
                    required.add(member.name() + member.descriptor());
            }
            if (required.isEmpty()) continue;
            // Another loader's copy cannot prove the target supports the method. Check each exact Class.
            for (Class<?> owner : definitions) {
                if (!owner.isInterface()) continue;
                var existing = new HashSet<String>();
                var removedNames = new HashSet<String>();
                try {
                    for (var method : owner.getDeclaredMethods()) {
                        String signature = method.getName() + Type.getMethodDescriptor(method);
                        existing.add(signature);
                        if (!incomingDeclarations.contains(signature)) removedNames.add(method.getName());
                    }
                } catch (LinkageError | SecurityException unavailable) {
                    interfaces.add(entry.getKey() + " (loaded methods unavailable)");
                    continue;
                }
                for (String method : required)
                    if (!existing.contains(method) && !removedNames.contains(method.substring(0, method.indexOf('('))))
                        interfaces.add(entry.getKey() + "." + method);
            }
        }
        if (interfaces.isEmpty()) return null;
        String subject = String.join(", ", interfaces);
        return new Hold(subject, "New abstract interface method requires restart: " + subject
                + ". Held this save: " + String.join(", ", new TreeSet<>(incoming.keySet())));
    }
}
