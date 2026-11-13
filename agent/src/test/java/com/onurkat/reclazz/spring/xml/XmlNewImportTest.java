/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class XmlNewImportTest {
    @TempDir Path dir;
    public static class Probe {
        private String value;
        public String getValue() { return value; }
        public void setValue(String value) { this.value=value; }
    }
    private String beans(String content) {
        return "<beans xmlns='http://www.springframework.org/schema/beans'>"+content+"</beans>";
    }
    private String bean(String name,String value) {
        return "<bean id='"+name+"' class='"+Probe.class.getName()+"'><property name='value' value='"+value+"'/></bean>";
    }
    @Test void newImportAddsBeanAndSubsequentChildEditUpdatesSameSingleton() throws Exception {
        Path parent=dir.resolve("parent-spring.xml"), child=dir.resolve("child-spring.xml");
        String baseline=beans(bean("control","stable"));
        Files.writeString(parent,baseline);Files.writeString(child,beans(bean("imported","v1")));
        ApplicationContextHolder.clear();
        try(var context=new GenericApplicationContext()) {
            var reader=new XmlBeanDefinitionReader(context);reader.setValidating(false);
            var resource=new FileSystemResource(parent);reader.loadBeanDefinitions(resource);
            SpringXmlResources.record(reader,resource);context.refresh();ApplicationContextHolder.register(context);
            PlatformContext platform=(PlatformContext)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                    (p,m,a)->m.getName().equals("getApplicationContext")?context:null);
            var reloader=new SpringXmlReloader(platform);var control=context.getBean("control",Probe.class);
            assertFalse(context.containsBean("imported"));
            Files.writeString(parent,baseline.replace("</beans>","<import resource='child-spring.xml'/></beans>"));
            reloader.reloadLoaded(parent);
            assertTrue(context.getBeanFactory().containsBeanDefinition("imported"));
            var imported=context.getBean("imported",Probe.class);assertEquals("v1",imported.getValue());
            assertTrue(XmlBeanRecreator.owns(context.getBeanFactory().getBeanDefinition("imported"),child));
            Files.writeString(child,beans(bean("imported","v2")));
            // Named XML under a normal resource root uses reload's existing owner selection.
            reloader.reload(child);
            assertSame(imported,context.getBean("imported"));assertEquals("v2",imported.getValue());
            assertSame(control,context.getBean("control"));assertEquals("stable",control.getValue());
        } finally {ApplicationContextHolder.clear();}
    }
}
