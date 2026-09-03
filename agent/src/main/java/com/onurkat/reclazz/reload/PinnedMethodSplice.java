/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.transform.CallSiteAdapter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

/**
 * Puts the previous implementation of pinned methods back into a redefine
 * payload.
 *
 * <p>Why the payload and not just the companion: the first per-method salvage
 * attempt skipped the entangled method in the companion and stopped there,
 * and it crashed a live application. The redefine payload still carried the
 * method's NEW body, the transformer renamed it over
 * {@code __reclazz$v0$...}, and the trampoline's fallback dispatched into a
 * body that called a member only the new superclass provides:
 * {@code UnsupportedOperationException: Reclazz: method not found:
 * Service.yalnizB2}, thrown on the application's own thread. The lesson is
 * baked in here: pinning a method means the OLD body has to be what the
 * loaded class ends up holding, so the fallback has nowhere wrong to go.
 *
 * <p>The splice works on the transformed payload, not the raw one. Both
 * sides then have the same shape per method, a trampoline under the original
 * name and the body under {@code __reclazz$v0$<name>$<descHash>}, and
 * replacing both members of the pair swaps the whole method. ASM's tree API
 * rebuilds the constant pool when the class is rewritten, so lifting a
 * MethodNode from one class file into another needs no pool surgery.
 */
public final class PinnedMethodSplice {

    private PinnedMethodSplice() {
    }

    /**
     * @param bytecode the spliced payload, or null when the splice refused
     * @param reason   why, when it refused
     */
    public record Result(byte[] bytecode, String reason) {

        public boolean applied() {
            return bytecode != null;
        }
    }

    /**
     * Replace each pinned method's trampoline and renamed body in
     * {@code transformedPayload} with the ones from {@code lastKnownGood}.
     *
     * @param transformedPayload the reverted new class, already transformed
     * @param lastKnownGood      the transformer's previous output for this class
     * @param pinnedMethods      keys as {@code name:descriptor}
     */
    public static Result apply(byte[] transformedPayload, byte[] lastKnownGood,
                               Set<String> pinnedMethods) {
        if (transformedPayload == null || lastKnownGood == null || pinnedMethods.isEmpty()) {
            return new Result(null, "nothing to splice from");
        }

        ClassNode payload = readTree(transformedPayload);
        ClassNode cached = readTree(lastKnownGood);
        if (payload == null || cached == null) {
            return new Result(null, "unreadable bytecode");
        }

        for (String key : pinnedMethods) {
            int colon = key.indexOf(':');
            String name = key.substring(0, colon);
            String descriptor = key.substring(colon + 1);
            String renamed = com.onurkat.reclazz.bootstrap.InjectedNames.renamed(name, CallSiteAdapter.descHash(descriptor));

            MethodNode oldBody = find(cached, renamed, descriptor);
            MethodNode oldTrampoline = find(cached, name, descriptor);
            if (oldBody == null || oldTrampoline == null) {
                // A method the previous version did not have (or that was not
                // trampolined there) has no implementation to pin. The caller
                // falls back to refusing the whole class, which is the
                // pre-salvage behaviour.
                return new Result(null, "the previous implementation of " + name
                        + " is not in the last-known-good bytecode");
            }
            if (!replace(payload, renamed, descriptor, oldBody)
                    || !replace(payload, name, descriptor, oldTrampoline)) {
                return new Result(null, "the payload does not carry " + name
                        + descriptor + " in the transformed shape");
            }
        }

        ClassWriter writer = new ClassWriter(0);
        payload.accept(writer);
        byte[] spliced = writer.toByteArray();

        // The same gate every transform output passes before the JVM sees it.
        // A spliced frame mismatch failing here costs a refusal; failing in
        // redefineClasses costs a VerifyError on a live server.
        try {
            ClassNode check = new ClassNode();
            new ClassReader(spliced).accept(
                    new org.objectweb.asm.util.CheckClassAdapter(check, true),
                    ClassReader.SKIP_DEBUG);
        } catch (Throwable t) {
            return new Result(null, "the spliced class did not verify: " + t.getMessage());
        }
        return new Result(spliced, null);
    }

    private static ClassNode readTree(byte[] bytecode) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytecode).accept(node, 0);
            return node;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        for (MethodNode m : node.methods) {
            if (m.name.equals(name) && m.desc.equals(descriptor)) return m;
        }
        return null;
    }

    private static boolean replace(ClassNode node, String name, String descriptor,
                                   MethodNode replacement) {
        for (int i = 0; i < node.methods.size(); i++) {
            MethodNode m = node.methods.get(i);
            if (m.name.equals(name) && m.desc.equals(descriptor)) {
                node.methods.set(i, replacement);
                return true;
            }
        }
        return false;
    }
}
