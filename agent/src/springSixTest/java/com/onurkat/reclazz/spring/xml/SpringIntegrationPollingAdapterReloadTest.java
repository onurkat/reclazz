/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import static org.junit.jupiter.api.Assertions.*;
import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.ui.StatusReporter;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.endpoint.SourcePollingChannelAdapter;

class SpringIntegrationPollingAdapterReloadTest {
    @TempDir Path dir;
    GenericApplicationContext context;
    Path path;
    final List<String> receipts = new CopyOnWriteArrayList<>();
    final List<Long> times = new CopyOnWriteArrayList<>();
    final List<String> events = new CopyOnWriteArrayList<>();
    final StatusReporter.StatusListener listener = (level, message) -> events.add(message);

    String xml(String regex, int rate) {
        return """
        <beans xmlns="http://www.springframework.org/schema/beans" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xmlns:int="http://www.springframework.org/schema/integration" xmlns:file="http://www.springframework.org/schema/integration/file"
         xsi:schemaLocation="http://www.springframework.org/schema/beans https://www.springframework.org/schema/beans/spring-beans.xsd
         http://www.springframework.org/schema/integration https://www.springframework.org/schema/integration/spring-integration.xsd
         http://www.springframework.org/schema/integration/file https://www.springframework.org/schema/integration/file/spring-integration-file.xsd">
         <file:inbound-channel-adapter id="input" directory="%s" filename-regex="%s"><int:poller fixed-rate="%d" max-messages-per-poll="1"/></file:inbound-channel-adapter>
         <file:inbound-channel-adapter id="other" directory="%s" filename-regex="other-.*"><int:poller fixed-rate="200" max-messages-per-poll="1"/></file:inbound-channel-adapter>
        </beans>""".formatted(dir.resolve("input"),regex,rate,dir.resolve("other"));
    }
    void load(String regex, int rate) throws Exception {load(regex,rate,false);}
    void load(String regex, int rate, boolean prior) throws Exception {
        Files.createDirectories(dir.resolve("input")); Files.createDirectories(dir.resolve("other"));
        path=dir.resolve("polling-spring.xml");Files.writeString(path,xml(regex,rate));
        context=new GenericApplicationContext();var reader=new XmlBeanDefinitionReader(context);
        if(prior) {
            Path first=dir.resolve("first-spring.xml");
            Files.writeString(first,xml("z-.*",200).replace("id=\"input\"","id=\"first\"").replace("id=\"other\"","id=\"second\""));
            reader.loadBeanDefinitions(new FileSystemResource(first));
        }
        var resource=new FileSystemResource(path);reader.loadBeanDefinitions(resource);SpringXmlResources.record(reader,resource);
        context.refresh();ApplicationContextHolder.register(context);
        if(prior) {context.getBean("first.adapter",SourcePollingChannelAdapter.class).stop();context.getBean("second.adapter",SourcePollingChannelAdapter.class).stop();}
        context.getBean("input",DirectChannel.class).subscribe(message -> {receipts.add(((java.io.File)message.getPayload()).getName());times.add(System.nanoTime());});
        context.getBean("other",DirectChannel.class).subscribe(message -> {});
    }
    void reload(String contents) throws Exception {
        Files.writeString(path,contents);
        PlatformContext platform=(PlatformContext)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
            (p,m,a)->m.getName().equals("getApplicationContext")?context:null);
        StatusReporter.addListener(listener);
        try {new SpringXmlReloader(platform).reloadLoaded(path);} finally {StatusReporter.removeListener(listener);}
    }
    void drop(String name) throws Exception {Files.writeString(dir.resolve("input").resolve(name),"synthetic");}
    static void await(BooleanSupplier check) throws Exception {
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(!check.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(20);
        assertTrue(check.getAsBoolean(),"native file delivery did not arrive");
    }
    @AfterEach void close() {if(context!=null)context.close();ApplicationContextHolder.clear();}

    @Test void regexRetainsSourceCacheAndUnrelatedAdapter() throws Exception {
        load("a-.*",100);
        Object source=context.getBean("input.adapter.source"),adapter=context.getBean("input.adapter"),other=context.getBean("other.adapter");
        drop("a-first");await(()->receipts.size()==1);
        reload(xml("b-.*",100));drop("b-fresh");drop("a-rejected");await(()->receipts.contains("b-fresh"));
        Thread.sleep(350);assertEquals(List.of("a-first","b-fresh"),receipts);
        assertSame(source,context.getBean("input.adapter.source"));assertSame(adapter,context.getBean("input.adapter"));assertSame(other,context.getBean("other.adapter"));
        reload(xml("a-.*",100));Thread.sleep(350);assertEquals(1,Collections.frequency(receipts,"a-first"));
        reload(xml("a-.*",100));assertSame(adapter,context.getBean("input.adapter"));
        assertTrue(events.stream().noneMatch(e->e.contains("Restart required")),events.toString());
    }
    @Test void rateChangesActualDeliveryIntervals() throws Exception {
        load("a-.*",600);drop("a-base1");drop("a-base2");drop("a-base3");await(()->times.size()==3);
        long baseline=Math.min(times.get(1)-times.get(0),times.get(2)-times.get(1));
        reload(xml("a-.*",100));drop("a-next1");drop("a-next2");drop("a-next3");await(()->times.size()==6);
        long changed=Math.max(times.get(4)-times.get(3),times.get(5)-times.get(4));
        assertTrue(changed<baseline*0.7,"baseline="+baseline+" changed="+changed);
        assertEquals(6,new HashSet<>(receipts).size());
    }
    @Test void invalidRegexAndUnsupportedDirectoryLeaveOldAdapterDelivering() throws Exception {
        load("a-.*",100);Object adapter=context.getBean("input.adapter");
        reload(xml("[",100));drop("a-invalid");await(()->receipts.contains("a-invalid"));
        reload(xml("b-.*",100).replace("directory=\""+dir.resolve("input"),"directory=\""+dir.resolve("different")));
        drop("a-old");await(()->receipts.contains("a-old"));drop("b-refused");Thread.sleep(350);
        assertFalse(receipts.contains("b-refused"));assertSame(adapter,context.getBean("input.adapter"));
        assertTrue(((SourcePollingChannelAdapter)adapter).isRunning());
        assertTrue(events.stream().anyMatch(e->e.contains("Restart required")),events.toString());
    }
    @Test void generatedNamesFromAnotherLoadedFileDoNotPairByOrdinal() throws Exception {
        load("a-.*",100,true);Object other=context.getBean("first.adapter");
        reload(xml("b-.*",100));drop("b-new");await(()->receipts.contains("b-new"));
        assertSame(other,context.getBean("first.adapter"));
        assertFalse(((SourcePollingChannelAdapter)other).isRunning());
        assertTrue(events.stream().noneMatch(e->e.contains("Restart required")),events.toString());
    }
    private static Object field(Object target,Class<?> owner,String name) throws Exception {
        var f=owner.getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    @Test void failedStartRestoresPatternTriggerMetadataAndRunningState() throws Exception {
        load("a-.*",100);
        var adapter=context.getBean("input.adapter",SourcePollingChannelAdapter.class);
        var polling=org.springframework.integration.endpoint.AbstractPollingEndpoint.class;
        Object oldTrigger=field(adapter,polling,"trigger");
        var scheduler=(org.springframework.scheduling.TaskScheduler)field(adapter,org.springframework.integration.context.IntegrationObjectSupport.class,"taskScheduler");
        var fail=new java.util.concurrent.atomic.AtomicBoolean(true);
        var injected=(org.springframework.scheduling.TaskScheduler)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{org.springframework.scheduling.TaskScheduler.class},(proxy,method,args)-> {
                if(method.getName().equals("schedule")&&fail.getAndSet(false))throw new IllegalStateException("synthetic one-start failure");
                try {return method.invoke(scheduler,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            });
        adapter.setTaskScheduler(injected);
        reload(xml("b-.*",50));
        assertFalse(fail.get(),"new start must actually have been attempted");
        assertSame(oldTrigger,field(adapter,polling,"trigger"));assertTrue(adapter.isRunning());
        assertEquals("100",context.getBeanDefinition("org.springframework.integration.config.PeriodicTriggerFactoryBean#0").getPropertyValues().get("fixedRateValue"));
        drop("a-restored");drop("b-refused");await(()->receipts.contains("a-restored"));Thread.sleep(300);
        assertFalse(receipts.contains("b-refused"));assertTrue(events.stream().anyMatch(e->e.contains("synthetic one-start failure")),events.toString());
    }
    @Test void stoppedAdapterStaysStoppedAndUnsupportedDelayIsRefused() throws Exception {
        load("a-.*",100);var adapter=context.getBean("input.adapter",SourcePollingChannelAdapter.class);adapter.stop();
        reload(xml("b-.*",50));assertFalse(adapter.isRunning());
        adapter.start();drop("b-started");await(()->receipts.contains("b-started"));
        reload(xml("a-.*",100).replace("fixed-rate=","fixed-delay="));drop("b-old");await(()->receipts.contains("b-old"));
        assertTrue(events.stream().anyMatch(e->e.contains("Restart required")),events.toString());
    }

    @Test void literalRegexWithInlineFlagsRemainsEditable() throws Exception {
        load("(?i)a-.*",100);drop("A-before");await(()->receipts.contains("A-before"));
        reload(xml("(?i)b-.*",100));drop("B-after");await(()->receipts.contains("B-after"));
        reload(xml("(?i)b-.*",50));
        assertTrue(events.stream().noneMatch(e->e.contains("Restart required")),events.toString());
    }

}
