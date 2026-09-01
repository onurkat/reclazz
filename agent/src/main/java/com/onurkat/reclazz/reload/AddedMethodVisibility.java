/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.transform.TransformContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Names an added method that the frameworks will not find.
 *
 * <p>A method added by a reload lives in the companion class, because a stock
 * JDK will not put a new method on a loaded one. Code that calls it works: the
 * call site is rewritten and reaches the companion. Reflection does not, and
 * reflection is how frameworks discover methods. Measured on a running JVM
 * after adding {@code getEmail()} to a watched class:
 *
 * <pre>
 *   getDeclaredMethods()   [getName]            (getEmail is not there)
 *   getMethod("getEmail")  NoSuchMethodException
 * </pre>
 *
 * <p>So an added {@code @Bean} method is not a bean, an added {@code @Scheduled}
 * method never runs, an added getter is not serialised, and none of that
 * announces itself: the reload succeeds, the log says so, and the thing the
 * developer added does nothing. That silence is the problem this fixes. It
 * cannot make the method visible, which is the JDK's wall rather than a cache,
 * so it says which method and what will not happen.
 *
 * <p>Request mappings are left out on purpose: those are already carried, by
 * handing the mapping scan a small class holding a copy of the method
 * ({@link com.onurkat.reclazz.spring.AddedEndpointAdapter}), so an added
 * endpoint really does answer and warning about it would be wrong.
 *
 * <p>Only reached on the companion path. A JVM with enhanced redefinition puts
 * the method on the class for real, where every scan finds it, and this
 * reloader does not run there at all.
 */
public final class AddedMethodVisibility {

    /**
     * Annotations that mean "a framework will come looking for this method".
     * Matched on the simple name, because the same names arrive under
     * {@code javax} and {@code jakarta}, and under Spring's own package and a
     * meta-annotated one of the application's.
     */
    private static final Set<String> DISCOVERED_BY_SCAN = Set.of(
            "Bean", "Scheduled", "EventListener", "TransactionalEventListener",
            "PostConstruct", "PreDestroy", "ExceptionHandler", "InitBinder",
            "ModelAttribute", "JmsListener", "KafkaListener", "RabbitListener",
            "Async", "Transactional", "Cacheable", "CacheEvict", "CachePut",
            "JsonProperty", "JsonGetter", "JsonValue", "JsonAnyGetter");

    /** Carried by the endpoint adapter, so not a silence and not reported. */
    private static final Set<String> ALREADY_CARRIED = Set.of(
            "RequestMapping", "GetMapping", "PostMapping",
            "PutMapping", "DeleteMapping", "PatchMapping");

    private AddedMethodVisibility() {
    }

    /** What an added method will not be picked up for, and why. */
    public record Unseen(String method, String reason) {
    }

    /**
     * @param newBytecode the class as it now reads on disk
     * @param added       the methods this reload added
     * @return one entry per added method a scan would have wanted, in source order
     */
    public static List<Unseen> check(byte[] newBytecode,
                                     List<TransformContext.MethodSig> added) {
        List<Unseen> unseen = new ArrayList<>();
        if (added == null || added.isEmpty()) return unseen;

        Set<String> addedKeys = new LinkedHashSet<>();
        for (TransformContext.MethodSig sig : added) {
            addedKeys.add(sig.name() + sig.descriptor());
        }

        try {
            new ClassReader(newBytecode).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!addedKeys.contains(name + descriptor)) return null;
                    if ((access & Opcodes.ACC_SYNTHETIC) != 0) return null;

                    return new MethodVisitor(Opcodes.ASM9) {
                        boolean reported;

                        @Override
                        public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                                String annotationDescriptor, boolean visible) {
                            String simple = simpleName(annotationDescriptor);
                            if (!reported && !ALREADY_CARRIED.contains(simple)
                                    && DISCOVERED_BY_SCAN.contains(simple)) {
                                reported = true;
                                unseen.add(new Unseen(name + "()", "@" + simple
                                        + " is found by scanning the class, and the scan cannot "
                                        + "see a method added by a reload"));
                            }
                            return null;
                        }

                        @Override
                        public void visitEnd() {
                            // A getter is not annotated and is looked for by
                            // shape, which is the same wall by a different
                            // door: the serialiser asks the class what its
                            // properties are and this one is not among them.
                            if (!reported && isGetter(access, name, descriptor)) {
                                unseen.add(new Unseen(name + "()",
                                        "a getter is found by shape, so serialisation "
                                        + "(Jackson, for one) will not include it"));
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Exception unreadable) {
            // Saying nothing beats failing a reload that worked over a message.
            return List.of();
        }
        return unseen;
    }

    private static boolean isGetter(int access, String name, String descriptor) {
        if ((access & Opcodes.ACC_PUBLIC) == 0) return false;
        if ((access & Opcodes.ACC_STATIC) != 0) return false;
        Type type = Type.getMethodType(descriptor);
        if (type.getArgumentTypes().length != 0) return false;
        if (type.getReturnType().getSort() == Type.VOID) return false;
        if (name.startsWith("get") && name.length() > 3) return true;
        return name.startsWith("is") && name.length() > 2
                && type.getReturnType().getSort() == Type.BOOLEAN;
    }

    private static String simpleName(String annotationDescriptor) {
        String internal = annotationDescriptor;
        if (internal.startsWith("L") && internal.endsWith(";")) {
            internal = internal.substring(1, internal.length() - 1);
        }
        int slash = internal.lastIndexOf('/');
        String name = slash < 0 ? internal : internal.substring(slash + 1);
        // A nested annotation type arrives as Outer$Inner.
        int dollar = name.lastIndexOf('$');
        return dollar < 0 ? name : name.substring(dollar + 1);
    }
}
