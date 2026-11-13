/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.example;

import de.hybris.platform.servicelayer.event.impl.AbstractEventListener;
import de.hybris.platform.servicelayer.cluster.ClusterService;
import de.hybris.platform.servicelayer.tenant.TenantService;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.ApplicationListener;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;

/** Opt-in actual SDK XML regression. Synthetic services here are NOT live tenant proof. */
public class XmlListenerApp {
    static final Map<String,Integer> added=new HashMap<>(), controls=new HashMap<>();
    public static class Listener extends AbstractEventListener<ProbeEvent> {
        protected void onEvent(ProbeEvent event){added.merge(event.nonce,1,Integer::sum);}
    }
    public static class CustomLifecycle extends AbstractEventListener<ProbeEvent> {
        public void afterPropertiesSet(){throw new AssertionError("custom lifecycle must be refused");}
        protected void onEvent(ProbeEvent event){added.merge(event.nonce,1,Integer::sum);}
    }
    public static class CustomConstructor extends AbstractEventListener<ProbeEvent> {
        private final Object resource=new Object();
        protected void onEvent(ProbeEvent event){added.merge(event.nonce,1,Integer::sum);}
    }
    public static class OrdinaryInit implements InitializingBean {
        public void afterPropertiesSet(){throw new AssertionError("ordinary lifecycle must be refused");}
    }
    public static class Control implements ApplicationListener<ProbeEvent> {
        public void onApplicationEvent(ProbeEvent event){controls.merge(event.nonce,1,Integer::sum);}
    }
    static final String HEAD="<beans xmlns=\"http://www.springframework.org/schema/beans\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd\">";
    static String definition(Class<?> type){return "<bean id='newListener' class='"+type.getName()+"'><property name='tenantService' ref='tenantService'/><property name='clusterService' ref='clusterService'/></bean>";}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static <T> T service(Class<T> type){
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->switch(m.getName()){
            case "getCurrentTenantId" -> "junit"; case "getClusterIslandId" -> 0L;
            case "getClusterId" -> 0; case "getClusterGroups" -> Collections.emptyList();
            case "hashCode" -> System.identityHashCode(p);case "equals" -> p==a[0];case "toString" -> "local SDK mechanics service";
            default -> throw new UnsupportedOperationException(m.getName());
        }));
    }
    static GenericApplicationContext baseline(Path xml,boolean observed)throws Exception{
        Files.writeString(xml,HEAD+"<bean id='control' class='"+Control.class.getName()+"'/></beans>");
        var context=new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("tenantService",service(TenantService.class));
        context.getBeanFactory().registerSingleton("clusterService",service(ClusterService.class));
        var reader=new XmlBeanDefinitionReader(context.getDefaultListableBeanFactory());var resource=new FileSystemResource(xml);
        if(observed)Class.forName("com.onurkat.reclazz.platform.SpringXmlAliases").getMethod("begin",Object.class,Object.class).invoke(null,reader,resource);
        reader.loadBeanDefinitions(resource);
        if(observed)Class.forName("com.onurkat.reclazz.platform.SpringXmlResources").getMethod("record",Object.class,Object.class).invoke(null,reader,resource);
        context.refresh();return context;
    }
    static void reload(GenericApplicationContext context,Path xml,boolean hybris)throws Exception{
        Class<?> platform=Class.forName("com.onurkat.reclazz.platform.PlatformContext");
        Class<?> id=Class.forName("com.onurkat.reclazz.platform.PlatformContext$Platform");
        Object kind=id.getField(hybris?"HYBRIS":"GENERIC").get(null);
        Object proxy=Proxy.newProxyInstance(platform.getClassLoader(),new Class<?>[]{platform},(p,m,a)->switch(m.getName()){
            case "getApplicationContext" -> context;case "getPlatformId" -> kind;
            case "hashCode" -> System.identityHashCode(p);case "equals" -> p==a[0];
            case "toString" -> "local SDK platform adapter";default -> null;
        });
        Class<?> reloader=Class.forName("com.onurkat.reclazz.spring.xml.SpringXmlReloader");
        reloader.getMethod("reload",Path.class).invoke(reloader.getConstructor(platform).newInstance(proxy),xml);
    }
    static void append(Path xml,String bean)throws Exception{Files.writeString(xml,Files.readString(xml).replace("</beans>",bean+"</beans>"));}
    static void publish(GenericApplicationContext context,int count){
        String nonce=UUID.randomUUID().toString();context.publishEvent(new ProbeEvent(nonce));
        check(controls.getOrDefault(nonce,0)==1,"existing control expected1");
        check(added.getOrDefault(nonce,0)==count,"new delivery expected"+count+" got"+added.getOrDefault(nonce,0));
    }
    static void absent(GenericApplicationContext context){check(!context.containsBeanDefinition("newListener")&&context.getBeanFactory().getSingleton("newListener")==null,"partial new definition/singleton remains");}
    static void processors(GenericApplicationContext context,boolean proxy,boolean fail){
        var list=context.getDefaultListableBeanFactory().getBeanPostProcessors();int position=-1;
        for(int i=0;i<list.size();i++)if(list.get(i).getClass().getName().equals("org.springframework.context.support.ApplicationListenerDetector"))position=i;
        check(position>=0,"native detector missing");
        if(proxy)list.add(position,new BeanPostProcessor(){
            public Object postProcessAfterInitialization(Object bean,String name){
                if(!name.equals("newListener"))return bean;
                return Proxy.newProxyInstance(bean.getClass().getClassLoader(),new Class<?>[]{ApplicationListener.class},(p,m,a)->switch(m.getName()){
                    case "onApplicationEvent" -> {if(a[0] instanceof ProbeEvent event)((Listener)bean).onApplicationEvent(event);yield null;}
                    case "hashCode" -> System.identityHashCode(p);case "equals" -> p==a[0];case "toString" -> "synthetic listener proxy";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
            }
        });
        if(fail)context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor(){
            public Object postProcessAfterInitialization(Object bean,String name){if(name.equals("newListener"))throw new IllegalStateException("synthetic failure after detector");return bean;}
        });
    }
    public static void main(String[] args)throws Exception{
        Path xml=Path.of(args[0]);int cases=0;
        try {
            try(var context=baseline(xml,true)){
                append(xml,definition(Listener.class));reload(context,xml,true);publish(context,1);
                Object first=context.getBeanFactory().getSingleton("newListener");check(first!=null,"native singleton absent");
                Files.writeString(xml,Files.readString(xml));reload(context,xml,true);publish(context,1);
                check(first==context.getBeanFactory().getSingleton("newListener"),"identical save replaced listener");cases++;
                System.out.println("PASS first and identical second save: control1/new1 each");
            }
            for(boolean proxy:List.of(false,true))try(var context=baseline(xml,true)){
                processors(context,proxy,true);var before=new ArrayList<>(context.getDefaultListableBeanFactory().getBeanPostProcessors());
                append(xml,definition(Listener.class));reload(context,xml,true);absent(context);publish(context,0);
                check(before.equals(context.getDefaultListableBeanFactory().getBeanPostProcessors()),"temporary capture processor remains/order changed");cases++;
                System.out.println("PASS post-detector failed creation cleanup proxy="+proxy+": control1/new0");
            }
            try(var context=baseline(xml,true)){
                processors(context,true,false);var before=new ArrayList<>(context.getDefaultListableBeanFactory().getBeanPostProcessors());
                append(xml,definition(Listener.class));reload(context,xml,true);absent(context);publish(context,0);
                check(before.equals(context.getDefaultListableBeanFactory().getBeanPostProcessors()),"capture remains");cases++;
                System.out.println("PASS successful proxy construction refused and exact registration removed");
            }
            for(Class<?> type:List.of(CustomLifecycle.class,CustomConstructor.class,OrdinaryInit.class))try(var context=baseline(xml,true)){
                append(xml,definition(type));reload(context,xml,true);absent(context);publish(context,0);cases++;
                System.out.println("PASS unsafe lifecycle/construction refused: "+type.getSimpleName());
            }
            for(boolean observed:List.of(false,true))try(var context=baseline(xml,observed)){
                append(xml,definition(Listener.class));reload(context,xml,false);absent(context);publish(context,0);cases++;
                System.out.println("PASS generic platform no SAP allowance observed="+observed);
            }
            try(var context=baseline(xml,false)){
                append(xml,definition(Listener.class));reload(context,xml,true);absent(context);publish(context,0);cases++;
                System.out.println("PASS unobserved owner no SAP allowance");
            }
            Path other=xml.resolveSibling("other-spring.xml");
            try(var actual=baseline(xml,true);var wrong=baseline(other,true)){
                append(xml,definition(Listener.class));reload(wrong,xml,true);absent(wrong);absent(actual);publish(wrong,0);cases++;
                System.out.println("PASS wrong captured owner cannot confer admission");
            } finally {Files.deleteIfExists(other);}
            try(var context=baseline(xml,true)){
                append(xml,definition(Listener.class));reload(context,xml,true);publish(context,1);
                Object original=context.getBeanFactory().getSingleton("newListener");
                context.getBeanFactory().registerSingleton("otherClusterService",service(ClusterService.class));
                Files.writeString(xml,Files.readString(xml).replace("ref='clusterService'","ref='otherClusterService'"));
                reload(context,xml,true);publish(context,1);
                check(original==context.getBeanFactory().getSingleton("newListener"),"existing listener mutation replaced registration");cases++;
                System.out.println("PASS changed existing listener remains refused and registered once");
            }
            System.out.println(cases+" SDK XML listener cases PASS; real tenant lifecycle UNVERIFIED here");
        } finally {Files.deleteIfExists(xml);}
    }
}
