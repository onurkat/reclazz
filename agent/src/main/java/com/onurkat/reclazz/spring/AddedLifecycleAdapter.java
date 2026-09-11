/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.bootstrap.ReclazzBootstrap;
import com.onurkat.reclazz.transform.CallSiteAdapter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Captures callback entry bodies for one generation of Spring instances. */
public final class AddedLifecycleAdapter {
    // Both the javax (Spring 5) and jakarta (Spring 6) namespaces of the
    // standard JSR-250 lifecycle annotations are recognised by descriptor.
    private static final Set<String> INIT = Set.of(
            "Ljavax/annotation/PostConstruct;", "Ljakarta/annotation/PostConstruct;");
    private static final Set<String> DESTROY = Set.of(
            "Ljavax/annotation/PreDestroy;", "Ljakarta/annotation/PreDestroy;");
    private static final Set<String> COMPONENTS = Set.of(
            "Lorg/springframework/stereotype/Component;", "Lorg/springframework/stereotype/Service;",
            "Lorg/springframework/stereotype/Repository;", "Lorg/springframework/stereotype/Controller;",
            "Lorg/springframework/web/bind/annotation/RestController;", "Ljava/lang/Deprecated;");

    private AddedLifecycleAdapter() { }

    record Plan(MethodNode init, MethodNode destroy, List<String> refused) {
        boolean empty() { return init == null && destroy == null; }
    }
    record Callbacks(Class<?> owner, MethodHandle init, MethodHandle destroy) { }

    static Plan inspect(byte[] bytes, Set<String> added) {
        if (bytes == null || added.isEmpty()) return new Plan(null, null, List.of());
        ClassNode source = new ClassNode();
        new ClassReader(bytes).accept(source, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        MethodNode init = null, destroy = null;
        List<String> refused = new ArrayList<>();
        for (MethodNode method : source.methods) {
            if (!added.contains(method.name + ":" + method.desc) || method.visibleAnnotations == null) continue;
            boolean isInit = method.visibleAnnotations.stream().anyMatch(a -> INIT.contains(a.desc));
            boolean isDestroy = method.visibleAnnotations.stream().anyMatch(a -> DESTROY.contains(a.desc));
            if (!isInit && !isDestroy) continue;
            if (!method.desc.equals("()V") || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                    | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC)) != 0)
                refused.add(method.name + method.desc + ": requires a no-argument void instance method");
            if (method.visibleAnnotations.stream().anyMatch(a -> !INIT.contains(a.desc)
                    && !DESTROY.contains(a.desc) && !a.desc.equals("Ljava/lang/Deprecated;")))
                refused.add(method.name + ": additional method annotations are unsupported");
            if (isInit && isDestroy) refused.add(method.name + ": init and destroy must be distinct methods");
            if (isInit) {
                if (init != null) refused.add("multiple added init methods are ambiguous");
                init = method;
            }
            if (isDestroy) {
                if (destroy != null) refused.add("multiple added destroy methods are ambiguous");
                destroy = method;
            }
        }
        if (init != null || destroy != null) {
            if (!"java/lang/Object".equals(source.superName)) refused.add("inherited lifecycle owners are unsupported");
            if (source.visibleAnnotations != null && source.visibleAnnotations.stream()
                    .anyMatch(a -> !COMPONENTS.contains(a.desc)))
                refused.add("additional class annotations are unsupported");
        }
        return new Plan(init, destroy, List.copyOf(refused));
    }

    static Callbacks capture(Class<?> owner, Plan plan) throws Throwable {
        return new Callbacks(owner, capture(owner, plan.init()), capture(owner, plan.destroy()));
    }

    private static MethodHandle capture(Class<?> owner, MethodNode method) throws Throwable {
        if (method == null) return null;
        var lookup = LookupCapture.get(owner);
        if (lookup == null) throw new IllegalStateException("no captured lookup for " + owner.getName());
        // Keep the entry target, not dynamicInvoker: a later save may remove
        // this callback or change how the next instance releases its resource.
        // Calls made inside the captured body still use ordinary live dispatch.
        return ReclazzBootstrap.bootstrapBody(lookup, method.name,
                MethodType.methodType(void.class, owner), Type.getInternalName(owner),
                CallSiteAdapter.descHash(method.desc)).getTarget()
                .asType(MethodType.methodType(void.class, Object.class));
    }
}
