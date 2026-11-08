/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Actual watcher, Spring observer, dispatcher and module; synthetic native contracts. */
class SapProcessDefinitionCreateTest {
    @TempDir Path temporary;
    static String resource(String id,Path file) { return "<bean id='"+id+"' class='de.hybris.platform.processengine.definition.ProcessDefinitionResource'><property name='resource' value='"+file.toUri()+"'/></bean>"; }
    static String beans(String extra) { return "<beans xmlns='http://www.springframework.org/schema/beans' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' xsi:schemaLocation='http://www.springframework.org/schema/beans https://www.springframework.org/schema/beans/spring-beans.xsd'><bean id='anchor' class='java.lang.Object'/>"+extra+"</beans>"; }
    @Test void watcherRegistersBothSaveOrdersInTwoContextsAndKeepsHistory() throws Exception {
        Files.createDirectories(temporary.resolve("config"));Files.createDirectories(temporary.resolve("bin/platform"));
        Files.writeString(temporary.resolve("config/localextensions.xml"),"<hybrisconfig><extensions><extension name='example'/></extensions></hybrisconfig>");
        Path extension=Files.createDirectories(temporary.resolve("bin/custom/example"));
        Files.writeString(extension.resolve("extensioninfo.xml"),"<extensioninfo><extension name='example'/></extensioninfo>");
        Path dir=Files.createDirectories(extension.resolve("resources"));Path spring=dir.resolve("example-spring.xml"),first=dir.resolve("first-process.xml"),second=dir.resolve("second-process.xml");
        Files.writeString(spring,beans(""));
        var builder=WatchedApp.in(temporary).classpath(WatchedApp.springClasspath()+File.pathSeparator+WatchedApp.agentJarOrSkip())
                .agentArgs("platform=hybris,hybrisHome="+temporary+",startupDelaySec=0,debounceMs=100").jvmArgs("-Dexample.spring="+spring);
        for(String name:List.of("ProcessDefinitionResource","ProcessDefinitionId","ProcessDefinition","ProcessDefinitionFactory","DefaultProcessDefinitionFactory","ProcessDefinitionsCache","ProcessDefinitionsProvider","XMLProcessDefinitionsReader")) {
            String source=Files.readString(Path.of("src/test/java/de/hybris/platform/processengine/definition/"+name+".java"));
            if(name.equals("ProcessDefinitionsProvider"))source=source.replace("com.onurkat.reclazz.platform.SapProcessDefinitions.parsed(holder.getResource(), parsed);","");
            builder.with(name,source);
        }
        for (String path:List.of("com/onurkat/reclazz/hybris/ProcessTenantFixture.java", "de/hybris/platform/core/Registry.java", "de/hybris/platform/jalo/JaloSession.java"))
            builder.with(Path.of(path).getFileName().toString().replace(".java",""),Files.readString(Path.of((path.startsWith("de/")?"src/processTestFixtures/java/":"src/test/java/")+path)));
        try(var app=builder.with("App",APP).start()) {
            app.awaitOrFail("CREATE_READY","warm native fixture missing");app.awaitOrFail("] Watching ","watcher missing");
            Files.writeString(spring,beans(resource("firstResource",first)));
            app.awaitOrFail("Spring bean added: firstResource","bean-first save not applied");
            Files.writeString(first,"<process name='exampleFirst' start='v1'/>");
            app.awaitOrFail("CREATE_FIRST_V1","bean-first process name not published");
            Files.writeString(second,"<process name='exampleSecond' start='v1'/>");
            Files.writeString(spring,beans(resource("firstResource",first)+resource("secondResource",second)));
            app.awaitOrFail("CREATE_SECOND_V1","process-first name not published");
            Files.writeString(first,"<process name='exampleFirst' start='v2'/>");
            app.awaitOrFail("CREATE_EDIT_V2_HISTORY_V1","two-context edit/history failed");
            assertFalse(app.output().stream().anyMatch(x->x.contains("VerifyError")||x.contains("ClassNotFoundException")||x.contains("[ ERR]")),app.tail());
        }
    }
    private static final String APP="""
            package app;
            import de.hybris.platform.processengine.definition.*;
            public class App {
                static org.springframework.context.support.GenericApplicationContext context(java.nio.file.Path path) {
                    var ctx=new org.springframework.context.support.GenericApplicationContext();
                    new org.springframework.beans.factory.xml.XmlBeanDefinitionReader(ctx).loadBeanDefinitions(new org.springframework.core.io.FileSystemResource(path));
                    ctx.refresh();return ctx;
                }
                public static void main(String[] args) throws Exception {
                    var source=java.nio.file.Path.of(System.getProperty("example.spring"));var ctx=context(source);var second=context(source);
                    com.onurkat.reclazz.hybris.ProcessTenantFixture.attach(ctx);com.onurkat.reclazz.hybris.ProcessTenantFixture.attach(second);
                    var reader=new XMLProcessDefinitionsReader();var provider=new ProcessDefinitionsProvider();provider.applicationContext=ctx;provider.reader=reader;
                    var cache=new ProcessDefinitionsCache();cache.processDefinitionsProvider=provider;
                    var factory=new DefaultProcessDefinitionFactory();factory.definitionsCache=cache;factory.xmlDefinitionsReader=reader;
                    ctx.getBeanFactory().registerSingleton("factory",factory);cache.initialise(true);
                    var readerB=new XMLProcessDefinitionsReader();var providerB=new ProcessDefinitionsProvider();providerB.applicationContext=second;providerB.reader=readerB;
                    var cacheB=new ProcessDefinitionsCache();cacheB.processDefinitionsProvider=providerB;
                    var factoryB=new DefaultProcessDefinitionFactory();factoryB.definitionsCache=cacheB;factoryB.xmlDefinitionsReader=readerB;
                    second.getBeanFactory().registerSingleton("factory",factoryB);cacheB.initialise(true);
                    System.out.println("CREATE_READY");ProcessDefinition pinned=null;boolean seenSecond=false;
                    for(int i=0;i<2400;i++) {
                        cache.definitionsLock.readLock().lock();
                        try {
                            var first=cache.definitions.get(new ProcessDefinitionId("exampleFirst"));
                            if(pinned==null&&first!=null&&cacheB.definitions.containsKey(new ProcessDefinitionId("exampleFirst"))){pinned=first;System.out.println("CREATE_FIRST_V1");}
                            if(!seenSecond&&cache.definitions.containsKey(new ProcessDefinitionId("exampleSecond"))&&cacheB.definitions.containsKey(new ProcessDefinitionId("exampleSecond"))){seenSecond=true;System.out.println("CREATE_SECOND_V1");}
                            if(pinned!=null&&first.graph().equals("v2")&&cacheB.definitions.get(new ProcessDefinitionId("exampleFirst")).graph().equals("v2")) {
                                if(cache.definitions.get(pinned.getId())!=pinned||!pinned.graph().equals("v1"))throw new AssertionError("history lost");
                                System.out.println("CREATE_EDIT_V2_HISTORY_V1");break;
                            }
                        }finally{cache.definitionsLock.readLock().unlock();}
                        Thread.sleep(50);
                    }
                    Thread.sleep(120000);second.close();ctx.close();
                }
            }
            """;
}
