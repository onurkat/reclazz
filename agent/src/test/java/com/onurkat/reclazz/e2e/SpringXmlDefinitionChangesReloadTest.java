/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class SpringXmlDefinitionChangesReloadTest {
    @TempDir Path temp;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void prototypeXmlEditsOnlyAffectFutureCreationAndPreserveLiveConsumer(boolean child)throws Exception {
        Path xml=Files.createDirectories(temp.resolve("classes")).resolve("definitions-spring.xml");Files.writeString(xml,prototype("before"));
        var builder=WatchedApp.in(temp).classpath(WatchedApp.springClasspath());if(child)builder.childClassLoader();
        try(var app=builder.jvmArgs("-Ddefs="+xml,"-Dtest.dir="+temp).with("App",APP).start()) {
            try{
                app.awaitOrFail("READY=before:init|1|1|0","initial prototype/consumer missing");app.awaitOrFail("] Watching ","watcher missing");
                save(app,xml,prototype("after"),1);probe(app,1,"true|true|before:init|after:init|1|2|2|0|false");
                save(app,xml,prototype("again"),2);probe(app,2,"true|true|before:init|again:init|2|3|3|0|false");
                assertFalse(app.output().stream().anyMatch(line->line.contains("Restart required for:")),app.tail());
                Files.createFile(temp.resolve("close"));app.awaitOrFail("CLOSED=1","consumer destruction must happen only at actual context close");
            }finally{String evidence=System.getenv("RECLAZZ_XML_DEFINITION_EVIDENCE");if(evidence!=null){Path dir=Files.createDirectories(Path.of(evidence));Files.write(dir.resolve("prototype-"+child+"-child.log"),app.output());}}
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void coldDependencyAndAutowirePolicyAreUsedAtFirstNativeCreation(boolean child)throws Exception {
        Path xml=Files.createDirectories(temp.resolve("classes")).resolve("definitions-spring.xml");Files.writeString(xml,coldPolicy("a","no"));
        var builder=WatchedApp.in(temp).classpath(WatchedApp.springClasspath());if(child)builder.childClassLoader();
        try(var app=builder.jvmArgs("-Ddefs="+xml,"-Dtest.dir="+temp).with("App",COLD_APP).start()) {
            try {
                app.awaitOrFail("READY=0|false","cold target was created at boot");app.awaitOrFail("] Watching ","watcher missing");
                save(app,xml,coldPolicy("b","byType"),1);probe(app,1,"true|B,T,A|true|0|b,a");
                assertFalse(app.output().stream().anyMatch(line->line.contains("Restart required for:")),app.tail());
                Files.createFile(temp.resolve("close"));app.awaitOrFail("CLOSED=1","target destruction must occur only on actual close");
            }finally{String evidence=System.getenv("RECLAZZ_XML_DEFINITION_EVIDENCE");if(evidence!=null){Path dir=Files.createDirectories(Path.of(evidence));Files.write(dir.resolve("cold-policy-"+child+"-child.log"),app.output());}}
        }
    }
    private static String coldPolicy(String dependency,String mode){return "<beans xmlns='http://www.springframework.org/schema/beans'><bean id='a' class='app.App$First' lazy-init='true'/><bean id='b' class='app.App$Second' lazy-init='true'/><bean id='value' class='app.App$Value' lazy-init='true' depends-on='"+dependency+"' autowire='"+mode+"'/></beans>";}
    private static final String COLD_APP="""
        package app;
        public class App {
            static java.util.List<String> order=new java.util.ArrayList<>();
            public static class First {public First(){order.add("A");}}
            public static class Second {public Second(){order.add("B");}}
            public static class Value implements org.springframework.beans.factory.DisposableBean {static int closed;First first;public Value(){order.add("T");}public void setFirst(First first){this.first=first;}public void destroy(){closed++;}}
            public static void main(String[]args)throws Exception {
                var context=new org.springframework.context.support.GenericApplicationContext();var reader=new org.springframework.beans.factory.xml.XmlBeanDefinitionReader(context);reader.setValidating(false);reader.loadBeanDefinitions(new org.springframework.core.io.FileSystemResource(System.getProperty("defs")));context.refresh();
                System.out.println("READY="+order.size()+"|"+context.getBeanFactory().containsSingleton("value"));var dir=java.nio.file.Path.of(System.getProperty("test.dir"));
                while(!java.nio.file.Files.exists(dir.resolve("probe1")))Thread.sleep(25);boolean empty=order.isEmpty();Value fresh=context.getBean("value",Value.class);System.out.println("XML1="+empty+"|"+String.join(",",order)+"|"+(fresh.first==context.getBean("a"))+"|"+Value.closed+"|"+String.join(",",context.getBeanFactory().getDependenciesForBean("value")));
                while(!java.nio.file.Files.exists(dir.resolve("close")))Thread.sleep(25);context.close();System.out.println("CLOSED="+Value.closed);
            }
        }
        """;
    private void save(WatchedApp app,Path xml,String document,int count)throws Exception {
        Files.writeString(xml,document);long until=System.nanoTime()+Duration.ofSeconds(20).toNanos();while(System.nanoTime()<until&&reports(app)<count)Thread.sleep(25);assertEquals(count,reports(app),app.tail());
    }
    private long reports(WatchedApp app){return app.output().stream().filter(line->line.contains("Spring XML definitions-spring.xml")&&(line.contains("reloaded:")||line.contains("parsed OK"))).count();}
    private void probe(WatchedApp app,int n,String expected)throws Exception {Files.createFile(temp.resolve("probe"+n));app.awaitOrFail("XML"+n+"=","missing real creation probe");String line=app.latest("XML"+n+"=");assertEquals("XML"+n+"="+expected,line.substring(line.indexOf("XML")),app.tail());}
    private static String prototype(String value){return "<beans xmlns='http://www.springframework.org/schema/beans'><bean id='value' class='app.App$Value' scope='prototype'><property name='text' value='"+value+"'/></bean><bean id='holder' class='app.App$Holder'><constructor-arg ref='value'/></bean></beans>";}
    private static final String APP="""
        package app;
        public class App {
            public static class Value implements org.springframework.beans.factory.InitializingBean {
                public static int created,initialized;public String text;public Value(){created++;}public void setText(String value){text=value;}public void afterPropertiesSet(){initialized++;text+=":init";}
            }
            public static class Holder implements org.springframework.beans.factory.DisposableBean {
                public static int closed;public final Value value;public Holder(Value value){this.value=value;}public void destroy(){closed++;}
            }
            public static void main(String[]args)throws Exception {
                var context=new org.springframework.context.support.GenericApplicationContext();var reader=new org.springframework.beans.factory.xml.XmlBeanDefinitionReader(context);reader.setValidating(false);reader.loadBeanDefinitions(new org.springframework.core.io.FileSystemResource(System.getProperty("defs")));context.refresh();Holder holder=context.getBean("holder",Holder.class);Value held=holder.value;
                System.out.println("READY="+held.text+"|"+Value.created+"|"+Value.initialized+"|"+Holder.closed);var dir=java.nio.file.Path.of(System.getProperty("test.dir"));
                for(int n=1;n<=2;n++){while(!java.nio.file.Files.exists(dir.resolve("probe"+n)))Thread.sleep(25);int before=Value.created;Value fresh=context.getBean("value",Value.class);System.out.println("XML"+n+"="+(holder==context.getBean("holder"))+"|"+(held==holder.value)+"|"+held.text+"|"+fresh.text+"|"+before+"|"+Value.created+"|"+Value.initialized+"|"+Holder.closed+"|"+context.getBeanFactory().containsSingleton("value"));}
                while(!java.nio.file.Files.exists(dir.resolve("close")))Thread.sleep(25);context.close();System.out.println("CLOSED="+Holder.closed);
            }
        }
        """;
}
