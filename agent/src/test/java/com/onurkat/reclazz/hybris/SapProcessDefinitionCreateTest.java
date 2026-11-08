/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import com.onurkat.reclazz.ext.*;
import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.spring.xml.SpringXmlReloader;
import com.onurkat.reclazz.ui.*;
import de.hybris.platform.processengine.definition.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Spring add hook, synthetic native contracts; native SDK lane is separate. */
class SapProcessDefinitionCreateTest {
    @TempDir Path temporary;
    Path spring, file;
    GenericApplicationContext context, second;
    XMLProcessDefinitionsReader reader;
    ProcessDefinitionsProvider provider;
    ProcessDefinitionsCache cache;
    DefaultProcessDefinitionFactory factory;
    final SapProcessDefinitionReloader processes = new SapProcessDefinitionReloader();
    final List<String> messages = new ArrayList<>();
    final StatusReporter.StatusListener listener = (level, message) -> messages.add(message);
    PlatformContext platform;

    @BeforeEach void setup() throws Exception {
        ApplicationContextHolder.clear(); ProcessTenantFixture.reset(); RestartLedger.clear(); StatusReporter.addListener(listener);
        spring = temporary.resolve("example-spring.xml"); file = temporary.resolve("new-process.xml");
        Files.writeString(spring, beans(""));
        context = observedContext();
        reader = new XMLProcessDefinitionsReader(); provider = new ProcessDefinitionsProvider();
        provider.reader = reader; provider.applicationContext = context;
        cache = new ProcessDefinitionsCache(); cache.processDefinitionsProvider = provider;
        factory = new DefaultProcessDefinitionFactory(); factory.definitionsCache = cache; factory.xmlDefinitionsReader = reader;
        context.getBeanFactory().registerSingleton("factory", factory);
        cache.initialise(true);
        platform = new PlatformContext() {
            public Platform getPlatformId() { return Platform.GENERIC; }
            public void initialize() { }
            public Map<String,List<Path>> getClassOutputDirs() { return Map.of(); }
            public Map<String,List<Path>> getSourceDirs() { return Map.of(); }
            public Map<String,List<Path>> getResourceDirs() { return Map.of(); }
            public String resolveClasspath() { return ""; }
            public String resolveClassName(Path p) { return null; }
            public Path resolveOutputDir(Path p) { return null; }
            public Object getApplicationContext() { return context; }
        };
    }
    GenericApplicationContext observedContext() {
        var c = new GenericApplicationContext(); var xml = new XmlBeanDefinitionReader(c);
        xml.loadBeanDefinitions(new FileSystemResource(spring));
        SpringXmlResources.record(xml, new FileSystemResource(spring));
        c.refresh(); ApplicationContextHolder.register(c); ProcessTenantFixture.attach(c); return c;
    }
    String beans(String extra) { return "<beans xmlns='http://www.springframework.org/schema/beans' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' xsi:schemaLocation='http://www.springframework.org/schema/beans https://www.springframework.org/schema/beans/spring-beans.xsd'><bean id='anchor' class='java.lang.Object'/>" + extra + "</beans>"; }
    String resource(String id, Path path) { return "<bean id='"+id+"' class='de.hybris.platform.processengine.definition.ProcessDefinitionResource'><property name='resource' value='"+path.toUri()+"'/></bean>"; }
    void xml(Path p, String name, String graph) throws Exception { Files.writeString(p,"<process name='"+name+"' start='"+graph+"'/>"); }
    void reload(String resources) throws Exception {
        Files.writeString(spring,beans(resources));
        new SpringXmlReloader(platform, paths -> paths.forEach(path -> apply(path,"CREATED"))).reloadLoaded(spring);
    }
    PlatformChangeHandler.Outcome apply(Path path, String event) { return processes.handle(new PlatformChangeHandler.Change(Capability.PROCESS_RELOAD,path,event,"example","resources")); }
    ProcessDefinition active(String name) { return cache.definitions.get(new ProcessDefinitionId(name)); }
    void absent(String name) { assertTrue(cache.definitions.keySet().stream().noneMatch(id -> id.name().equals(name))); assertTrue(SapProcessDefinitions.observed(cache,provider,context).stream().noneMatch(d -> d.name().equals(name))); }
    @AfterEach void cleanup() {
        context.close(); if (second!=null) second.close(); ApplicationContextHolder.clear();
        SapProcessDefinitions.discard(cache); StatusReporter.removeListener(listener); RestartLedger.clear();
        ProcessTenantFixture.reset();
    }
    @Test void warmCacheXmlThenBeanRegistersAndStarts() throws Exception {
        xml(file,"exampleNew","v1"); reload(resource("newResource",file));
        assertNotNull(active("exampleNew"),"New native name must resolve after the save");
        assertEquals("v1",active("exampleNew").graph());
        assertSame(active("exampleNew"),cache.definitions.get(active("exampleNew").getId()));
    }
    @Test void warmCacheBeanThenXmlRegistersAndStarts() throws Exception {
        reload(resource("newResource",file)); absent("exampleNew");
        xml(file,"exampleNew","v1"); assertEquals(PlatformChangeHandler.Outcome.HANDLED,apply(file,"CREATED"));
        assertEquals("v1",active("exampleNew").graph());
    }
    @Test void beanInTwoContextsCreateThenEditKeepsHistory() throws Exception {
        second=observedContext(); xml(file,"exampleNew","v1"); reload(resource("newResource",file));
        var started=active("exampleNew"); assertNotNull(started);
        xml(file,"exampleNew","v2"); assertEquals(PlatformChangeHandler.Outcome.HANDLED,apply(file,"MODIFIED"));
        assertEquals("v2",active("exampleNew").graph()); assertEquals("v1",started.graph());
        assertSame(started,cache.definitions.get(started.getId()));
    }
    @Test void twoSameNameResourcesInOneSpringSavePublishesNeither() throws Exception {
        Path other=temporary.resolve("other-process.xml"); xml(file,"exampleNew","v1"); xml(other,"exampleNew","other");
        reload(resource("first",file)+resource("second",other)); absent("exampleNew"); assertEquals(0,cache.publications);
    }
    @Test void malformedNewXmlRefusesThenValidSaveRegisters() throws Exception {
        Files.writeString(file,"<process>"); reload(resource("newResource",file)); absent("exampleNew");
        xml(file,"exampleNew","v1"); assertEquals(PlatformChangeHandler.Outcome.HANDLED,apply(file,"MODIFIED")); assertNotNull(active("exampleNew"));
    }
    @Test void coldCacheLeavesNativeLazyLoadAndPublishesNothing() throws Exception {
        cache.definitions=null; int publications=cache.publications;
        xml(file,"exampleNew","v1"); reload(resource("newResource",file));
        assertNull(cache.definitions); assertEquals(publications,cache.publications); assertEquals(0,reader.parses);
        assertTrue(messages.toString().contains("will be read when they first load"));
    }
    @Test void coldLoadedNewProcessInTwoContextsLaterEditReloads() throws Exception {
        cache.definitions=null; second=observedContext(); xml(file,"exampleNew","v1"); reload(resource("newResource",file));
        assertNull(cache.definitions); cache.initialise(true); var old=active("exampleNew");
        xml(file,"exampleNew","v2"); assertEquals(PlatformChangeHandler.Outcome.HANDLED,apply(file,"MODIFIED"));
        assertSame(old,cache.definitions.get(old.getId())); assertEquals("v2",active("exampleNew").graph());
    }
    @Test void noProviderOwnerContextRefuses() throws Exception {
        second=observedContext(); provider.applicationContext=second;
        xml(file,"exampleNew","v1"); reload(resource("newResource",file)); absent("exampleNew");
        assertTrue(messages.toString().contains("native provider owner"));
    }
    @Test void existingActiveNameIsRefusedAsCollision() throws Exception {
        var old=new ProcessDefinition("exampleNew","old","old"); factory.add(old);
        xml(file,"exampleNew","v1"); reload(resource("newResource",file)); assertSame(old,active("exampleNew"));
        assertEquals(1,cache.publications); assertTrue(SapProcessDefinitions.observed(cache,provider,context).isEmpty());
    }
    @Test void dbBackedNameIsRefused() throws Exception {
        provider.dynamic=true; xml(file,"exampleNew","v1"); reload(resource("newResource",file)); absent("exampleNew");
        assertTrue(provider.latestCalls>0); assertTrue(messages.toString().contains("DB-backed"));
    }
    @Test void providerFailureRefusesWithoutPublishing() throws Exception {
        provider.failProvider=true; xml(file,"exampleNew","v1"); reload(resource("newResource",file)); absent("exampleNew");
        assertTrue(provider.latestCalls>0);
    }
    @Test void otherResourceDefiningSameNameRefuses() throws Exception {
        Path other=temporary.resolve("old-process.xml"); xml(other,"exampleNew","old");
        var holder=new ProcessDefinitionResource(); holder.setResource(new FileSystemResource(other));
        context.getBeanFactory().registerSingleton("oldResource",holder); cache.initialise(true);
        var old=active("exampleNew"); xml(file,"exampleNew","v1"); reload(resource("newResource",file));
        assertSame(old,active("exampleNew")); assertTrue(messages.toString().contains("multiple registered resources"));
    }
    @Test void unobservedRegisteredSiblingCannotBeIgnored() throws Exception {
        Path other=temporary.resolve("other-process.xml"); xml(other,"exampleOther","other");
        var holder=new ProcessDefinitionResource(); holder.setResource(new FileSystemResource(other));
        context.getBeanFactory().registerSingleton("unproven",holder);
        xml(file,"exampleNew","v1"); reload(resource("newResource",file)); absent("exampleNew");
        assertEquals(0,reader.parses,"Do not parse unproven siblings");
        assertTrue(messages.toString().contains("unproven process resource"));
    }
    @Test void singletonReplacementAfterFailedParseRefuses() throws Exception {
        Files.writeString(file,"<process>"); reload(resource("newResource",file));
        var bf=context.getDefaultListableBeanFactory(); bf.destroySingleton("newResource");
        var replacement=new ProcessDefinitionResource(); replacement.setResource(new FileSystemResource(file));
        bf.registerSingleton("newResource",replacement); xml(file,"exampleNew","v1");
        assertEquals(PlatformChangeHandler.Outcome.ADVICE,apply(file,"MODIFIED")); absent("exampleNew");
    }
    @Test void resourceReplacementDuringParseRefuses() throws Exception {
        reader.afterParse=()->((ProcessDefinitionResource)context.getBean("newResource")).setResource(new FileSystemResource(file));
        xml(file,"exampleNew","v1"); reload(resource("newResource",file)); absent("exampleNew");
        assertTrue(SapProcessDefinitions.receipts().stream().noneMatch(r->r.path().equals(file)));
    }
    @Test void identityChangeBeforePublishRefuses() throws Exception {
        var replacement=new ProcessDefinitionsCache(); replacement.processDefinitionsProvider=provider; replacement.definitions=new HashMap<>();
        reader.afterParse=()->factory.definitionsCache=replacement;
        xml(file,"exampleNew","v1"); reload(resource("newResource",file)); absent("exampleNew"); assertTrue(replacement.definitions.isEmpty());
        factory.definitionsCache=cache;
        assertEquals(PlatformChangeHandler.Outcome.ADVICE,apply(file,"MODIFIED")); absent("exampleNew");
    }
    @Test void fileWithoutReclazzRegisteredBeanIsNeverPublished() throws Exception {
        xml(file,"exampleNew","v1"); var holder=new ProcessDefinitionResource(); holder.setResource(new FileSystemResource(file));
        context.getBeanFactory().registerSingleton("unproven",holder);
        assertEquals(PlatformChangeHandler.Outcome.ADVICE,apply(file,"CREATED")); absent("exampleNew"); assertEquals(0,reader.parses);
    }
    @Test void unobservedSpringFallbackCannotGrantProcessOwnership() throws Exception {
        Path unobserved=temporary.resolve("unobserved-spring.xml"); xml(file,"exampleNew","v1");
        Files.writeString(unobserved,beans(resource("newResource",file)));
        new SpringXmlReloader(platform,paths->paths.forEach(p->apply(p,"CREATED"))).reload(unobserved);
        assertTrue(context.getBeanFactory().containsSingleton("newResource"));
        assertEquals(PlatformChangeHandler.Outcome.ADVICE,apply(file,"CREATED")); absent("exampleNew");
    }
    @Test void deleteOfRegisteredProcessKeepsDiagnosticAndDropsAssociation() throws Exception {
        xml(file,"exampleNew","v1"); reload(resource("newResource",file)); var old=active("exampleNew"); assertNotNull(old);
        Files.delete(file); assertEquals(PlatformChangeHandler.Outcome.ADVICE,apply(file,"DELETED"));
        assertSame(old,active("exampleNew")); assertTrue(SapProcessDefinitions.observed(cache,provider,context).isEmpty());
        xml(file,"exampleNew","v2"); assertEquals(PlatformChangeHandler.Outcome.ADVICE,apply(file,"CREATED")); assertSame(old,active("exampleNew"));
    }
    @Test void removedSpringBeanRetiresItsAssociationWithoutDeletingNativeHistory() throws Exception {
        xml(file,"exampleNew","v1"); reload(resource("newResource",file)); var old=active("exampleNew"); assertNotNull(old);
        reload(""); xml(file,"exampleNew","v2"); assertEquals(PlatformChangeHandler.Outcome.ADVICE,apply(file,"MODIFIED"));
        assertSame(old,active("exampleNew")); assertTrue(SapProcessDefinitions.observed(cache,provider,context).isEmpty());
    }

