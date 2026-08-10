package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for {@link SpringXmlReloader}. Builds a real
 * {@link GenericApplicationContext} from an initial XML, mutates the XML on
 * disk, fires the reloader, and asserts the live singleton reflects the
 * change (or that an unsafe change was recorded and rejected).
 *
 * Exercises the full pipeline: XML parse via sandbox factory, diff against
 * live factory, property mutation via reflection setter + Spring's
 * BeanDefinitionValueResolver, new-bean registration + rollback.
 */
class SpringXmlReloaderTest {

    @TempDir
    Path tempDir;

    private GenericApplicationContext liveContext;
    private SpringXmlReloader reloader;

    @BeforeEach
    void setUp() {
        liveContext = new GenericApplicationContext();
        TestPlatformContext platform = new TestPlatformContext(liveContext);
        reloader = new SpringXmlReloader(platform);
    }

    // ─── Property value mutation ──────────────────────────────────────────────

    @Test
    void stringPropertyChangeIsAppliedToLiveSingleton() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="hello"/>
                        <property name="count" value="1"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        TestBean bean = liveContext.getBean("target", TestBean.class);
        assertEquals("hello", bean.getMessage());
        assertEquals(1, bean.getCount());

        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="world"/>
                        <property name="count" value="42"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        // Same instance — no destroy + recreate
        TestBean sameInstance = liveContext.getBean("target", TestBean.class);
        assertTrue(bean == sameInstance, "live singleton identity preserved");

