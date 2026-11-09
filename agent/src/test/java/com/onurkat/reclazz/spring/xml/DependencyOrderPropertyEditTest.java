/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.ui.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DependencyOrderPropertyEditTest {
    @TempDir Path dir;
    public static class Node {
        String value, captured;
        Node target;
        boolean fail;
        int targetCalls;
        public void setValue(String v) { if(fail) throw new IllegalArgumentException("synthetic prerequisite failure"); value=v; }
        public void setTarget(Node v) { targetCalls++; target=v; captured=v.value; value=v.value; }
        public void setTargets(List<Node> v) { setTarget(v.get(0)); }
        public void setTargetSet(Set<Node> v) { setTarget(v.iterator().next()); }
        public void setTargetMap(Map<String,Node> v) { setTarget(v.get("key")); }
    }
    @BeforeEach @AfterEach void clear() { ApplicationContextHolder.clear(); RestartLedger.clear(); }
    private static String value(String v) { return "<property name='value' value='"+v+"'/>"; }
    private static String ref(String name) { return "<property name='target' ref='"+name+"'/>"; }
    private static String bean(String name,String props) { return "<bean id='"+name+"' class='"+Node.class.getName()+"'>"+props+"</bean>"; }
    private static String graph(boolean next,boolean chain) {
        return bean("consumer",ref(next?(chain?"middle":"dependency"):"other"))
                +(chain?bean("middle",ref(next?"dependency":"other")):"")
                +bean("dependency",value(next?"new":"old"))
                +bean("independent",value(next?"new":"old"))+bean("other",value("other"));
    }
    @ParameterizedTest @CsvSource({"1,false","2,false","2,true"})
    void dependencyOrderAppliesEditedDependencyBeforeConsumer(int count,boolean shared) throws Exception {
        try(var s=new Scope(graph(false,false),graph(true,false),count,shared)) {
            s.edit(); for(var c:s.owners) { assertEquals("new",s.node(c,"consumer").captured); s.assertPair(c,"consumer",true); s.assertPair(c,"dependency",true); }
        }
    }
    @ParameterizedTest @ValueSource(strings={"list","set","map"})
    void listRefToEditedBeanIsOrdered(String carrier) throws Exception {
        String property=switch(carrier) {
            case "list" -> "<property name='targets'><list><ref bean='dependency'/></list></property>";
            case "set" -> "<property name='targetSet'><set><ref bean='dependency'/></set></property>";
            default -> "<property name='targetMap'><map><entry key='key' value-ref='dependency'/></map></property>";
        };
        String before=bean("consumer",ref("other"))+bean("dependency",value("old"))+bean("other",value("other"));
        // Keep the old property too: removals are deliberately refused by the classifier.
        String after=bean("consumer",ref("other")+property)+bean("dependency",value("new"))+bean("other",value("other"));
        try(var s=new Scope(before,after,2,true)) { s.edit(); for(var c:s.owners) assertEquals("new",s.node(c,"consumer").captured); }
    }
    @Test void cyclicEditedRefsKeepXmlOrderAndAdviseRestart() throws Exception {
        String before=bean("a",ref("other"))+bean("b",ref("other"))+bean("other",value("old"));
        String after=bean("a",ref("b"))+bean("b",ref("a"))+bean("other",value("old"));
        try(var s=new Scope(before,after,1,false)) {
            s.edit();var c=s.owners.get(0);
            assertSame(s.node(c,"b"),s.node(c,"a").target);assertSame(s.node(c,"a"),s.node(c,"b").target);
            assertEquals(List.of("a","b"),s.before.get(c));
            assertEquals(1,s.status.stream().filter(x->x.startsWith("WARN:")&&x.contains("edited beans reference each other")&&x.contains("a")&&x.contains("b")).count());
            assertTrue(RestartLedger.digest().stream().anyMatch(x->x.contains("edited beans reference each other")));
            s.assertPair(c,"a",true);s.assertPair(c,"b",true);
        }
    }
    @Test void failedDependencySetterDefersConsumerInThatOwner() throws Exception {
        try(var s=new Scope(graph(false,false),graph(true,false),1,false)) {
            var c=s.owners.get(0);Node consumer=s.node(c,"consumer");int calls=consumer.targetCalls;s.node(c,"dependency").fail=true;
            s.edit();assertSame(s.node(c,"other"),consumer.target);assertEquals(calls,consumer.targetCalls);
            assertEquals("other",s.rawRef(c,"consumer"));assertFalse(s.before.get(c).contains("consumer"));
            assertTrue(s.status.stream().anyMatch(x->x.startsWith("WARN:")&&x.contains("consumer")&&x.contains("dependency")&&x.contains("deferred")));
        }
    }
    @Test void retryAfterDependencyFixedAppliesConsumer() throws Exception {
        try(var s=new Scope(graph(false,false),graph(true,false),1,false)) {
            var c=s.owners.get(0);s.node(c,"dependency").fail=true;s.edit();
            s.node(c,"dependency").fail=false;s.edit();
            assertEquals("new",s.node(c,"consumer").captured);assertEquals("dependency",s.rawRef(c,"consumer"));
            s.assertPair(c,"consumer",true);s.assertPair(c,"dependency",true);
        }
    }
    @Test void transitiveConsumerIsDeferred() throws Exception {
        try(var s=new Scope(graph(false,true),graph(true,true),1,false)) {
            var c=s.owners.get(0);s.node(c,"dependency").fail=true;s.edit();
            for(String name:List.of("consumer","middle")) { assertEquals("other",s.rawRef(c,name));assertFalse(s.before.get(c).contains(name)); }
            s.node(c,"dependency").fail=false;s.edit();assertEquals("new",s.node(c,"consumer").captured);
        }
    }
    @Test void independentBeanStillApplies() throws Exception {
        try(var s=new Scope(graph(false,false),graph(true,false),1,false)) {
            var c=s.owners.get(0);s.node(c,"dependency").fail=true;s.edit();assertEquals("new",s.node(c,"independent").value);s.assertPair(c,"independent",true);
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void otherOwnerWhereDependencySucceedsAppliesConsumer(boolean shared) throws Exception {
        try(var s=new Scope(graph(false,true),graph(true,true),2,shared)) {
            var failed=s.owners.get(0);var healthy=s.owners.get(1);s.node(failed,"dependency").fail=true;s.edit();
            assertEquals("other",s.node(failed,"consumer").captured);assertEquals("new",s.node(healthy,"consumer").captured);
            assertEquals("new",s.node(failed,"independent").value);s.assertPair(healthy,"consumer",true);
            s.node(failed,"dependency").fail=false;s.edit();for(var c:s.owners) assertEquals("new",s.node(c,"consumer").captured);
        }
    }
    @Test void failedInitializedEditKeepsMergedIdentityWhileLazyFactoryRollsBack() throws Exception {
        String before=bean("dependency",value("old")).replace("class=","lazy-init='true' class=");
        String after=bean("dependency",value("new")).replace("class=","lazy-init='true' class=");
        try(var s=new Scope(before,after,2,true)) {
            var first=s.owners.get(0);var second=s.owners.get(1);Node dep=s.node(first,"dependency");dep.fail=true;
            Object initializedMerged=first.getBeanFactory().getMergedBeanDefinition("dependency");
            Object lazyMerged=second.getBeanFactory().getMergedBeanDefinition("dependency");s.edit();
            assertSame(initializedMerged,first.getBeanFactory().getMergedBeanDefinition("dependency"));
            assertNotSame(lazyMerged,second.getBeanFactory().getMergedBeanDefinition("dependency"));
            assertFalse(second.getBeanFactory().containsSingleton("dependency"));
            Object raw=second.getBeanFactory().getMergedBeanDefinition("dependency").getPropertyValues().get("value");
            assertEquals("old",raw instanceof TypedStringValue t?t.getValue():raw);
            dep.fail=false;s.edit();assertEquals("new",dep.value);assertFalse(second.getBeanFactory().containsSingleton("dependency"));assertEquals("new",s.node(second,"dependency").value);
        }
    }
    private final class Scope implements AutoCloseable {
        final Path xml;
        final String after;
        final List<GenericApplicationContext> owners=new ArrayList<>();
        final Map<Object,List<String>> before=new IdentityHashMap<>();
        final Map<Object,Map<String,List<Boolean>>> completed=new IdentityHashMap<>();
        final List<String> status=new ArrayList<>();
        final SpringXmlReloader reloader;
        Scope(String initial,String after,int count,boolean shared) throws Exception {
            this.after=after;xml=Files.createTempFile(dir,"dependency-","-spring.xml");write(initial);
            for(int i=0;i<count;i++) {
                var c=new GenericApplicationContext();owners.add(c);var reader=new XmlBeanDefinitionReader(c);reader.setValidating(false);
                var resource=new FileSystemResource(xml);reader.loadBeanDefinitions(resource);SpringXmlResources.record(reader,resource);
                if(shared&&i>0)for(String name:c.getBeanDefinitionNames())c.registerBeanDefinition(name,owners.get(0).getBeanFactory().getBeanDefinition(name));
                c.refresh();ApplicationContextHolder.register(c);before.put(c,new ArrayList<>());completed.put(c,new LinkedHashMap<>());
            }
            PlatformContext platform=(PlatformContext)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                    (p,m,a)->m.getName().equals("getApplicationContext")?owners.get(0):null);
            XmlBeanLifecycle hook=(owner,name,previous)->{
                before.get(owner).add(name);
                return (current,success)->completed.get(owner).computeIfAbsent(name,k->new ArrayList<>()).add(success);
            };
            reloader=new SpringXmlReloader(platform,paths->{},hook);
        }
        Node node(GenericApplicationContext c,String name) { return c.getBean(name,Node.class); }
        String rawRef(GenericApplicationContext c,String name) { return ((RuntimeBeanReference)c.getBeanFactory().getBeanDefinition(name).getPropertyValues().get("target")).getBeanName(); }
        void write(String body) throws Exception { Files.writeString(xml,"<beans xmlns='http://www.springframework.org/schema/beans'>"+body+"</beans>"); }
        void edit() throws Exception {
            write(after);before.values().forEach(List::clear);completed.values().forEach(Map::clear);status.clear();
            StatusReporter.StatusListener listener=(level,message)->status.add(level+":"+message);StatusReporter.addListener(listener);
            try { reloader.reloadLoaded(xml); } finally { StatusReporter.removeListener(listener); }
        }
        void assertPair(GenericApplicationContext c,String name,boolean success) { assertEquals(1,Collections.frequency(before.get(c),name));assertEquals(List.of(success),completed.get(c).get(name)); }
        public void close() { owners.forEach(GenericApplicationContext::close); }
    }
}
