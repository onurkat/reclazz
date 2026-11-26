/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.util.ClassLookup;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.util.TraceSignatureVisitor;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Decide before the stock VM publishes any member of the captured save. */
public final class JpaBatchCheck {
    private JpaBatchCheck() {}

    public static InterfaceBatchCheck.Hold check(Map<String, byte[]> incoming, ClassLookup.LoadedClasses loaded) {
        for (String name : new TreeSet<>(incoming.keySet())) {
            byte[] bytes = incoming.get(name);
            if (!JpaMappingRefresh.carriesEntityAnnotation(bytes)) continue;
            var owners = loaded.definitions(name);
            if (owners.isEmpty()) {
                // A factory rebuild cannot register the Spring Data repository or its client.
                // A standalone mapping retains its existing opt-in schema/validation policy.
                if (hasDependentClass(name, incoming) || !JpaMappingRefresh.canMapStandaloneEntity()) {
                    return hold(name, "is a new entity whose mapping is not installed", incoming);
                }
                continue;
            }
            var candidate = new ClassNode();
            new ClassReader(bytes).accept(candidate, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (Class<?> owner : owners) {
                if (JpaPreparedSchemaRefresh.isPending(owner)) {
                    if (hasDependentClass(name, incoming)) {
                        return hold(name, "has a pending mapping that its callers cannot use", incoming);
                    }
                    // Hibernate has never mapped this class. The prepared-schema retry owns
                    // its physical fingerprint and its existing validation diagnostic.
                    continue;
                }
                String reason;
                try {
                    reason = difference(owner, candidate);
                } catch (ReflectiveOperationException | LinkageError | TypeNotPresentException | SecurityException unavailable) {
                    reason = "has a persistent mapping that could not be compared";
                }
                if (reason == null) continue;
                if (reason.startsWith("gained the persistent field") && "update".equals(JpaSchemaAdvice.ddlAutoSetting(owner))) {
                    reason += ". With ddl-auto=update Hibernate adds the column on startup";
                }
                return hold(name, reason, incoming);
            }
        }
        return null;
    }

    private static InterfaceBatchCheck.Hold hold(String name, String reason, Map<String, byte[]> incoming) {
        return new InterfaceBatchCheck.Hold(name, name + " " + reason + ". Restart to apply it. Held this save: "
                + String.join(", ", new TreeSet<>(incoming.keySet())));
    }

    private static boolean hasDependentClass(String entity, Map<String, byte[]> incoming) {
        String target = entity.replace('.', '/');
        for (var entry : incoming.entrySet()) {
            if (entry.getKey().equals(entity)) continue;
            // A generic repository may mention its domain only in a Signature attribute.
            var reader = new ClassReader(entry.getValue());
            var used = new boolean[1];
            var remapper = new org.objectweb.asm.commons.Remapper(org.objectweb.asm.Opcodes.ASM9) {
                @Override public String map(String internalName) {
                    if (target.equals(internalName)) used[0] = true;
                    return internalName;
                }
            };
            reader.accept(new org.objectweb.asm.commons.ClassRemapper(new org.objectweb.asm.ClassWriter(0), remapper),
                    ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (used[0]) return true;
        }
        return false;
    }

    private record FieldShape(String type, String relation, String target, String mappedBy) {}
    private static final Set<String> RELATIONS = Set.of("OneToOne", "OneToMany", "ManyToOne", "ManyToMany", "ElementCollection");

    private static String difference(Class<?> owner, ClassNode candidate) throws ReflectiveOperationException {
        Map<String, FieldShape> before = new TreeMap<>(), after = new TreeMap<>();
        boolean properties = propertyAccess(owner);
        String oldAccess = access(owner);
        String newAccess = access(candidate.visibleAnnotations);
        if (!oldAccess.equals(newAccess)) return "changed its persistent access type";
        for (Field field : owner.getDeclaredFields()) {
            if (!persistent(field.getModifiers(), field.getName(), field.isSynthetic())) continue;
            if (!properties || "FIELD".equals(access(field))) {
                put(before, field.getName(), shape(field.getGenericType().getTypeName(), field));
            }
        }
        for (var method : owner.getDeclaredMethods()) {
            String name = propertyName(method.getName(), Type.getMethodDescriptor(method), method.getModifiers());
            if (name != null && (properties || "PROPERTY".equals(access(method)))) {
                put(before, name, shape(method.getGenericReturnType().getTypeName(), method));
            }
        }
        for (FieldNode field : candidate.fields) {
            if (!persistent(field.access, field.name, (field.access & Opcodes.ACC_SYNTHETIC) != 0)) continue;
            if (!properties || "FIELD".equals(access(field.visibleAnnotations))) {
                put(after, field.name, shape(typeName(field.desc, field.signature, false), field.visibleAnnotations));
            }
        }
        for (var method : candidate.methods) {
            String name = propertyName(method.name, method.desc, method.access);
            if (name != null && (properties || "PROPERTY".equals(access(method.visibleAnnotations)))) {
                put(after, name, shape(typeName(method.desc, method.signature, true), method.visibleAnnotations));
            }
        }
        for (var entry : after.entrySet()) {
            String name = entry.getKey();
            if (!before.containsKey(name)) return "gained the persistent field " + name;
            if (!entry.getValue().equals(before.get(name))) return "changed the persistent field " + name;
        }
        for (String name : before.keySet()) if (!after.containsKey(name)) return "lost the persistent field " + name;
        return null;
    }

    private static boolean propertyAccess(Class<?> owner) throws ReflectiveOperationException {
        String explicit = access(owner);
        if (!explicit.isEmpty()) return "PROPERTY".equals(explicit);
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) if (id(field)) return false;
            for (var method : type.getDeclaredMethods()) if (id(method)) return true;
        }
        return false;
    }

