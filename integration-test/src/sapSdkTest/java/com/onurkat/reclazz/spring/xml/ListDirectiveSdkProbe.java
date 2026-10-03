/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.*;
import de.hybris.platform.converters.Populator;
import de.hybris.platform.converters.impl.*;
import de.hybris.platform.spring.config.*;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.*;
import java.util.*;

public class ListDirectiveSdkProbe {
    public static class Mark implements Populator<Object,StringBuilder> {
        private String value;
        public void setValue(String value) { this.value=value; }
        public void populate(Object source,StringBuilder target) { target.append(value); }
    }
    public static class CustomModify extends ModifyPopulatorList<Object,StringBuilder> { }
    public static class CustomMerge extends ListMergeDirective { }
    static int assertions;
    static final String HEAD="<beans xmlns='http://www.springframework.org/schema/beans'>";
    static String mark(String id) { return "<bean id='"+id+"' class='"+Mark.class.getName()+"'><property name='value' value='"+id+"'/></bean>"; }
    static String converter(String id) { return "<bean id='"+id+"' class='"+AbstractPopulatingConverter.class.getName()+"'><property name='targetClass' value='java.lang.StringBuilder'/><property name='populators'><list><ref bean='A'/></list></property></bean>"; }
    static String xml(boolean merge,String add,String variant) {
        String type=(merge?ListMergeDirective.class:ModifyPopulatorList.class).getName();
        if(variant.equals("subclass")) type=(merge?CustomMerge.class:CustomModify.class).getName();
        String attribute=variant.equals("inherited") ? "parent='template'" : "class='"+type+"'";
        String directive="<bean id='directive' "+attribute+(variant.equals("lazy")?" lazy-init='true'":"")
                +(merge?" depends-on='converter'":"")+">";
        directive += merge ? "<property name='listPropertyDescriptor' value='populators'/><property name='beforeBeanNames'><list><value>A</value></list></property>"
                : "<property name='list' ref='converter'/>";
        return HEAD+mark("plain")+directive+"<property name='add' ref='"+add+"'/></bean></beans>";
    }
    static void require(boolean condition,String message) { assertions++; if(!condition)throw new AssertionError(message); }
    static void equal(String expected,Object value) { assertions++; if(!expected.equals(value.toString())) throw new AssertionError("Expected "+expected+", got "+value); }
    static String output(GenericApplicationContext c) { return ((AbstractPopulatingConverter)c.getBean("converter")).convert(new Object()).toString(); }

