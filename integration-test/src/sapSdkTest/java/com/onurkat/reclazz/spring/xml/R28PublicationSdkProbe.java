/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.ui.RestartLedger;
import de.hybris.platform.converters.impl.*;
import de.hybris.platform.spring.config.*;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;

/** R28 K1-K3: native fresh-start order, concurrent converter output, and resolution-side mutation. */
public class R28PublicationSdkProbe {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static String text(String add,boolean anchor,boolean modify){
        String head=ListDirectiveSdkProbe.HEAD;
        return head+ListDirectiveSdkProbe.mark("A")+ListDirectiveSdkProbe.mark("P")+ListDirectiveSdkProbe.mark("P2")+ListDirectiveSdkProbe.mark("Q")+ListDirectiveSdkProbe.converter("converter")
                +"<bean id='first' class='"+ListMergeDirective.class.getName()+"' depends-on='converter'><property name='listPropertyDescriptor' value='populators'/><property name='add' ref='"+add+"'/>"
                +(anchor?"<property name='beforeBeanNames'><list><value>Q</value></list></property>":"")+"</bean>"
                +"<bean id='second' class='"+(modify?ModifyPopulatorList.class:ListMergeDirective.class).getName()+"'"+(modify?"":" depends-on='converter'")+">"
                +(modify?"<property name='list' ref='converter'/>":"<property name='listPropertyDescriptor' value='populators'/>")+"<property name='add' ref='Q'/></bean></beans>";
    }
    static GenericApplicationContext context(Path file,boolean observe)throws Exception {
        var ctx=new GenericApplicationContext();var reader=new XmlBeanDefinitionReader(ctx);reader.setValidating(false);var resource=new FileSystemResource(file);reader.loadBeanDefinitions(resource);SpringXmlResources.record(reader,resource);
        var merge=new ListMergeDirectiveBeanPostProcessor();merge.setBeanFactory(ctx.getBeanFactory());if((Object)merge instanceof org.springframework.beans.factory.InitializingBean callback)callback.afterPropertiesSet();
        var modify=new ModifyPopulatorListBeanPostProcessor();modify.setBeanFactory(ctx.getBeanFactory());try{modify.getClass().getMethod("initialize").invoke(modify);}catch(NoSuchMethodException absent){}
        ctx.getBeanFactory().addBeanPostProcessor(merge);ctx.getBeanFactory().addBeanPostProcessor(modify);ctx.refresh();if(observe)ApplicationContextHolder.register(ctx);return ctx;
    }
    static String output(GenericApplicationContext ctx){return ((AbstractPopulatingConverter)ctx.getBean("converter")).convert(new Object()).toString();}
    static SpringXmlReloader reloader(GenericApplicationContext ctx){PlatformContext platform=(PlatformContext)java.lang.reflect.Proxy.newProxyInstance(R28PublicationSdkProbe.class.getClassLoader(),new Class<?>[]{PlatformContext.class},(p,m,a)->switch(m.getName()){case "getApplicationContext"->ctx;case "getPlatformId"->PlatformContext.Platform.GENERIC;default->null;});return new SpringXmlReloader(platform);}
    static void shared(Path root)throws Exception {
        for(int variant=0;variant<7;variant++) {
            Path dir=root.resolve("kind-"+variant);Files.createDirectories(dir);Path live=dir.resolve("live.xml"),fresh=dir.resolve("fresh.xml");boolean anchor=variant==1,modify=variant==2;
            Files.writeString(live,variant(text("P",anchor,modify),variant));Files.writeString(fresh,variant(text("P2",anchor,modify),variant));
            try(var ctx=context(live,true);var restarted=context(fresh,false)) {
                if(variant==3)check(ctx.getBeanFactory().getSingleton("second")==null,"lazy other directive stays uncreated before check");
                String original=output(ctx),expected=output(restarted);check(!original.equals(expected),"fresh SDK application witnesses the changed first directive");
                Files.writeString(live,variant(text("P2",anchor,modify),variant));RestartLedger.clear();reloader(ctx).reloadLoaded(live);
                var ledger=RestartLedger.digest();boolean refused=ledger.stream().anyMatch(l->l.contains("directive")&&l.contains("restart"));
                if(refused){check(ledger.stream().anyMatch(l->l.contains("second")),"shared-list restart must name the other directive");check(output(ctx).equals(original),"refused edit preserves original live order");}
                else check(output(ctx).equals(expected),"reload order must equal a fresh native application: reload="+output(ctx)+" restart="+expected);
                if(variant==3)check(ctx.getBeanFactory().getSingleton("second")==null,"ownership check never instantiates a lazy directive");
            }finally{ApplicationContextHolder.clear();RestartLedger.clear();}
        }
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static void ownershipControls(Path root)throws Exception {
        for(boolean modify:List.of(false,true))try(var f=new R28DirectiveSdkProbe.Fixture(root.resolve("other-"+modify),true)) {
            Path next=root.resolve("ownership-"+modify+".xml");Files.writeString(next,R28DirectiveSdkProbe.xml(true,"C","beforeBeanNames","converter"));
            var parsed=new org.springframework.beans.factory.support.DefaultListableBeanFactory();var reader=new XmlBeanDefinitionReader(parsed);reader.setValidating(false);reader.loadBeanDefinitions(new FileSystemResource(next));
            var other=new RootBeanDefinition(modify?ModifyPopulatorList.class:ListMergeDirective.class);other.setLazyInit(true);
            other.getPropertyValues().add("add",new org.springframework.beans.factory.config.RuntimeBeanReference("D"));
            if(modify)other.getPropertyValues().add("list",new org.springframework.beans.factory.config.RuntimeBeanReference("other"));
            else {other.setDependsOn("other");other.getPropertyValues().add("listPropertyDescriptor","populators");}
            f.factory.registerBeanDefinition("otherDirective",other);
            check(SapPopulatorDirectives.supported(f.factory,"directive",f.factory.getBeanDefinition("directive"),parsed.getBeanDefinition("directive")),"a directive on an independent list does not block reload");
            var converter=(AbstractPopulatingConverter)f.ctx.getBean("other");List old=converter.getPopulators();converter.setPopulators((List)f.list());
            check(!SapPopulatorDirectives.supported(f.factory,"directive",f.factory.getBeanDefinition("directive"),parsed.getBeanDefinition("directive")),"different owner beans sharing a backing list block reload");
            check("otherDirective".equals(SapListMergeDirectives.sharedDirective(f.factory,"directive",f.factory.getBeanDefinition("directive"),f.ctx.getBean("directive"))),"backing-list ownership diagnostic names the other directive");
            check(f.factory.getSingleton("otherDirective")==null,"ownership proof does not initialize a lazy directive");
            converter.setPopulators(old);
        }
    }
    static String variant(String xml,int variant) {
        if(variant==3)return xml.replace("<bean id='second'","<bean lazy-init='true' id='second'");
        if(variant==4)return xml.replace("<bean id='second' class='"+ListMergeDirective.class.getName()+"'","<bean id='second' class='"+ListDirectiveSdkProbe.CustomMerge.class.getName()+"'");
        if(variant==5)return xml.replace("<bean id='second' class='"+ListMergeDirective.class.getName()+"'","<bean id='template' abstract='true' class='"+ListMergeDirective.class.getName()+"'/><bean id='second' parent='template'");
        if(variant==6)return xml.replace("<bean id='second'","<alias name='converter' alias='converterAlias'/><bean id='second'").replace("id='second' class='"+ListMergeDirective.class.getName()+"' depends-on='converter'","id='second' class='"+ListMergeDirective.class.getName()+"' depends-on='converterAlias'");
        return xml;
    }
    static class PausedClear extends ArrayList<Object> {
        final CountDownLatch wake,read;volatile boolean cleared;
        PausedClear(Collection<?> values,CountDownLatch wake,CountDownLatch read){super(values);this.wake=wake;this.read=read;}
        @Override public void clear(){super.clear();cleared=true;wake.countDown();try{if(!read.await(5,TimeUnit.SECONDS))throw new AssertionError("reader did not reach clear window");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static void noEmpty(Path root)throws Exception {
        try(var f=new R28DirectiveSdkProbe.Fixture(root,false)) {
            var converter=(AbstractPopulatingConverter)f.ctx.getBean("converter");var wake=new CountDownLatch(1);var read=new CountDownLatch(1);var started=new CountDownLatch(1);var observed=new AtomicReference<String>();var failure=new AtomicReference<Throwable>();
            var original=new PausedClear(f.list(),wake,read);converter.setPopulators((List)original);
            Thread reader=new Thread(()->{try{check(converter.convert(new Object()).toString().equals("AB"),"reader initial native conversion");started.countDown();check(wake.await(5,TimeUnit.SECONDS),"publication woke reader");observed.set(converter.convert(new Object()).toString());}catch(Throwable e){failure.set(e);}finally{read.countDown();}},"r28-concurrent-converter");reader.start();check(started.await(5,TimeUnit.SECONDS),"reader started before publication");
            try{f.reload(R28DirectiveSdkProbe.xml(false,"C","","converter"));f.applied();}finally{wake.countDown();reader.join(6000);}
            check(!reader.isAlive()&&failure.get()==null,"concurrent converter completed: "+failure.get());check(observed.get()!=null&&!observed.get().isEmpty(),"concurrent convert must never observe an empty populator list");check(f.output("converter").equals("AC"),"edited converter output");
        }
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static void changed(Path root)throws Exception {
        for(int mode=0;mode<5;mode++)try(var f=new R28DirectiveSdkProbe.Fixture(root.resolve("mode-"+mode),mode!=4)) {
            boolean merge=mode!=4;int selected=mode;
            Object before=((org.springframework.beans.factory.support.AbstractBeanDefinition)f.factory.getBeanDefinition("directive")).cloneBeanDefinition();Object directive=f.ctx.getBean("directive");
            var converter=(AbstractPopulatingConverter)f.ctx.getBean("converter");List<Object> oldList=f.list();
            var newBean=new RootBeanDefinition(ListDirectiveSdkProbe.Mark.class);newBean.setInstanceSupplier(()->{
                if(selected==1)converter.setPopulators((List)new ArrayList<>(List.of(f.ctx.getBean("D"))));
                else if(selected==2)Collections.swap(f.list(),0,1);
                else f.list().add(f.ctx.getBean("D"));
                return f.ctx.getBean(selected==3?"A":"C");
            });f.factory.registerBeanDefinition("newBean",newBean);check(f.factory.getSingleton("newBean")==null,"bean resolves only during apply");
            String candidate=R28DirectiveSdkProbe.xml(merge,mode==3?"C":"newBean",merge?"beforeBeanNames":"","converter");
            if(mode==3)candidate=candidate.replace("<value>A</value>","<value>newBean</value>");
            Path next=root.resolve("next-"+mode+".xml");Files.writeString(next,candidate);var parsed=new org.springframework.beans.factory.support.DefaultListableBeanFactory();var reader=new XmlBeanDefinitionReader(parsed);reader.setValidating(false);reader.loadBeanDefinitions(new FileSystemResource(next));
            boolean refused=false;try{SapPopulatorDirectives.apply(f.factory,new SapPopulatorDirectives.Edit("directive",parsed.getBeanDefinition("directive")));}catch(IllegalStateException expected){refused=expected.getMessage().contains("changed");}
            check(refused,"live list mutation during add/position resolution must refuse publication: "+mode);
            check(f.output("converter").equals(mode==1?"D":mode==2?"AB":mode==4?"ABD":"BAD"),"preserve the external list mutation; do not overwrite or roll it back");
            check((f.list()==oldList)==(mode!=1),"preserve external list-reference replacement");
            check(f.factory.getBeanDefinition("directive").equals(before),"raw definition remains old after stale snapshot refusal");check(directive.getClass().getMethod("getAdd").invoke(directive)==f.ctx.getBean("B"),"directive remains old after stale snapshot refusal");
        }
    }
    public static void main(String[]args)throws Exception {
        Path root=Path.of(args[0]);switch(args[1]){case "shared-order"->{shared(root);ownershipControls(root);}case "publish-no-empty"->noEmpty(root);case "snapshot-mutation"->changed(root);default->throw new IllegalArgumentException(args[1]);}
        System.out.println("R28_PUBLICATION_PASS scenario="+args[1]+" checks="+checks+" Spring="+org.springframework.core.SpringVersion.getVersion());
    }
}
