/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.ui.RestartLedger;
import de.hybris.platform.converters.impl.*;
import de.hybris.platform.spring.config.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;

/** Actual installed SDK beans/processors; synthetic XML and independent output assertions. */
public class R28DirectiveSdkProbe {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static String head=ListDirectiveSdkProbe.HEAD;
    static String xml(boolean merge,String add,String position,String target){
        return head+"<bean id='directive' class='"+(merge?ListMergeDirective.class:ModifyPopulatorList.class).getName()+"'"
                +(merge?" depends-on='converter'":"")+">"
                +(merge?"<property name='listPropertyDescriptor' value='populators'/>"+(position.isEmpty()?"":"<property name='"+position+"'><list><value>A</value></list></property>"):"<property name='list' ref='"+target+"'/>")
                +"<property name='add' ref='"+add+"'/></bean></beans>";
    }
    static class FailingFactory extends DefaultListableBeanFactory {
        boolean fail;
        @Override public void clearMetadataCache(){if(fail){fail=false;throw new IllegalStateException("synthetic metadata publication failure");}super.clearMetadataCache();}
    }
    static class Fixture implements AutoCloseable {
        final Path file;final GenericApplicationContext ctx;final FailingFactory factory=new FailingFactory();final SpringXmlReloader reloader;
        Fixture(Path dir,boolean merge)throws Exception {
            Files.createDirectories(dir);file=dir.resolve("directives.xml");Files.writeString(file,xml(merge,"B",merge?"beforeBeanNames":"","converter"));
            var base=dir.resolve("base.xml");Files.writeString(base,head+ListDirectiveSdkProbe.mark("A")+ListDirectiveSdkProbe.mark("B")+ListDirectiveSdkProbe.mark("C")+ListDirectiveSdkProbe.mark("D")+ListDirectiveSdkProbe.converter("converter")+ListDirectiveSdkProbe.converter("other")+"</beans>");
            ctx=new GenericApplicationContext(factory);var reader=new XmlBeanDefinitionReader(ctx);reader.setValidating(false);reader.loadBeanDefinitions(new FileSystemResource(base));
            var resource=new FileSystemResource(file);reader.loadBeanDefinitions(resource);SpringXmlResources.record(reader,resource);
            var modify=new ModifyPopulatorListBeanPostProcessor();modify.setBeanFactory(factory);try{modify.getClass().getMethod("initialize").invoke(modify);}catch(NoSuchMethodException absent){}
            var merger=new ListMergeDirectiveBeanPostProcessor();merger.setBeanFactory(factory);if((Object)merger instanceof org.springframework.beans.factory.InitializingBean callback)callback.afterPropertiesSet();
            factory.addBeanPostProcessor(merge?merger:modify);ctx.refresh();ApplicationContextHolder.register(ctx);
            PlatformContext platform=(PlatformContext)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},(p,m,a)->switch(m.getName()){case "getApplicationContext"->ctx;case "getPlatformId"->PlatformContext.Platform.GENERIC;default->null;});
            reloader=new SpringXmlReloader(platform);RestartLedger.clear();
        }
        String output(String name){return ((AbstractPopulatingConverter<?,?>)ctx.getBean(name)).convert(null).toString();}
        @SuppressWarnings("unchecked") List<Object> list(){return (List<Object>)(List<?>)((AbstractPopulatingConverter<?,?>)ctx.getBean("converter")).getPopulators();}
        void reload(String text)throws Exception{Files.writeString(file,text);RestartLedger.clear();reloader.reloadLoaded(file);}
        void applied(){check(RestartLedger.digest().equals(List.of("Nothing from this session needs a restart.")),"edit requires no restart: "+RestartLedger.digest());}
        void refused(){check(RestartLedger.digest().stream().anyMatch(s->s.contains("SAP")&&s.contains("directive")),"unsafe directive must retain a restart concern");}
        public void close(){ctx.close();ApplicationContextHolder.clear();RestartLedger.clear();}
    }
    static void modify(Path root)throws Exception {
        try(var f=new Fixture(root,false)){
            check(f.output("converter").equals("AB")&&f.output("other").equals("A"),"initial native converters");Object directive=f.ctx.getBean("directive");
            f.reload(xml(false,"C","","other"));f.refused();check(f.output("converter").equals("AB")&&f.output("other").equals("A"),"unrecorded native removal history blocks every retarget");
            check(f.ctx.getBean("directive")==directive,"same directive identity");check(((ModifyPopulatorList<?,?>)directive).getList()==f.ctx.getBean("converter"),"refused directive keeps its target");
            f.reload(xml(false,"B","","converter"));f.applied();check(f.output("converter").equals("AB")&&f.output("other").equals("A"),"restored XML stays quiet");
            f.reload(xml(false,"B","","converter").replace("</bean></beans>","<property name='remove' ref='A'/></bean></beans>"));f.applied();check(f.output("converter").equals("B"),"legacy same-target removal");
            f.reload(xml(false,"B","","other"));f.refused();check(f.output("converter").equals("B")&&f.output("other").equals("A"),"unknown old removal position blocks retarget");
        }
    }
    static void merge(Path root)throws Exception {
        try(var f=new Fixture(root,true)){
            check(f.output("converter").equals("BA"),"native beforeBeanNames startup");Object directive=f.ctx.getBean("directive");
            List<Object> heldList=f.list();
            f.reload(xml(true,"C","beforeBeanNames","converter"));f.applied();check(f.list()!=heldList&&heldList.equals(List.of(f.ctx.getBean("B"),f.ctx.getBean("A"))),"converter publication swaps list; an in-flight reader keeps the complete old snapshot");check(f.output("converter").equals("CA"),"replace old addition and preserve position");
            f.reload(xml(true,"C","afterBeanNames","converter"));f.applied();check(f.output("converter").equals("AC"),"position save reaches native converter");
            f.reload(xml(true,"B","beforeBeanNames","converter"));f.applied();check(f.output("converter").equals("BA"),"merge revert");check(f.ctx.getBean("directive")==directive,"merge directive identity");check(f.output("other").equals("A"),"unrelated converter untouched");
            f.reload(xml(true,"A","beforeBeanNames","converter"));f.refused();check(f.output("converter").equals("BA"),"candidate already present cannot corrupt native sort");
            f.list().add(f.ctx.getBean("B"));List<Object> before=new ArrayList<>(f.list());
            f.reload(xml(true,"C","beforeBeanNames","converter"));f.refused();check(f.list().equals(before),"ambiguous old addition leaves list intact");
        }
    }
    static void failure(Path root)throws Exception {
        for(boolean merge:List.of(false,true))try(var f=new Fixture(root.resolve("kind-"+merge),merge)){
            Object before=((org.springframework.beans.factory.support.AbstractBeanDefinition)f.factory.getBeanDefinition("directive")).cloneBeanDefinition();List<Object> original=f.list();List<Object> list=new ArrayList<>(original);Object directive=f.ctx.getBean("directive");
            Path next=root.resolve("candidate-"+merge+".xml");Files.writeString(next,xml(merge,"C",merge?"afterBeanNames":"","converter"));
            var parsed=new DefaultListableBeanFactory();var reader=new XmlBeanDefinitionReader(parsed);reader.setValidating(false);reader.loadBeanDefinitions(new FileSystemResource(next));
            Object candidate=parsed.getBeanDefinition("directive");check(SapPopulatorDirectives.supported(f.factory,"directive",f.factory.getBeanDefinition("directive"),candidate),"failure witness uses supported edit");
            if(merge) {
                ((ListMergeDirective)directive).setBeforeValues(List.of());
                check(!SapPopulatorDirectives.supported(f.factory,"directive",f.factory.getBeanDefinition("directive"),candidate),"even empty value positions enable native sorting and stay excluded");
                ((ListMergeDirective)directive).setBeforeValues(null);
            }
            f.factory.fail=true;boolean failed=false;try{SapPopulatorDirectives.apply(f.factory,new SapPopulatorDirectives.Edit("directive",candidate));}catch(IllegalStateException expected){failed=expected.getMessage().contains("synthetic metadata");}
            check(failed,"metadata publication failure reached");check(f.list()==original&&f.list().equals(list),"old list reference and contents restored on publication failure");check(f.output("other").equals("A"),"new target restored on publication failure");check(f.factory.getBeanDefinition("directive").equals(before),"full old raw definition restored");
            check(directive.getClass().getMethod("getAdd").invoke(directive)==f.ctx.getBean("B"),"old directive restored");
            if(merge){f.list().remove(f.ctx.getBean("B"));check(!SapPopulatorDirectives.supported(f.factory,"directive",f.factory.getBeanDefinition("directive"),candidate),"missing old addition is refused");f.list().clear();f.list().addAll(list);}
            Path parent=root.resolve("parent-"+merge+".xml");Files.writeString(parent,xml(merge,"C",merge?"afterBeanNames":"","converter").replace("<property name='add' ref='C'/>","<property name='add'><ref parent='C'/></property>"));
            var parentParsed=new DefaultListableBeanFactory();var parentReader=new XmlBeanDefinitionReader(parentParsed);parentReader.setValidating(false);parentReader.loadBeanDefinitions(new FileSystemResource(parent));
            check(!SapPopulatorDirectives.supported(f.factory,"directive",f.factory.getBeanDefinition("directive"),parentParsed.getBeanDefinition("directive")),"parent references cannot resolve against a shadowing local bean");
            if(merge){((AbstractPopulatingConverter)f.ctx.getBean("converter")).setPopulators(Collections.unmodifiableList(new ArrayList<>(f.list())));
            check(!SapPopulatorDirectives.supported(f.factory,"directive",f.factory.getBeanDefinition("directive"),candidate),"immutable/custom target stays diagnostic before mutation");}
        }
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static void directList(Path root)throws Exception {
        Files.createDirectories(root);Path file=root.resolve("direct-spring.xml");
        String prefix=head+ListDirectiveSdkProbe.mark("A")+ListDirectiveSdkProbe.mark("B")+ListDirectiveSdkProbe.mark("C")
                +"<bean id='directList' class='java.util.ArrayList'><constructor-arg><list><ref bean='A'/></list></constructor-arg></bean>";
        java.util.function.BiFunction<String,String,String> text=(add,position)->xml(true,add,position,"converter").replace(head,prefix).replace("depends-on='converter'","depends-on='directList'").replace("<property name='listPropertyDescriptor' value='populators'/>","");
        Files.writeString(file,text.apply("B","beforeBeanNames"));
        try(var ctx=new GenericApplicationContext()) {
            var reader=new XmlBeanDefinitionReader(ctx);reader.setValidating(false);var resource=new FileSystemResource(file);reader.loadBeanDefinitions(resource);SpringXmlResources.record(reader,resource);
            var processor=new ListMergeDirectiveBeanPostProcessor();processor.setBeanFactory(ctx.getBeanFactory());if((Object)processor instanceof org.springframework.beans.factory.InitializingBean callback)callback.afterPropertiesSet();ctx.getBeanFactory().addBeanPostProcessor(processor);ctx.refresh();ApplicationContextHolder.register(ctx);
            PlatformContext platform=(PlatformContext)java.lang.reflect.Proxy.newProxyInstance(R28DirectiveSdkProbe.class.getClassLoader(),new Class<?>[]{PlatformContext.class},(p,m,args)->switch(m.getName()){case "getApplicationContext"->ctx;case "getPlatformId"->PlatformContext.Platform.GENERIC;default->null;});var reloader=new SpringXmlReloader(platform);
            List list=(List)ctx.getBean("directList");check(list.getClass()==ArrayList.class,"direct target is the actual List singleton");check(list.equals(List.of(ctx.getBean("B"),ctx.getBean("A"))),"native direct-list initial position");
            for(String position:List.of("afterBeanNames","beforeBeanNames")) {
                var heldIterator=list.iterator();
                Files.writeString(file,text.apply("C",position));RestartLedger.clear();reloader.reloadLoaded(file);
                check(RestartLedger.digest().equals(List.of("Nothing from this session needs a restart.")),"direct List position is supported");
                check(list.equals(position.equals("afterBeanNames")?List.of(ctx.getBean("A"),ctx.getBean("C")):List.of(ctx.getBean("C"),ctx.getBean("A"))),"direct List identity and order preserved");
                check(ctx.getBean("directList")==list,"direct List singleton identity preserved");
                check(heldIterator.next()==list.get(0),"position publication is non-structural; no clear/addAll window");
            }
        } finally {ApplicationContextHolder.clear();RestartLedger.clear();}
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static void informationLoss()throws Exception {
        var factory=new DefaultListableBeanFactory();var processor=new ModifyPopulatorListBeanPostProcessor();processor.setBeanFactory(factory);
        var nativeApply=ModifyPopulatorListBeanPostProcessor.class.getDeclaredMethod("processOnTarget",ModifyPopulatorList.class,de.hybris.platform.converters.PopulatorList.class);nativeApply.setAccessible(true);
        var a=new ListDirectiveSdkProbe.Mark();var b=new ListDirectiveSdkProbe.Mark();
        List<Object> one=new ArrayList<>(Arrays.asList(null,a)),two=new ArrayList<>(List.of(a));
        for(List<Object> list:List.of(one,two)) {
            var converter=new AbstractPopulatingConverter();converter.setPopulators(list);var directive=new ModifyPopulatorList();directive.setList(converter);directive.setAdd(b);
            nativeApply.invoke(processor,directive,converter);
        }
        check(one.equals(two)&&one.equals(List.of(a,b)),"different pre-directive null histories collapse to the same native result");
    }
    public static void main(String[]args)throws Exception {
        Path root=Path.of(args[0]);switch(args[1]){case "modify-target"->{informationLoss();modify(root);}case "merge"->{merge(root);directList(root.resolve("direct"));}case "boundaries"->failure(root);default->throw new IllegalArgumentException(args[1]);}
        System.out.println("R28_SDK_PASS scenario="+args[1]+" checks="+checks+" Spring="+org.springframework.core.SpringVersion.getVersion());
    }
}