    public static void main(String[] args) throws Exception {
        Path dir=Path.of(args[0]); Files.createDirectories(dir);
        for(boolean merge:List.of(false,true)) for(String variant:List.of("normal","inherited","subclass","lazy","shared")) {
            Path file=dir.resolve(merge?"merge.xml":"modify.xml"); Files.writeString(file,xml(merge,"B",variant));
            String template="<bean id='template' abstract='true' class='"+(merge?ListMergeDirective.class:ModifyPopulatorList.class).getName()+"'/>";
            String shared=variant.equals("shared") ? (merge
                    ? "<bean id='shared' class='"+ListMergeDirective.class.getName()+"' depends-on='converter'><property name='listPropertyDescriptor' value='populators'/><property name='add' ref='C'/></bean>"
                    : "<bean id='shared' class='"+ModifyPopulatorList.class.getName()+"'><property name='list' ref='converter'/><property name='add' ref='C'/></bean>") : "";
            Path base=dir.resolve("base.xml");Files.writeString(base,HEAD+mark("A")+mark("B")+mark("C")+converter("converter")+converter("unrelated")+template+shared+"</beans>");
            try(var ctx=new GenericApplicationContext()) {
                var reader=new XmlBeanDefinitionReader(ctx);reader.setValidating(false);reader.loadBeanDefinitions(new FileSystemResource(base));var resource=new FileSystemResource(file);reader.loadBeanDefinitions(resource);SpringXmlResources.record(reader,resource);
                var modify=new ModifyPopulatorListBeanPostProcessor();modify.setBeanFactory(ctx.getBeanFactory());
                // Older SDKs initialize the strategy through this public @PostConstruct callback;
                // newer SDKs initialize it lazily. These processors are installed manually here.
                try { modify.getClass().getMethod("initialize").invoke(modify); }
                catch (NoSuchMethodException absentOnNewerSdk) { }
                var merger=new ListMergeDirectiveBeanPostProcessor();merger.setBeanFactory(ctx.getBeanFactory());
                if ((Object)merger instanceof org.springframework.beans.factory.InitializingBean callback)
                    callback.afterPropertiesSet();
                ctx.getBeanFactory().addBeanPostProcessor(merge?merger:modify);
                ctx.refresh();ApplicationContextHolder.register(ctx);
                PlatformContext platform=(PlatformContext)java.lang.reflect.Proxy.newProxyInstance(ListDirectiveSdkProbe.class.getClassLoader(),new Class<?>[]{PlatformContext.class},(p,m,a)->switch(m.getName()){case "getApplicationContext"->ctx;case "getPlatformId"->PlatformContext.Platform.GENERIC;default->null;});
                var reloader=new SpringXmlReloader(platform);
                String original=variant.equals("shared") ? (merge?"BAC":"ACB") : (merge?"BA":variant.equals("lazy")?"A":"AB");
                equal(original,output(ctx));
                com.onurkat.reclazz.ui.RestartLedger.clear();
                reloader.reloadLoaded(file);
                require(com.onurkat.reclazz.ui.RestartLedger.digest().equals(List.of("Nothing from this session needs a restart.")), "unchanged directive must stay quiet");
                Object directive=ctx.getBeanFactory().getSingleton("directive");
                require((directive==null)==variant.equals("lazy"), "lazy directive initialization changed");
                Object definition=((org.springframework.beans.factory.support.AbstractBeanDefinition)ctx.getBeanFactory().getBeanDefinition("directive")).cloneBeanDefinition();
                for(String changed:List.of(xml(merge,"C",variant), xml(merge,"C",variant))) {
                    com.onurkat.reclazz.ui.RestartLedger.clear();
                    Files.writeString(file,changed);reloader.reloadLoaded(file);
                    require(com.onurkat.reclazz.ui.RestartLedger.digest().stream().anyMatch(s -> s.contains("SAP list directive edit requires restart")), "missing directive restart diagnostic");
                    require(ctx.getBeanFactory().getSingleton("directive")==directive, "directive identity/lazy state changed");
                    if(directive!=null) require(directive.getClass().getMethod("getAdd").invoke(directive)==ctx.getBean("B"), "live directive must not mutate");
                    require(definition.equals(ctx.getBeanFactory().getBeanDefinition("directive")), "original definition must not mutate");
                    equal(original,output(ctx));
                }
                // Order/remove properties and depends-on changes must also be rejected.
                String alternate=merge ? xml(true,"B",variant).replace("<value>A</value>","<value>C</value>")
                        : xml(false,"B",variant).replace("</bean></beans>","<property name='remove' ref='A'/></bean></beans>");
                for(String changed:List.of(alternate, merge ? xml(true,"B",variant).replace("depends-on='converter'","depends-on='unrelated'")
                        : xml(false,"B",variant).replace("ref='converter'","ref='unrelated'"))) {
                    com.onurkat.reclazz.ui.RestartLedger.clear();Files.writeString(file,changed);reloader.reloadLoaded(file);
                    require(com.onurkat.reclazz.ui.RestartLedger.digest().stream().anyMatch(s -> s.contains("SAP list directive edit requires restart")), "missing metadata restart diagnostic");
                    equal(original,output(ctx));
                }
                com.onurkat.reclazz.ui.RestartLedger.clear();Files.writeString(file,xml(merge,"B",variant));reloader.reloadLoaded(file);
                require(com.onurkat.reclazz.ui.RestartLedger.digest().equals(List.of("Nothing from this session needs a restart.")), "restored original XML must stay quiet");
                System.out.println(variant+" "+(merge?"MERGE":"MODIFY")+" edit refused; original converted output preserved: "+output(ctx));
                equal("A",((AbstractPopulatingConverter)ctx.getBean("unrelated")).convert(new Object()));
                // A refused directive does not prevent an unrelated ordinary property edit.
                Files.writeString(file,xml(merge,"C",variant).replace("value='plain'","value='updated'"));
                reloader.reloadLoaded(file);
                var out=new StringBuilder();((Mark)ctx.getBean("plain")).populate(new Object(),out);
                equal("updated",out);equal(original,output(ctx));
            } finally {ApplicationContextHolder.clear();}
        }
        System.out.println("PASS: "+assertions+" list directive assertions; Spring "+org.springframework.core.SpringVersion.getVersion());
    }
}
