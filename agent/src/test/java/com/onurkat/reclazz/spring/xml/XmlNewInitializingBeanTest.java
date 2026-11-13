/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.*;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.*;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class XmlNewInitializingBeanTest {
    @TempDir Path dir;
    final List<GenericApplicationContext> contexts = new ArrayList<>();
    public static class Probe {
        static int constructed;
        public int calls;
        public String value;
        public Probe() { constructed++; }
        public void setValue(String value) { this.value=value; }
        public void init() { calls++; if ("bad".equals(value)) throw new IllegalStateException("synthetic init failure"); }
    }
    public static class Initializer extends Probe implements InitializingBean {
        public void afterPropertiesSet() { init(); }
    }
    public static class Bpp extends Initializer implements BeanPostProcessor { }
    public static class Bfpp extends Initializer implements BeanFactoryPostProcessor {
        public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) { }
    }
    public static class Factory extends Initializer implements FactoryBean<Object> {
        public Object getObject() { return new Object(); }
        public Class<?> getObjectType() { return Object.class; }
    }
    public static class Smart extends Initializer implements SmartLifecycle {
        public void start() { } public void stop() { } public boolean isRunning() { return false; }
    }
    public static class Disposable extends Initializer implements DisposableBean { public void destroy() { } }
    public static class ListenerInitializer extends Initializer implements ApplicationListener<ApplicationEvent> {
        public void onApplicationEvent(ApplicationEvent event) { }
    }
    public static class ListenerInitMethod extends Probe implements ApplicationListener<ApplicationEvent> {
        public void onApplicationEvent(ApplicationEvent event) { }
    }
    public static class Lookup implements ApplicationContextAware {
        ApplicationContext context;
        public void setApplicationContext(ApplicationContext context) { this.context=context; }
        Probe lookup() { return context.getBean("probe",Probe.class); }
    }
    @BeforeEach void reset() { ApplicationContextHolder.clear(); Probe.constructed=0; }
    @AfterEach void close() { contexts.forEach(GenericApplicationContext::close); ApplicationContextHolder.clear(); }
    String bean(Class<?> type,String attributes,String value) {
        return "<bean id='probe' class='"+type.getName()+"' "+attributes+"><property name='value' value='"+value+"'/></bean>";
    }
    Path xml(String added) throws Exception {
        Path file=dir.resolve("new-lifecycle-spring.xml");
        Files.writeString(file,"<beans xmlns='http://www.springframework.org/schema/beans'><bean id='lookup' class='"+Lookup.class.getName()+"'/>"+added+"</beans>");return file;
    }
    GenericApplicationContext load(Path file) {
        var context=new GenericApplicationContext();contexts.add(context);
        var reader=new XmlBeanDefinitionReader(context);reader.setValidating(false);
        var resource=new FileSystemResource(file);reader.loadBeanDefinitions(resource);SpringXmlResources.record(reader,resource);
        context.refresh();ApplicationContextHolder.register(context);return context;
    }
    void reload(Path file,GenericApplicationContext context) {
        PlatformContext platform=(PlatformContext)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                (p,m,a)->m.getName().equals("getApplicationContext")?context:null);
        new SpringXmlReloader(platform).reloadLoaded(file);
    }
    @Test void newInitializerRunsOnceAndExistingLookupSeesIt() throws Exception {
        Path file=xml("");var context=load(file);var consumer=context.getBean("lookup",Lookup.class);
        xml(bean(Initializer.class,"","ready"));reload(file,context);
        assertTrue(context.getBeanFactory().containsSingleton("probe"));
        Probe probe=consumer.lookup();assertEquals(1,probe.calls);assertEquals("ready",probe.value);
        reload(file,context);assertSame(probe,consumer.lookup());assertSame(consumer,context.getBean("lookup"));assertEquals(1,probe.calls);assertEquals(1,Probe.constructed);
    }
    @Test void newInitMethodRunsOnceAndIdenticalSaveKeepsInstance() throws Exception {
        Path file=xml("");var context=load(file);xml(bean(Probe.class,"init-method='init'","ready"));reload(file,context);
        assertTrue(context.getBeanFactory().containsSingleton("probe"));var probe=context.getBean("probe",Probe.class);assertEquals(1,probe.calls);
        reload(file,context);assertSame(probe,context.getBean("probe"));assertEquals(1,probe.calls);assertEquals(1,Probe.constructed);
    }
    @Test void failedNativeInitializationRemovesDefinitionAndCorrectedSaveWorks() throws Exception {
        for(Class<?> type:List.of(Initializer.class,Probe.class)) {
            Path file=xml("");var context=load(file);String attributes=type==Probe.class?"init-method='init'":"";
            xml(bean(type,attributes,"bad"));reload(file,context);
            assertFalse(context.getBeanFactory().containsBeanDefinition("probe"));assertFalse(context.getBeanFactory().containsSingleton("probe"));
            xml(bean(type,attributes,"ready"));reload(file,context);var probe=context.getBean("probe",Probe.class);assertEquals(1,probe.calls);assertEquals("ready",probe.value);
            context.close();ApplicationContextHolder.clear();
        }
    }
    @Test void infrastructureAndDisposalInitializersAreNotConstructed() throws Exception {
        for(Class<?> type:List.of(Bpp.class,Bfpp.class,Factory.class,Smart.class,Disposable.class)) {
            Path file=xml("");var context=load(file);Probe.constructed=0;
            xml(bean(type,"","ready"));reload(file,context);
            assertFalse(context.getBeanFactory().containsBeanDefinition("probe"),type.getName());assertFalse(context.getBeanFactory().containsSingleton("probe"));assertEquals(0,Probe.constructed);
            context.close();ApplicationContextHolder.clear();
        }
    }
    @Test void listenerRegistrationDoesNotUseOrdinaryInitializationAllowance() throws Exception {
        for(Class<?> type:List.of(ListenerInitializer.class,ListenerInitMethod.class)) {
            Path file=xml("");var context=load(file);Probe.constructed=0;
            xml(bean(type,type==ListenerInitMethod.class?"init-method='init'":"","ready"));reload(file,context);
            assertFalse(context.getBeanFactory().containsBeanDefinition("probe"),type.getName());
            assertFalse(context.getBeanFactory().containsSingleton("probe"));assertEquals(0,Probe.constructed);
            context.close();ApplicationContextHolder.clear();
        }
    }
    @Test void existingInitializerPropertyEditRemainsRefused() throws Exception {
        Path file=xml(bean(Initializer.class,"","before"));var context=load(file);var probe=context.getBean("probe",Probe.class);
        Object definition=context.getBeanFactory().getBeanDefinition("probe");
        xml(bean(Initializer.class,"","after"));reload(file,context);
        assertSame(probe,context.getBean("probe"));assertEquals("before",probe.value);assertEquals(1,probe.calls);assertSame(definition,context.getBeanFactory().getBeanDefinition("probe"));
        assertEquals("before",((org.springframework.beans.factory.config.TypedStringValue)context.getBeanFactory().getBeanDefinition("probe").getPropertyValues().get("value")).getValue());
    }
}
