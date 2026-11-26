/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.util.ClassLookup;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class JpaBatchCheckTest {
    @Entity static class Item {
        @Id long id;
        @OneToMany List<Item> related;
        @Transient String memo;
    }
    @Entity static class PropertyItem {
        private long backing;
        @Id public long getId() { return backing; }
        public void setId(long id) { backing = id; }
    }
    static class Other {}

    private static byte[] bytes(Class<?> type, Consumer<ClassNode> edit) throws Exception {
        var node = new ClassNode();
        try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertNotNull(in);
            new ClassReader(in).accept(node, 0);
        }
        edit.accept(node);
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static InterfaceBatchCheck.Hold check(Class<?> owner, Consumer<ClassNode> edit) throws Exception {
        var incoming = Map.of(owner.getName(), bytes(owner, edit), Other.class.getName(), bytes(Other.class, node -> {}));
        return JpaBatchCheck.check(incoming, ClassLookup.loadedClasses(new Class<?>[]{owner, Other.class}));
    }

    private static FieldNode field(String name) {
        return new FieldNode(0, name, "Ljava/lang/String;", null, null);
    }

    private static FieldNode namedField(ClassNode node, String name) {
        return node.fields.stream().filter(field -> field.name.equals(name)).findFirst().orElseThrow();
    }

    @Test void namesAddedRemovedAndRetypedFieldsAndHoldsTheEntireSave() throws Exception {
        var added = check(Item.class, node -> node.fields.add(field("label")));
        assertEquals(Item.class.getName(), added.subject());
        assertTrue(added.reason().contains("gained the persistent field label"));
        assertTrue(added.reason().contains(Other.class.getName()));

        var removed = check(Item.class, node -> node.fields.remove(namedField(node, "id")));
        assertTrue(removed.reason().contains("lost the persistent field id"));
        var retyped = check(Item.class, node -> namedField(node, "id").desc = "I");
        assertTrue(retyped.reason().contains("changed the persistent field id"));
        assertNull(check(Item.class, node -> {}));
    }

    @Test void relationGenericTargetIsPartOfThePersistentShape() throws Exception {
        var held = check(Item.class, node -> namedField(node, "related").signature = "Ljava/util/List<Ljava/lang/String;>;");
        assertTrue(held.reason().contains("changed the persistent field related"));
    }

    @Test void scratchStaticAndTransientFieldsDoNotBlockARevertedSave() throws Exception {
        assertNull(check(Item.class, node -> {
            var scratch = field("scratch");
            scratch.visibleAnnotations = List.of(new AnnotationNode("Ljakarta/persistence/Transient;"));
            var global = field("global");
            global.access = Opcodes.ACC_STATIC;
            var session = field("session");
            session.access = Opcodes.ACC_TRANSIENT;
            node.fields.addAll(List.of(scratch, global, session));
            node.fields.remove(namedField(node, "memo"));
        }));
    }

    @Test void propertyAccessIgnoresBackingFieldsAndHoldsANewMappedGetter() throws Exception {
        assertNull(check(PropertyItem.class, node -> node.fields.add(field("scratch"))));
        var held = check(PropertyItem.class, node -> {
            node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "getLabel", "()Ljava/lang/String;", null, null));
            node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "setLabel", "(Ljava/lang/String;)V", null, null));
        });
        assertTrue(held.reason().contains("gained the persistent field label"));
    }

    @Test void ordinaryClassIsOutsideJpaAdmission() throws Exception {
        var incoming = Map.of(Other.class.getName(), bytes(Other.class, node -> node.fields.add(field("field"))));
        assertNull(JpaBatchCheck.check(incoming, ClassLookup.loadedClasses(new Class<?>[]{Other.class})));
    }
}