        // New property values visible on the existing instance
        assertEquals("world", bean.getMessage());
        assertEquals(42, bean.getCount());
    }

    @Test
    void propertyChangeResolvesBeanReference() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="collab1" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$Collaborator">
                        <property name="name" value="first"/>
                    </bean>
                    <bean id="collab2" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$Collaborator">
                        <property name="name" value="second"/>
                    </bean>
                    <bean id="host" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$HostBean">
                        <property name="collaborator" ref="collab1"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        HostBean host = liveContext.getBean("host", HostBean.class);
        assertEquals("first", host.getCollaborator().getName());

        // Swap the ref to collab2
        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="collab1" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$Collaborator">
                        <property name="name" value="first"/>
                    </bean>
                    <bean id="collab2" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$Collaborator">
                        <property name="name" value="second"/>
                    </bean>
                    <bean id="host" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$HostBean">
                        <property name="collaborator" ref="collab2"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        // Ref resolved through BeanDefinitionValueResolver → collab2 instance
        assertEquals("second", host.getCollaborator().getName());
    }

    // ─── New bean registration ────────────────────────────────────────────────

    @Test
    void newSafeBeanIsRegisteredAndInstantiable() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="existing" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="hi"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="existing" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="hi"/>
                    </bean>
                    <bean id="brandNew" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="fresh"/>
                        <property name="count" value="7"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        assertTrue(liveContext.containsBean("brandNew"));
        TestBean added = liveContext.getBean("brandNew", TestBean.class);
        assertEquals("fresh", added.getMessage());
        assertEquals(7, added.getCount());
    }

    // ─── Unsafe-change rejection ──────────────────────────────────────────────

    @Test
    void classChangeIsRejectedLiveContextUntouched() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="orig"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        TestBean original = liveContext.getBean("target", TestBean.class);
        String originalClass = original.getClass().getName();

        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$Collaborator">
                        <property name="name" value="renamed"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        // Live context unchanged — class still the original TestBean
        assertEquals(originalClass, liveContext.getBean("target").getClass().getName());
    }

    @Test
    void initMethodBeanIsRejected() throws Exception {
        // Start with a simple bean
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="v1"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        // User adds an init-method — reloader must refuse (side effects not rollback-safe)
        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean" init-method="init">
                        <property name="message" value="v2"/>
                    </bean>
                </beans>
                """);

        TestBean bean = liveContext.getBean("target", TestBean.class);
        reloader.reload(xml);

        // Reloader must not have called setter — init-method rule rejects the whole bean
        assertEquals("v1", bean.getMessage());
    }

    @Test
    void parseErrorLeavesLiveContextUntouched() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="before"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        TestBean bean = liveContext.getBean("target", TestBean.class);
        assertEquals("before", bean.getMessage());

        // Malformed XML
        Files.writeString(xml, "<beans><bean id='broken' class='nope' >"); // unclosed

        reloader.reload(xml);

        // Nothing applied, live bean unchanged
        assertEquals("before", bean.getMessage());
    }

    @Test
    void removedBeanIsDetectedAndReportedButLiveContextUntouched() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="keeper" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="stays"/>
                    </bean>
                    <bean id="goner" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="leaves"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        TestBean goner = liveContext.getBean("goner", TestBean.class);
        assertEquals("leaves", goner.getMessage());

        // Remove the "goner" bean from the XML
        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="keeper" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="stays"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        // Report-only: the reloader detected the removal and surfaced it in
        // the warning, but the live context is unchanged. The user restarts
        // to actually remove the bean.
        assertTrue(liveContext.containsBean("goner"),
                "removed bean must still be in the live factory (detection only)");
        assertSame(goner, liveContext.getBean("goner"),
                "removed bean's singleton instance must not be destroyed");
    }

    @Test
    void noChangesIsANoOp() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="same"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        TestBean bean = liveContext.getBean("target", TestBean.class);
        assertEquals("same", bean.getMessage());

        // Rewrite the same file, no change
        reloader.reload(xml);

        assertEquals("same", bean.getMessage());
    }

    // ═══ Extended coverage: property mutation edge cases ══════════════════════

    @Test
    void primitiveTypesAreAllCoercedAndApplied() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="hi"/>
                        <property name="count" value="1"/>
                        <property name="flag" value="false"/>
                        <property name="bigCount" value="1"/>
                        <property name="ratio" value="1.0"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        TestBean bean = liveContext.getBean("target", TestBean.class);

        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="hi"/>
                        <property name="count" value="99"/>
                        <property name="flag" value="true"/>
                        <property name="bigCount" value="9999999999"/>
                        <property name="ratio" value="3.14159"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        assertEquals(99, bean.getCount());
        assertTrue(bean.isFlag());
        assertEquals(9999999999L, bean.getBigCount());
        assertEquals(3.14159, bean.getRatio(), 1e-9);
    }

    @Test
    void placeholderValueResolvesFromEnvironment() throws Exception {
        // Seed a property source + register PSPC so ${host} resolves
        liveContext.getEnvironment().getPropertySources().addLast(
                new org.springframework.core.env.MapPropertySource(
                        "test-props", Map.of("host", "db.prod.local")));
        DefaultListableBeanFactory bf = (DefaultListableBeanFactory) liveContext.getBeanFactory();
        bf.registerBeanDefinition("pspc", org.springframework.beans.factory.support.BeanDefinitionBuilder
                .rootBeanDefinition(org.springframework.context.support.PropertySourcesPlaceholderConfigurer.class)
                .getBeanDefinition());

        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="original"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        TestBean bean = liveContext.getBean("target", TestBean.class);
        assertEquals("original", bean.getMessage());

        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="connected to ${host}"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        assertEquals("connected to db.prod.local", bean.getMessage());
    }

    @Test
    void multiplePropertiesOnSameBeanAllApplied() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="m1"/>
                        <property name="count" value="1"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        TestBean bean = liveContext.getBean("target", TestBean.class);

        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="m2"/>
                        <property name="count" value="2"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        assertEquals("m2", bean.getMessage());
        assertEquals(2, bean.getCount());
    }

    @Test
    void lazyBeanGetsBeanDefinitionUpdateBeforeFirstInstantiation() throws Exception {
        // Mark the bean as lazy-init so it isn't created during refresh()
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="lazy" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean" lazy-init="true">
                        <property name="message" value="v1"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        // Verify the singleton doesn't exist yet
        assertFalse(liveContext.getBeanFactory().containsSingleton("lazy"),
                "lazy bean must not be instantiated by refresh()");

        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="lazy" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean" lazy-init="true">
                        <property name="message" value="v2"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        // Singleton still not created (reloader must not trigger instantiation
        // of lazy beans just to apply property changes)
        assertFalse(liveContext.getBeanFactory().containsSingleton("lazy"));

        // But when the user finally looks it up, the new value is there —
        // the BD was mirrored with the new property.
        TestBean bean = liveContext.getBean("lazy", TestBean.class);
        assertEquals("v2", bean.getMessage());
    }

    @Test
    void singletonIdentityPreservedAcrossMultipleReloads() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="a"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        TestBean original = liveContext.getBean("target", TestBean.class);

        // Three consecutive edits
        for (String v : List.of("b", "c", "d")) {
            writeXml("beans.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <beans xmlns="http://www.springframework.org/schema/beans">
                        <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                            <property name="message" value="%s"/>
                        </bean>
                    </beans>
                    """.formatted(v));
            reloader.reload(xml);
            assertSame(original, liveContext.getBean("target"),
                    "identity must survive every reload (no destroy+recreate)");
            assertEquals(v, original.getMessage());
        }
    }

    // ═══ Extended coverage: new bean registration ═════════════════════════════

    @Test
    void newBeanCanReferenceExistingLiveBean() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="pre" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$Collaborator">
                        <property name="name" value="existing"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        Collaborator pre = liveContext.getBean("pre", Collaborator.class);

        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="pre" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$Collaborator">
                        <property name="name" value="existing"/>
                    </bean>
                    <bean id="hostNew" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$HostBean">
                        <property name="collaborator" ref="pre"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        HostBean host = liveContext.getBean("hostNew", HostBean.class);
        assertSame(pre, host.getCollaborator(),
                "new bean's <ref> must resolve to the live singleton of the existing bean");
    }

    @Test
    void newBeanInstantiationFailureRollsBackRegistration() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="ok" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="ok"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        // Add an ExplodingBean whose setter throws during instantiation
        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="ok" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="ok"/>
                    </bean>
                    <bean id="boom" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$ExplodingBean">
                        <property name="payload" value="anything"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        // Rollback: the failed bean is not left in the live factory
        assertFalse(liveContext.containsBean("boom"),
                "failed new-bean registration must be rolled back (no BD, no partial singleton)");
    }

    @Test
    void multipleNewBeansAreAllRegistered() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="existing" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="old"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="existing" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="old"/>
                    </bean>
                    <bean id="newA" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="A"/>
                    </bean>
                    <bean id="newB" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="B"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        assertEquals("A", liveContext.getBean("newA", TestBean.class).getMessage());
        assertEquals("B", liveContext.getBean("newB", TestBean.class).getMessage());
    }

    // ═══ Extended coverage: unsafe classification paths ═══════════════════════

    @Test
    void beanPostProcessorIsRejected() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="keeper" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="v1"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="keeper" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="v1"/>
                    </bean>
                    <bean id="bpp" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$CustomPostProcessor"/>
                </beans>
                """);

        reloader.reload(xml);

        // BPP rejected — live factory untouched
        assertFalse(liveContext.containsBean("bpp"),
                "BeanPostProcessor must not be live-added (side effects on other beans)");
    }

    @Test
    void initializingBeanImplementerIsRejected() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="keeper" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="v1"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="keeper" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="v1"/>
                    </bean>
                    <bean id="initBean" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$CustomInitializingBean">
                        <property name="data" value="x"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        assertFalse(liveContext.containsBean("initBean"),
                "InitializingBean implementer must be rejected");
    }

    @Test
    void destroyMethodBeanIsRejected() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="v1"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        TestBean bean = liveContext.getBean("target", TestBean.class);

        // User adds a destroy-method — reloader must refuse
        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean" destroy-method="cleanup">
                        <property name="message" value="v2"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        assertEquals("v1", bean.getMessage(),
                "destroy-method bean must be rejected as unsafe — property must not be applied");
    }

    @Test
    void constructorArgBeanIsRejected() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="keeper" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="ok"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="keeper" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="ok"/>
                    </bean>
                    <bean id="ctor" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$CtorBean">
                        <constructor-arg value="hello"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        assertFalse(liveContext.containsBean("ctor"),
                "bean with constructor-arg must not be live-added (not reloadable)");
    }

    @Test
    void prototypeScopeBeanIsRejected() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="keeper" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="v1"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="keeper" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="v1"/>
                    </bean>
                    <bean id="proto" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean" scope="prototype">
                        <property name="message" value="each"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        assertFalse(liveContext.containsBean("proto"),
                "non-singleton scope must be rejected");
    }

    @Test
    void abstractBeanIsRejected() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="keeper" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="v1"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="keeper" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="v1"/>
                    </bean>
                    <bean id="tpl" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean" abstract="true">
                        <property name="message" value="template"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        assertFalse(liveContext.containsBean("tpl"),
                "abstract bean template must be rejected");
    }

    @Test
    void scopeChangeOnExistingBeanRejected() throws Exception {
        // Start with singleton
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="orig"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        TestBean bean = liveContext.getBean("target", TestBean.class);

        // Switch to prototype — reloader must reject even the property change
        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="target" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean" scope="prototype">
                        <property name="message" value="new"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        assertEquals("orig", bean.getMessage(),
                "scope change classifies the whole bean as unsafe — property change skipped");
    }

    // ═══ Extended coverage: removal detection ═════════════════════════════════

    @Test
    void multipleBeansRemovedAreAllReportedAndKept() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="keep" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="stay"/>
                    </bean>
                    <bean id="gone1" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="a"/>
                    </bean>
                    <bean id="gone2" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="b"/>
                    </bean>
                    <bean id="gone3" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="c"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="keep" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="stay"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        // All three removed beans still in the live factory (report-only)
        assertTrue(liveContext.containsBean("gone1"));
        assertTrue(liveContext.containsBean("gone2"));
        assertTrue(liveContext.containsBean("gone3"));
    }

    @Test
    void beanDefinedInOtherFileIsNotFalselyReportedAsRemoved() throws Exception {
        // Two XML files, each defines its own bean with a distinct id
        Path fileA = writeXml("a-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="fromA" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="a"/>
                    </bean>
                </beans>
                """);
        Path fileB = writeXml("b-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="fromB" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="b"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(fileA);
        loadIntoLiveContext(fileB);
        liveContext.refresh();

        // Edit file A, empty it out — fromA's removal must be detected but
        // fromB must NOT show up as removed (it's defined in file B).
        writeXml("a-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                </beans>
                """);

        reloader.reload(fileA);

        // Both beans still live (report-only for removal)
        assertTrue(liveContext.containsBean("fromA"));
        assertTrue(liveContext.containsBean("fromB"),
                "bean from a different file must not be touched on reload");
    }

    // ═══ Extended coverage: mixed + isolation ═════════════════════════════════

    @Test
    void mixedReloadAppliesPropertyAddsNewAndDetectsRemoval() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="existing" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="old"/>
                    </bean>
                    <bean id="toRemove" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="bye"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        TestBean existing = liveContext.getBean("existing", TestBean.class);
        TestBean toRemove = liveContext.getBean("toRemove", TestBean.class);

        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="existing" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="fresh"/>
                    </bean>
                    <bean id="freshlyAdded" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="born"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        // Property change applied to live instance
        assertEquals("fresh", existing.getMessage());
        // New bean registered
        assertEquals("born", liveContext.getBean("freshlyAdded", TestBean.class).getMessage());
        // Removed bean still live (report-only)
        assertTrue(liveContext.containsBean("toRemove"));
        assertSame(toRemove, liveContext.getBean("toRemove"));
    }

    @Test
    void oneBeanApplyFailureDoesNotBlockOtherBeans() throws Exception {
        Path xml = writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="good" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="v1"/>
                    </bean>
                    <bean id="bad" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$ExplodingBean">
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        TestBean good = liveContext.getBean("good", TestBean.class);

        // Change a property on 'good' and try to set payload on 'bad' (setter throws)
        writeXml("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="good" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="v2"/>
                    </bean>
                    <bean id="bad" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$ExplodingBean">
                        <property name="payload" value="boom"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        // 'good' bean's property change applied even though 'bad' blew up
        assertEquals("v2", good.getMessage(),
                "per-bean failure must not block other beans' applies");
    }

    // ═══ Extended coverage: SAP Commerce / Hybris idioms ══════════════════════

    @Test
    void hybrisAliasPatternPropertyChangePropagatesThroughAlias() throws Exception {
        // Classic Hybris pattern: <bean id="defaultFooService"/> + <alias>
        // Consumers look up the bean as "fooService" via the alias.
        Path xml = writeXml("hybrisalias-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="defaultFooService" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="default impl"/>
                    </bean>
                    <alias name="defaultFooService" alias="fooService"/>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        // Verify the alias works at startup
        TestBean viaAlias = liveContext.getBean("fooService", TestBean.class);
        TestBean viaId = liveContext.getBean("defaultFooService", TestBean.class);
        assertSame(viaAlias, viaId, "alias and id must resolve to the same singleton");

        // User tweaks the default impl via the XML
        writeXml("hybrisalias-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="defaultFooService" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="customised"/>
                    </bean>
                    <alias name="defaultFooService" alias="fooService"/>
                </beans>
                """);

        reloader.reload(xml);

        // Both the id and the alias look-ups see the new value — same
        // singleton, property applied in place, alias never changed.
        assertEquals("customised", liveContext.getBean("defaultFooService", TestBean.class).getMessage());
        assertEquals("customised", liveContext.getBean("fooService", TestBean.class).getMessage());
        assertSame(viaAlias, liveContext.getBean("fooService", TestBean.class),
                "alias must still resolve to the same instance after reload");
    }

    @Test
    void hybrisParentChildTemplatePatternPropertyChange() throws Exception {
        // Common Hybris pattern: an abstract "template" bean + concrete children
        // inheriting class and default properties from it (think
        // abstractPopulatingConverter, defaultOrderEntryConverter).
        Path xml = writeXml("parentchild-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="abstractFoo" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean" abstract="true">
                        <property name="message" value="inherited-default"/>
                    </bean>
                    <bean id="productFoo" parent="abstractFoo">
                        <property name="count" value="1"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        TestBean productFoo = liveContext.getBean("productFoo", TestBean.class);
        assertEquals("inherited-default", productFoo.getMessage());
        assertEquals(1, productFoo.getCount());

        writeXml("parentchild-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="abstractFoo" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean" abstract="true">
                        <property name="message" value="inherited-default"/>
                    </bean>
                    <bean id="productFoo" parent="abstractFoo">
                        <property name="count" value="42"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        // Abstract parent is always rejected (no instance to mutate), but the
        // concrete child's count is applied on the live singleton. The
        // inherited message stays at its original value.
        assertEquals(42, productFoo.getCount());
        assertEquals("inherited-default", productFoo.getMessage());
    }

    @Test
    void hybrisInterceptorListPropertyReplacementAppliesLive() throws Exception {
        // Interceptor chain / populator list — the #1 list-valued injection
        // in Hybris XML config.
        Path xml = writeXml("interceptor-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="firstInterceptor" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$Collaborator">
                        <property name="name" value="first"/>
                    </bean>
                    <bean id="secondInterceptor" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$Collaborator">
                        <property name="name" value="second"/>
                    </bean>
                    <bean id="chain" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$InterceptorChain">
                        <property name="interceptors">
                            <list>
                                <ref bean="firstInterceptor"/>
                            </list>
                        </property>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        InterceptorChain chain = liveContext.getBean("chain", InterceptorChain.class);
        assertEquals(1, chain.getInterceptors().size());
        assertEquals("first", chain.getInterceptors().get(0).getName());

        // Add a second interceptor to the list (typical Hybris workflow:
        // register a new validation/security interceptor on a chain)
        writeXml("interceptor-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="firstInterceptor" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$Collaborator">
                        <property name="name" value="first"/>
                    </bean>
                    <bean id="secondInterceptor" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$Collaborator">
                        <property name="name" value="second"/>
                    </bean>
                    <bean id="chain" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$InterceptorChain">
                        <property name="interceptors">
                            <list>
                                <ref bean="firstInterceptor"/>
                                <ref bean="secondInterceptor"/>
                            </list>
                        </property>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        // Live singleton now has the expanded list — next method call reads
        // it without any bean destroy / recreate.
        assertEquals(2, chain.getInterceptors().size());
        assertEquals("first", chain.getInterceptors().get(0).getName());
        assertEquals("second", chain.getInterceptors().get(1).getName());
    }

    @Test
    void hybrisMapPropertyTypeCodeDispatchReplacementAppliesLive() throws Exception {
        // Map-valued injection — think "type code → handler" dispatch tables
        // (defaultCartEntryProductInfoPopulator's typeCodeToProductConverterMap, etc.)
        Path xml = writeXml("typecodemap-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="handlerProduct" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$Collaborator">
                        <property name="name" value="product handler"/>
                    </bean>
                    <bean id="handlerOrder" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$Collaborator">
                        <property name="name" value="order handler"/>
                    </bean>
                    <bean id="dispatch" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$InterceptorChain">
                        <property name="byTypeCode">
                            <map>
                                <entry key="Product" value-ref="handlerProduct"/>
                            </map>
                        </property>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        InterceptorChain dispatch = liveContext.getBean("dispatch", InterceptorChain.class);
        assertEquals(1, dispatch.getByTypeCode().size());
        assertEquals("product handler", dispatch.getByTypeCode().get("Product").getName());

        // Extend the dispatch table
        writeXml("typecodemap-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="handlerProduct" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$Collaborator">
                        <property name="name" value="product handler"/>
                    </bean>
                    <bean id="handlerOrder" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$Collaborator">
                        <property name="name" value="order handler"/>
                    </bean>
                    <bean id="dispatch" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$InterceptorChain">
                        <property name="byTypeCode">
                            <map>
                                <entry key="Product" value-ref="handlerProduct"/>
                                <entry key="Order" value-ref="handlerOrder"/>
                            </map>
                        </property>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        assertEquals(2, dispatch.getByTypeCode().size());
        assertEquals("order handler", dispatch.getByTypeCode().get("Order").getName());
    }

    @Test
    void multiplePlaceholdersInOneStringAllResolve() throws Exception {
        // Hybris JDBC URL pattern: jdbc:mysql://${db.host}:${db.port}/${db.name}
        liveContext.getEnvironment().getPropertySources().addLast(
                new org.springframework.core.env.MapPropertySource(
                        "test-db-props",
                        Map.of("db.host", "mysql.prod.local",
                               "db.port", "3306",
                               "db.name", "commercesuite")));
        DefaultListableBeanFactory bf = (DefaultListableBeanFactory) liveContext.getBeanFactory();
        bf.registerBeanDefinition("pspc", org.springframework.beans.factory.support.BeanDefinitionBuilder
                .rootBeanDefinition(org.springframework.context.support.PropertySourcesPlaceholderConfigurer.class)
                .getBeanDefinition());

        Path xml = writeXml("jdbc-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="jdbc" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="uninitialized"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        TestBean jdbc = liveContext.getBean("jdbc", TestBean.class);

        writeXml("jdbc-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="jdbc" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="jdbc:mysql://${db.host}:${db.port}/${db.name}"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        assertEquals("jdbc:mysql://mysql.prod.local:3306/commercesuite", jdbc.getMessage(),
                "all three placeholders in the single string must be resolved");
    }

    @Test
    void hybrisNewInterceptorBeanWithRefToExistingBeanIsAddedLive() throws Exception {
        // Realistic Hybris workflow: a developer writes a new interceptor
        // class, drops it into an extension's -spring.xml, and expects the
        // bean to be registered without a server restart.
        Path xml = writeXml("workflow-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="firstInterceptor" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$Collaborator">
                        <property name="name" value="first"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        Collaborator first = liveContext.getBean("firstInterceptor", Collaborator.class);

        writeXml("workflow-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="firstInterceptor" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$Collaborator">
                        <property name="name" value="first"/>
                    </bean>
                    <bean id="newValidationInterceptor" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$HostBean">
                        <property name="collaborator" ref="firstInterceptor"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        HostBean newInterceptor = liveContext.getBean("newValidationInterceptor", HostBean.class);
        assertSame(first, newInterceptor.getCollaborator(),
                "new bean must wire to the live instance of the existing bean");
    }

    @Test
    void beanIdWithDotsAndHyphensIsSupported() throws Exception {
        // Hybris commonly uses FQCN-style ids (e.g., "com.foo.BarService")
        // or names like "my-extension.someComponent".
        Path xml = writeXml("dotted-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="com.onurkat.foo.BarService" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="v1"/>
                    </bean>
                    <bean id="my-extension.componentA" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="a1"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(xml);
        liveContext.refresh();

        TestBean dotted = liveContext.getBean("com.onurkat.foo.BarService", TestBean.class);
        TestBean hyphen = liveContext.getBean("my-extension.componentA", TestBean.class);

        writeXml("dotted-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="com.onurkat.foo.BarService" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="v2"/>
                    </bean>
                    <bean id="my-extension.componentA" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="a2"/>
                    </bean>
                </beans>
                """);

        reloader.reload(xml);

        assertEquals("v2", dotted.getMessage());
        assertEquals("a2", hyphen.getMessage());
    }

    @Test
    void hybrisStyleTwoExtensionXmlFilesAreIsolated() throws Exception {
        // Simulates two extensions each owning their own -spring.xml with
        // disjoint bean ids. Editing one must not touch beans from the other.
        Path coreExt = writeXml("coreext-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="defaultPlatformService" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="core-v1"/>
                    </bean>
                </beans>
                """);
        Path customExt = writeXml("customext-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="defaultCustomService" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="custom-v1"/>
                    </bean>
                </beans>
                """);
        loadIntoLiveContext(coreExt);
        loadIntoLiveContext(customExt);
        liveContext.refresh();

        TestBean coreService = liveContext.getBean("defaultPlatformService", TestBean.class);
        TestBean customService = liveContext.getBean("defaultCustomService", TestBean.class);

        // Edit custom extension — core must be untouched
        writeXml("customext-spring.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="defaultCustomService" class="com.onurkat.reclazz.spring.xml.SpringXmlReloaderTest$TestBean">
                        <property name="message" value="custom-v2"/>
                    </bean>
                </beans>
                """);

        reloader.reload(customExt);

        assertEquals("custom-v2", customService.getMessage());
        assertEquals("core-v1", coreService.getMessage(),
                "core extension's bean must remain untouched when customext-spring.xml is reloaded");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Path writeXml(String fileName, String content) throws IOException {
        Path p = tempDir.resolve(fileName);
        Files.writeString(p, content);
        return p;
    }

    private void loadIntoLiveContext(Path xml) {
        XmlBeanDefinitionReader reader = new XmlBeanDefinitionReader(
                (DefaultListableBeanFactory) liveContext.getBeanFactory());
        reader.setValidating(false);
        reader.loadBeanDefinitions(new FileSystemResource(xml.toFile()));
    }

    /** Minimal PlatformContext stub — only getApplicationContext() is consulted. */
    private static final class TestPlatformContext implements PlatformContext {
        private final Object context;

        TestPlatformContext(Object context) {
            this.context = context;
        }

        @Override public Platform getPlatformId() { return Platform.GENERIC; }
        @Override public void initialize() { }
        @Override public Map<String, List<Path>> getClassOutputDirs() { return Map.of(); }
        @Override public Map<String, List<Path>> getSourceDirs() { return Map.of(); }
        @Override public Map<String, List<Path>> getResourceDirs() { return Map.of(); }
        @Override public String resolveClasspath() { return ""; }
        @Override public String resolveClassName(Path classFile) { return null; }
        @Override public Path resolveOutputDir(Path classFile) { return null; }
        @Override public Object getApplicationContext() { return context; }
    }

    // ─── Test fixtures ────────────────────────────────────────────────────────

    public static class TestBean {
        private String message;
        private int count;
        private boolean flag;
        private long bigCount;
        private double ratio;

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
        public boolean isFlag() { return flag; }
        public void setFlag(boolean flag) { this.flag = flag; }
        public long getBigCount() { return bigCount; }
        public void setBigCount(long bigCount) { this.bigCount = bigCount; }
        public double getRatio() { return ratio; }
        public void setRatio(double ratio) { this.ratio = ratio; }

        public void init() { /* would have side effects in real code */ }
        public void cleanup() { /* referenced from destroy-method in tests */ }
    }

    public static class Collaborator {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class HostBean {
        private Collaborator collaborator;
        public Collaborator getCollaborator() { return collaborator; }
        public void setCollaborator(Collaborator collaborator) { this.collaborator = collaborator; }
    }

    /** Ctor-only bean — tests constructor-arg unsafe classification. */
    public static class CtorBean {
        private final String value;
        public CtorBean(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    /** Bean whose setter throws — exercises per-bean failure isolation. */
    public static class ExplodingBean {
        private String payload;
        public String getPayload() { return payload; }
        public void setPayload(String payload) {
            throw new IllegalStateException("setter refuses: " + payload);
        }
    }

    /** Implements Spring's BeanPostProcessor — should be rejected as unsafe. */
    public static class CustomPostProcessor
            implements org.springframework.beans.factory.config.BeanPostProcessor {
        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) { return bean; }
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) { return bean; }
    }

    /** Implements InitializingBean — should be rejected as unsafe. */
    public static class CustomInitializingBean
            implements org.springframework.beans.factory.InitializingBean {
        private String data;
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
        @Override
        public void afterPropertiesSet() { /* would register things externally in real code */ }
    }

    /**
     * Service bean with a list of collaborators — models an interceptor chain
     * or populator list, the most common Hybris list-valued injection pattern.
     */
    public static class InterceptorChain {
        private java.util.List<Collaborator> interceptors;
        private java.util.Map<String, Collaborator> byTypeCode;

        public java.util.List<Collaborator> getInterceptors() { return interceptors; }
        public void setInterceptors(java.util.List<Collaborator> interceptors) { this.interceptors = interceptors; }
        public java.util.Map<String, Collaborator> getByTypeCode() { return byTypeCode; }
        public void setByTypeCode(java.util.Map<String, Collaborator> byTypeCode) { this.byTypeCode = byTypeCode; }
    }
}
