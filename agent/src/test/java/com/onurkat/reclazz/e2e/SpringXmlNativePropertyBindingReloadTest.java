/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class SpringXmlNativePropertyBindingReloadTest {
    @TempDir Path tmp;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void nativeTypesSurviveRepeatedWatchedEditsAndInvalidValuesKeepMetadata(boolean child) throws Exception {
        Path xml=tmp.resolve("binding.xml");Files.writeString(xml,document(false,false,"before"));
        var builder=WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());if(child)builder.childClassLoader();
        try(var app=builder.jvmArgs("-Ddefs="+xml,"-Dtest.dir="+tmp).with("App",APP).start()) {
            app.awaitOrFail("READY=true","native initial binding missing");app.awaitOrFail("] Watching ","watcher missing");
            for(int n=1;n<=4;n++) {
                boolean next=n!=3,invalid=n==4;
                Files.writeString(xml,document(next,invalid,"other"+n));
                long until=System.nanoTime()+Duration.ofSeconds(20).toNanos();
                while(System.nanoTime()<until&&reports(app)<n)Thread.sleep(25);
                assertEquals(n,reports(app),app.tail());Files.createFile(tmp.resolve("probe"+n));
                app.awaitOrFail("PROBE"+n+"=","typed binding probe missing");String line=app.latest("PROBE"+n+"=");
                String expected=invalid?"before:A:1.0:urn:old:OLD:STRING":""+(next?"next:B:2.5:urn:new:NEXT:STRING":"before:A:1.0:urn:old:OLD:STRING");
                assertEquals("PROBE"+n+"=true:true:true:true:"+expected+":other"+n+":"+(invalid?"OLD":""+(next?"NEXT":"OLD")),line.substring(line.indexOf("PROBE")),app.tail());
            }
            assertEquals(1,app.output().stream().filter(v->v.contains("property apply failed")).count(),app.tail());
            assertEquals(1,app.output().stream().filter(v->v.contains("Restart required")).count(),app.tail());
        }
    }
    private long reports(WatchedApp app){return app.output().stream().filter(v->v.contains("Spring XML binding.xml")&&v.contains("reloaded:")).count();}
    private static String document(boolean next,boolean invalid,String sibling) {
        String mode=next?"NEXT":"OLD",cl=next?"app.App$Next":"app.App$Before";
        if(invalid) {next=false;cl="app.App$Before";mode="INVALID";}
        return "<beans xmlns='http://www.springframework.org/schema/beans'><bean id='dependency' class='java.lang.Object'/>"
            +"<bean id='target' class='app.App$Target'><property name='type' value='"+cl+"'/><property name='mode' value='"+mode+"'/>"
            +"<property name='code' value='"+(next?"B":"A")+"'/><property name='amount' value='"+(next?"2.5":"1.0")+"'/><property name='location' value='"+(next?"urn:new":"urn:old")+"'/>"
            +"<property name='types'><list><value>"+cl+"</value></list></property><property name='modes'><list><value>"+(invalid?"OLD":mode)+"</value></list></property>"
            +"<property name='mapping'><map><entry key='a' value='"+(invalid?"OLD":mode)+"'/></map></property><property name='selection' value='"+(next?"next":"before")+"'/><property name='dependency' ref='dependency'/></bean>"
            +"<bean id='other' class='app.App$Settings'><property name='label' value='"+sibling+"'/></bean></beans>";
    }
    private static final String APP="""
        package app;
        import java.nio.file.*;import java.util.*;import java.math.BigDecimal;import java.net.URI;
        import org.springframework.context.support.GenericApplicationContext;
        import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
        import org.springframework.core.io.FileSystemResource;
        import org.springframework.beans.factory.config.TypedStringValue;
        public class App {
            public enum Mode {OLD,NEXT} public static class Before {} public static class Next {}
            public static class Settings {public String label;public void setLabel(String v){label=v;}}
            public static class Base {public String selection,writer;public String getSelection(){throw new AssertionError("getter called");}
                public void setSelection(String v){selection=v;writer="STRING";}}
            public static class Target extends Base {
                public Class<?> type;public Mode mode;public char code;public BigDecimal amount;public URI location;
                public List<Class<?>> types;public List<Mode> modes;public Map<String,Mode> mapping;public Object dependency;
                public void setType(Class<?> v){type=v;}public void setMode(Mode v){mode=v;}public void setCode(char v){code=v;}
                public void setAmount(BigDecimal v){amount=v;}public void setLocation(URI v){location=v;}
                public void setTypes(List<Class<?>> v){types=v;}public void setModes(List<Mode> v){modes=v;}
                public void setMapping(Map<String,Mode> v){mapping=v;}public void setDependency(Object v){dependency=v;}
                public void setSelection(Object v){selection=v.toString();writer="OBJECT";}
            }
            public static void main(String[] args)throws Exception {
                Thread.currentThread().setContextClassLoader(ClassLoader.getSystemClassLoader());
                var context=new GenericApplicationContext();context.setClassLoader(App.class.getClassLoader());context.getBeanFactory().setBeanClassLoader(App.class.getClassLoader());
                var reader=new XmlBeanDefinitionReader(context);reader.setValidating(false);reader.setBeanClassLoader(App.class.getClassLoader());
                reader.loadBeanDefinitions(new FileSystemResource(System.getProperty("defs")));context.refresh();
                Target held=context.getBean("target",Target.class);Settings other=context.getBean("other",Settings.class);Object dep=context.getBean("dependency");
                Object raw=context.getBeanFactory().getBeanDefinition("target");
                System.out.println("READY="+(held.type==Before.class&&held.types.get(0)==Before.class&&held.mode==Mode.OLD&&held.dependency==dep&&held.writer.equals("STRING")));
                Path dir=Path.of(System.getProperty("test.dir"));
                for(int n=1;n<=4;n++) {
                    while(!Files.exists(dir.resolve("probe"+n)))Thread.sleep(20);
                    Object l=((List<?>)held.types).get(0),e=((List<?>)held.modes).get(0),m=((Map<?,?>)held.mapping).get("a");
                    boolean types=l instanceof Class<?> && l==held.type && e instanceof Mode && m instanceof Mode;
                    boolean ids=held==context.getBean("target")&&other==context.getBean("other")&&dep==held.dependency;
                    boolean generation=held.type==(n==1||n==2?Next.class:Before.class)&&e==(n==1||n==2?Mode.NEXT:Mode.OLD)&&m==e;
                    String rawMode=((TypedStringValue)context.getBeanFactory().getBeanDefinition("target").getPropertyValues().get("mode")).getValue();
                    System.out.println("PROBE"+n+"="+ids+":"+(raw==context.getBeanFactory().getBeanDefinition("target"))+":"+types+":"+generation+":"
                        +held.selection+":"+held.code+":"+held.amount+":"+held.location+":"+held.mode+":"+held.writer+":"+other.label+":"+rawMode);
                }
                while(true)Thread.sleep(100);
            }
        }
        """;
}
