/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import static org.junit.jupiter.api.Assertions.*;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.platform.SpringXmlResources;
import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.ui.StatusReporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.MessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;

/** An edited Spring Integration endpoint in XML answers new messages without a restart. */
class SpringIntegrationEndpointSwapTest {
    @TempDir Path dir;
    private final List<GenericApplicationContext> contexts = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final StatusReporter.StatusListener listener = (level, message) -> events.add(level + " " + message);

    public static class Greeter {
        public String hello(String payload) { return "hello " + payload; }
        public String bye(String payload) { return "bye " + payload; }
    }

    private Path xml(String endpoints) throws Exception {
        Path path = dir.resolve("si-probe-spring.xml");
        Files.writeString(path, """
            <beans xmlns="http://www.springframework.org/schema/beans"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              xmlns:int="http://www.springframework.org/schema/integration"
              xsi:schemaLocation="http://www.springframework.org/schema/beans https://www.springframework.org/schema/beans/spring-beans.xsd
                http://www.springframework.org/schema/integration https://www.springframework.org/schema/integration/spring-integration.xsd">
              <int:channel id="in"/>
              <int:channel id="other"/>
              <bean id="greeter" class="%s"/>
              %s
            </beans>""".formatted(Greeter.class.getName(), endpoints));
        return path;
    }

    private GenericApplicationContext load(Path path) {
        var context = new GenericApplicationContext(); contexts.add(context);
        var reader = new XmlBeanDefinitionReader(context);
        var resource = new FileSystemResource(path);
        reader.loadBeanDefinitions(resource); SpringXmlResources.record(reader, resource);
        context.refresh(); ApplicationContextHolder.register(context);
        return context;
    }

    private void reload(Path path) {
        PlatformContext platform = (PlatformContext) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PlatformContext.class}, (p, m, a) -> m.getName().equals("getApplicationContext") ? contexts.get(0) : null);
        StatusReporter.addListener(listener);
        try { new SpringXmlReloader(platform).reloadLoaded(path); }
        finally { StatusReporter.removeListener(listener); }
    }

    private static List<Object> send(GenericApplicationContext context, String channel) {
        var template = new MessagingTemplate(); template.setReceiveTimeout(2000);
        List<Object> replies = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            var reply = template.sendAndReceive(context.getBean(channel, DirectChannel.class), MessageBuilder.withPayload("x").build());
            replies.add(reply == null ? null : reply.getPayload());
        }
        return replies;
    }

    private static int subscribers(GenericApplicationContext context, String channel) {
        return context.getBean(channel, DirectChannel.class).getSubscriberCount();
    }

    private String restartWarnings() {
        return String.join("\n", events.stream().filter(e -> e.contains("Restart required")).toList());
    }

    @AfterEach void close() { contexts.forEach(GenericApplicationContext::close); ApplicationContextHolder.clear(); }

    @Test void editedExpressionAnswersTheNextMessages() throws Exception {
        Path path = xml("<int:service-activator id='act' input-channel='in' expression=\"'A:' + payload\"/>");
        var context = load(path);
        assertEquals(List.of("A:x", "A:x", "A:x", "A:x"), send(context, "in"));
        xml("<int:service-activator id='act' input-channel='in' expression=\"'B:' + payload\"/>");
        reload(path);
        assertEquals(List.of("B:x", "B:x", "B:x", "B:x"), send(context, "in"));
        assertEquals(1, subscribers(context, "in"));
        assertEquals("", restartWarnings(), events.toString());
        assertTrue(events.stream().anyMatch(e -> e.contains("Spring Integration endpoint act") && e.contains("replaced")), events.toString());
    }

    @Test void editedMethodAndInputChannelMoveTheEndpoint() throws Exception {
        Path path = xml("<int:service-activator id='act' input-channel='in' ref='greeter' method='hello'/>");
        var context = load(path);
        assertEquals("hello x", send(context, "in").get(0));
        xml("<int:service-activator id='act' input-channel='other' ref='greeter' method='bye'/>");
        reload(path);
        assertEquals(List.of("bye x", "bye x", "bye x", "bye x"), send(context, "other"));
        assertEquals(0, subscribers(context, "in"));
        assertEquals(1, subscribers(context, "other"));
        assertEquals("", restartWarnings(), events.toString());
    }

    @Test void failingNewHandlerKeepsTheOldEndpoint() throws Exception {
        Path path = xml("<int:service-activator id='act' input-channel='in' ref='greeter' method='hello'/>");
        var context = load(path);
        xml("<int:service-activator id='act' input-channel='in' ref='greeter' method='noSuchMethod'/>");
        reload(path);
        assertEquals(List.of("hello x", "hello x", "hello x", "hello x"), send(context, "in"));
        assertEquals(1, subscribers(context, "in"));
        assertTrue(restartWarnings().contains("old endpoint kept"), events.toString());
    }

    /** SAP loads other SI files first, so the live handler is #N while a fresh parse of this file names it #0. */
    @Test void liveHandlerNameDifferentFromParsedNameIsReportedClean() throws Exception {
        Path other = dir.resolve("other-si-spring.xml");
        Files.writeString(other, Files.readString(xml("<int:service-activator id='first' input-channel='other' expression=\"'O:' + payload\"/>"))
                .replace("<int:channel id=\"in\"/>", "").replace("<int:channel id=\"other\"/>", "<int:channel id=\"other\"/>")
                .replace("<bean id=\"greeter\" class=\"" + Greeter.class.getName() + "\"/>", ""));
        Path path = dir.resolve("si-probe-spring.xml");
        Files.writeString(path, Files.readString(xml("<int:service-activator id='act' input-channel='in' expression=\"'A:' + payload\"/>"))
                .replace("<int:channel id=\"other\"/>", ""));
        var context = new GenericApplicationContext(); contexts.add(context);
        var reader = new XmlBeanDefinitionReader(context);
        for (Path file : List.of(other, path)) {
            var resource = new FileSystemResource(file);
            reader.loadBeanDefinitions(resource); SpringXmlResources.record(reader, resource);
        }
        context.refresh(); ApplicationContextHolder.register(context);
        assertEquals("A:x", send(context, "in").get(0));
        Files.writeString(path, Files.readString(path).replace("'A:'", "'B:'"));
        reload(path);
        assertEquals(List.of("B:x", "B:x", "B:x", "B:x"), send(context, "in"));
        assertEquals(List.of("O:x", "O:x", "O:x", "O:x"), send(context, "other"));
        assertEquals(1, subscribers(context, "in"));
        assertEquals(1, subscribers(context, "other"));
        assertEquals("", restartWarnings(), events.toString());
    }

    @Test void unchangedSaveTouchesNothing() throws Exception {
        Path path = xml("<int:service-activator id='act' input-channel='in' expression=\"'A:' + payload\"/>");
        var context = load(path);
        Object endpoint = context.getBean("act");
        reload(path);
        assertSame(endpoint, context.getBean("act"));
        assertEquals("A:x", send(context, "in").get(0));
        assertEquals(1, subscribers(context, "in"));
        assertEquals("", restartWarnings(), events.toString());
        assertTrue(events.stream().anyMatch(e -> e.contains("no live-applicable changes")), events.toString());
    }
}
