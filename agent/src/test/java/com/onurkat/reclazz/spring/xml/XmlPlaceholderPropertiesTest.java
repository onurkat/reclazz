/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.ui.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class XmlPlaceholderPropertiesTest {
    @TempDir Path dir;
    final List<GenericApplicationContext> contexts=new ArrayList<>();
    public static class Probe {
        public String value; public int number; public int calls;
        public void setValue(String v) { if("bad".equals(v)) throw new IllegalStateException("synthetic failure");value=v;calls++; }
        public void setNumber(int v) {number=v;}
    }
    public static class Initializer extends Probe implements InitializingBean { public void afterPropertiesSet() { } }
    public static class Factory extends Probe implements FactoryBean<Object> {
        public Object getObject(){return new Object();} public Class<?> getObjectType(){return Object.class;}
    }
    String bean(String name,Class<?> type,String property,String value,String extra) {
        return "<bean id='"+name+"' class='"+type.getName()+"' "+extra+"><property name='"+property+"' value='"+value+"'/></bean>";
    }
    GenericApplicationContext load(String xml) throws Exception {
        Path file=dir.resolve("probe"+contexts.size()+"-spring.xml");Files.writeString(file,"<beans xmlns='http://www.springframework.org/schema/beans'>"+xml+"</beans>");
        var c=new GenericApplicationContext();contexts.add(c);var p=new Properties();p.setProperty("key","v1");p.setProperty("other","old");p.setProperty("number","1");
        var config=new PropertyPlaceholderConfigurer();config.setProperties(p);c.addBeanFactoryPostProcessor(config);
        var reader=new XmlBeanDefinitionReader(c);reader.setValidating(false);var resource=new FileSystemResource(file);reader.loadBeanDefinitions(resource);SpringXmlResources.record(reader,resource);
        c.refresh();ApplicationContextHolder.register(c);return c;
    }
    void apply(Map<String,String> values) throws Exception {
        PlatformContext platform=(PlatformContext)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},(p,m,a)->m.getName().equals("getApplicationContext")?contexts.get(0):null);
        new SpringXmlReloader(platform).reapplyPlaceholderProperties(values);
    }
    @AfterEach void close(){contexts.forEach(GenericApplicationContext::close);ApplicationContextHolder.clear();RestartLedger.clear();}
    @Test void changedKeyUpdatesSameBeanAndSecondChangeWhileUnrelatedStaysUntouched() throws Exception {
        var c=load(bean("probe",Probe.class,"value","${key}","")+bean("other",Probe.class,"value","${other}",""));var probe=c.getBean("probe",Probe.class);var other=c.getBean("other",Probe.class);
        Object raw=c.getBeanFactory().getBeanDefinition("probe").getPropertyValues().get("value");int otherCalls=other.calls;
        apply(Map.of("key","v2"));assertEquals("v2",probe.value);assertSame(probe,c.getBean("probe"));assertEquals("old",other.value);assertEquals(otherCalls,other.calls);
        apply(Map.of("key","v3"));assertEquals("v3",probe.value);assertSame(raw,c.getBeanFactory().getBeanDefinition("probe").getPropertyValues().get("value"));
    }
    @Test void scalarConversionAndLazyBeanDoesNotInitialize() throws Exception {
        var c=load(bean("probe",Probe.class,"number","${number}","")+bean("lazy",Probe.class,"value","${key}","lazy-init='true'"));
        apply(Map.of("number","42","key","v2"));assertEquals(42,c.getBean("probe",Probe.class).number);assertFalse(c.getBeanFactory().containsSingleton("lazy"));
    }
    @Test void lifecycleAndFactoryRemainRefused() throws Exception {
        var c=load(bean("init",Initializer.class,"value","${key}","")+bean("factory",Factory.class,"value","${key}",""));
        var init=c.getBean("init",Initializer.class);var factory=c.getBean("&factory",Factory.class);apply(Map.of("key","v2"));assertEquals("v1",init.value);assertEquals("v1",factory.value);
    }
    @Test void failedSetterReportsKeyAndBeanAndNextSaveCanRetry() throws Exception {
        var c=load(bean("probe",Probe.class,"value","${key}",""));var probe=c.getBean("probe",Probe.class);
        apply(Map.of("key","bad"));assertEquals("v1",probe.value);assertTrue(RestartLedger.digest().stream().anyMatch(s->s.contains("key")&&s.contains("probe")),RestartLedger.digest().toString());
        apply(Map.of("key","v2"));assertEquals("v2",probe.value);
    }
}
