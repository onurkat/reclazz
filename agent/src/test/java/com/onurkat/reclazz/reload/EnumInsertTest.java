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

    enum Status { NEW, PAID }
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