    @Test void replacedCacheMapDuringParseRetiresCreationReceipt() throws Exception {
        reader.afterParse=()->cache.definitions=new HashMap<>();
        xml(file,"exampleNew","v1"); reload(resource("newResource",file)); absent("exampleNew");
        assertEquals(PlatformChangeHandler.Outcome.ADVICE,apply(file,"MODIFIED")); absent("exampleNew");
    }
    @Test void registrationWithoutAdmittedProcessDispatchCannotBePublishedByAnotherSource() throws Exception {
        Path otherSource=temporary.resolve("other-spring.xml"),otherFile=temporary.resolve("other-process.xml");
        xml(otherFile,"exampleOther","v1");Files.writeString(otherSource,beans(""));
        var loader=new XmlBeanDefinitionReader(context);loader.loadBeanDefinitions(new FileSystemResource(otherSource));
        SpringXmlResources.record(loader,new FileSystemResource(otherSource));
        Files.writeString(otherSource,beans(resource("otherResource",otherFile)));
        new SpringXmlReloader(platform).reloadLoaded(otherSource); // no optional process admission
        xml(file,"exampleNew","v1"); reload(resource("newResource",file));
        absent("exampleOther");absent("exampleNew");assertEquals(0,reader.parses);
    }

    @Test void additionalResourceInSameOwnerDoesNotBypassEditAmbiguityGuard() throws Exception {
        xml(file,"exampleNew","v1");reload(resource("newResource",file));var old=active("exampleNew");assertNotNull(old);
        var holder=new ProcessDefinitionResource();holder.setResource(new FileSystemResource(file));
        context.getBeanFactory().registerSingleton("duplicate",holder);
        xml(file,"exampleNew","v2");assertEquals(PlatformChangeHandler.Outcome.ADVICE,apply(file,"MODIFIED"));assertSame(old,active("exampleNew"));
    }

