/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.ui.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;

final class XmlDefinitionTestScope implements AutoCloseable {
    final Path xml;final GenericApplicationContext context;final SpringXmlReloader reloader;final List<String> events=new ArrayList<>();
    private final StatusReporter.StatusListener listener=(level,message)->events.add(level+" "+message);
    XmlDefinitionTestScope(Path xml,String beans)throws Exception{this(xml,beans,null);}
    XmlDefinitionTestScope(Path xml,String beans,GenericApplicationContext parent)throws Exception {
        this.xml=xml;Files.writeString(xml,document(beans));context=parent==null?new GenericApplicationContext():new GenericApplicationContext(parent);
        var reader=new XmlBeanDefinitionReader(context);reader.setValidating(false);var resource=new FileSystemResource(xml);reader.loadBeanDefinitions(resource);SpringXmlResources.record(reader,resource);context.refresh();ApplicationContextHolder.register(context);
        PlatformContext platform=(PlatformContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},(o,m,a)->m.getName().equals("getApplicationContext")?context:null);reloader=new SpringXmlReloader(platform);StatusReporter.addListener(listener);
    }
    static String document(String beans){return "<beans xmlns='http://www.springframework.org/schema/beans'>"+beans+"</beans>";}
    void reload(String beans)throws Exception{events.clear();Files.writeString(xml,document(beans));reloader.reload(xml);}
    boolean warned(){return events.stream().anyMatch(s->s.contains("Restart required")||s.startsWith("WARN "));}
    public void close(){StatusReporter.removeListener(listener);context.close();}
}
