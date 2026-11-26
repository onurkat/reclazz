/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.platform.HybrisPlatformContext;
import com.onurkat.reclazz.platform.SpringXmlResources;
import com.onurkat.reclazz.spring.xml.SpringXmlReloader;
import com.onurkat.reclazz.ui.StatusReporter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import static org.junit.jupiter.api.Assertions.*;

public class SpringXmlClassArrivalTest {
    public static class First { public String toString() { return "first"; } }
    public static class Second { public String toString() { return "second"; } }
    public static class Third { public String toString() { return "third"; } }
    public static class Converter {
        private List<Object> steps;
        int setterCalls;
        public void setSteps(List<Object> steps) { setterCalls++; this.steps = steps; }
        public List<String> convert() { return steps.stream().map(Object::toString).toList(); }
    }

    /** Like the native loader, a premature lookup stays negative even after the class appears. */
    static class DeliveryLoader extends ClassLoader {
        final Set<String> waiting = new HashSet<>(), poisoned = new HashSet<>();
        DeliveryLoader() { super(SpringXmlClassArrivalTest.class.getClassLoader()); }
        @Override public URL getResource(String path) {
            if (waiting.stream().anyMatch(name -> path.equals(name.replace('.', '/') + ".class"))) return null;
            return super.getResource(path);
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (waiting.contains(name)) poisoned.add(name);
            if (poisoned.contains(name)) throw new ClassNotFoundException(name);
            return super.loadClass(name, resolve);
        }
    }
    @TempDir Path directory;
    Path xml;
    final List<GenericApplicationContext> owners = new ArrayList<>();
    final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
    SpringXmlReloader reloader;
    @BeforeEach void start() throws Exception {
        ApplicationContextHolder.clear();
        xml = directory.resolve("delivery-spring.xml");
        Files.writeString(xml, document(false,false,false));
        reloader = new SpringXmlReloader(new HybrisPlatformContext(directory, AgentConfig.parse(null)) {
            @Override public Object getApplicationContext() { return owners.isEmpty() ? null : owners.get(0); }
        });
        reloader.setClassWaitTimer(timer);
    }
    @AfterEach void close() {
        timer.shutdownNow();
        owners.forEach(GenericApplicationContext::close);
        ApplicationContextHolder.clear();
    }
    static String bean(String id,String type) { return "<bean id='"+id+"' class='"+type+"'/>"; }
    static String ref(String id) { return "<ref bean='"+id+"'/>"; }
    static String document(boolean second,boolean third,boolean placeholder) {
        String beans=bean("first",First.class.getName());
        String added = second ? "<bean class='" + (placeholder ? "${c3.populator.class}" : Second.class.getName()) + "'/>" : "";
        if (third) added += "<bean class='" + Third.class.getName() + "'/>";
        return "<beans xmlns='http://www.springframework.org/schema/beans'>"+beans
                +"<bean id='converter' class='"+Converter.class.getName()+"'><property name='steps'><list>"
                +ref("first")+added+"</list></property></bean></beans>";
    }
    Converter owner(DeliveryLoader loader) {
        var context = new GenericApplicationContext();
        context.setClassLoader(loader);
        context.getBeanFactory().addEmbeddedValueResolver(value -> value.replace("${c3.populator.class}",Second.class.getName()));
        var reader = new XmlBeanDefinitionReader(context);
        reader.setValidating(false);
        var resource = new FileSystemResource(xml);
        reader.loadBeanDefinitions(resource);
        SpringXmlResources.record(reader,resource);
        context.refresh();
        owners.add(context);
        ApplicationContextHolder.register(context);
        return context.getBean(Converter.class);
    }
    void edit(boolean third,boolean placeholder) throws Exception {
        Files.writeString(xml,document(true,third,placeholder));
        reloader.reload(xml);
    }
    void deliver(DeliveryLoader loader,Class<?> type) {
        assertTrue(loader.waiting.remove(type.getName()));
        reloader.classReady(type.getName());
    }
    @Test void onlyLastClassMakesBothOwnersApplyOnce() throws Exception {
        var available = new DeliveryLoader();
        var pending = new DeliveryLoader();
        pending.waiting.addAll(List.of(Second.class.getName(), Third.class.getName()));
        var first=owner(available);var second=owner(pending);
        edit(true,false);
        deliver(pending,Second.class);
        for (var converter : List.of(first,second)) {
            assertEquals(List.of("first"),converter.convert());
            assertEquals(1,converter.setterCalls);
        }
        deliver(pending,Third.class);
        reloader.classReady(Third.class.getName());
        for (var converter : List.of(first,second)) {
            assertEquals(List.of("first","second","third"),converter.convert());
            assertEquals(2,converter.setterCalls);
        }
        assertTrue(pending.poisoned.isEmpty());
    }
    @Test void placeholderWaitsForResolvedName() throws Exception {
        var loader=new DeliveryLoader();loader.waiting.add(Second.class.getName());
        var converter=owner(loader);edit(false,true);
        assertEquals(List.of("first"),converter.convert());
        deliver(loader,Second.class);
        assertEquals(List.of("first","second"),converter.convert());
        assertEquals(2,converter.setterCalls);
        assertTrue(loader.poisoned.isEmpty());
    }
    @Test void revertingXmlCancelsItsDeadlineAndDiscardsTheOldSave() throws Exception {
        var loader = new DeliveryLoader();
        loader.waiting.add(Second.class.getName());
        var converter = owner(loader);
        edit(false, false);
        var deadline = (ScheduledFuture<?>) timer.getQueue().element();
        Files.writeString(xml, document(false, false, false));
        reloader.reload(xml);
        assertTrue(deadline.isCancelled());
        deliver(loader, Second.class);
        assertEquals(List.of("first"), converter.convert());
        assertEquals(1, converter.setterCalls);
    }

    @Test void idleDeadlineNamesMissingClassAndLaterDeliveryRecovers() throws Exception {
        var loader=new DeliveryLoader();loader.waiting.add(Second.class.getName());
        var converter=owner(loader);
        var warnings=new LinkedBlockingQueue<String>();
        StatusReporter.StatusListener listener=(level,message)->{
            if(message.contains("not compiled yet")) warnings.add(message);
        };
        StatusReporter.addListener(listener);
        try {
            edit(false,false);
            var warning=warnings.poll(15,TimeUnit.SECONDS);
            assertNotNull(warning);
            assertTrue(warning.contains(xml.getFileName().toString()) && warning.contains(Second.class.getName()));
            assertEquals(List.of("first"),converter.convert());
            deliver(loader,Second.class);
            assertEquals(List.of("first","second"),converter.convert());
            assertEquals(2,converter.setterCalls);
            assertTrue(warnings.isEmpty());
        } finally { StatusReporter.removeListener(listener); }
    }
}
