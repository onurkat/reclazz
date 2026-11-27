/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import jakarta.persistence.*;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.hibernate.SessionFactory;
import com.onurkat.reclazz.platform.ApplicationContextHolder;
import org.springframework.context.support.GenericApplicationContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.internal.SessionFactoryRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.onurkat.reclazz.reload.EnumFactoryCoverageTest.*;
import static org.junit.jupiter.api.Assertions.*;

public class EnumFactoryLifecycleTest {
    @TempDir Path dir;

    @Test void missingBootstrapBridgeDoesNotThrowIntoHibernate() throws Exception {
        var nativeType = org.hibernate.internal.SessionFactoryImpl.class;
        String name = nativeType.getName();
        var target = nativeType.getProtectionDomain().getCodeSource().getLocation();
        var transformer = new com.onurkat.reclazz.transform.HibernateFactoryTransformer();
        // Hibernate is isolated as a unit. Only the Reclazz construction bridge is deliberately hidden.
        try (var isolated = new java.net.URLClassLoader(new java.net.URL[]{target}, getClass().getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                if (requested.equals("com.onurkat.reclazz.bootstrap.HibernateFactories")) throw new ClassNotFoundException(requested);
                if (!requested.startsWith("org.hibernate.")) return super.loadClass(requested, resolve);
                var found = findLoadedClass(requested);
                if (found == null) found = findClass(requested);
                if (resolve) resolveClass(found);
                return found;
            }
            @Override protected Class<?> findClass(String requested) throws ClassNotFoundException {
                if (!requested.equals(name)) return super.findClass(requested);
                String internal = requested.replace('.', '/');
                try (var in = findResource(internal + ".class").openStream()) {
                    byte[] bytes = transformer.transform(this, internal, null, null, in.readAllBytes());
                    assertNotNull(bytes);
                    return defineClass(requested, bytes, 0, bytes.length);
                } catch (java.io.IOException e) { throw new ClassNotFoundException(requested,e); }
            }
        }) {
            Class<?> hooked = isolated.loadClass(name);
            var marker = hooked.getDeclaredField(com.onurkat.reclazz.transform.HibernateFactoryTransformer.WITNESS);
            marker.setAccessible(true);
            assertFalse(marker.getBoolean(null));
            for (String step : List.of("constructing", "initialized")) {
                var hook = hooked.getDeclaredMethod(marker.getName() + step, Object.class);
                hook.setAccessible(true);
                hook.invoke(null, new Object());
                assertTrue(marker.getBoolean(null));
            }
        }
    }

    @Test void startupTracksNativeAndOpaqueMappings() throws Exception { launch(true); }
    @Test void preexistingFactoryIsUnknown() throws Exception { launch(false); }

    @Test void factoryCompletingBetweenInspectionAndEnumPublication() throws Exception { launch("overlap", true); }

    @Test void realSpringProxyWithSharedCacheKeepsItsFactory() throws Exception { launch("spring-cache", true); }

    @Test void realSpringProxyAndLateOrdinalFactory() throws Exception { launch("spring", true); }

    @Test void destructiveCreateKeepsExistingRows() throws Exception { launch("create", true); }
    @Test void destructiveCreateDropKeepsExistingRows() throws Exception { launch("create-drop", true); }
    @Test void createDropKeepsTheOriginalFactoryWithoutEnumChanges() throws Exception { launch("retire", true); }
    @Test void createKeepsMappedRowsWithoutEnumChanges() throws Exception { launch("refresh-create", true); }
    @Test void convertedWrapperCannotPersistDivergentOrdinal() throws Exception { launch("wrapper", true); }
    @Test void embeddableGetterCannotPersistDivergentOrdinal() throws Exception { launch("getter", true); }
    @Test void idClassMethodsCannotPersistDivergentOrdinal() throws Exception { launch("idclass", true); }

    @Test void defaultXmlListenerCannotPersistDivergentOrdinal() throws Exception { launch("xml-listener", true); }

    public static class XmlListener {
        public void save(Object row) { if (row instanceof XmlOrder entity) entity.id = entity.state.ordinal(); }
    }

    private void launch(boolean startup) throws Exception { launch(String.valueOf(startup), startup); }

    private void launch(String mode, boolean startup) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djdk.attach.allowAttachSelf=true"));
        if (startup) command.add("-javaagent:" + System.getProperty("reclazz.hibernate.agent") + "=watchDirs=" + dir);
        command.addAll(List.of("-cp", System.getProperty("reclazz.hibernate.classpath"),
                getClass().getName(), mode));
        Path log = dir.resolve("child.log");
        var child = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(90, TimeUnit.SECONDS), "provider probe timed out");
            if (child.exitValue() != 0) {
                List<String> lines = Files.readAllLines(log);
                fail(String.join("\n", lines.subList(Math.max(0, lines.size()-30), lines.size())));
            }
        } finally { child.destroyForcibly(); }
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "xml-listener" -> {
                String xml = """
                    <entity-mappings xmlns="https://jakarta.ee/xml/ns/persistence/orm" version="3.1">
                      <persistence-unit-metadata><persistence-unit-defaults><entity-listeners>
                        <entity-listener class="%s"><pre-persist method-name="save"/></entity-listener>
                      </entity-listeners></persistence-unit-defaults></persistence-unit-metadata>
                      <entity class="%s" access="FIELD">
                        <attributes><id name="id"/><basic name="state"><enumerated>STRING</enumerated></basic></attributes>
                      </entity>
                    </entity-mappings>
                    """.formatted(XmlListener.class.getName(), XmlOrder.class.getName());
                try (var sf = configuration().addInputStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                        .buildSessionFactory()) {
                    assertTrue(sf.isOpen());
                    assertReason(ByteBuddyAgent.install(), "XmlListener.save()", "ordinal()");
                }
                return;
            }
            case "create", "create-drop", "retire", "refresh-create" -> { destructiveSchema(args[0]); return; }
            case "wrapper", "getter", "idclass" -> {
                Class<?> entity = switch (args[0]) {
                    case "wrapper" -> WrappedOrder.class;
                    case "getter" -> ComputedOrder.class;
                    default -> KeyedOrder.class;
                };
                try (var sf = factory(entity)) {
                    assertTrue(sf.isOpen());
                    String path = switch (args[0]) {
                        case "wrapper" -> "WrappedOrder.tag";
                        case "getter" -> "ComputedDetail.getStored";
                        default -> "OrdinalId.hashCode";
                    };
                    assertReason(ByteBuddyAgent.install(), path);
                }
                return;
            }
        }
        if (args[0].equals("overlap")) {
            var window = HibernateEnumStorage.beginOrderChange(State.class);
            try (var factory = factory(OrdinalOrder.class)) {
                String subject = "org.hibernate.internal.SessionFactoryImpl";
                assertTrue(com.onurkat.reclazz.ui.RestartLedger.reasonsFor(subject).isEmpty());
                assertTrue(HibernateEnumStorage.freshFactoryProblems(factory).stream()
                        .anyMatch(reason -> reason.contains("OrdinalOrder.state")));
                HibernateEnumStorage.recordOrder(State.class, true);
                HibernateEnumStorage.finishOrderChange(State.class, window);
                assertTrue(com.onurkat.reclazz.ui.RestartLedger.reasonsFor(subject).stream()
                        .anyMatch(reason -> reason.contains("OrdinalOrder.state")));
            } finally { HibernateEnumStorage.recordOrder(State.class, false); }
            return;
        }
        if (args[0].equals("spring") || args[0].equals("spring-cache")) { springFactory(args[0].equals("spring-cache")); return; }
        try (SessionFactory named = factory(NamedOrder.class); var context = new GenericApplicationContext()) {
            context.getBeanFactory().registerSingleton("visibleNamedFactory", named);
            context.refresh();
            ApplicationContextHolder.register(context);
            Instrumentation inst = ByteBuddyAgent.install();
            if (!Boolean.parseBoolean(args[0])) {
                assertReason(inst, "before lifecycle tracking");
                return;
            }
            try (SessionFactory second = factory(NamedOrder.class)) {
                assertTrue(second.isOpen());
                assertNull(refusal(inst), "both factories store the name");
                try (SessionFactory ordinal = factory(OrdinalOrder.class)) {
                    var hidden = ordinal.unwrap(SessionFactoryImplementor.class);
                    SessionFactoryRegistry.INSTANCE.removeSessionFactory(hidden.getUuid(), null, false, null);
                    assertTrue(hidden.isOpen());
                    assertNull(SessionFactoryRegistry.INSTANCE.getSessionFactory(hidden.getUuid()));
                    assertReason(inst, "OrdinalOrder.state", "TinyIntJdbcType");
                }
                assertNull(refusal(inst), "a closed ordinal factory no longer participates");
            }
            try (var converted = factory(ConvertedOrder.class)) {
                assertReason(inst, "ConvertedOrder.state", "converter");
                try (var session = converted.openSession()) {
                    var tx = session.beginTransaction();
                    var row = new ConvertedOrder(); row.id = 1; row.state = State.PAID;
                    session.persist(row); tx.commit();
                    String stored = (String) session.createNativeQuery(
                            "select state from ConvertedOrder", Object.class).getSingleResult();
                    assertEquals("1", stored);
                    assertEquals(RestartOrder.NEW, RestartOrder.values()[Integer.parseInt(stored)]);
                }
            }
            try (var lists = factory(CollectionOrder.class)) { assertReason(inst, "CollectionOrder.states[element]", "TinyIntJdbcType"); }
            try (var keys = factory(KeyOrder.class)) { assertReason(inst, "KeyOrder.labels[key]", "TinyIntJdbcType"); }
            try (var embedded = factory(EmbeddedOrder.class)) { assertReason(inst, "EmbeddedOrder.detail.state", "TinyIntJdbcType"); }
            try (var values = configuration().addAnnotatedClass(NamedCollection.class).addAnnotatedClass(NamedKey.class)
                    .addAnnotatedClass(NamedEmbedded.class).buildSessionFactory()) {
                assertTrue(values.isOpen()); assertNull(refusal(inst));
            }
            var jsonMapper = new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_ENUMS_USING_INDEX);
            var jsonConfig = configuration().addAnnotatedClass(JsonOrder.class);
            jsonConfig.getProperties().put("hibernate.type.json_format_mapper",
                    new org.hibernate.type.format.jackson.JacksonJsonFormatMapper(jsonMapper));
            try (var json = jsonConfig.buildSessionFactory()) {
                assertReason(inst, "JsonOrder.payload", "opaque");
                try (var session = json.openSession()) {
                    var tx = session.beginTransaction();
                    var row = new JsonOrder(); row.id = 7; row.payload = new Payload(); row.payload.value = State.PAID;
                    session.persist(row); tx.commit();
                    assertTrue(session.createNativeQuery("select payload from JsonOrder where id=7", String.class)
                            .getSingleResult().contains("1"));
                }
            }
            jsonMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_ENUMS_USING_INDEX);
            try (var callbacks = factory(CallbackOrder.class)) { assertReason(inst, "CallbackOrder.store()", "ordinal()"); }
            try (var external = factory(ListenedOrder.class)) { assertReason(inst, "OrderListener.save()", "ordinal()"); }
            try (var composite = factory(CompositeOrder.class)) { assertReason(inst, "CompositeOrder.value", "composite user type"); }
            String xml = """
                    <entity-mappings xmlns="https://jakarta.ee/xml/ns/persistence/orm" version="3.1">
                        <entity class="%s" access="FIELD">
                            <attributes><id name="id"/><basic name="state"><enumerated>ORDINAL</enumerated></basic></attributes>
                        </entity>
                    </entity-mappings>
                    """.formatted(XmlOrder.class.getName());
            try (var mapped = configuration().addInputStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                    .buildSessionFactory()) { assertReason(inst, "XmlOrder.state", "TinyIntJdbcType"); }
            try (var mapped = configuration().addInputStream(new ByteArrayInputStream(xml.replace("ORDINAL", "STRING")
                    .getBytes(StandardCharsets.UTF_8))).buildSessionFactory()) { assertTrue(mapped.isOpen()); assertNull(refusal(inst)); }
            var during = new java.util.concurrent.atomic.AtomicReference<String>();
            var observer = new org.hibernate.SessionFactoryObserver() {
                @Override public void sessionFactoryCreated(SessionFactory building) { during.set(refusal(inst)); }
            };
            try (var observed = configuration().addAnnotatedClass(NamedOrder.class).setSessionFactoryObserver(observer)
                    .buildSessionFactory()) {
                assertTrue(observed.isOpen());
                assertNotNull(during.get());
                assertTrue(during.get().contains("incomplete Hibernate factory construction"), during.get());
            }
        } finally { ApplicationContextHolder.clear(); }
        Instrumentation inst = ByteBuddyAgent.install();
        assertReason(inst, "no tracked open Hibernate factory");
        try (var named = factory(NamedOrder.class)) {
            assertTrue(named.isOpen());
            var racing = new ChangingContext();
            ApplicationContextHolder.register(racing);
            try { assertReason(inst, "coverage changed during inspection"); }
            finally { ApplicationContextHolder.clear(); }
            assertTrue(racing.changed);
            assertNull(refusal(inst));
            checkIncompleteCoverage(inst);
        }
    }

    public static class ChangingContext {
        boolean changed;
        public Object getBeanFactory() { return this; }
        public String[] getBeanNamesForType(Class<?> type, boolean includeNonSingletons, boolean eager) {
            if (!changed) {
                changed = true;
                try (var fresh = factory(NamedOrder.class)) { assertTrue(fresh.isOpen()); }
            }
            return new String[0];
        }
    }

    private static void destructiveSchema(String mode) throws Exception {
        boolean reordered = mode.equals("create") || mode.equals("create-drop");
        String ddl = mode.equals("retire") ? "create-drop" : mode.equals("refresh-create") ? "create" : mode;
        var bean = new org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean();
        var data = new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:destructive;DB_CLOSE_DELAY=-1", "sa", "");
        bean.setDataSource(data);
        bean.setPersistenceUnitName("schema");
        bean.setEntityManagerFactoryInterface(EntityManagerFactory.class);
        bean.setManagedTypes(org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes.of(NamedOrder.class.getName()));
        bean.setJpaVendorAdapter(new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());
        bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", ddl, "hibernate.archive.autodetection", "none"));
        try (var context = new GenericApplicationContext()) {
            context.registerBean("emf", org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean.class, () -> bean);
            context.refresh();
            ApplicationContextHolder.register(context);
            var old = bean.getNativeEntityManagerFactory();
            try (var em = old.createEntityManager()) {
                em.getTransaction().begin();
                var row = new NamedOrder(); row.id = 42; row.state = State.PAID;
                em.persist(row); em.getTransaction().commit();
            }
            HibernateEnumStorage.recordOrder(State.class, reordered);
            JpaMappingRefresh.configure(() -> true, () -> false);
            JpaMappingRefresh.applyForNewEntity(OrdinalOrder.class.getName(), OrdinalOrder.class);
            assertSame(old, bean.getNativeEntityManagerFactory());
            try (var connection = data.getConnection(); var sql = connection.createStatement();
                 var rows = sql.executeQuery("select state from NamedOrder where id=42")) {
                assertTrue(rows.next(), "rebuilding erased the existing row");
                assertEquals("PAID", rows.getString(1));
            }
            assertFalse(bean.getPersistenceUnitInfo().getManagedClassNames().contains(OrdinalOrder.class.getName()));
        } finally { HibernateEnumStorage.recordOrder(State.class, false); ApplicationContextHolder.clear(); }
        try (var connection = data.getConnection();
             var tables = connection.getMetaData().getTables(null, null, "NAMEDORDER", null)) {
            assertEquals(ddl.equals("create"), tables.next(), "the last live factory must keep its own shutdown policy");
        }
    }

    private static void springFactory(boolean sharedCache) throws Exception {
        String url = "jdbc:h2:mem:spring_names;DB_CLOSE_DELAY=-1";
        var bean = new org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(new org.springframework.jdbc.datasource.DriverManagerDataSource(url, "sa", ""));
        bean.setPersistenceUnitName("named");
        bean.setEntityManagerFactoryInterface(EntityManagerFactory.class);
        bean.setManagedTypes(org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes.of(
                NamedOrder.class.getName(), Parent.class.getName()));
        bean.setJpaVendorAdapter(new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());
        var settings = new java.util.HashMap<String, Object>();
        settings.put("hibernate.hbm2ddl.auto", "update");
        settings.put("hibernate.archive.autodetection", "none");
        if (!sharedCache) settings.put("hibernate.cache.region.factory_class", org.hibernate.cache.internal.NoCachingRegionFactory.class);
        bean.setJpaPropertyMap(settings);
        try (var context = new GenericApplicationContext()) {
            context.registerBean("emf", org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean.class, () -> bean);
            context.refresh();
            ApplicationContextHolder.register(context);
            var proxy = context.getBean(EntityManagerFactory.class);
            assertTrue(java.lang.reflect.Proxy.isProxyClass(proxy.getClass()));
            var original = bean.getNativeEntityManagerFactory();
            assertNotSame(proxy, original);
            if (sharedCache) {
                JpaMappingRefresh.configure(() -> true, () -> false);
                var messages = new ArrayList<String>();
                com.onurkat.reclazz.ui.StatusReporter.StatusListener listener = (level, message) -> messages.add(message);
                com.onurkat.reclazz.ui.StatusReporter.addListener(listener);
                try { JpaMappingRefresh.applyForNewEntity(OrdinalOrder.class.getName(), OrdinalOrder.class); }
                finally { com.onurkat.reclazz.ui.StatusReporter.removeListener(listener); }
                assertTrue(messages.stream().anyMatch(message -> message.contains("JCacheRegionFactory")), messages.toString());
                assertFalse(bean.getPersistenceUnitInfo().getManagedClassNames().contains(OrdinalOrder.class.getName()));
                assertSame(original, bean.getNativeEntityManagerFactory());
                assertTrue(original.isOpen());
                return;
            }
            var parent = original.unwrap(SessionFactoryImplementor.class).getMappingMetamodel().getEntityDescriptor(Parent.class);
            var children = (org.hibernate.metamodel.mapping.PluralAttributeMapping) parent.findAttributeMapping("children");
            assertInstanceOf(org.hibernate.metamodel.mapping.internal.OneToManyCollectionPart.class, children.getElementDescriptor());
            var inst = ByteBuddyAgent.install();
            assertNull(refusal(inst));
            Path source = Files.createTempDirectory("enum-source");
            Path file = source.resolve("EnumFactoryCoverageTest.java");
            Files.writeString(file, "package " + State.class.getPackageName()
                    + "; class EnumFactoryCoverageTest { enum State { NEW, HELD, PAID } }");
            assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-d", source.toString(), file.toString()));
            byte[] bytes = Files.readAllBytes(source.resolve(State.class.getName().replace('.', '/') + ".class"));
            assertTrue(EnumConstantAppender.applyOrExplain(State.class.getName(), State.class, bytes,
                    EnumConstantChange.check(State.class, bytes), inst));
            assertEquals(2, State.valueOf("HELD").ordinal());
            try (var em = proxy.createEntityManager()) {
                em.getTransaction().begin();
                var row = new NamedOrder(); row.id = 1; row.state = State.PAID; em.persist(row);
                em.getTransaction().commit();
                assertEquals("PAID", em.createNativeQuery("select state from NamedOrder where id = 1").getSingleResult());
            }
            try (var late = factory(OrdinalOrder.class)) {
                assertTrue(late.isOpen());
                assertTrue(com.onurkat.reclazz.ui.RestartLedger.reasonsFor("org.hibernate.internal.SessionFactoryImpl")
                        .stream().anyMatch(reason -> reason.contains("OrdinalOrder.state") && reason.contains("TinyIntJdbcType")),
                        com.onurkat.reclazz.ui.RestartLedger.digest().toString());
            }
            JpaMappingRefresh.configure(() -> true, () -> false);
            var messages = new ArrayList<String>();
            com.onurkat.reclazz.ui.StatusReporter.StatusListener listener = (level, message) -> messages.add(message);
            com.onurkat.reclazz.ui.StatusReporter.addListener(listener);
            try { JpaMappingRefresh.applyForNewEntity(OrdinalOrder.class.getName(), OrdinalOrder.class); }
            finally { com.onurkat.reclazz.ui.StatusReporter.removeListener(listener); }
            assertTrue(messages.stream().anyMatch(message -> message.contains("Retained the previous persistence factory")
                    && message.contains("OrdinalOrder.state")), messages.toString());
            assertFalse(bean.getPersistenceUnitInfo().getManagedClassNames().contains(OrdinalOrder.class.getName()));
            assertSame(original, bean.getNativeEntityManagerFactory());
            assertTrue(original.isOpen());
            assertTrue(com.onurkat.reclazz.bootstrap.HibernateFactories.snapshot().factories().stream()
                    .filter(entry -> entry.instance() != original).allMatch(entry -> ((SessionFactory) entry.instance()).isClosed()));
        } finally { ApplicationContextHolder.clear(); }
        try (var restarted = configuration().setProperty("hibernate.connection.url", url)
                .setProperty("hibernate.connection.username", "sa").setProperty("hibernate.hbm2ddl.auto", "validate")
                .addAnnotatedClass(RestartedNamed.class).buildSessionFactory(); var session = restarted.openSession()) {
            assertEquals(RestartState.PAID, session.find(RestartedNamed.class, 1L).state);
            assertEquals(2, RestartState.PAID.ordinal());
        }
    }

    enum RestartState { NEW, HELD, PAID }
    @Entity(name="RestartedNamed") @Table(name="NamedOrder")
    public static class RestartedNamed { @Id long id; @Enumerated(EnumType.STRING) RestartState state; }
    @Entity(name="ParentOrder")
    public static class Parent { @Id long id; @OneToMany @JoinColumn(name="parent_id") Set<NamedOrder> children; }

    // Hold the actual factory set fixed and vary one observed loader/provider at a time.
    private static void checkIncompleteCoverage(Instrumentation inst) throws Exception {
        Class<?>[] actual = inst.getAllLoadedClasses();
        assertNull(refusal(inst));
        class Isolated extends ClassLoader {
            private final boolean xml;
            Isolated(boolean xml) { super(EnumFactoryLifecycleTest.class.getClassLoader()); this.xml = xml; }
            Class<?> add(String name) {
                var writer = new org.objectweb.asm.ClassWriter(0);
                writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                        name.replace('.', '/'), null, "java/lang/Object", null);
                writer.visitEnd();
                byte[] bytes = writer.toByteArray();
                return defineClass(name, bytes, 0, bytes.length);
            }
            @Override public java.net.URL getResource(String name) {
                if (xml && name.equals("META-INF/persistence.xml")) {
                    return EnumFactoryLifecycleTest.class.getResource("EnumFactoryLifecycleTest.class");
                }
                return super.getResource(name);
            }
        }
        assertCoverageReason(actual, new Isolated(false).add("jakarta.persistence.Entity"), "uncovered JPA marker");
        assertCoverageReason(actual, new Isolated(true).add("example.XmlUnit"), "JPA XML mappings");
        assertCoverageReason(actual, new Isolated(false).add("org.hibernate.internal.SessionFactoryImpl"), "unmeasured Hibernate");
        Object otherProvider = java.lang.reflect.Proxy.newProxyInstance(EnumFactoryLifecycleTest.class.getClassLoader(),
                new Class<?>[]{EntityManagerFactory.class}, (proxy, method, args) -> null);
        assertCoverageReason(actual, otherProvider.getClass(), "untracked persistence factory type");
    }

    private static void assertCoverageReason(Class<?>[] actual, Class<?> extra, String reason) {
        Class<?>[] combined = Arrays.copyOf(actual, actual.length + 1);
        combined[actual.length] = extra;
        Instrumentation view = (Instrumentation) java.lang.reflect.Proxy.newProxyInstance(
                EnumFactoryLifecycleTest.class.getClassLoader(), new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getAllLoadedClasses")) return combined;
                    throw new AssertionError(method);
                });
        assertReason(view, reason);
    }

    private static String refusal(Instrumentation inst) {
        return EnumConstantAppender.ordinalPersistence(State.class, inst);
    }

    private static void assertReason(Instrumentation inst, String... parts) {
        String reason = refusal(inst);
        assertNotNull(reason);
        for (String part : parts) assertTrue(reason.contains(part), reason);
    }

    enum RestartOrder { PAID, NEW }
    @Converter public static class AsOrdinalText implements AttributeConverter<State, String> {
        public String convertToDatabaseColumn(State state) { return state == null ? null : String.valueOf(state.ordinal()); }
        public State convertToEntityAttribute(String text) { return text == null ? null : State.values()[Integer.parseInt(text)]; }
    }

    @Entity(name="ConvertedOrder") public static class ConvertedOrder {
        @Id long id;
        @Convert(converter=AsOrdinalText.class) State state;
    }
    @Entity(name="CollectionOrder") public static class CollectionOrder {
        @Id long id;
        @ElementCollection @Enumerated(EnumType.ORDINAL) Set<State> states;
    }
    @Entity(name="KeyOrder") public static class KeyOrder {
        @Id long id;
        @ElementCollection @MapKeyEnumerated(EnumType.ORDINAL) Map<State,String> labels;
    }
    @Embeddable public static class Detail { @Enumerated(EnumType.ORDINAL) State state; }
    @Entity(name="EmbeddedOrder") public static class EmbeddedOrder { @Id long id; @Embedded Detail detail; }
    @Entity(name="NamedCollection") public static class NamedCollection {
        @Id long id; @ElementCollection @Enumerated(EnumType.STRING) Set<State> states;
    }
    @Entity(name="NamedKey") public static class NamedKey {
        @Id long id; @ElementCollection @MapKeyEnumerated(EnumType.STRING) Map<State,String> labels;
    }
    @Embeddable public static class NamedDetail { @Enumerated(EnumType.STRING) State state; }
    @Entity(name="NamedEmbedded") public static class NamedEmbedded { @Id long id; @Embedded NamedDetail detail; }
    public static class GenericPayload<T> { public T value; }
    public static final class Payload extends GenericPayload<State> { }
    @Entity(name="JsonOrder") public static class JsonOrder {
        @Id long id; @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON) Payload payload;
    }
    @Entity(name="CallbackOrder") public static class CallbackOrder {
        @Id long id; @Enumerated(EnumType.STRING) State state; int stored;
        @PrePersist void store() { stored = state.ordinal(); }
    }
    public static class OrderListener {
        @PrePersist public void save(ListenedOrder row) { row.stored = row.state.ordinal(); }
    }
    @Entity(name="ListenedOrder") @EntityListeners(OrderListener.class)
    public static class ListenedOrder { @Id long id; @Enumerated(EnumType.STRING) State state; int stored; }

    @Converter public static class TagConverter implements AttributeConverter<WrappedState, Integer> {
        public Integer convertToDatabaseColumn(WrappedState value) { return value.state().ordinal(); }
        public WrappedState convertToEntityAttribute(Integer code) { return new WrappedState(State.values()[code]); }
    }
    @Entity(name="WrappedOrder") public static class WrappedOrder {
        @Id long id; @Convert(converter=TagConverter.class) WrappedState tag;
    }
    @Embeddable @Access(AccessType.PROPERTY) public static class ComputedDetail {
        private State state = State.NEW;
        public int getStored() { return state.ordinal(); }
        public void setStored(int value) { state = State.values()[value]; }
    }
    @Entity(name="ComputedOrder") public static class ComputedOrder { @Id long id; @Embedded ComputedDetail detail; }
    public static class OrdinalId implements java.io.Serializable {
        public State state;
        public int sequence;
        public int hashCode() { return 31 * state.ordinal() + sequence; }
        public boolean equals(Object other) {
            return other instanceof OrdinalId id && state == id.state && sequence == id.sequence;
        }
    }
    @Entity(name="KeyedOrder") @IdClass(OrdinalId.class) public static class KeyedOrder {
        @Id @Enumerated(EnumType.STRING) State state;
        @Id int sequence;
    }

    public record WrappedState(State state) implements java.io.Serializable { }
    public static class OrdinalFields { Integer stored; }
    public static class OrdinalComposite implements org.hibernate.usertype.CompositeUserType<WrappedState> {
        public Object getPropertyValue(WrappedState value, int index) { return value.state().ordinal(); }
        public WrappedState instantiate(org.hibernate.metamodel.spi.ValueAccess values, SessionFactoryImplementor factory) {
            return new WrappedState(State.values()[values.getValue(0, Integer.class)]);
        }
        public Class<?> embeddable() { return OrdinalFields.class; }
        public Class<WrappedState> returnedClass() { return WrappedState.class; }
        public boolean equals(WrappedState a, WrappedState b) { return Objects.equals(a,b); }
        public int hashCode(WrappedState value) { return value.hashCode(); }
        public WrappedState deepCopy(WrappedState value) { return value; }
        public boolean isMutable() { return false; }
        public java.io.Serializable disassemble(WrappedState value) { return value; }
        public WrappedState assemble(java.io.Serializable value, Object owner) { return (WrappedState)value; }
        public WrappedState replace(WrappedState value, WrappedState target, Object owner) { return value; }
    }
    @Entity(name="CompositeOrder") public static class CompositeOrder {
        @Id long id; @org.hibernate.annotations.CompositeType(OrdinalComposite.class) WrappedState value;
    }
    public static class XmlOrder { public long id; public State state; }
}
