package com.example;

import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.spring.xml.SpringXmlReloader;
import de.hybris.platform.acceleratorservices.dataimport.batch.*;
import de.hybris.platform.acceleratorservices.dataimport.batch.converter.impl.DefaultImpexConverter;
import de.hybris.platform.acceleratorservices.dataimport.batch.converter.mapping.impl.DefaultConverterMapping;
import de.hybris.platform.acceleratorservices.dataimport.batch.task.ImpexTransformerTask;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import org.springframework.aop.framework.*;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;

/** Actual SDK local conversion mechanics only; no native tenant/import/database claim. */
public class XmlHotFolderApp {
    static class Context implements PlatformContext {
        final Object value;Context(Object v){value=v;}
        public Platform getPlatformId(){return Platform.HYBRIS;} public void initialize(){}
        public Map<String,List<Path>> getClassOutputDirs(){return Map.of();}public Map<String,List<Path>> getSourceDirs(){return Map.of();}public Map<String,List<Path>> getResourceDirs(){return Map.of();}
        public String resolveClasspath(){return "";}public String resolveClassName(Path p){return null;}public Path resolveOutputDir(Path p){return null;}public Object getApplicationContext(){return value;}
    }
    public static class FailingMapping extends DefaultConverterMapping {
        public String getMapping(){throw new IllegalStateException("persistent foreign mapping fault");}
    }
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static final String HEAD="<beans xmlns='http://www.springframework.org/schema/beans' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' xsi:schemaLocation='http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd'>";
    static String xml(){
        String text=HEAD;
        for(String id:List.of("A","B"))text+="<bean id='converter"+id+"' class='de.hybris.platform.acceleratorservices.dataimport.batch.converter.impl.DefaultImpexConverter'><property name='header' value='INSERT ReclazzSchemaProbe;nonce[unique=true]'/><property name='impexRow' value=';"+id+"-{+0}'/></bean>";
        return text+"<bean id='mapping' class='de.hybris.platform.acceleratorservices.dataimport.batch.converter.mapping.impl.DefaultConverterMapping'><property name='mapping' value='c51-'/><property name='converter' ref='converterA'/></bean><bean id='transformer' class='de.hybris.platform.acceleratorservices.dataimport.batch.task.ImpexTransformerTask' init-method='initConvertersMap'/></beans>";
    }
    /** Explicit Spring lookup failure injection; native classes are unchanged. */
    public static class FaultContext extends GenericApplicationContext {
        boolean failLookup;
        @Override public <T> Map<String,T> getBeansOfType(Class<T> type) {
            if (failLookup) throw new IllegalStateException("injected native staging lookup failure");
            return super.getBeansOfType(type);
        }
    }
    static GenericApplicationContext load(Path xml,boolean proxy)throws Exception { return load(xml,proxy,true); }
    static GenericApplicationContext load(Path xml,boolean proxy,boolean observed)throws Exception {
        Files.writeString(xml,xml());var context=new FaultContext();
        if(proxy)context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor(){public Object postProcessAfterInitialization(Object bean,String name){
            if(!name.equals("transformer"))return bean;
            var factory=new ProxyFactory();factory.setTarget(bean);factory.setInterfaces(HeaderTask.class);return factory.getProxy();
        }});
        var reader=new XmlBeanDefinitionReader(context.getDefaultListableBeanFactory());var resource=new FileSystemResource(xml);
        SpringXmlAliases.begin(reader,resource);reader.loadBeanDefinitions(resource);if(observed)SpringXmlResources.record(reader,resource);context.refresh();return context;
    }
    static ImpexTransformerTask target(GenericApplicationContext context)throws Exception{
        Object exposed=context.getBeanFactory().getSingleton("transformer");
        if(exposed instanceof Advised advised){check(advised.getTargetSource().getClass()==SingletonTargetSource.class&&advised.getTargetSource().isStatic(),"unsupported target source");exposed=advised.getTargetSource().getTarget();}
        check(exposed!=null&&exposed.getClass()==ImpexTransformerTask.class,"not exact native transformer");return (ImpexTransformerTask)exposed;
    }
    static void convert(GenericApplicationContext context,Path root,String expected)throws Exception {
        // Actual native selector and converter only: CSVReader execute needs native
        // platform configuration. Do not emulate CSV/import or claim a generated file.
        var select=ImpexTransformerTask.class.getDeclaredMethod("getConverters",java.io.File.class);select.setAccessible(true);
        var selected=(java.util.List<?>)select.invoke(target(context),root.resolve("c51-"+UUID.randomUUID()+".csv").toFile());
        check(selected.size()==1,"native cache did not select one converter");
        var converter=(de.hybris.platform.acceleratorservices.dataimport.batch.converter.ImpexConverter)selected.get(0);
        String output=converter.convert(Map.of(0,"fresh"),1L);
        check(output.equals(";"+expected+"-fresh"),"expected converter"+expected+"; actual row="+output);
    }
    static void edit(Path path)throws Exception{Files.writeString(path,Files.readString(path).replace("ref='converterA'","ref='converterB'"));}
    static Object cache(GenericApplicationContext context)throws Exception {
        Field field=ImpexTransformerTask.class.getDeclaredField("converterMap");field.setAccessible(true);return field.get(target(context));
    }
    static void refused(GenericApplicationContext context,Path xml,Path root,String why)throws Exception {
        var mapping=context.getBean("mapping",DefaultConverterMapping.class);Object old=mapping.getConverter(),cache=cache(context);
        edit(xml);new SpringXmlReloader(new Context(context)).reload(xml);
        check(mapping.getConverter()==old,"refusal changed mapping: "+why);
        check(cache(context)==cache,"refusal changed live cache: "+why);convert(context,root,"A");
        var raw=context.getBeanFactory().getBeanDefinition("mapping").getPropertyValues().get("converter");
        check(((org.springframework.beans.factory.config.RuntimeBeanReference)raw).getBeanName().equals("converterA"),"refusal changed raw metadata: "+why);
        System.out.println("CASE "+why+" PASS");
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);Path one=root.resolve("one-spring.xml"),two=root.resolve("two-spring.xml");
        try {
            try(var first=load(one,false);var second=load(two,true)) {
                var mapping=first.getBean("mapping",DefaultConverterMapping.class);Object task=target(first),a=first.getBean("converterA"),b=first.getBean("converterB");
                edit(one);new SpringXmlReloader(new Context(first)).reload(one);convert(first,root,"B");convert(second,root,"A");
                check(mapping.getConverter()==b&&first.getBean("mapping")==mapping&&target(first)==task&&first.getBean("converterA")==a&&first.getBean("converterB")==b,"live identities changed");
                System.out.println("CASE realReloaderMappingCache PASS");
                new SpringXmlReloader(new Context(first)).reload(one);convert(first,root,"B");System.out.println("CASE identicalSave PASS");
                Object exposed=second.getBean("transformer");edit(two);new SpringXmlReloader(new Context(second)).reload(two);convert(second,root,"B");
                check(second.getBean("transformer")==exposed,"static proxy replaced");System.out.println("CASE staticProxyAndForeignOwner PASS");
            }
            try(var first=load(one,false)) {
                first.getBeanFactory().registerSingleton("foreignFault",new FailingMapping());
                refused(first,one,root,"persistentCustomMappingRefused");
            }
            try(var first=load(one,false)) {
                var lazy=new org.springframework.beans.factory.support.RootBeanDefinition(DefaultConverterMapping.class);lazy.setLazyInit(true);
                first.registerBeanDefinition("unusedLazyMapping",lazy);refused(first,one,root,"lazyMappingRefused");
                check(!first.getBeanFactory().containsSingleton("unusedLazyMapping"),"lazy mapping instantiated");
            }
            try(var first=load(one,false)) {
                Object exposed=first.getBean("transformer");var proxy=new ProxyFactory();
                proxy.setInterfaces(HeaderTask.class);proxy.setTargetSource(new org.springframework.aop.target.HotSwappableTargetSource(exposed));
                first.getDefaultListableBeanFactory().destroySingleton("transformer");first.getBeanFactory().registerSingleton("transformer",proxy.getProxy());
                var mapping=first.getBean("mapping",DefaultConverterMapping.class);Object old=mapping.getConverter();
                Field cache=ImpexTransformerTask.class.getDeclaredField("converterMap");cache.setAccessible(true);Object prior=cache.get(exposed);
                edit(one);new SpringXmlReloader(new Context(first)).reload(one);
                check(mapping.getConverter()==old&&cache.get(exposed)==prior,"dynamic target mutated");
                System.out.println("CASE dynamicTaskRefused PASS");
            }
            try(var first=load(one,false)) {
                var field=ImpexTransformerTask.class.getDeclaredField("applicationContext");field.setAccessible(true);field.set(target(first),null);
                refused(first,one,root,"wrongTaskOwnerRefused");
            }
            try(var first=load(one,false)) {
                var field=ImpexTransformerTask.class.getDeclaredField("converterMap");field.setAccessible(true);field.set(target(first),null);
                var mapping=first.getBean("mapping",DefaultConverterMapping.class);Object old=mapping.getConverter();edit(one);new SpringXmlReloader(new Context(first)).reload(one);
                check(mapping.getConverter()==old&&field.get(target(first))==null,"uninitialized cache mutated");System.out.println("CASE missingCacheRefused PASS");
            }
            try(var first=load(one,false)) {
                ((FaultContext)first).failLookup=true;
                refused(first,one,root,"stagingFailureRollsBackRefCacheMetadata");
            }
            try(var first=load(one,false,false)) {
                refused(first,one,root,"unobservedOwnerRefused");
            }
            try(var first=load(one,false)) {
                var converter=first.getBean("converterB");var proxy=new ProxyFactory();proxy.setTarget(converter);
                proxy.setInterfaces(de.hybris.platform.acceleratorservices.dataimport.batch.converter.ImpexConverter.class);
                first.getDefaultListableBeanFactory().destroySingleton("converterB");first.getBeanFactory().registerSingleton("converterB",proxy.getProxy());
                refused(first,one,root,"proxyConverterRefused");
            }
            try(var first=load(one,false)) {
                // Two mappings collected by the same exact native task, edited together.
                String initial=xml().replace("</beans>","<bean id='secondMapping' class='de.hybris.platform.acceleratorservices.dataimport.batch.converter.mapping.impl.DefaultConverterMapping'><property name='mapping' value='other-'/><property name='converter' ref='converterA'/></bean></beans>");
                first.close();Files.writeString(one,initial);
                try(var both=new GenericApplicationContext()) {
                    var reader=new XmlBeanDefinitionReader(both.getDefaultListableBeanFactory());var resource=new FileSystemResource(one);
                    SpringXmlAliases.begin(reader,resource);reader.loadBeanDefinitions(resource);SpringXmlResources.record(reader,resource);both.refresh();
                    // Startup transformer appears earlier in XML; ensure second mapping was collected by native initialization.
                    var before=both.getBean("mapping",DefaultConverterMapping.class);var other=both.getBean("secondMapping",DefaultConverterMapping.class);
                    edit(one);new SpringXmlReloader(new Context(both)).reload(one);convert(both,root,"B");
                    check(before.getConverter()==both.getBean("converterB")&&other.getConverter()==both.getBean("converterB"),"multiple mapping refs not committed");
                    var select=ImpexTransformerTask.class.getDeclaredMethod("getConverters",java.io.File.class);select.setAccessible(true);
                    check(((List<?>)select.invoke(target(both),root.resolve("other-fresh.csv").toFile())).get(0)==both.getBean("converterB"),"second prefix stale");
                    System.out.println("CASE multipleMappingEdits PASS");
                }
            }
            System.out.println("12 SDK hot-folder cases PASS; native selector/converter only, no tenant/import/database claim");
        }finally{Files.deleteIfExists(one);Files.deleteIfExists(two);}
    }
}
