/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.EnumSurgery;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** R32: a constant inserted in the middle is applied as an append, unless an entity persists the enum by ordinal. */
class EnumInsertTest {

    @Test
    void anInsertWithoutRemovalOrReorderIsInsertOnlyWithItsRestartOrdinal() throws IOException {
        assertTrue(EnumConstantChange.isInsertOnly(Status.class, bytecodeOf(StatusInserted.class)));
        assertEquals(new LinkedHashMap<>(Map.of("SHIPPED", 1)),
                EnumConstantChange.insertedNames(Status.class, bytecodeOf(StatusInserted.class)));
        assertEquals(List.of("HELD", "SHIPPED"),
                new ArrayList<>(EnumConstantChange.insertedNames(Status.class, bytecodeOf(StatusTwoInserted.class)).keySet()));
    }

    @Test
    void appendRemovalAndReorderAreNotInserts() throws IOException {
        assertFalse(EnumConstantChange.isInsertOnly(Status.class, bytecodeOf(StatusAppended.class)), "append keeps its own path");
        assertFalse(EnumConstantChange.isInsertOnly(Status.class, bytecodeOf(StatusInsertedAndRemoved.class)), "a removal renumbers");
        assertFalse(EnumConstantChange.isInsertOnly(Status.class, bytecodeOf(StatusInsertedAndReordered.class)), "a reorder renumbers");
        assertFalse(EnumConstantChange.isInsertOnly(Status.class, bytecodeOf(Status.class)), "nothing changed");
    }

    @Test
    void ordinalPersistenceRefusalLeavesThePreviousLogicalOrderIntact() throws IOException {
        var bytes = bytecodeOf(StatusInsertedAndReordered.class);
        var change = EnumConstantChange.check(Status.class, bytes);
        assertTrue(change.reordered());
        assertEquals(List.of("SHIPPED"), change.added());
        assertFalse(EnumConstantAppender.applyOrExplain(Status.class.getName(), Status.class, bytes, change,
                loaded(jakarta.persistence.EntityManagerFactory.class)));
        assertEquals(change, EnumConstantChange.check(Status.class, bytes));
        var reordered = bytecodeOf(StatusReordered.class);
        var pure = EnumConstantChange.check(Status.class, reordered);
        assertTrue(pure.reordered());
        assertTrue(pure.added().isEmpty());
        assertFalse(EnumConstantAppender.applyOrExplain(Status.class.getName(), Status.class, reordered, pure,
                loaded(jakarta.persistence.EntityManagerFactory.class)));
        assertEquals(pure, EnumConstantChange.check(Status.class, reordered));
        assertArrayEquals(new Status[]{Status.NEW, Status.PAID}, Status.values());
    }

    /** The surgery the insert uses keeps every existing ordinal, so maps and sets built before stay right. */
    @Test
    void anInsertedConstantTakesTheNextOrdinalAndExistingCollectionsKeepWorking() {
        EnumMap<Live, String> before = new EnumMap<>(Live.class);
        before.put(Live.PAID, "paid");
        var outcome = EnumSurgery.append(Live.class, List.of("SHIPPED"));
        assertTrue(outcome.applied(), outcome.declinedBecause());
        @SuppressWarnings({"unchecked", "rawtypes"}) Enum<?> shipped = Enum.valueOf((Class) Live.class, "SHIPPED");
        assertEquals(0, Live.NEW.ordinal());
        assertEquals(1, Live.PAID.ordinal(), "no existing ordinal moves");
        assertEquals(2, shipped.ordinal());
        assertEquals("paid", before.get(Live.PAID));
    }

    @Test
    void anEntityFieldWithoutEnumeratedOrWithOrdinalIsNamed() {
        var instrumentation = ByteBuddyAgent.install();
        assertNotNull(new DefaultOrdinal().state);
        assertEquals(DefaultOrdinal.class.getName() + ".state",
                EnumConstantAppender.ordinalPersistence(PersistedByDefault.class, instrumentation));
        assertNotNull(new ExplicitOrdinal().states);
        assertEquals(ExplicitOrdinal.class.getName() + ".states",
                EnumConstantAppender.ordinalPersistence(PersistedExplicitly.class, instrumentation));
    }

