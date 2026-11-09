package com.onurkat.reclazz.hybris.interceptor;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.spring.SpringBeanReloader;
import de.hybris.platform.servicelayer.interceptor.InterceptorRegistry;
import de.hybris.platform.servicelayer.interceptor.impl.InterceptorMapping;
import de.hybris.platform.servicelayer.internal.model.impl.DefaultModelService;
import org.springframework.beans.factory.support.*;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.context.support.GenericApplicationContext;
import java.util.*;

/** Real native configured graph and inherited cached getter; no database or model-save seam. */
public class RegistryPreservationSdkProbe {
    public static class Service extends DefaultModelService {
        final GenericApplicationContext c;
        public Service(GenericApplicationContext c) { this.c=c; }
        @Override public InterceptorRegistry lookupInterceptorRegistry() { return c.getBean("interceptorRegistry",InterceptorRegistry.class); }
    }
    public static class Holder {
        Object target;
        public void setTarget(Object target) { this.target=target; }
    }
    public static void main(String[] args) throws Exception {
        if(args.length>0) {run(args[0].equals("batch"),true);return;}
        run(false, false); run(true, true); twoOwners(); staleRegistry();
    }
    static PlatformContext platform(List<GenericApplicationContext> owners) {
        return (PlatformContext)java.lang.reflect.Proxy.newProxyInstance(RegistryPreservationSdkProbe.class.getClassLoader(),new Class<?>[]{PlatformContext.class},
                (p,m,a)->switch(m.getName()){case "getApplicationContext"->owners.get(0);case "getAllApplicationContexts"->owners;default->null;});
    }
    static GenericApplicationContext context(boolean alias) {
        var c=new GenericApplicationContext();
        c.registerBeanDefinition("target",new RootBeanDefinition(InterceptorRegistrySdkProbe.Target.class));
        c.registerBeanDefinition("other",new RootBeanDefinition(InterceptorRegistrySdkProbe.Other.class));
        InterceptorRegistrySdkProbe.mapping(c,"mapping","target",7);
        InterceptorRegistrySdkProbe.mapping(c,"unrelated","other",12);
        var holder=new RootBeanDefinition(Holder.class);holder.getPropertyValues().add("target",new RuntimeBeanReference("target"));
        c.registerBeanDefinition("ordinary",holder);
        var definition=new RootBeanDefinition(InterceptorRegistrySdkProbe.Registry.class);
        var mappings=new ManagedList<Object>(); mappings.add(new RuntimeBeanReference("mapping"));mappings.add(new RuntimeBeanReference("unrelated"));
        definition.getPropertyValues().add("interceptorMappings",mappings);
        definition.getPropertyValues().add("applicationContext",c);
        definition.getPropertyValues().add("interceptorExecutionPolicy",new InterceptorRegistrySdkProbe.Policy());
        c.registerBeanDefinition(alias?"nativeRegistry":"interceptorRegistry",definition);
        if(alias)c.registerAlias("nativeRegistry","interceptorRegistry");
        c.refresh();
        var model=new Service(c);c.getBeanFactory().registerSingleton("modelService",model);
        model.getInterceptorRegistry().getValidateInterceptors("Product");
        return c;
    }
    static SpringBeanReloader reloader(List<GenericApplicationContext> owners) {
        var beans=new SpringBeanReloader(platform(owners));
        if(!Boolean.getBoolean("probe.disablePreservation")) try {
            SpringBeanReloader.class.getMethod("setPreservedSingletons",com.onurkat.reclazz.platform.PreservedSingletons.class)
                    .invoke(beans,(com.onurkat.reclazz.platform.PreservedSingletons)InterceptorSingletons::preserved);
        } catch(NoSuchMethodException baseline) { /* Run the same behavioral assertion on baseline. */ }
        catch(ReflectiveOperationException failure) {throw new IllegalStateException(failure);}
        return beans;
    }
    static void verify(GenericApplicationContext c,Object registry,Object mapping,Object oldTarget,Object oldHolder) {
        var model=c.getBean("modelService",Service.class);
        Object target=c.getBean("target");
        check(c.getBean("interceptorRegistry")==registry && model.getInterceptorRegistry()==registry,"native registry identity replaced");
        check(c.getBean("mapping")==mapping,"mapping identity replaced");
        check(((InterceptorMapping)mapping).getInterceptor()==target,"preserved mapping points to old instance");
        check(oldTarget!=target,"target was not refreshed");
        check(c.getBean("ordinary")!=oldHolder && c.getBean("ordinary",Holder.class).target==target,"ordinary dependent not recreated");
        var targets=model.getInterceptorRegistry().getValidateInterceptors("Product");
        check(targets.size()==2 && targets.stream().filter(t->t==target).count()==1 && !targets.contains(oldTarget) && targets.contains(c.getBean("other")),"stale/duplicate/lost native targets");
    }
    static void run(boolean batch,boolean alias) throws Exception {
        try(var c=context(alias)) {
            var beans=reloader(List.of(c));var r=new InterceptorReloader();
            Object registry=c.getBean("interceptorRegistry"),mapping=c.getBean("mapping");
            for(int i=0;i<3;i++) {
                Object target=c.getBean("target"),holder=c.getBean("ordinary");
                var pending=r.prepareInContext(InterceptorRegistrySdkProbe.Target.class.getName(),c,c.getClassLoader());
                if(batch)beans.beginBatch();
                beans.refreshBean(InterceptorRegistrySdkProbe.Target.class.getName(),InterceptorRegistrySdkProbe.Target.class);
                check(pending.complete(),"native completion failed");
                if(batch)beans.endBatch();
                verify(c,registry,mapping,target,holder);
                check(Arrays.asList(c.getDefaultListableBeanFactory().getDependentBeans("target")).contains("mapping"),"mapping dependency edge lost");
            }
            System.out.println("PASS: "+(batch?"batchCascadeKeepsRegistryUsedByModelService + registryAliasResolvedToCanonical":"bodyRefreshKeepsRegistryUsedByModelService")+"; preservedMappingPointsToNewInterceptorInstance; ordinaryDependentsStillRecreated; repeat3");
        }
    }
    static void twoOwners() throws Exception {
        try(var a=context(true);var b=context(true)) {
            var owners=List.of(a,b);var beans=reloader(owners);var r=new InterceptorReloader();
            var pending=new ArrayList<InterceptorReloader.Refresh>();
            var before=new ArrayList<Object[]>();
            for(var c:owners) {before.add(new Object[]{c.getBean("interceptorRegistry"),c.getBean("mapping"),c.getBean("target"),c.getBean("ordinary")});pending.add(r.prepareInContext(InterceptorRegistrySdkProbe.Target.class.getName(),c,c.getClassLoader()));}
            beans.beginBatch();beans.refreshBean(InterceptorRegistrySdkProbe.Target.class.getName(),InterceptorRegistrySdkProbe.Target.class);
            for(var p:pending)check(p.complete(),"owner completion failed");beans.endBatch();
            for(int i=0;i<2;i++){var v=before.get(i);verify(owners.get(i),v[0],v[1],v[2],v[3]);}
            check(a.getBean("mapping")!=b.getBean("mapping") && a.getBean("interceptorRegistry")!=b.getBean("interceptorRegistry"),"owner identities crossed");
            System.out.println("PASS: independent native registries in two owners");
        }
    }
    static void staleRegistry() throws Exception {
        try(var c=context(false)) {
            var old=(InterceptorRegistrySdkProbe.Registry)c.getBean("interceptorRegistry");
            // Reproduce an already stale ModelService cache; this is a compatibility control, not test-side live setup.
            var fresh=new InterceptorRegistrySdkProbe.Registry();fresh.setApplicationContext(c);fresh.setInterceptorExecutionPolicy(new InterceptorRegistrySdkProbe.Policy());
            fresh.registerInterceptor(c.getBean("mapping",InterceptorMapping.class));fresh.registerInterceptor(c.getBean("unrelated",InterceptorMapping.class));
            c.getDefaultListableBeanFactory().registerAlias("freshRegistry","interceptorRegistry");c.getBeanFactory().registerSingleton("freshRegistry",fresh);
            check(c.getBean("modelService",Service.class).getInterceptorRegistry()==old,"stale cache control failed");
            var pending=new InterceptorReloader().prepareInContext(InterceptorRegistrySdkProbe.Target.class.getName(),c,c.getClassLoader());
            reloader(List.of(c)).refreshBean(InterceptorRegistrySdkProbe.Target.class.getName(),InterceptorRegistrySdkProbe.Target.class);
            check(pending.complete(),"stale registry reconciliation failed");
            for(var registry:List.of(old,fresh)) check(registry.getValidateInterceptors("Product").contains(c.getBean("target")),"one retained registry is stale");
            System.out.println("PASS: ModelService retained registry and differing context registry reconciled");
        }
    }
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
