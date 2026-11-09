/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.interceptor;

import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.spring.xml.SpringXmlReloader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.*;
import java.util.*;

/** Native Spring controls for shared metadata, lazy creation and failed-ref retry. */
public class XmlDependencySdkProbe {
    public static class Settings {
        String value;
        boolean fail;
        public void setValue(String value) {
            if (fail) throw new IllegalArgumentException("synthetic prerequisite failure");
            this.value = value;
        }
    }
    public static class Consumer {
        String captured;
        int calls;
        public void setTarget(Settings value) { calls++; captured = value.value; }
    }
    private static String xml(String value,String ref,boolean consumerFirst) {
        String dependency="<bean id='dependency' class='"+Settings.class.getName()+"' lazy-init='true'><property name='value' value='"+value+"'/></bean>";
        String consumer="<bean id='consumer' class='"+Consumer.class.getName()+"'><property name='target' ref='"+ref+"'/></bean>";
        return "<beans xmlns='http://www.springframework.org/schema/beans'>"
                +"<bean id='other' class='"+Settings.class.getName()+"'><property name='value' value='other'/></bean>"
                +(consumerFirst?consumer+dependency:dependency+consumer)+"</beans>";
    }
    public static void main(String[] args) throws Exception {
        int cases=0;
        for(boolean shared:List.of(false,true)) for(boolean reverse:List.of(false,true))
            for(boolean consumerFirst:List.of(false,true)) for(boolean mixed:List.of(false,true)) {
                try(var fixture=new Fixture(shared,reverse,consumerFirst,mixed)) {
                    fixture.edit();fixture.assertNew();
                    fixture.reloader.reloadLoaded(fixture.xml);fixture.assertNew();
                    cases++;
                }
            }
        try(var fixture=new Fixture(false,false,false,true)) {
            Settings dependency=fixture.owners.get(0).getBean("dependency",Settings.class);
            Consumer consumer=fixture.owners.get(0).getBean("consumer",Consumer.class);
            int calls=consumer.calls;dependency.fail=true;fixture.edit();
            if(consumer.calls!=calls||!"other".equals(consumer.captured)) throw new AssertionError("failed prerequisite did not defer consumer");
            dependency.fail=false;fixture.reloader.reloadLoaded(fixture.xml);fixture.assertNew();
            if(consumer.calls!=calls+1) throw new AssertionError("consumer retry did not run exactly once");
            cases++;
        }
        System.out.println("XML_DEPENDENCY_SDK_PASS="+cases);
    }
    private static final class Fixture implements AutoCloseable {
        final Path xml;
        final boolean consumerFirst;
        final List<GenericApplicationContext> owners=new ArrayList<>();
        final SpringXmlReloader reloader;
        Fixture(boolean shared,boolean reverse,boolean consumerFirst,boolean mixed) throws Exception {
            this.consumerFirst=consumerFirst;xml=Files.createTempFile("reclazz-native-dependency-","-spring.xml");
            Files.writeString(xml,xml("old","other",consumerFirst));
            for(int i=0;i<2;i++) {
                var c=new GenericApplicationContext();owners.add(c);var reader=new XmlBeanDefinitionReader(c);reader.setValidating(false);
                var resource=new FileSystemResource(xml);reader.loadBeanDefinitions(resource);SpringXmlResources.record(reader,resource);
                if(shared&&i>0)c.registerBeanDefinition("dependency",owners.get(0).getBeanFactory().getBeanDefinition("dependency"));
                c.refresh();if(mixed&&i==0)c.getBean("dependency");c.getBeanFactory().getMergedBeanDefinition("dependency");
            }
            var order=new ArrayList<>(owners);if(reverse)Collections.reverse(order);order.forEach(ApplicationContextHolder::register);
            PlatformContext platform=(PlatformContext)java.lang.reflect.Proxy.newProxyInstance(XmlDependencySdkProbe.class.getClassLoader(),
                    new Class<?>[]{PlatformContext.class},(p,m,a)->m.getName().equals("getApplicationContext")?owners.get(0):null);
            reloader=new SpringXmlReloader(platform);
        }
        void edit() throws Exception { Files.writeString(xml,xml("new","dependency",consumerFirst));reloader.reloadLoaded(xml); }
        void assertNew() {
            for(var owner:owners)if(!"new".equals(owner.getBean("consumer",Consumer.class).captured)
                    ||!"new".equals(owner.getBean("dependency",Settings.class).value)) throw new AssertionError("stale dependency in owner");
        }
        public void close() throws Exception { owners.forEach(GenericApplicationContext::close);ApplicationContextHolder.clear();Files.deleteIfExists(xml); }
    }
}
