/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class XmlBeanLifecycleTest {
    @TempDir Path dir;
    private final List<GenericApplicationContext> contexts = new ArrayList<>();
    private final List<Call> calls = new ArrayList<>();
    record Call(Object owner, String name, Object previous, Object current, boolean applied, String beforeValue) { }
    public static class Bean {
        private String value;
        private int number;
        public Bean() { }
        public Bean(String value) { this.value=value; }
        public void ready() { if("bad".equals(value)) throw new IllegalStateException("synthetic init failure"); }
        public String getValue() { return value; }
        public void setValue(String value) { this.value=value; }
        public int getNumber() { return number; }
        public void setNumber(int number) { this.number=number; }
        public void setExplode(String ignored) { throw new IllegalArgumentException("synthetic setter failure"); }
    }
    private final XmlBeanLifecycle hook = (owner,name,previous) -> {
        String beforeValue = previous instanceof Bean b ? b.getValue() : null;
        return (current,applied) -> calls.add(new Call(owner,name,previous,current,applied,beforeValue));
    };
    private String bean(String attributes, String content) {
        return "<bean id='probe' class='"+Bean.class.getName()+"' "+attributes+">"+content+"</bean>";
    }
    private Path xml(String content) throws Exception {
        Path path=dir.resolve("probe-spring.xml");
        Files.writeString(path,"<beans xmlns='http://www.springframework.org/schema/beans'>"+content+"</beans>");return path;
    }
    private GenericApplicationContext load(Path path) {
        var context=new GenericApplicationContext();contexts.add(context);
        var reader=new XmlBeanDefinitionReader(context);reader.setValidating(false);
        var resource=new FileSystemResource(path);reader.loadBeanDefinitions(resource);SpringXmlResources.record(reader,resource);
        context.refresh();ApplicationContextHolder.register(context);return context;
    }
    private SpringXmlReloader reloader(XmlBeanLifecycle lifecycle) {
        PlatformContext platform=(PlatformContext)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                (p,m,a)->m.getName().equals("getApplicationContext")?contexts.get(0):null);
        return new SpringXmlReloader(platform, paths->{}, lifecycle);
    }
    @AfterEach void close() {contexts.forEach(GenericApplicationContext::close);ApplicationContextHolder.clear();}

    @Test void newSingletonCallbackHasExactOwnerAndActualInstance() throws Exception {
        Path path=xml("");var context=load(path);xml(bean("",""));reloader(hook).reloadLoaded(path);
        assertEquals(1,calls.size());var call=calls.get(0);
        assertSame(context,call.owner());assertNull(call.previous());assertSame(context.getBean("probe"),call.current());assertTrue(call.applied());
    }
    @Test void twoPropertiesUseOneBeforeAfterPairPerOwner() throws Exception {
        Path path=xml(bean("","<property name='value' value='old'/><property name='number' value='1'/>"));
        var first=load(path);var second=load(path);
        xml(bean("","<property name='value' value='next'/><property name='number' value='2'/>"));
        reloader(hook).reloadLoaded(path);
        assertEquals(2,calls.size());
        for(var context:List.of(first,second)) {
            var matches=calls.stream().filter(c->c.owner()==context).toList();assertEquals(1,matches.size());
            var call=matches.get(0);assertEquals("old",call.beforeValue());assertSame(call.previous(),call.current());assertTrue(call.applied());
            assertEquals("next",((Bean)call.current()).getValue());assertEquals(2,((Bean)call.current()).getNumber());
        }
    }
    @Test void recreationPassesActualOldAndReplacement() throws Exception {
        Path path=xml(bean("","<constructor-arg value='old'/>"));var context=load(path);Object old=context.getBean("probe");
        xml(bean("","<constructor-arg value='new'/>"));reloader(hook).reloadLoaded(path);
        assertEquals(1,calls.size());assertSame(old,calls.get(0).previous());assertSame(context.getBean("probe"),calls.get(0).current());
        assertNotSame(old,calls.get(0).current());assertTrue(calls.get(0).applied());
    }
    @Test void recreationFailureCompletesWithRestoredInstance() throws Exception {
        Path path=xml(bean("init-method='ready'","<property name='value' value='old'/>"));var context=load(path);
        xml(bean("init-method='ready'","<property name='value' value='bad'/>"));reloader(hook).reloadLoaded(path);
        assertEquals(1,calls.size());assertFalse(calls.get(0).applied());assertSame(context.getBean("probe"),calls.get(0).current());
        assertEquals("old",((Bean)calls.get(0).current()).getValue());
    }
    @Test void setterFailureStillCompletesOnceWithCurrentInstance() throws Exception {
        Path path=xml(bean("","<property name='value' value='old'/>"));var context=load(path);
        xml(bean("","<property name='value' value='next'/><property name='explode' value='bad'/>"));reloader(hook).reloadLoaded(path);
        assertEquals(1,calls.size());assertSame(context.getBean("probe"),calls.get(0).current());assertFalse(calls.get(0).applied());
    }
    @Test void failedCreationCompletesWithNullAndRemovesPartialDefinition() throws Exception {
        Path path=xml("");var context=load(path);
        xml(bean("","<property name='explode' value='bad'/>"));reloader(hook).reloadLoaded(path);
        assertEquals(1,calls.size());assertNull(calls.get(0).current());assertFalse(calls.get(0).applied());assertFalse(context.containsBean("probe"));
    }
    @Test void absentHookRetainsOrdinaryMutation() throws Exception {
        Path path=xml(bean("","<property name='value' value='old'/>"));var context=load(path);Object old=context.getBean("probe");
        xml(bean("","<property name='value' value='new'/>"));reloader(XmlBeanLifecycle.NONE).reloadLoaded(path);
        assertSame(old,context.getBean("probe"));assertEquals("new",((Bean)old).getValue());assertTrue(calls.isEmpty());
    }
}
