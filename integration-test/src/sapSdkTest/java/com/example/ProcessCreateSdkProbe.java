/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import com.onurkat.reclazz.ext.*;
import com.onurkat.reclazz.hybris.ProcessTenantFixture;
import com.onurkat.reclazz.hybris.SapProcessDefinitionReloader;
import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.spring.xml.SpringXmlReloader;
import de.hybris.platform.processengine.definition.*;
import de.hybris.platform.scripting.engine.ScriptingLanguagesService;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

/** Real SDK Spring/parser/cache/initial observer; empty latest-ID seam, no service/task claims. */
public final class ProcessCreateSdkProbe {
    static int checks;
    static void check(boolean condition,String label) { if(!condition)throw new AssertionError(label); checks++;System.out.println("PASS "+label); }
    record Platform(Object context) implements PlatformContext {
        public PlatformContext.Platform getPlatformId(){return PlatformContext.Platform.GENERIC;}
        public void initialize(){}
        public Map<String,List<Path>> getClassOutputDirs(){return Map.of();}
        public Map<String,List<Path>> getSourceDirs(){return Map.of();}
        public Map<String,List<Path>> getResourceDirs(){return Map.of();}
        public String resolveClasspath(){return "";}
        public String resolveClassName(Path p){return null;}
        public Path resolveOutputDir(Path p){return null;}
        public Object getApplicationContext(){return context;}
    }
    static String resource(String id,Path file) { return "<bean id='"+id+"' class='de.hybris.platform.processengine.definition.ProcessDefinitionResource'><property name='resource' value='"+file.toUri()+"'/></bean>"; }
    static String beans(String extra) { return "<beans xmlns='http://www.springframework.org/schema/beans'><bean id='anchor' class='java.lang.Object'/>"+extra+"</beans>"; }
    static void save(Path file,String name,String start) throws Exception {
        Files.writeString(file,"<process xmlns='http://www.hybris.de/xsd/processdefinition' name='"+name+"' start='"+start+"' processClass='de.hybris.platform.processengine.model.BusinessProcessModel'><end id='v1' state='SUCCEEDED'>v1</end><end id='v2' state='SUCCEEDED'>v2</end></process>");
    }
    static GenericApplicationContext observed(Path file) {
        var context=new GenericApplicationContext();var reader=new XmlBeanDefinitionReader(context);
        reader.setValidationMode(XmlBeanDefinitionReader.VALIDATION_NONE); reader.loadBeanDefinitions(new FileSystemResource(file));
        SpringXmlResources.record(reader,new FileSystemResource(file));context.refresh();ApplicationContextHolder.register(context);return context;
    }
    static void scenario(Path directory,boolean cold) throws Exception {
        Files.createDirectories(directory);directory=directory.toRealPath();
        Path source=directory.resolve("example-spring.xml"),a=directory.resolve("first-process.xml"),b=directory.resolve("second-process.xml");
        Files.writeString(source,beans(""));ProcessTenantFixture.reset();var context=observed(source);var second=observed(source);
        ProcessTenantFixture.attach(context);ProcessTenantFixture.attach(second);
        var scripting=(ScriptingLanguagesService)Proxy.newProxyInstance(ProcessCreateSdkProbe.class.getClassLoader(),new Class<?>[]{ScriptingLanguagesService.class},(p,m,v)->{throw new AssertionError("No script");});
        var reader=new XMLProcessDefinitionsReader(scripting);var provider=new ProcessDefinitionsProvider(reader,null);provider.setApplicationContext(context);
        var cache=new ProcessDefinitionsCache();cache.setProcessDefinitionsProvider(provider);
        var factory=new DefaultProcessDefinitionFactory();factory.setDefinitionsCache(cache);factory.setXmlDefinitionsReader(reader);
        context.getBeanFactory().registerSingleton("factory",factory);
        var readerB=new XMLProcessDefinitionsReader(scripting);var providerB=new ProcessDefinitionsProvider(readerB,null);providerB.setApplicationContext(second);
        var cacheB=new ProcessDefinitionsCache();cacheB.setProcessDefinitionsProvider(providerB);
        var factoryB=new DefaultProcessDefinitionFactory();factoryB.setDefinitionsCache(cacheB);factoryB.setXmlDefinitionsReader(readerB);
        second.getBeanFactory().registerSingleton("factory",factoryB);
        var handler=new SapProcessDefinitionReloader();
        var spring=new SpringXmlReloader(new Platform(context),paths->paths.forEach(path->handler.handle(new PlatformChangeHandler.Change(Capability.PROCESS_RELOAD,path,"CREATED","example","resources"))));
        try {
            if(!cold) check(cache.getAllIds().isEmpty() && cacheB.getAllIds().isEmpty(),"two warm empty native caches");
            save(a,"exampleFirst","v1");Files.writeString(source,beans(resource("first",a)));spring.reloadLoaded(source);
            if(cold)check(SapProcessDefinitions.field(cache,"definitions")==null,"cold hook leaves native lazy cache alone");
            check(factory.getProcessDefinition("exampleFirst").getStartNode().getId().equals("v1"),"process-first new name resolves"+(cold?" via existing native cold load":" via warm creation"));
            var pinned=factory.getProcessDefinition("exampleFirst");
            var pinnedB=factoryB.getProcessDefinition("exampleFirst");
            check(pinnedB.getStartNode().getId().equals("v1"),"second native owner resolves new name");
            check(SapProcessResources.registrations().stream().filter(r->r.path().equals(a)).count()==2,"two real context resource owners");
            save(a,"exampleFirst","v2");
            check(handler.handle(new PlatformChangeHandler.Change(Capability.PROCESS_RELOAD,a,"MODIFIED","example","resources"))==PlatformChangeHandler.Outcome.HANDLED,"two-context subsequent edit");
            check(factory.getProcessDefinition("exampleFirst").getStartNode().getId().equals("v2"),"new lookup selects v2");
            check(factory.getProcessDefinition(pinned.getId())==pinned,"pinned v1 historical entry preserved");
            Files.writeString(source,beans(resource("first",a)+resource("second",b)));spring.reloadLoaded(source);
            check(!cache.getAllIds().contains(new ProcessDefinitionId("exampleSecond")),"bean-first absent file does not publish");
            Files.writeString(b,"<process>");handler.handle(new PlatformChangeHandler.Change(Capability.PROCESS_RELOAD,b,"MODIFIED","example","resources"));
            check(!cache.getAllIds().contains(new ProcessDefinitionId("exampleSecond")),"malformed file refuses");
            save(b,"exampleSecond","v1");
            check(handler.handle(new PlatformChangeHandler.Change(Capability.PROCESS_RELOAD,b,"CREATED","example","resources"))==PlatformChangeHandler.Outcome.HANDLED,"bean-first valid save recovers");
            check(factory.getProcessDefinition("exampleSecond").getStartNode().getId().equals("v1"),"second native name resolves");
            check(factoryB.getProcessDefinition("exampleFirst").getStartNode().getId().equals("v2"),"second native owner edits independently");
            check(factoryB.getProcessDefinition(pinnedB.getId())==pinnedB,"second owner retains own historical definition");
            check(factoryB.getProcessDefinition("exampleSecond").getStartNode().getId().equals("v1"),"second owner bean-first name resolves");
            Path c=directory.resolve("third-process.xml"),d=directory.resolve("fourth-process.xml");save(c,"exampleDuplicate","v1");save(d,"exampleDuplicate","v2");
            Files.writeString(source,beans(resource("first",a)+resource("second",b)+resource("third",c)+resource("fourth",d)));spring.reloadLoaded(source);
            check(!cache.getAllIds().contains(new ProcessDefinitionId("exampleDuplicate")),"same Spring batch duplicate name publishes neither");
            check(SapProcessDefinitions.observed(cache,provider,context).stream().noneMatch(x->x.name().equals("exampleDuplicate")),"duplicate has no association");
        } finally {context.close();second.close();ApplicationContextHolder.clear();SapProcessDefinitions.discard(cache);SapProcessDefinitions.discard(cacheB);ProcessTenantFixture.reset();}
    }
    public static void main(String[] args) throws Exception {
        scenario(Path.of(args[0]).resolve("warm"),false);scenario(Path.of(args[0]).resolve("cold"),true);
        System.out.println("SDK_CREATE_CHECKS="+checks);
        System.out.println("SCOPE=real native parser/cache/Spring hook and observer; test-only empty-DB query and tenant/session binding seams; explicit Spring load observation; no native tenant/startProcess/task execution");
    }
}