    /** Audit F2-F4: STRING, converters, transient fields, map keys and getters are not proven safe; any entity mention refuses. */
    @Test
    void anyMentionByAnEntityRefusesWhateverItsAnnotationsSay() {
        var instrumentation = ByteBuddyAgent.install();
        new SafeEntity(); new PropertyEntity(); new MapEntity();
        assertNotNull(EnumConstantAppender.ordinalPersistence(PersistedSafely.class, instrumentation), "STRING/@Convert/@Transient");
        assertNotNull(EnumConstantAppender.ordinalPersistence(ViaGetter.class, instrumentation), "property access getter");
        assertNotNull(EnumConstantAppender.ordinalPersistence(AsMapKey.class, instrumentation), "map key with STRING values");
    }

    @Test
    void useOutsideEntitiesAndStaticEntityFieldsIsSafe() {
        var instrumentation = ByteBuddyAgent.install();
        new NotAnEntity(); new StaticOnlyEntity();
        // No entity mentions it, but this test JVM has JPA loaded, so the JVM-wide rule (audit round 2) still refuses.
        String reason = EnumConstantAppender.ordinalPersistence(UsedElsewhere.class, instrumentation);
        assertNotNull(reason);
        assertTrue(reason.startsWith("JPA in this JVM"), reason);
    }

    /** Audit round 2 F3a/F5a/F5b: once a JPA API type is loaded, inserts are not proven safe; without JPA they are. */
    @Test
    void jpaInUseIsDecidedByLoadedApiTypes() {
        assertNull(EnumConstantAppender.jpaInUse(loaded(String.class, Integer.class)));
        assertEquals("jakarta.persistence.Entity", EnumConstantAppender.jpaInUse(loaded(String.class, jakarta.persistence.Entity.class)));
        assertEquals("jakarta.persistence.ElementCollection", EnumConstantAppender.jpaInUse(loaded(jakarta.persistence.ElementCollection.class)));
    }
    private static java.lang.instrument.Instrumentation loaded(Class<?>... types) {
        return (java.lang.instrument.Instrumentation) java.lang.reflect.Proxy.newProxyInstance(EnumInsertTest.class.getClassLoader(),
                new Class<?>[]{java.lang.instrument.Instrumentation.class},
                (proxy, method, args) -> method.getName().equals("getAllLoadedClasses") ? types : null);
    }

    /** Audit F5: XML mappings can map unannotated types or override annotations, so their presence refuses. */
    @Test
    void jpaXmlMappingsMakeTheInsertUnprovable() {
        ClassLoader orm = new ClassLoader(null) {
            @Override public java.net.URL getResource(String name) {
                return name.equals("META-INF/orm.xml") ? EnumInsertTest.class.getResource("EnumInsertTest.class") : null;
            }
        };
        assertNotNull(EnumConstantAppender.xmlMappings(orm));
        ClassLoader plain = new ClassLoader(null) { @Override public java.net.URL getResource(String name) { return null; } };
        assertNull(EnumConstantAppender.xmlMappings(plain));
    }