    private static boolean id(AnnotatedElement element) {
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            String kind = persistenceName(Type.getDescriptor(annotation.annotationType()));
            if ("Id".equals(kind) || "EmbeddedId".equals(kind)) return true;
        }
        return false;
    }

    private static String access(AnnotatedElement element) throws ReflectiveOperationException {
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            if ("Access".equals(persistenceName(Type.getDescriptor(annotation.annotationType())))) {
                return ((Enum<?>) annotation.annotationType().getMethod("value").invoke(annotation)).name();
            }
        }
        return "";
    }

    private static String access(List<AnnotationNode> annotations) {
        if (annotations != null) for (var annotation : annotations) {
            if ("Access".equals(persistenceName(annotation.desc))) return ((String[]) annotation.values.get(1))[1];
        }
        return "";
    }

    private static String propertyName(String method, String descriptor, int modifiers) {
        if ((modifiers & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0
                || (modifiers & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0
                || !descriptor.startsWith("()") || InjectedNames.isInjected(method)) return null;
        int prefix;
        if (method.startsWith("get") && !descriptor.equals("()V")) prefix = 3;
        else if (method.startsWith("is") && descriptor.equals("()Z")) prefix = 2;
        else return null;
        if (method.length() == prefix) return null;
        String name = method.substring(prefix);
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1)) && Character.isUpperCase(name.charAt(0))) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static String typeName(String descriptor, String signature, boolean method) {
        if (signature == null) return (method ? Type.getReturnType(descriptor) : Type.getType(descriptor)).getClassName();
        var visitor = new TraceSignatureVisitor(0);
        var reader = new SignatureReader(signature);
        if (method) {
            reader.accept(visitor);
            return visitor.getReturnType();
        }
        reader.acceptType(visitor);
        return visitor.getDeclaration();
    }

    private static void put(Map<String, FieldShape> fields, String name, FieldShape shape) {
        if (shape != null) fields.put(name, shape);
    }

    private static FieldShape shape(String type, AnnotatedElement element) throws ReflectiveOperationException {
        type = type.replace(" ", "").replace('$', '.');
        FieldShape result = new FieldShape(type, "", "", "");
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            String kind = persistenceName(Type.getDescriptor(annotation.annotationType()));
            if ("Transient".equals(kind)) return null;
            if (RELATIONS.contains(kind)) {
                Class<?> target = (Class<?>) annotation.annotationType().getMethod(
                        kind.equals("ElementCollection") ? "targetClass" : "targetEntity").invoke(annotation);
                String mappedBy = kind.equals("ManyToOne") || kind.equals("ElementCollection") ? "" :
                        (String) annotation.annotationType().getMethod("mappedBy").invoke(annotation);
                result = new FieldShape(type, kind, target == void.class ? "" : Type.getDescriptor(target), mappedBy);
            }
        }
        return result;
    }

    private static FieldShape shape(String type, List<AnnotationNode> annotations) {
        type = type.replace(" ", "").replace('$', '.');
        FieldShape result = new FieldShape(type, "", "", "");
        if (annotations != null) for (var annotation : annotations) {
            String kind = persistenceName(annotation.desc);
            if ("Transient".equals(kind)) return null;
            if (RELATIONS.contains(kind)) {
                String target = "", mappedBy = "";
                List<Object> values = annotation.values;
                if (values != null) for (int i = 0; i < values.size(); i += 2) {
                    Object key = values.get(i), value = values.get(i + 1);
                    if (("targetEntity".equals(key) || "targetClass".equals(key)) && !Type.VOID_TYPE.equals(value)) {
                        target = ((Type) value).getDescriptor();
                    }
                    if ("mappedBy".equals(key)) mappedBy = (String) value;
                }
                result = new FieldShape(type, kind, target, mappedBy);
            }
        }
        return result;
    }

    private static boolean persistent(int modifiers, String name, boolean synthetic) {
        return !Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers) && !synthetic && !InjectedNames.isInjected(name);
    }

    private static String persistenceName(String descriptor) {
        if (!descriptor.startsWith("Ljakarta/persistence/") && !descriptor.startsWith("Ljavax/persistence/")) return "";
        return descriptor.substring(descriptor.lastIndexOf('/') + 1, descriptor.length() - 1);
    }
}
