/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.platform.SpringXmlResources;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.xml.BeanDefinitionParserDelegate;
import org.springframework.beans.factory.xml.DefaultBeanDefinitionDocumentReader;
import org.springframework.beans.factory.xml.DocumentDefaultsDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.beans.factory.xml.XmlReaderContext;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.w3c.dom.Element;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A file the agent parses again must read the way the live context read it,
 * and must reach only the contexts that hold it. Found on a SAP Commerce
 * install, where an edited OCC field set was refused in the context that owned
 * it and applied to sixty-seven that did not.
 */
class XmlLiveReaderParityTest {
    @TempDir Path tmp;
    private final List<String> events = new ArrayList<>();
    private final StatusReporter.StatusListener listener = (level, message) -> events.add(level + ":" + message);
    private final List<GenericApplicationContext> contexts = new ArrayList<>();

    public static class Target {
        public String label;
        public void setLabel(String value) { label = value; }
    }

    /**
     * What SAP Commerce's web document reader does when
     * {@code web.spring.lazy.load.singletons} is on: every top-level bean
     * without its own lazy-init attribute is lazy.
     */
    public static class LazyByDefaultReader extends DefaultBeanDefinitionDocumentReader {
        @Override
        protected BeanDefinitionParserDelegate createDelegate(XmlReaderContext readerContext, Element root,
                                                              BeanDefinitionParserDelegate parentDelegate) {
            BeanDefinitionParserDelegate delegate = new BeanDefinitionParserDelegate(readerContext) {
                @Override
                protected void populateDefaults(DocumentDefaultsDefinition defaults,
                                                DocumentDefaultsDefinition parentDefaults, Element element) {
                    super.populateDefaults(defaults, parentDefaults, element);
                    defaults.setLazyInit("true");
                }
            };
            delegate.initDefaults(root, parentDelegate);
            return delegate;
        }
    }

    @BeforeEach
    void listen() {
        ApplicationContextHolder.clear();
        RestartLedger.clear();
        StatusReporter.addListener(listener);
    }

    @AfterEach
    void close() {
        StatusReporter.removeListener(listener);
        contexts.forEach(GenericApplicationContext::close);
        ApplicationContextHolder.clear();
        RestartLedger.clear();
    }

    @Test
    void aFileReadByTheContextsOwnDocumentReaderIsParsedAgainWithIt() throws Exception {
        Path xml = Files.writeString(tmp.resolve("web-spring.xml"), document("before", ""));
        GenericApplicationContext live = load(xml, LazyByDefaultReader.class, true);
        assertTrue(live.getBeanFactory().getBeanDefinition("target").isLazyInit());

        Files.writeString(xml, document("after", ""));
        reloader(live).reload(xml);

        assertFalse(events.stream().anyMatch(e -> e.contains("Restart required")), events.toString());
        assertEquals("after", live.getBean("target", Target.class).label);
        assertTrue(live.getBeanFactory().getBeanDefinition("target").isLazyInit());
    }

    @Test
    void aFileUnderASymlinkedExtensionIsTheFileTheContextLoaded() throws Exception {
        Path real = Files.createDirectories(tmp.resolve("repo/ext/resources"));
        Path link = Files.createSymbolicLink(tmp.resolve("custom"), tmp.resolve("repo"));
        Path loaded = Files.writeString(real.resolve("ext-spring.xml"), document("before", ""));
        Path watched = link.resolve("ext/resources/ext-spring.xml");
        GenericApplicationContext live = load(loaded, LazyByDefaultReader.class, true);

        assertTrue(SpringXmlResources.isLoaded(watched), "the watcher reports the path under the link");
        Files.writeString(watched, document("after", ""));
        reloader(live).reload(watched);

        assertFalse(events.stream().anyMatch(e -> e.contains("Restart required")), events.toString());
        assertEquals("after", live.getBean("target", Target.class).label);
    }

    @Test
    void springsOwnProcessorNamesDoNotMakeAContextAnOwner() throws Exception {
        String scan = "<context:annotation-config/>";
        Path xml = Files.writeString(tmp.resolve("owned-spring.xml"), document("before", scan));
        GenericApplicationContext owner = load(xml, null, false);
        GenericApplicationContext unrelated = new GenericApplicationContext();
        AnnotationConfigUtils.registerAnnotationConfigProcessors(unrelated);
        unrelated.refresh();
        contexts.add(unrelated);
        ApplicationContextHolder.register(owner);
        ApplicationContextHolder.register(unrelated);

        Files.writeString(xml, document("after", scan + "<bean id='added' class='" + Target.class.getName() + "'/>"));
        reloader(owner).reload(xml);

        assertEquals("after", owner.getBean("target", Target.class).label);
        assertTrue(owner.containsBeanDefinition("added"));
        assertFalse(unrelated.containsBeanDefinition("target"), events.toString());
        assertFalse(unrelated.containsBeanDefinition("added"), events.toString());
    }

    private static String document(String label, String extra) {
        return "<beans xmlns='http://www.springframework.org/schema/beans'"
                + " xmlns:context='http://www.springframework.org/schema/context'>"
                + extra
                + "<bean id='target' class='" + Target.class.getName() + "'>"
                + "<property name='label' value='" + label + "'/></bean></beans>";
    }

    private GenericApplicationContext load(Path xml, Class<?> documentReader, boolean record) {
        GenericApplicationContext context = new GenericApplicationContext();
        XmlBeanDefinitionReader reader = new XmlBeanDefinitionReader(context);
        reader.setValidating(false);
        if (documentReader != null) reader.setDocumentReaderClass(documentReader.asSubclass(DefaultBeanDefinitionDocumentReader.class));
        FileSystemResource resource = new FileSystemResource(xml);
        reader.loadBeanDefinitions(resource);
        if (record) SpringXmlResources.record(reader, resource);
        context.refresh();
        contexts.add(context);
        ApplicationContextHolder.register(context);
        return context;
    }

    private static SpringXmlReloader reloader(GenericApplicationContext context) {
        PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(
                XmlLiveReaderParityTest.class.getClassLoader(), new Class<?>[]{PlatformContext.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getApplicationContext")) return context;
                    throw new UnsupportedOperationException(method.getName());
                });
        return new SpringXmlReloader(platform);
    }
}