    record Engine(XMLProcessDefinitionsReader reader, ProcessDefinitionsProvider provider,
                  ProcessDefinitionsCache cache, DefaultProcessDefinitionFactory factory) { }
    Engine secondEngine() throws Exception {
        second=observedContext();
        var r=new XMLProcessDefinitionsReader();var p=new ProcessDefinitionsProvider();p.reader=r;p.applicationContext=second;
        var c=new ProcessDefinitionsCache();c.processDefinitionsProvider=p;
        var f=new DefaultProcessDefinitionFactory();f.definitionsCache=c;f.xmlDefinitionsReader=r;
        second.getBeanFactory().registerSingleton("factory",f);c.initialise(true);return new Engine(r,p,c,f);
    }
    @Test void sameSourceInTwoContextsWithTwoNativeOwnersPublishesToEachIndependently() throws Exception {
        var b=secondEngine();xml(file,"exampleNew","v1");reload(resource("newResource",file));
        assertNotNull(active("exampleNew"));assertNotNull(b.cache.definitions.get(new ProcessDefinitionId("exampleNew")));
        assertEquals(2,SapProcessDefinitions.associations(file).size());
    }
    @Test void oneOwnerRefusalDoesNotBlockTheOther() throws Exception {
        var b=secondEngine();var old=new ProcessDefinition("exampleNew","old","old");b.factory.add(old);
        xml(file,"exampleNew","v1");reload(resource("newResource",file));
        assertNotNull(active("exampleNew"));assertSame(old,b.cache.definitions.get(new ProcessDefinitionId("exampleNew")));
    }
    @Test void contextWithoutNativeOwnerIsSkipped() throws Exception {
        second=observedContext();xml(file,"exampleNew","v1");reload(resource("newResource",file));
        assertNotNull(active("exampleNew"));assertTrue(messages.toString().contains("native provider owner"));
    }
    @Test void laterEditReloadsEachOwnerIndependently() throws Exception {
        var b=secondEngine();xml(file,"exampleNew","v1");reload(resource("newResource",file));
        var a1=active("exampleNew");var b1=b.cache.definitions.get(new ProcessDefinitionId("exampleNew"));
        assertNotNull(a1);assertNotNull(b1);xml(file,"exampleNew","v2");assertEquals(PlatformChangeHandler.Outcome.HANDLED,apply(file,"MODIFIED"));
        assertEquals("v2",active("exampleNew").graph());assertEquals("v2",b.cache.definitions.get(new ProcessDefinitionId("exampleNew")).graph());
        assertSame(a1,cache.definitions.get(a1.getId()));assertSame(b1,b.cache.definitions.get(b1.getId()));assertEquals("v1",a1.graph());
    }
    @Test void dbBackedNameOnlyInTenantBRefusesBOnlyAndPublishesA() throws Exception {
        var b=secondEngine();var tenantB=ProcessTenantFixture.tenants.get(1);b.provider.dynamic=true;
        b.provider.beforeLatest=()->assertSame(tenantB,ProcessTenantFixture.CURRENT.get());
        xml(file,"exampleNew","v1");reload(resource("newResource",file));
        assertNotNull(active("exampleNew"));assertTrue(b.cache.definitions.isEmpty());assertTrue(b.provider.latestCalls>0);
    }
    @Test void callerTenantTcclAndSessionRestoredAfterSuccessAndFailure() throws Exception {
        var b=secondEngine();b.provider.failProvider=true;var a=ProcessTenantFixture.tenants.get(0);
        ProcessTenantFixture.CURRENT.set(a);var previous=new ProcessTenantFixture.Session(a);previous.activate();
        provider.beforeLatest=()->{
            assertSame(a,ProcessTenantFixture.CURRENT.get());
            assertNotSame(previous,ProcessTenantFixture.SESSIONS.get().get(a));
        };
        b.provider.beforeLatest=()->{
            var tenantB=ProcessTenantFixture.tenants.get(1);
            assertSame(tenantB,ProcessTenantFixture.CURRENT.get());
            assertNotNull(ProcessTenantFixture.SESSIONS.get().get(tenantB));
        };
        var loader=new ClassLoader(Thread.currentThread().getContextClassLoader()) { };var before=Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            xml(file,"exampleNew","v1");reload(resource("newResource",file));assertNotNull(active("exampleNew"));assertTrue(b.provider.latestCalls>0);
            assertSame(a,ProcessTenantFixture.CURRENT.get());assertSame(previous,ProcessTenantFixture.SESSIONS.get().get(a));
            assertFalse(previous.closed);assertEquals(1,ProcessTenantFixture.SESSIONS.get().size());assertSame(loader,Thread.currentThread().getContextClassLoader());
            for (var owner : List.of(context,second)) {
                var sessions=(ProcessTenantFixture.Sessions)owner.getBean("sessionService");
                assertEquals(sessions.created,sessions.closed);assertTrue(sessions.created>0);
            }
        } finally {Thread.currentThread().setContextClassLoader(before);}
    }
    @Test void unprovenTenantForOneOwnerDoesNotBlockTheOther() throws Exception {
        var b=secondEngine();ProcessTenantFixture.tenants.remove(1);xml(file,"exampleNew","v1");reload(resource("newResource",file));
        assertNotNull(active("exampleNew"));assertTrue(b.cache.definitions.isEmpty());assertEquals(0,b.provider.latestCalls);
    }
    @Test void ownerBIdentityFailureKeepsOwnerAReceiptHistoryAndEditability() throws Exception {
        var b=secondEngine();
        b.reader.afterParse=()->((ProcessDefinitionResource)second.getBean("newResource")).setResource(new FileSystemResource(file));
        xml(file,"exampleNew","v1");reload(resource("newResource",file));var a1=active("exampleNew");assertNotNull(a1);
        assertTrue(b.cache.definitions.isEmpty());assertTrue(SapProcessDefinitions.receipts().stream().anyMatch(r->r.context()==context&&r.path().equals(file)));
        xml(file,"exampleNew","v2");assertEquals(PlatformChangeHandler.Outcome.HANDLED,apply(file,"MODIFIED"));
        assertEquals("v2",active("exampleNew").graph());assertSame(a1,cache.definitions.get(a1.getId()));
    }
    @Test void warmOwnerPublishesWhileColdOwnerKeepsNativeLazyLoad() throws Exception {
        var b=secondEngine();b.cache.definitions=null;
        xml(file,"exampleNew","v1");reload(resource("newResource",file));
        assertNotNull(active("exampleNew"));assertNull(b.cache.definitions);assertEquals(0,b.reader.parses);
        b.cache.initialise(true);assertEquals("v1",b.cache.definitions.get(new ProcessDefinitionId("exampleNew")).graph());
        xml(file,"exampleNew","v2");assertEquals(PlatformChangeHandler.Outcome.HANDLED,apply(file,"MODIFIED"));
        assertEquals("v2",b.cache.definitions.get(new ProcessDefinitionId("exampleNew")).graph());
    }
    @Test void editRefusalInTenantBDoesNotBlockAOrDiscardHistory() throws Exception {
        var b=secondEngine();xml(file,"exampleNew","v1");reload(resource("newResource",file));
        var a1=active("exampleNew");var b1=b.cache.definitions.get(new ProcessDefinitionId("exampleNew"));
        b.provider.dynamic=true;b.provider.beforeLatest=()->assertSame(ProcessTenantFixture.tenants.get(1),ProcessTenantFixture.CURRENT.get());
        xml(file,"exampleNew","v2");assertEquals(PlatformChangeHandler.Outcome.HANDLED,apply(file,"MODIFIED"));
        assertEquals("v2",active("exampleNew").graph());assertSame(b1,b.cache.definitions.get(new ProcessDefinitionId("exampleNew")));
        assertSame(a1,cache.definitions.get(a1.getId()));assertEquals(2,SapProcessDefinitions.associations(file).size());
        b.provider.dynamic=false;assertEquals(PlatformChangeHandler.Outcome.HANDLED,apply(file,"MODIFIED"));
        assertEquals("v2",b.cache.definitions.get(new ProcessDefinitionId("exampleNew")).graph());assertSame(b1,b.cache.definitions.get(b1.getId()));
    }
    @Test void newNameWithCustomProviderRemainsRefused() throws Exception {
        var custom=new ProcessDefinitionsProvider() { };custom.applicationContext=context;custom.reader=reader;
        cache.processDefinitionsProvider=custom;xml(file,"exampleNew","v1");reload(resource("newResource",file));
        assertNull(active("exampleNew"));assertEquals(0,custom.latestCalls);
    }
}
