/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.ui.RestartLedger;
import de.hybris.platform.converters.Populator;
import de.hybris.platform.converters.impl.AbstractPopulatingConverter;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

public class ConverterListSdkProbe {
    public static class OverrideConverter extends AbstractPopulatingConverter<Object,StringBuilder> {
        @Override public void setPopulators(List<Populator<Object,StringBuilder>> members) { super.setPopulators(members); }
    }
    private static int checks;
    private static final String REF_A = "<ref bean='A'/>";
    private static final String REF_B = "<ref bean='B'/>";
    private static final String REF_C = "<ref bean='C'/>";
    private static final String TYPE = AbstractPopulatingConverter.class.getName();

    private static String xml(String type, String refs, String extra) {
        return ListDirectiveSdkProbe.HEAD + ListDirectiveSdkProbe.mark("A") + ListDirectiveSdkProbe.mark("B") + extra
                + "<bean id='converter' class='" + type + "'><property name='targetClass' value='java.lang.StringBuilder'/>"
                + "<property name='populators'><list>" + refs + "</list></property></bean></beans>";
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError(what + " " + RestartLedger.digest());
    }

    private static GenericApplicationContext owner(Path file, DefaultListableBeanFactory factory) {
        var ctx = new GenericApplicationContext(factory);
        var reader = new XmlBeanDefinitionReader(ctx);
        reader.setValidating(false);
        var resource = new FileSystemResource(file);
        reader.loadBeanDefinitions(resource);
        SpringXmlResources.record(reader, resource);
        ctx.refresh();
        ApplicationContextHolder.register(ctx);
        return ctx;
    }

    private static void reload(Path file, GenericApplicationContext ctx, String text) throws Exception {
        Files.writeString(file, text);
        RestartLedger.clear();
        PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(ctx.getClassLoader(),new Class<?>[]{PlatformContext.class},
                (p,m,a) -> switch(m.getName()) { case "getApplicationContext" -> ctx; case "getPlatformId" -> PlatformContext.Platform.GENERIC; default -> null; });
        new SpringXmlReloader(platform).reloadLoaded(file);
    }

    private static class FailingFactory extends DefaultListableBeanFactory {
        boolean fail;
        @Override public Object getBean(String name) {
            if (fail && name.equals("C")) throw new IllegalStateException("injected second-owner lookup failure");
            return super.getBean(name);
        }
    }

    public static void main(String[] args) throws Exception {
        Path f=Path.of(args[0]).resolve("converter.xml");Files.createDirectories(f.getParent());
        String initial=xml(TYPE, REF_A, "");Files.writeString(f, initial);
        var secondFactory = new FailingFactory();
        try(var one=owner(f,new DefaultListableBeanFactory());var two=owner(f,secondFactory)) {
            var first=one.getBean("converter",AbstractPopulatingConverter.class);var second=two.getBean("converter",AbstractPopulatingConverter.class);
            List<?> a=first.getPopulators(), b=second.getPopulators();
            String inner="<bean class='"+ListDirectiveSdkProbe.Mark.class.getName()+"'><property name='value' value='I'/></bean>";
            String added=xml(TYPE,REF_A+REF_C,ListDirectiveSdkProbe.mark("C"));
            secondFactory.fail=true;reload(f,one,xml(TYPE, inner+REF_A+REF_C,ListDirectiveSdkProbe.mark("C")));
            check(first==one.getBean("converter") && second==two.getBean("converter"),"rollback lost retained converter");
            check(a==first.getPopulators() && b==second.getPopulators(),"rollback lost original list");
            for(var c:List.of(one,two)) {
                check(!c.containsBean("C"),"failed staged bean escaped");
                check(Arrays.equals(c.getBeanFactory().getDependenciesForBean("converter"),new String[]{"A"}),"old dependencies changed");
            }
            secondFactory.fail=false;reload(f,one,added);
            for(var c:List.of(one,two)) check(ListDirectiveSdkProbe.output(c).equals("AC"),"same-save reference");
            reload(f,one,xml(TYPE,REF_C+REF_A+REF_C,ListDirectiveSdkProbe.mark("C")));
            for(var c:List.of(one,two)) check(ListDirectiveSdkProbe.output(c).equals("CA"),"reorder and native de-duplication");
            reload(f,one,xml(TYPE,inner+REF_B,ListDirectiveSdkProbe.mark("C")));
            for(var c:List.of(one,two)) check(ListDirectiveSdkProbe.output(c).equals("IB"),"replace with inner and remove old members");
            reload(f,one,xml(TYPE,REF_B,ListDirectiveSdkProbe.mark("C")));
            check(ListDirectiveSdkProbe.output(one).equals("B"),"remove inner bean");
            reload(f,one,xml(TYPE,REF_A,ListDirectiveSdkProbe.mark("C")).replace("java.lang.StringBuilder", "java.lang.StringBuffer"));
            check(ListDirectiveSdkProbe.output(one).equals("B"),"changed second property was admitted");
        } finally {ApplicationContextHolder.clear();}
        String lazy = xml(TYPE, REF_A, "").replace("id='converter'", "id='converter' lazy-init='true'");
        Files.writeString(f, lazy);
        try (var live = owner(f, new DefaultListableBeanFactory()); var idle = owner(f, new DefaultListableBeanFactory())) {
            check(ListDirectiveSdkProbe.output(live).equals("A") && idle.getBeanFactory().getSingleton("converter") == null,
                    "only one owner initialized the converter");
            reload(f, live, xml(TYPE, REF_C, ListDirectiveSdkProbe.mark("C")).replace("id='converter'", "id='converter' lazy-init='true'"));
            check(ListDirectiveSdkProbe.output(live).equals("A") && idle.getBeanFactory().getSingleton("converter") == null,
                    "partial owning-context change escaped or initialized lazy converter");
            check(!live.containsBean("C") && !idle.containsBean("C"), "partial owner staged a same-save member");
        } finally { ApplicationContextHolder.clear(); }
        Files.writeString(f,xml(OverrideConverter.class.getName(),REF_A,""));
        try(var ctx=owner(f,new DefaultListableBeanFactory())) {
            reload(f,ctx,xml(OverrideConverter.class.getName(),REF_B,""));
            check(ListDirectiveSdkProbe.output(ctx).equals("A"),"overridden setter was admitted");
        } finally {ApplicationContextHolder.clear();}
        System.out.println("PASS converter base list: "+checks+" checks");
    }
}