    @Test
    void laterDirectSwitchSaveSeesRefusedInMemoryDeclarationEvenWithOldBytesOnDisk() throws IOException {
        var loaded = com.onurkat.reclazz.util.ClassLookup.loadedClasses(new Class<?>[]{MemoryOrder.class});
        var reason = new HashMap<String,String>();
        String owner = MemoryOrder.class.getName(), internal = owner.replace('.', '/');
        var fixture = new org.objectweb.asm.ClassWriter(0);
        fixture.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, "example/Outer$External", null, "java/lang/Object", null);
        fixture.visitNestHost("example/Outer");
        var mv = fixture.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "store", "(L" + internal + ";)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, internal, "ordinal", "()I", false);
        var zero = new org.objectweb.asm.Label(); var other = new org.objectweb.asm.Label();
        mv.visitLookupSwitchInsn(other, new int[]{0}, new org.objectweb.asm.Label[]{zero});
        mv.visitLabel(zero); mv.visitInsn(org.objectweb.asm.Opcodes.ICONST_0); mv.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        mv.visitLabel(other); mv.visitInsn(org.objectweb.asm.Opcodes.ICONST_1); mv.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        mv.visitMaxs(1, 1); mv.visitEnd(); fixture.visitEnd();
        var outer = new org.objectweb.asm.ClassWriter(0);
        outer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, "example/Outer", null, "java/lang/Object", null);
        outer.visitNestMember("example/Outer$External");
        outer.visitEnd();
        var consumer = Map.of("example.Outer$External", fixture.toByteArray(), "example.Outer", outer.toByteArray());
        SwitchMapPreflight.refuseDirectSwitches(consumer, loaded, reason);
        assertTrue(reason.isEmpty());
        try {
            EnumConstantChange.compiledOrder(MemoryOrder.class, bytecodeOf(StatusReordered.class));
            SwitchMapPreflight.refuseDirectSwitches(consumer, loaded, reason);
            assertTrue(reason.get("example.Outer$External").contains("direct ordinal switch"));
            assertTrue(reason.get("example.Outer$External").contains(owner));
            assertEquals(reason.get("example.Outer$External"), reason.get("example.Outer"),
                    "the new host could load the refused helper from disk");
        } finally { EnumConstantChange.compiledOrder(MemoryOrder.class, bytecodeOf(MemoryOrder.class)); }
    }

    @Test void destructiveBootstrapAndDivergentEnumPublicationExcludeEachOther() throws IOException {
        var pending = HibernateEnumStorage.beginOrderChange(MemoryOrder.class);
        assertNotNull(pending);
        try { assertFalse(HibernateEnumStorage.beginDestructiveRebuild()); }
        finally { HibernateEnumStorage.finishOrderChange(MemoryOrder.class, pending); }
        assertTrue(HibernateEnumStorage.beginDestructiveRebuild());
        try {
            var next = bytecodeOf(StatusReordered.class);
            var change = EnumConstantChange.check(MemoryOrder.class, next);
            assertFalse(EnumConstantAppender.applyOrExplain(MemoryOrder.class.getName(), MemoryOrder.class,
                    next, change, loaded()), "an enum must not publish divergent ordinals during schema recreation");
            assertEquals(change, EnumConstantChange.check(MemoryOrder.class, next));
        } finally { HibernateEnumStorage.finishDestructiveRebuild(); }
        var retry = HibernateEnumStorage.beginOrderChange(MemoryOrder.class);
        assertNotNull(retry);
        HibernateEnumStorage.finishOrderChange(MemoryOrder.class, retry);
    }

    @Test void anOlderNestedEnumRequiresAProvenSwitchNest() throws IOException {
        var node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytecodeOf(MemoryOrder.class)).accept(node, 0);
        node.version = org.objectweb.asm.Opcodes.V1_8;
        node.nestHostClass = null;
        Collections.swap(node.fields, 0, 1);
        var out = new org.objectweb.asm.ClassWriter(0);
        node.accept(out);
        String name = MemoryOrder.class.getName();
        var reason = new HashMap<String, String>();
        try {
            SwitchMapPreflight.refuseDirectSwitches(Map.of(name, out.toByteArray()),
                    com.onurkat.reclazz.util.ClassLookup.loadedClasses(new Class<?>[]{MemoryOrder.class}), reason);
            assertTrue(reason.getOrDefault(name, "").contains("pre-nest"), reason.toString());
        } finally { EnumConstantChange.compiledOrder(MemoryOrder.class, bytecodeOf(MemoryOrder.class)); }
    }

    @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.NUMBER)
    private enum Numeric { A, B }
    @Test void numericJacksonFormatRequiresRestart() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals("1", mapper.writeValueAsString(Numeric.B));
        String reason = EnumConstantAppender.ordinalPersistence(Numeric.class, loaded(Numeric.class));
        assertTrue(reason != null && reason.contains("JsonFormat"), String.valueOf(reason));
    }

    @Test void indexedJacksonMapperRequiresRestart() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_ENUMS_USING_INDEX);
        com.onurkat.reclazz.bootstrap.JacksonMapperRegistry.track(mapper);
        try {
            String reason = EnumConstantAppender.ordinalPersistence(MemoryOrder.class, loaded(mapper.getClass()));
            assertTrue(reason != null && reason.contains("WRITE_ENUMS_USING_INDEX"), String.valueOf(reason));
        } finally { mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_ENUMS_USING_INDEX); }
    }

    @Test void loadedMyBatisOrdinalHandlerRequiresRestart() {
        String name = "org.apache.ibatis.type.EnumOrdinalTypeHandler";
        var writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                name.replace('.', '/'), null, "java/lang/Object", null);
        writer.visitEnd();
        class FixtureLoader extends ClassLoader {
            Class<?> marker(byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
        }
        Class<?> marker = new FixtureLoader().marker(writer.toByteArray());
        assertEquals("loaded MyBatis EnumOrdinalTypeHandler", EnumConstantAppender.ordinalPersistence(MemoryOrder.class, loaded(marker)));
    }

    private static class EcjConsumer {
        static int[] $SWITCH_TABLE$com$onurkat$reclazz$reload$EnumInsertTest$MemoryOrder;
    }
    private static class WhenMappings {
        static final int[] $EnumSwitchMapping$0 = new int[MemoryOrder.values().length];
    }
    @Test void ecjTableRequiresRestart() {
        String reason = EnumConstantAppender.ordinalPersistence(MemoryOrder.class, loaded(EcjConsumer.class));
        assertTrue(reason != null && reason.contains("$SWITCH_TABLE$"), String.valueOf(reason));
    }
    @Test void kotlinTableRequiresRestartOnlyForTheReferencedEnum() {
        String reason = EnumConstantAppender.ordinalPersistence(MemoryOrder.class, loaded(WhenMappings.class));
        assertTrue(reason != null && reason.contains("WhenMappings"), String.valueOf(reason));
        assertNull(EnumConstantAppender.ordinalPersistence(Status.class, loaded(WhenMappings.class)));
    }

    enum MemoryOrder { NEW, PAID }
    enum Status { NEW, PAID }
    enum StatusReordered { PAID, NEW }
    enum StatusInserted { NEW, SHIPPED, PAID }
    enum StatusTwoInserted { HELD, NEW, SHIPPED, PAID }
    enum StatusAppended { NEW, PAID, SHIPPED }
    enum StatusInsertedAndRemoved { SHIPPED, PAID }
    enum StatusInsertedAndReordered { PAID, SHIPPED, NEW }
    enum Live { NEW, PAID }

    enum PersistedByDefault { A, B }
    enum PersistedExplicitly { A, B }
    enum PersistedSafely { A, B }
    enum ViaGetter { A, B }
    enum AsMapKey { A, B }
    enum UsedElsewhere { A, B }

    @Entity static class DefaultOrdinal { PersistedByDefault state = PersistedByDefault.A; }
    @Entity static class ExplicitOrdinal { @Enumerated(EnumType.ORDINAL) List<PersistedExplicitly> states = List.of(); }
    @Entity static class SafeEntity {
        @Enumerated(EnumType.STRING) PersistedSafely byName;
        @Convert(converter = SafeConverter.class) PersistedSafely converted;
        @jakarta.persistence.Transient PersistedSafely ignored;
        transient PersistedSafely javaTransient;
        static PersistedSafely STATIC;
    }
    static class NotAnEntity { PersistedSafely plain; UsedElsewhere elsewhere; }
    @Entity @jakarta.persistence.Access(jakarta.persistence.AccessType.PROPERTY) static class PropertyEntity {
        private transient ViaGetter backing = ViaGetter.A;
        @Enumerated(EnumType.ORDINAL) public ViaGetter getState() { return backing; }
        public void setState(ViaGetter value) { backing = value; }
    }
    @Entity static class MapEntity {
        @jakarta.persistence.ElementCollection @Enumerated(EnumType.STRING) Map<AsMapKey, String> byKey = Map.of();
    }
    @Entity static class StaticOnlyEntity { static UsedElsewhere DEFAULT = UsedElsewhere.A; }
    static class SafeConverter implements jakarta.persistence.AttributeConverter<PersistedSafely, String> {
        public String convertToDatabaseColumn(PersistedSafely value) { return value == null ? null : value.name(); }
        public PersistedSafely convertToEntityAttribute(String value) { return value == null ? null : PersistedSafely.valueOf(value); }
    }

    private static byte[] bytecodeOf(Class<?> type) throws IOException {
        try (InputStream in = type.getResourceAsStream(type.getSimpleName().isEmpty() ? null
                : type.getName().substring(type.getName().lastIndexOf('.') + 1) + ".class")) {
            return Objects.requireNonNull(in, type.getName()).readAllBytes();
        }
    }
}
